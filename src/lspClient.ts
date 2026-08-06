import { ChildProcessWithoutNullStreams, spawn } from "child_process";
import path from "path";
import { PassThrough } from "stream";
import {
  createMessageConnection,
  MessageConnection,
  StreamMessageReader,
  StreamMessageWriter,
} from "vscode-jsonrpc/node";
import { DiagnosticUpdateCallback, LoggingLevel } from "./types/index.js";
import { debug, info, notice, warning, log, logError } from "./logging/index.js";
import { resolveLspServer, LspServerLaunch } from "./downloader/index.js";

// The Rell LSP server may write non-LSP output to stdout before its first framed
// message (e.g. the kotlin-logging banner), which would otherwise desync
// vscode-jsonrpc's header parser. Everything up to the first "Content-Length:"
// is discarded; the stream is passed through untouched from then on.
//
// NOTE: an earlier version of this tried to peek at process.stdout directly and
// Readable.unshift() the remainder back for StreamMessageReader to read. That
// silently drops data: unshift() only refills the internal buffer for the *next*
// read on that same stream instance, but re-attaching a fresh 'data' listener
// afterward does not replay it — only bytes that arrive after the new listener
// attaches are delivered. Route through an explicit PassThrough instead.
function stripLeadingNonLspOutput(source: NodeJS.ReadableStream): NodeJS.ReadableStream {
  const output = new PassThrough();
  let sawHeader = false;
  let pending = Buffer.alloc(0);

  source.on("data", (chunk: Buffer) => {
    if (sawHeader) {
      output.write(chunk);
      return;
    }

    pending = Buffer.concat([pending, chunk]);
    const headerStart = pending.indexOf("Content-Length:");
    if (headerStart === -1) return;

    const junk = pending.subarray(0, headerStart).toString().trim();
    if (junk) debug(`Skipping non-LSP output from server: ${junk}`);

    sawHeader = true;
    output.write(pending.subarray(headerStart));
    pending = Buffer.alloc(0);
  });
  source.on("end", () => output.end());
  source.on("error", (error) => output.destroy(error));

  return output;
}

export class LSPClient {
  private process: ChildProcessWithoutNullStreams | null = null;
  private connection: MessageConnection | null = null;
  private launchPromise: Promise<LspServerLaunch> | null = null;
  private initialized: boolean = false;
  private openedDocuments: Set<string> = new Set();
  private documentVersions: Map<string, number> = new Map();
  private documentDiagnostics: Map<string, any[]> = new Map();
  private diagnosticSubscribers: Set<DiagnosticUpdateCallback> = new Set();
  private readonly rellLspVersion?: string

  private languageId: string = "rell"

  constructor(lspVersion?: string) {
    this.rellLspVersion = lspVersion;
    this.prefetchServer();
  }

  // Kick off the runtime/JAR download or cache lookup as soon as the client is created,
  // so the first start_lsp call doesn't pay for it inside its request timeout.
  private prefetchServer(): void {
    const promise = resolveLspServer(this.rellLspVersion);
    this.launchPromise = promise;
    promise.catch((error) => {
      warning(`Failed to prefetch Rell LSP server: ${error instanceof Error ? error.message : String(error)}`);
      this.launchPromise = null; // retry on next startProcess
    });
  }

  private async startProcess(): Promise<void> {
    info(`Starting RELL LSP client`);

    try {
      const launch = await (this.launchPromise ?? resolveLspServer(this.rellLspVersion));
      debug(`Launching ${launch.jarPath} with ${launch.bundled ? 'bundled runtime' : 'system Java'} at ${launch.javaPath}`);

      this.process = spawn(launch.javaPath, ['-jar', launch.jarPath], { stdio: "pipe" });

      this.process.stderr.on("data", (data: Buffer) => debug(`LSP: ${data}`));
      this.process.on("close", (code: number) => notice(`Exit: ${code}`));
      // Without this handler a failed spawn (e.g. an unrunnable java binary) becomes an
      // uncaught exception that kills the whole MCP server.
      this.process.on("error", (error: Error) => logError(`LSP server process error: ${error.message}`));

      this.connection = createMessageConnection(
        new StreamMessageReader(stripLeadingNonLspOutput(this.process.stdout)),
        new StreamMessageWriter(this.process.stdin)
      );
      this.connection.onNotification((method: string, params: any) => this.handleNotification(method, params));
      this.connection.onError(([error]: [Error, unknown, number | undefined]) =>
        logError(`LSP connection error: ${error?.message ?? String(error)}`));
      this.connection.onClose(() => debug("LSP connection closed"));
      this.connection.listen();
    } catch (error: any) {
      throw new Error(`Startup failed: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  private handleNotification(method: string, params: any): void {
    this.logLspMessage('RECEIVED', method, params);

    if (method === 'textDocument/publishDiagnostics' && params) {
      const { uri, diagnostics } = params;

      if (uri && Array.isArray(diagnostics)) {
        const severity = diagnostics.length > 0 ?
          Math.min(...diagnostics.map((d: any) => d.severity || 4)) : 4;

        // Map LSP severity to our log levels
        const severityToLevel: Record<number, string> = {
          1: 'error',      // Error
          2: 'warning',    // Warning
          3: 'info',       // Information
          4: 'debug'       // Hint
        };

        const level = severityToLevel[severity] || 'debug';

        log(level as any, `Received ${diagnostics.length} diagnostics for ${uri}`);

        // Store diagnostics, replacing any previous ones for this URI.
        // The Rell server emits short-form "file:/path" URIs while createFileUri
        // produces "file:///path"; normalize only that short form.
        this.documentDiagnostics.set(uri.replace(/^file:\/(?!\/)/, 'file:///'), diagnostics);

        // Notify all subscribers about this update
        this.notifyDiagnosticUpdate(uri, diagnostics);
      }
    }
  }

  private getLSPMethodLogLevel(method: string): LoggingLevel {
    // Define appropriate log levels for different LSP methods
    if (method.startsWith('textDocument/did')) {
      return 'debug'; // Document changes are usually debug level
    }

    if (method.includes('diagnostic') || method.includes('publishDiagnostics')) {
      return 'info'; // Diagnostics depend on their severity, but base level is info
    }

    if (method === 'initialize' || method === 'initialized' ||
      method === 'shutdown' || method === 'exit') {
      return 'notice'; // Important lifecycle events are notice level
    }

    // Default to debug level for most LSP operations
    return 'debug';
  }

  private logLspMessage(direction: 'SENT' | 'RECEIVED', method: string, payload: any): void {
    try {
      const logLevel = this.getLSPMethodLogLevel(method);
      log(logLevel as any, `LSP ${direction} (${method}): ${JSON.stringify(payload, null, 2)}`);
    } catch (error) {
      warning(`Error logging LSP ${direction.toLowerCase()} message:`, error);
    }
  }

  private async sendRequest<T>(method: string, params?: any, timeoutMs: number = 10000): Promise<T> {
    if (!this.connection) {
      throw new Error("LSP process not started. Please call start_lsp first.");
    }

    this.logLspMessage('SENT', method, params);

    let timeoutId!: NodeJS.Timeout;
    const timeout = new Promise<never>((_, reject) => {
      timeoutId = setTimeout(
        () => reject(new Error(`Timeout waiting for response to ${method} request after ${timeoutMs}ms`)),
        timeoutMs
      );
    });

    try {
      const result = await Promise.race([this.connection.sendRequest<T>(method, params), timeout]);
      this.logLspMessage('RECEIVED', method, result);
      return result;
    } finally {
      clearTimeout(timeoutId);
    }
  }

  private sendNotification(method: string, params?: any): void {
    if (!this.connection) {
      console.error("LSP process not started. Please call start_lsp first.");
      return;
    }

    this.logLspMessage('SENT', method, params);
    this.connection.sendNotification(method, params).catch((error) => {
      warning(`Error sending LSP notification (${method}): ${error instanceof Error ? error.message : String(error)}`);
    });
  }

  async initialize(rootDirectory: string = "."): Promise<void> {
    if (this.initialized) return;

    try {
      // Start the process if it hasn't been started yet
      if (!this.process) {
        await this.startProcess();
      }

      info("Initializing LSP connection...");
      // Initialization covers JVM startup plus project indexing, which can far
      // exceed the default request timeout on larger projects.
      await this.sendRequest<any>("initialize", {
        processId: process.pid,
        clientInfo: {
          name: "lsp-mcp-server"
        },
        rootUri: "file://" + path.resolve(rootDirectory),
        capabilities: {
          textDocument: {
            hover: {
              contentFormat: ["markdown", "plaintext"]
            },
            completion: {
              completionItem: {
                snippetSupport: false
              }
            },
            codeAction: {
              dynamicRegistration: true
            },
            diagnostic: {
              dynamicRegistration: false
            },
            publishDiagnostics: {
              relatedInformation: true,
              versionSupport: false,
              tagSupport: {},
              codeDescriptionSupport: true,
              dataSupport: true
            }
          }
        }
      }, 60000);

      this.sendNotification("initialized", {});
      this.initialized = true;
      notice("LSP connection initialized successfully");
    } catch (error) {
      logError("Failed to initialize LSP connection:", error);
      throw error;
    }
  }

  async openDocument(uri: string, text: string): Promise<void> {
    // Check if initialized, but don't auto-initialize
    if (!this.initialized) {
      throw new Error("LSP client not initialized. Please call start_lsp first.");
    }

    // If document is already open, update it instead of reopening
    if (this.openedDocuments.has(uri)) {
      // Get current version and increment
      const currentVersion = this.documentVersions.get(uri) || 1;
      const newVersion = currentVersion + 1;

      debug(`Document already open, updating content: ${uri} (version ${newVersion})`);
      this.sendNotification("textDocument/didChange", {
        textDocument: {
          uri,
          version: newVersion
        },
        contentChanges: [
          {
            text // Full document update
          }
        ]
      });

      // Update version
      this.documentVersions.set(uri, newVersion);
      return;
    }

    debug(`Opening document: ${uri}`);
    this.sendNotification("textDocument/didOpen", {
      textDocument: {
        uri,
        languageId: this.languageId,
        version: 1,
        text
      }
    });

    // Mark document as open and initialize version
    this.openedDocuments.add(uri);
    this.documentVersions.set(uri, 1);
  }

  // Check if a document is open
  isDocumentOpen(uri: string): boolean {
    return this.openedDocuments.has(uri);
  }

  // Get a list of all open documents
  getOpenDocuments(): string[] {
    return Array.from(this.openedDocuments);
  }
  // save document
  async saveDocument(uri: string, text: string): Promise<void> {
    if (!this.initialized) {
      throw new Error("LSP client not initialized. Please call start_lsp first.");
    }

    if (this.openedDocuments.has(uri)) {
      debug(`Saving document: ${uri}`);
      this.sendNotification("textDocument/didSave", {
        textDocument: {
          uri,
          version: this.documentVersions.get(uri) || 1,
          text
        }
      });
    }
  }

  // Close a document
  async closeDocument(uri: string): Promise<void> {
    // Check if initialized
    if (!this.initialized) {
      throw new Error("LSP client not initialized. Please call start_lsp first.");
    }

    // Only close if document is open
    if (this.openedDocuments.has(uri)) {
      debug(`Closing document: ${uri}`);
      this.sendNotification("textDocument/didClose", {
        textDocument: { uri }
      });

      // Remove from tracking
      this.openedDocuments.delete(uri);
      this.documentVersions.delete(uri);
    } else {
      debug(`Document not open: ${uri}`);
    }
  }

  // Get diagnostics for a file
  getDiagnostics(uri: string): any[] {
    return this.documentDiagnostics.get(uri) || [];
  }

  // Get all diagnostics
  getAllDiagnostics(): Map<string, any[]> {
    return new Map(this.documentDiagnostics);
  }

  // Subscribe to diagnostic updates
  subscribeToDiagnostics(callback: DiagnosticUpdateCallback): void {
    this.diagnosticSubscribers.add(callback);

    // Send initial diagnostics for all open documents
    this.documentDiagnostics.forEach((diagnostics, uri) => {
      callback(uri, diagnostics);
    });
  }

  // Unsubscribe from diagnostic updates
  unsubscribeFromDiagnostics(callback: DiagnosticUpdateCallback): void {
    this.diagnosticSubscribers.delete(callback);
  }

  // Notify all subscribers about diagnostic updates
  private notifyDiagnosticUpdate(uri: string, diagnostics: any[]): void {
    this.diagnosticSubscribers.forEach(callback => {
      try {
        callback(uri, diagnostics);
      } catch (error) {
        warning("Error in diagnostic subscriber callback:", error);
      }
    });
  }

  // Clear all diagnostic subscribers
  clearDiagnosticSubscribers(): void {
    this.diagnosticSubscribers.clear();
  }

  async getInfoOnLocation(uri: string, position: { line: number, character: number }): Promise<string> {
    // Check if initialized, but don't auto-initialize
    if (!this.initialized) {
      throw new Error("LSP client not initialized. Please call start_lsp first.");
    }

    debug(`Getting info on location: ${uri} (${position.line}:${position.character})`);

    try {
      // Use hover request to get information at the position
      const response = await this.sendRequest<any>("textDocument/hover", {
        textDocument: { uri },
        position
      });

      if (response?.contents) {
        if (typeof response.contents === 'string') {
          return response.contents;
        } else if (response.contents.value) {
          return response.contents.value;
        } else if (Array.isArray(response.contents)) {
          return response.contents.map((item: any) =>
            typeof item === 'string' ? item : item.value || ''
          ).join('\n');
        }
      }
    } catch (error) {
      warning(`Error getting hover information: ${error instanceof Error ? error.message : String(error)}`);
    }

    return '';
  }

  async getCompletion(uri: string, position: { line: number, character: number }): Promise<any[]> {
    // Check if initialized, but don't auto-initialize
    if (!this.initialized) {
      throw new Error("LSP client not initialized. Please call start_lsp first.");
    }

    debug(`Getting completions at location: ${uri} (${position.line}:${position.character})`);

    try {
      const response = await this.sendRequest<any>("textDocument/completion", {
        textDocument: { uri },
        position
      });

      if (Array.isArray(response)) {
        return response;
      } else if (response?.items && Array.isArray(response.items)) {
        return response.items;
      }
    } catch (error) {
      warning(`Error getting completions: ${error instanceof Error ? error.message : String(error)}`);
    }

    return [];
  }

  async getCodeActions(uri: string, range: { start: { line: number, character: number }, end: { line: number, character: number } }): Promise<any[]> {
    // Check if initialized, but don't auto-initialize
    if (!this.initialized) {
      throw new Error("LSP client not initialized. Please call start_lsp first.");
    }

    debug(`Getting code actions for range: ${uri} (${range.start.line}:${range.start.character} to ${range.end.line}:${range.end.character})`);

    try {
      const response = await this.sendRequest<any>("textDocument/codeAction", {
        textDocument: { uri },
        range,
        context: {
          diagnostics: []
        }
      });

      if (Array.isArray(response)) {
        return response;
      }
    } catch (error) {
      warning(`Error getting code actions: ${error instanceof Error ? error.message : String(error)}`);
    }

    return [];
  }

  async shutdown(): Promise<void> {
    if (!this.initialized) return;

    try {
      info("Shutting down LSP connection...");

      // Clear all diagnostic subscribers
      this.clearDiagnosticSubscribers();

      // Close all open documents before shutting down
      for (const uri of this.openedDocuments) {
        try {
          this.sendNotification("textDocument/didClose", {
            textDocument: { uri }
          });
        } catch (error) {
          warning(`Error closing document ${uri}:`, error);
        }
      }

      await this.sendRequest("shutdown");
      this.sendNotification("exit");
      this.connection?.dispose();
      this.connection = null;
      this.initialized = false;
      this.openedDocuments.clear();
      notice("LSP connection shut down successfully");
    } catch (error) {
      logError("Error shutting down LSP connection:", error);
    }
  }

  async restart(rootDirectory?: string): Promise<void> {
    info("Restarting LSP server...");

    // If initialized, try to shut down cleanly first
    if (this.initialized) {
      try {
        await this.shutdown();
      } catch (error) {
        warning("Error shutting down LSP server during restart:", error);
      }
    }

    // Kill the process if it's still running
    if (this.process && !this.process.killed) {
      try {
        this.process.kill();
        notice("Killed existing LSP process");
      } catch (error) {
        logError("Error killing LSP process:", error);
      }
    }

    // Reset state
    this.connection?.dispose();
    this.connection = null;
    this.initialized = false;
    this.openedDocuments.clear();
    this.documentVersions.clear();
    this.documentDiagnostics.clear();
    this.clearDiagnosticSubscribers();

    // Start a new process
    await this.startProcess();

    // Initialize with the provided root directory or use the stored one
    if (rootDirectory) {
      await this.initialize(rootDirectory);
      notice(`LSP server restarted and initialized with root directory: ${rootDirectory}`);
    } else {
      info("LSP server restarted but not initialized. Call start_lsp to initialize.");
    }
  }
}
