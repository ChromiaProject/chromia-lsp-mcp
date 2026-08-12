package com.chromia.lspmcp

import com.chromia.lspmcp.lsp.DiagnosticsListener
import com.chromia.lspmcp.lsp.LspLaunch
import com.chromia.lspmcp.lsp.RellLspClient
import com.chromia.lspmcp.mcp.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArraySet

private const val SERVER_NAME = "chromia-lsp-mcp"

private val SERVER_VERSION: String = buildInfoVersion()

private const val INSTRUCTIONS =
    "Rell language intelligence over MCP. Call start_lsp once with the project root, open a file with " +
            "open_document, then query it with get_diagnostics, get_info_on_location, get_completions, " +
            "get_definition, get_references, and the symbol tools. Line and column arguments are 1-based."

/**
 * Takes sole ownership of file descriptor 1 for the MCP stream and points `System.out` at stderr.
 *
 * Anything a library prints to stdout would be read as a JSON-RPC message and corrupt the session —
 * kotlin-logging, which the MCP SDK logs through, announces itself there on first use. Redirecting
 * the JVM's idea of stdout means such output lands harmlessly on stderr instead of mid-protocol.
 */
private fun claimStdout(): OutputStream {
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    val stdout = FileOutputStream(FileDescriptor.out)
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))
    return stdout
}

/** Version stamped into `build-info.properties` at build time. */
private fun buildInfoVersion(): String =
    object {}.javaClass.getResourceAsStream("/build-info.properties")?.use { stream ->
        java.util.Properties().apply { load(stream) }.getProperty("version")
    } ?: "dev"

fun main(): Unit = runBlocking {
    val protocolOutput = claimStdout()
    val lsp = RellLspClient(LspLaunch.fromEnvironment())
    val context = ServerContext(lsp, Path.of("").toAbsolutePath().normalize())

    val server = Server(
        serverInfo = Implementation(name = SERVER_NAME, version = SERVER_VERSION),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
                resources = ServerCapabilities.Resources(listChanged = true, subscribe = true),
                prompts = ServerCapabilities.Prompts(listChanged = false),
                logging = ServerCapabilities.Logging,
            ),
            resourceTemplateMatcherFactory = resourceTemplateMatcherFactory,
        ),
        instructions = INSTRUCTIONS,
    ) {
        registerTools(context)
        registerResources(context)
        registerPrompts()
    }

    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = protocolOutput.asSink().buffered(),
    )

    Log.notice { "Starting Rell LSP MCP server $SERVER_VERSION" }
    val session = server.createSession(transport)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    session.connectNotifications(scope)
    session.handleSubscriptions(context.lsp, scope)

    val closed = CompletableDeferred<Unit>()
    session.onClose { closed.complete(Unit) }
    Log.info { "Ready. Call start_lsp to start the language server." }

    closed.await()
    Log.info { "Client disconnected, shutting down" }
    scope.cancel()
    context.lsp.shutdown()
}

/**
 * Sends log messages to the client. Notifications are queued rather than sent inline because
 * logging happens from callbacks that cannot suspend, and a full channel must never block the
 * language server's I/O threads.
 */
private fun ServerSession.connectNotifications(scope: CoroutineScope) {
    val messages = Channel<LoggingMessageNotification>(Channel.UNLIMITED)

    scope.launch {
        for (message in messages) {
            runCatching { sendLoggingMessage(message) }
        }
    }

    Log.connect { notification -> messages.trySend(notification) }

    setRequestHandler<SetLevelRequest>(Method.Defined.LoggingSetLevel) { request, _ ->
        Log.setLevel(request.level)
        EmptyResult()
    }
}

/**
 * Tracks diagnostics subscriptions and notifies subscribers when the language server republishes.
 *
 * The SDK's built-in subscription bookkeeping only fires for resources whose registration changes,
 * which is not what drives diagnostics — they arrive as server-pushed notifications — so these
 * handlers replace it.
 */
private fun ServerSession.handleSubscriptions(lsp: RellLspClient, scope: CoroutineScope) {
    val subscribed = CopyOnWriteArraySet<String>()
    val updates = Channel<String>(Channel.UNLIMITED)

    scope.launch {
        for (uri in updates) {
            runCatching { sendResourceUpdated(ResourceUpdatedNotification(ResourceUpdatedNotificationParams(uri))) }
        }
    }

    setRequestHandler<SubscribeRequest>(Method.Defined.ResourcesSubscribe) { request, _ ->
        subscribed.add(request.uri)
        Log.debug { "Subscribed to ${request.uri}" }
        EmptyResult()
    }

    setRequestHandler<UnsubscribeRequest>(Method.Defined.ResourcesUnsubscribe) { request, _ ->
        subscribed.remove(request.uri)
        Log.debug { "Unsubscribed from ${request.uri}" }
        EmptyResult()
    }

    lsp.diagnosticsListener = DiagnosticsListener { fileUri, _ ->
        diagnosticsResourceUris(fileUri).filter(subscribed::contains).forEach(updates::trySend)
    }
}
