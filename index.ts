#!/usr/bin/env node

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  ReadResourceRequestSchema,
  ListResourcesRequestSchema,
  ListResourceTemplatesRequestSchema,
  SubscribeRequestSchema,
  UnsubscribeRequestSchema,
  SetLevelRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";

import { LSPClient } from "./src/lspClient.js";
import { debug, info, notice, warning, logError, critical, alert, emergency, setLogLevel, setServer } from "./src/logging/index.js";
import { registerTools } from "./src/tools/index.js";
import { registerPrompts } from "./src/prompts/index.js";
import {
  getResourceHandlers,
  getSubscriptionHandlers,
  getUnsubscriptionHandlers,
  getResourceTemplates,
  generateResourcesList
} from "./src/resources/index.js";

// We'll create the LSP client but won't initialize it until start_lsp is called
let lspClient: LSPClient | null = null;
let rootDir = "."; // Default to current directory

// Set the LSP client function
const setLspClient = (client: LSPClient) => {
  lspClient = client;
};

/// Set Rell LSP version if found
const rellLspVersion = process.argv[2]

// Set the root directory function
const setRootDir = (dir: string) => {
  rootDir = dir;
};

// Server setup. Tools and prompts are registered through the high-level McpServer API
// below; resources, subscriptions, and log level use the underlying low-level Server
// (mcpServer.server) directly, since this server's dynamic per-open-document resource
// listing and diagnostics-push subscriptions have no McpServer equivalent.
const mcpServer = new McpServer(
  {
    name: "lsp-mcp-server",
    version: "0.3.0",
    description: "MCP server for Language Server Protocol (LSP) integration, providing hover information, code completions, diagnostics, and code actions with resource-based access and extensibility"
  },
  {
    capabilities: {
      resources: {
        subscribe: true
      },
      logging: {}
    },
  },
);
const server = mcpServer.server;

// Set the server instance for logging and tools
setServer(server);

registerTools(mcpServer, () => lspClient, setLspClient, () => rootDir, setRootDir);
registerPrompts(mcpServer);

// Resource handler
server.setRequestHandler(ReadResourceRequestSchema, async (request) => {
  try {
    const uri = request.params.uri;
    debug(`Handling ReadResource request for URI: ${uri}`);

    // Get the core handlers
    const coreHandlers = getResourceHandlers(lspClient);

    const resourceHandlers = { ...coreHandlers };

    // Find the appropriate handler for this URI scheme
    const handlerKey = Object.keys(resourceHandlers).find(key => uri.startsWith(key));
    if (handlerKey) {
      return await resourceHandlers[handlerKey](uri);
    }

    throw new Error(`Unknown resource URI: ${uri}`);
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logError(`Error handling resource request: ${errorMessage}`);
    return {
      contents: [{ type: "text", text: `Error: ${errorMessage}`, uri: request.params.uri }],
      isError: true,
    };
  }
});

// Resource subscription handler
server.setRequestHandler(SubscribeRequestSchema, async (request) => {
  try {
    const { uri } = request.params;
    debug(`Handling SubscribeResource request for URI: ${uri}`);

    // Get the core and extension subscription handlers
    const coreHandlers = getSubscriptionHandlers(lspClient, server);

    const subscriptionHandlers = { ...coreHandlers };

    // Find the appropriate handler for this URI scheme
    const handlerKey = Object.keys(subscriptionHandlers).find(key => uri.startsWith(key));
    if (handlerKey) {
      return await subscriptionHandlers[handlerKey](uri);
    }

    throw new Error(`Unknown resource URI: ${uri}`);
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logError(`Error handling subscription request: ${errorMessage}`);
    return {
      ok: false,
      error: errorMessage
    };
  }
});

// Resource unsubscription handler
server.setRequestHandler(UnsubscribeRequestSchema, async (request) => {
  try {
    const { uri } = request.params;
    debug(`Handling UnsubscribeResource request for URI: ${uri}`);

    // Get the core and extension unsubscription handlers
    const coreHandlers = getUnsubscriptionHandlers(lspClient);

    const unsubscriptionHandlers = { ...coreHandlers };

    // Find the appropriate handler for this URI scheme
    const handlerKey = Object.keys(unsubscriptionHandlers).find(key => uri.startsWith(key));
    if (handlerKey) {
      // The MCP UnsubscribeRequest schema carries no client-supplied "context" field,
      // so the callback-bearing SubscriptionContext returned by subscribe can never
      // round-trip here over real JSON-RPC (it isn't part of the spec's params, and a
      // live function reference wouldn't survive JSON serialization anyway).
      return await unsubscriptionHandlers[handlerKey](uri, undefined);
    }

    throw new Error(`Unknown resource URI: ${uri}`);
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logError(`Error handling unsubscription request: ${errorMessage}`);
    return {
      ok: false,
      error: errorMessage
    };
  }
});

// Handle log level changes from client
server.setRequestHandler(SetLevelRequestSchema, async (request) => {
  try {
    const { level } = request.params;
    debug(`Received request to set log level to: ${level}`);

    // Set the log level
    setLogLevel(level);

    return {};
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logError(`Error handling set level request: ${errorMessage}`);
    return {
      ok: false,
      error: errorMessage
    };
  }
});

// Resource listing handler
server.setRequestHandler(ListResourcesRequestSchema, async () => {
  try {
    debug("Handling ListResource request");

    // Generate the core resources list
    const coreResources = generateResourcesList(lspClient);

    // Combine core resources and extension templates
    const resources = [...coreResources];

    return { resources };
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logError(`Error handling list resources request: ${errorMessage}`);
    return {
      resources: [],
      isError: true,
      error: errorMessage
    };
  }
});

// Resource template listing handler
server.setRequestHandler(ListResourceTemplatesRequestSchema, async () => {
  try {
    debug("Handling ListResourceTemplates request");
    const resourceTemplates = getResourceTemplates().map(({ pattern, name, description }) => ({
      uriTemplate: pattern,
      name,
      description
    }));
    return { resourceTemplates };
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logError(`Error handling list resource templates request: ${errorMessage}`);
    return {
      resourceTemplates: [],
      isError: true,
      error: errorMessage
    };
  }
});

// Clean up on process exit
process.on('exit', async () => {
  info("Shutting down MCP server...");
  try {
    // Only attempt shutdown if lspClient exists and is initialized
    if (lspClient) {
      await lspClient.shutdown();
    }
  } catch (error) {
    warning("Error during shutdown:", error);
  }
});

// Log uncaught exceptions
process.on('uncaughtException', (error) => {
  const errorMessage = error instanceof Error ? error.message : String(error);

  // Don't exit for "Not connected" errors during startup
  if (errorMessage === 'Not connected') {
    warning(`Uncaught exception (non-fatal): ${errorMessage}`, error);
    return;
  }

  critical(`Uncaught exception: ${errorMessage}`, error);
  // Exit with status code 1 to indicate error
  process.exit(1);
});

// Start server
async function runServer() {
  notice(`Starting LSP MCP Server`);

  const transport = new StdioServerTransport();
  await mcpServer.connect(transport);
  notice("LSP MCP Server running on stdio");

  // Create LSP client instance but don't start the process or initialize yet
  // Both will happen when start_lsp is called
  lspClient = new LSPClient(rellLspVersion);
  info("LSP client created. Use the start_lsp tool to start and initialize with a root directory.");
}

runServer().catch((error) => {
  emergency("Fatal error running server:", error);
  process.exit(1);
});
