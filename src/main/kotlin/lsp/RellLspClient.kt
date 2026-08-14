package com.chromia.lspmcp.lsp

import com.chromia.lspmcp.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.*
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Result of writing one file's share of a workspace edit. */
data class AppliedEdit(val uri: String, val applied: Boolean)

/** Result of applying a code action: which files changed, and whether its command ran. */
data class CodeActionOutcome(val applied: Boolean, val changedFiles: List<String>, val commandExecuted: Boolean)

/** Receives diagnostics as the server pushes them. */
fun interface DiagnosticsListener {
    fun onDiagnostics(uri: String, diagnostics: List<Diagnostic>)
}

/**
 * Drives the Rell language server as a subprocess and speaks LSP to it over LSP4J.
 *
 * The process starts on the first [initialize]. Read-only queries (hover, completion, code
 * actions, symbols, ...) log and return an empty result when the server errors or times out, so
 * an empty result means "no data or the request failed", not "no such symbol" — turn the log
 * level up to `debug` when a result looks wrong.
 *
 * Positions here are LSP's own: zero-based. The MCP layer converts.
 */
class RellLspClient(private val launch: LspLaunch) {
    private val lifecycle = Mutex()
    private val openDocuments = ConcurrentHashMap<String, Int>()
    private val diagnostics = ConcurrentHashMap<String, List<Diagnostic>>()

    private var process: Process? = null
    private var executor: ExecutorService? = null
    private var listening: Future<Void>? = null
    private var remote: LanguageServer? = null

    @Volatile
    private var initialized = false

    @Volatile
    var diagnosticsListener: DiagnosticsListener? = null

    // --- lifecycle ---------------------------------------------------------------------------

    /** Starts the server process if needed and completes the LSP handshake for [rootDirectory]. */
    suspend fun initialize(rootDirectory: Path) {
        lifecycle.withLock {
            if (initialized) return
            startProcess()

            Log.info { "Initializing LSP connection with root directory: $rootDirectory" }
            val chromiaConfigFiles = ChromiaSettings.nonDefaultConfigFileUris(rootDirectory)
            if (chromiaConfigFiles.isNotEmpty()) {
                Log.info { "Non-default Chromia settings files in scope: $chromiaConfigFiles" }
            }
            val params = InitializeParams().apply {
                processId = ProcessHandle.current().pid().toInt()
                clientInfo = ClientInfo(CLIENT_NAME, CLIENT_VERSION)
                @Suppress("DEPRECATION") // The Rell server reads rootUri; it does not use workspace folders.
                rootUri = fileUri(rootDirectory)
                capabilities = clientCapabilities()
                if (chromiaConfigFiles.isNotEmpty()) {
                    // Merged with the server's own by-name discovery of chromia.yml; anchors an
                    // index root at each of these so a directory governed by a non-default
                    // settings file (e.g. atbash.yml, no chromia.yml present) is analysed at that
                    // file's declared compile.rellVersion instead of the server's default.
                    initializationOptions = mapOf("chromiaConfigFiles" to chromiaConfigFiles)
                }
            }

            // Covers JVM startup plus project indexing, which on a large project far exceeds the
            // timeout an ordinary request gets.
            request("initialize", INITIALIZE_TIMEOUT) { server().initialize(params) }
            server().initialized(InitializedParams())
            initialized = true
            Log.notice { "LSP connection initialized successfully" }
        }
    }

    suspend fun shutdown() {
        lifecycle.withLock { shutdownLocked() }
    }

    suspend fun restart(rootDirectory: Path?) {
        Log.info { "Restarting LSP server..." }
        lifecycle.withLock { shutdownLocked() }
        rootDirectory?.let { initialize(it) }
            ?: Log.info { "LSP server stopped. Call start_lsp to start it again." }
    }

    private suspend fun shutdownLocked() {
        val server = remote
        if (server != null && initialized) {
            Log.info { "Shutting down LSP connection..." }
            for (uri in openDocuments.keys) {
                server.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
            }
            runCatching { request("shutdown", SHUTDOWN_TIMEOUT) { server.shutdown() } }
                .onFailure { Log.warning { "Error shutting down LSP connection: ${it.message}" } }
            runCatching { server.exit() }
        }

        listening?.cancel(true)
        process?.destroy()
        withContext(Dispatchers.IO) {
            if (process?.waitFor(SHUTDOWN_TIMEOUT.inWholeSeconds, TimeUnit.SECONDS) == false) {
                Log.warning { "LSP server did not exit in time; killing it" }
                process?.destroyForcibly()
            }
        }
        executor?.shutdownNow()

        listening = null
        process = null
        executor = null
        remote = null
        initialized = false
        openDocuments.clear()
        diagnostics.clear()
    }

    private fun startProcess() {
        Log.info { "Starting Rell language server: ${launch.command().joinToString(" ")}" }
        val started = ProcessBuilder(launch.command())
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        process = started

        // The server's own stderr is diagnostic noise; it belongs at debug level, never on stdout.
        val stderrPump = Thread {
            runCatching {
                started.errorStream.bufferedReader().forEachLine { line -> Log.debug { "LSP: $line" } }
            }
        }
        stderrPump.isDaemon = true
        stderrPump.start()

        started.onExit()
            .thenAccept { exited -> Log.notice { "Rell language server exited with ${exited.exitValue()}" } }

        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "rell-lsp").apply { isDaemon = true }
        }
        executor = pool

        val launcher: Launcher<LanguageServer> = LSPLauncher.Builder<LanguageServer>()
            .setLocalService(ClientEndpoint())
            .setRemoteInterface(LanguageServer::class.java)
            .setInput(PreambleFilteringInputStream(started.inputStream))
            .setOutput(started.outputStream)
            .setExecutorService(pool)
            .create()
        listening = launcher.startListening()
        remote = launcher.remoteProxy
    }

    private fun server(): LanguageServer =
        remote ?: error("LSP server not started. Call start_lsp first with a root directory.")

    private fun requireInitialized(): LanguageServer {
        check(initialized) { "LSP server not started. Call start_lsp first with a root directory." }
        return server()
    }

    // --- documents ---------------------------------------------------------------------------

    /** Opens [uri], or pushes [text] as a new version when it is already open. */
    fun openDocument(uri: String, text: String) {
        val server = requireInitialized()
        val version = openDocuments[uri]
        if (version == null) {
            Log.debug { "Opening document: $uri" }
            server.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, LANGUAGE_ID, 1, text)),
            )
            openDocuments[uri] = 1
            return
        }

        val next = version + 1
        Log.debug { "Document already open, updating content: $uri (version $next)" }
        server.textDocumentService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, next),
                listOf(TextDocumentContentChangeEvent(text)),
            ),
        )
        openDocuments[uri] = next
    }

    fun saveDocument(uri: String, text: String) {
        val server = requireInitialized()
        if (!openDocuments.containsKey(uri)) {
            Log.debug { "Document not open, nothing to save: $uri" }
            return
        }
        Log.debug { "Saving document: $uri" }
        server.textDocumentService.didSave(DidSaveTextDocumentParams(TextDocumentIdentifier(uri), text))
    }

    fun closeDocument(uri: String) {
        val server = requireInitialized()
        if (openDocuments.remove(uri) == null) {
            Log.debug { "Document not open: $uri" }
            return
        }
        Log.debug { "Closing document: $uri" }
        server.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
    }

    fun isDocumentOpen(uri: String): Boolean = openDocuments.containsKey(uri)

    fun openDocumentUris(): Set<String> = openDocuments.keys.toSet()

    fun diagnosticsFor(uri: String): List<Diagnostic> = diagnostics[uri] ?: emptyList()

    /** Diagnostics for every open document, keyed by file URI. */
    fun openDocumentDiagnostics(): Map<String, List<Diagnostic>> =
        diagnostics.filterKeys { openDocuments.containsKey(it) }

    // --- queries -----------------------------------------------------------------------------

    suspend fun hover(uri: String, position: Position): String {
        val server = requireInitialized()
        val hover = query("textDocument/hover") {
            server.textDocumentService.hover(HoverParams(TextDocumentIdentifier(uri), position))
        } ?: return ""

        val contents = hover.contents ?: return ""
        return when {
            contents.isRight -> contents.right.value
            contents.isLeft -> contents.left.joinToString("\n") { if (it.isLeft) it.left else it.right.value }
            else -> ""
        }
    }

    suspend fun completion(uri: String, position: Position): List<CompletionItem> {
        val server = requireInitialized()
        val result = query("textDocument/completion") {
            server.textDocumentService.completion(CompletionParams(TextDocumentIdentifier(uri), position))
        } ?: return emptyList()
        return if (result.isLeft) result.left else result.right.items
    }

    suspend fun codeActions(uri: String, range: Range): List<Either<Command, CodeAction>> {
        val server = requireInitialized()
        val params = CodeActionParams(TextDocumentIdentifier(uri), range, CodeActionContext(emptyList()))
        return query("textDocument/codeAction") { server.textDocumentService.codeAction(params) } ?: emptyList()
    }

    suspend fun definition(uri: String, position: Position): Any {
        val server = requireInitialized()
        val result = query("textDocument/definition") {
            server.textDocumentService.definition(DefinitionParams(TextDocumentIdentifier(uri), position))
        } ?: return emptyList<Any>()
        return if (result.isLeft) result.left else result.right
    }

    suspend fun references(uri: String, position: Position, includeDeclaration: Boolean) =
        requireInitialized().let { server ->
            val params = ReferenceParams(
                TextDocumentIdentifier(uri),
                position,
                ReferenceContext(includeDeclaration),
            )
            query("textDocument/references") { server.textDocumentService.references(params) } ?: emptyList()
        }

    suspend fun documentSymbols(uri: String) = requireInitialized().let { server ->
        query("textDocument/documentSymbol") {
            server.textDocumentService.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri)))
        } ?: emptyList()
    }

    suspend fun workspaceSymbols(name: String): Any {
        val server = requireInitialized()
        val result = query("workspace/symbol") {
            server.workspaceService.symbol(WorkspaceSymbolParams(name))
        } ?: return emptyList<Any>()
        return if (result.isLeft) result.left else result.right
    }

    /** Asks for a rename edit. Callers apply it through [applyWorkspaceEdit]. */
    suspend fun rename(uri: String, position: Position, newName: String): WorkspaceEdit? {
        val server = requireInitialized()
        return query("textDocument/rename") {
            server.textDocumentService.rename(RenameParams(TextDocumentIdentifier(uri), position, newName))
        }
    }

    suspend fun formatDocument(uri: String): List<TextEdit> {
        val server = requireInitialized()
        val params = DocumentFormattingParams(TextDocumentIdentifier(uri), formattingOptions())
        return query("textDocument/formatting") { server.textDocumentService.formatting(params) } ?: emptyList()
    }

    suspend fun formatRange(uri: String, range: Range): List<TextEdit> {
        val server = requireInitialized()
        val params = DocumentRangeFormattingParams(TextDocumentIdentifier(uri), formattingOptions(), range)
        return query("textDocument/rangeFormatting") {
            server.textDocumentService.rangeFormatting(params)
        } ?: emptyList()
    }

    // --- edits -------------------------------------------------------------------------------

    /**
     * Writes a workspace edit to disk. File content is read fresh rather than tracked in memory,
     * matching where the tools source document text; any affected file that is open in the server
     * is resynced afterwards so its view does not go stale.
     */
    fun applyWorkspaceEdit(edit: WorkspaceEdit): List<AppliedEdit> {
        val changes = LinkedHashMap<String, MutableList<TextEdit>>()
        edit.changes?.forEach { (uri, edits) -> changes.getOrPut(uri) { mutableListOf() }.addAll(edits) }

        edit.documentChanges?.forEach { change ->
            if (change.isLeft) {
                val documentEdit = change.left
                changes.getOrPut(documentEdit.textDocument.uri) { mutableListOf() }.addAll(documentEdit.edits)
            } else {
                Log.warning { "Unsupported resource operation in workspace edit; skipping: ${change.right.kind}" }
            }
        }

        return changes.map { (uri, edits) ->
            try {
                val path = uriToPath(uri)
                val updated = applyTextEdits(path.readText(), edits)
                path.writeText(updated)
                if (openDocuments.containsKey(uri)) openDocument(uri, updated)
                Log.debug { "Applied ${edits.size} edit(s) to $uri" }
                AppliedEdit(uri, applied = true)
            } catch (failure: IOException) {
                Log.error { "Error applying edit to $uri: ${failure.message}" }
                AppliedEdit(uri, applied = false)
            }
        }
    }

    /**
     * Applies a code action exactly as `get_code_actions` returned it: resolves it first when the
     * server marked it resolve-needed (no edit, but data present), writes any edit to disk, then
     * runs its command if it has one.
     */
    suspend fun applyCodeAction(action: Either<Command, CodeAction>): CodeActionOutcome {
        val server = requireInitialized()

        var edit: WorkspaceEdit? = null
        val command: Command?

        if (action.isLeft) {
            command = action.left
        } else {
            var resolved = action.right
            if (resolved.edit == null && resolved.data != null) {
                resolved = query("codeAction/resolve") {
                    server.textDocumentService.resolveCodeAction(resolved)
                } ?: resolved
            }
            edit = resolved.edit
            command = resolved.command
        }

        val changedFiles = edit?.let { applyWorkspaceEdit(it).filter(AppliedEdit::applied).map(AppliedEdit::uri) }
            ?: emptyList()

        val commandExecuted = command?.let {
            val params = ExecuteCommandParams(it.command, it.arguments ?: emptyList())
            query("workspace/executeCommand") { server.workspaceService.executeCommand(params) }
            true
        } ?: false

        return CodeActionOutcome(
            applied = changedFiles.isNotEmpty() || commandExecuted,
            changedFiles = changedFiles,
            commandExecuted = commandExecuted,
        )
    }

    // --- plumbing ----------------------------------------------------------------------------

    /** Runs a read-only request, returning null when the server errors or times out. */
    private suspend fun <T> query(method: String, send: () -> CompletableFuture<T>): T? =
        try {
            request(method, REQUEST_TIMEOUT, send)
        } catch (failure: Exception) {
            Log.warning { "Error in $method: ${failure.message}" }
            null
        }

    private suspend fun <T> request(method: String, timeout: Duration, send: () -> CompletableFuture<T>): T {
        Log.debug { "LSP SENT: $method" }
        val future = send()
        val result = try {
            withTimeout(timeout) { future.await() }
        } catch (expired: TimeoutCancellationException) {
            future.cancel(true)
            throw IllegalStateException("Timed out after $timeout waiting for a response to $method", expired)
        }
        Log.debug { "LSP RECEIVED: $method" }
        return result
    }

    private fun clientCapabilities() = ClientCapabilities().apply {
        textDocument = TextDocumentClientCapabilities().apply {
            hover = HoverCapabilities(listOf(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT), false)
            completion = CompletionCapabilities(CompletionItemCapabilities(false))
            codeAction = CodeActionCapabilities(true)
            publishDiagnostics = PublishDiagnosticsCapabilities().apply {
                relatedInformation = true
                versionSupport = false
                codeDescriptionSupport = true
                dataSupport = true
            }
            definition = DefinitionCapabilities()
            references = ReferencesCapabilities()
            documentSymbol = DocumentSymbolCapabilities()
            formatting = FormattingCapabilities()
            rangeFormatting = RangeFormattingCapabilities()
            // The server only advertises renameProvider when this capability is present.
            rename = RenameCapabilities(true, false)
        }
        workspace = WorkspaceClientCapabilities().apply {
            // Must be a real boolean: the server unboxes WorkspaceClientCapabilities.getWorkspaceFolders()
            // without a null check and throws NPE on initialize when it is absent.
            workspaceFolders = false
            symbol = SymbolCapabilities()
            applyEdit = true
            workspaceEdit = WorkspaceEditCapabilities().apply { documentChanges = true }
        }
    }

    private fun formattingOptions() = FormattingOptions(4, true)

    /** The half of the conversation the server drives. */
    private inner class ClientEndpoint : LanguageClient {
        override fun telemetryEvent(payload: Any?) = Unit

        override fun publishDiagnostics(params: PublishDiagnosticsParams) {
            // The Rell server emits short-form "file:/path" URIs while we send "file:///path".
            val uri = params.uri.replace(SHORT_FILE_URI, "file:///")
            val published = params.diagnostics ?: emptyList()
            Log.debug { "Received ${published.size} diagnostics for $uri" }
            diagnostics[uri] = published
            diagnosticsListener?.onDiagnostics(uri, published)
        }

        override fun showMessage(params: MessageParams) = Log.info { "LSP message: ${params.message}" }

        override fun showMessageRequest(
            params: ShowMessageRequestParams,
        ): CompletableFuture<MessageActionItem> {
            Log.info { "LSP message request: ${params.message}" }
            return CompletableFuture.completedFuture(null)
        }

        override fun logMessage(params: MessageParams) = Log.debug { "LSP log: ${params.message}" }

        override fun applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture<ApplyWorkspaceEditResponse> {
            val results = applyWorkspaceEdit(params.edit)
            return CompletableFuture.completedFuture(ApplyWorkspaceEditResponse(results.all(AppliedEdit::applied)))
        }
    }

    companion object {
        private const val CLIENT_NAME = "chromia-lsp-mcp"
        private const val CLIENT_VERSION = "1.0.0"
        private const val LANGUAGE_ID = "rell"
        private val SHORT_FILE_URI = Regex("^file:/(?!/)")
        private val REQUEST_TIMEOUT = 10.seconds
        private val INITIALIZE_TIMEOUT = 60.seconds
        private val SHUTDOWN_TIMEOUT = 5.seconds
    }
}

/** The `file://` URI for [path], percent-encoded the way the LSP server expects. */
fun fileUri(path: Path): String = path.toAbsolutePath().normalize().toUri().toString()

/** Inverse of [fileUri], tolerant of the unencoded URIs some servers hand back. */
fun uriToPath(uri: String): Path =
    runCatching { Path.of(URI.create(uri)) }.getOrElse { Path.of(uri.removePrefix("file://")) }
