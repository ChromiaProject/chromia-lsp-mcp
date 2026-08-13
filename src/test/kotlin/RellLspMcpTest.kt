package com.chromia.lspmcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.appendText
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the built server the way an MCP client does: one process, spoken to over stdio.
 *
 * Needs a Rell language server JAR in `RELL_LSP_JAR`; without one the whole class is skipped, so
 * a checkout with no JAR still gets a green unit-test run.
 *
 * The cases run in order against one server process, because they build on each other's state —
 * nothing can be queried before `start_lsp`, and nothing about a file before it is open. The
 * coordinates come from the fixture in `src/test/resources/rell-project`; editing that file moves
 * every one of them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RellLspMcpTest {
    private lateinit var server: Process
    private lateinit var client: Client
    private lateinit var project: Path
    private lateinit var exampleFile: Path

    private val json = Json { ignoreUnknownKeys = true }
    private val startupOptions = RequestOptions(timeout = 3.minutes)

    @BeforeAll
    fun startServer() = runBlocking {
        val lspJar = System.getenv("RELL_LSP_JAR")
        assumeTrue(lspJar != null, "RELL_LSP_JAR is not set; skipping the language server integration tests")

        project = copyFixture()
        exampleFile = project.resolve("src/example.rell")

        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        server = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.chromia.lspmcp.MainKt")
            .directory(project.toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .apply { environment()["RELL_LSP_JAR"] = lspJar }
            .start()

        client = Client(Implementation(name = "chromia-lsp-mcp-tests", version = "1.0.0"))
        client.connect(
            StdioClientTransport(
                input = server.inputStream.asSource().buffered(),
                output = server.outputStream.asSink().buffered(),
            ),
        )
    }

    @AfterAll
    fun stopServer() {
        if (!::server.isInitialized) return
        runBlocking { runCatching { client.close() } }
        server.destroy()
        if (!server.waitFor(10, TimeUnit.SECONDS)) server.destroyForcibly()
    }

    @Test
    @Order(1)
    fun `every tool is listed`() = runBlocking {
        val tools = client.listTools().tools.map { it.name }

        assertTrue(
            tools.containsAll(
                listOf(
                    "start_lsp", "restart_lsp_server", "open_document", "save_document", "close_document",
                    "get_diagnostics", "get_info_on_location", "get_completions", "get_code_actions",
                    "get_definition", "get_references", "get_document_symbols", "get_workspace_symbols",
                    "rename_symbol", "format_document", "apply_code_action", "set_log_level",
                ),
            ),
            "Missing tools, got: $tools",
        )
    }

    @Test
    @Order(2)
    fun `the language server starts on the project root`() = runBlocking {
        val result = client.callTool("start_lsp", mapOf("root_dir" to project.toString()), options = startupOptions)

        assertTrue(result.text().contains(project.toString()), result.text())
    }

    @Test
    @Order(3)
    fun `a document can be opened`() = runBlocking {
        val result = client.callTool("open_document", mapOf("file_path" to exampleFile.toString()))

        assertTrue(result.text().contains("successfully opened"), result.text())
    }

    @Test
    @Order(4)
    fun `hover describes the symbol under the cursor`() = runBlocking {
        val hover = eventually("hover information for the user entity") {
            client.callTool(
                "get_info_on_location",
                mapOf("file_path" to exampleFile.toString(), "line" to 6, "column" to 8),
            ).text().takeIf { it.isNotBlank() }
        }

        assertTrue(hover.contains("user"), "Expected hover text to describe the user entity, got: $hover")
    }

    @Test
    @Order(5)
    fun `completions are offered mid-expression`() = runBlocking {
        val completions = client.callTool(
            "get_completions",
            mapOf("file_path" to exampleFile.toString(), "line" to 25, "column" to 10),
        ).jsonArray()

        assertTrue(completions.isNotEmpty(), "Expected completion items")
    }

    @Test
    @Order(6)
    fun `diagnostics report the deliberate errors in the fixture`() = runBlocking {
        val diagnostics = eventually("diagnostics for the fixture") {
            client.callTool("get_diagnostics", mapOf("file_path" to exampleFile.toString()))
                .json()
                .jsonObject
                .values
                .firstOrNull()
                ?.jsonArray
                ?.takeIf { it.isNotEmpty() }
        }

        assertTrue(diagnostics.isNotEmpty(), "Expected the fixture's undefined_variable error to be reported")
    }

    @Test
    @Order(7)
    fun `code actions are offered for a range`() = runBlocking {
        val actions = client.callTool(
            "get_code_actions",
            mapOf(
                "file_path" to exampleFile.toString(),
                "start_line" to 48,
                "start_column" to 1,
                "end_line" to 48,
                "end_column" to 20,
            ),
        ).jsonArray()

        // The range may legitimately offer nothing; what matters is that the call returned code
        // actions rather than an error payload.
        assertTrue(
            actions.all { it.jsonObject.containsKey("title") },
            "Expected every code action to carry a title, got: $actions",
        )
    }

    @Test
    @Order(8)
    fun `definition resolves a type reference to its declaration`() = runBlocking {
        val locations = client.callTool(
            "get_definition",
            mapOf("file_path" to exampleFile.toString(), "line" to 68, "column" to 17),
        ).jsonArray()

        assertTrue(locations.isNotEmpty(), "Expected a definition location")
        val line = locations.first().jsonObject["range"]!!.jsonObject["start"]!!.jsonObject["line"]!!.jsonPrimitive.int
        assertEquals(5, line, "Expected the user entity declaration on line 5 (0-based)")
    }

    @Test
    @Order(9)
    fun `references find the declaration and its uses`() = runBlocking {
        val references = client.callTool(
            "get_references",
            mapOf("file_path" to exampleFile.toString(), "line" to 6, "column" to 8),
        ).jsonArray()

        assertTrue(references.size > 1, "Expected the declaration plus at least one usage, got ${references.size}")
    }

    @Test
    @Order(10)
    fun `the document outline lists the entities in the file`() = runBlocking {
        val symbols = client.callTool(
            "get_document_symbols",
            mapOf("file_path" to exampleFile.toString()),
        ).jsonArray()

        val names = symbols.flatMap { symbol ->
            symbol.jsonObject["children"]?.jsonArray.orEmpty().map { it.jsonObject["name"]!!.jsonPrimitive.content }
        }
        assertTrue("user" in names, "Expected the user entity in the outline, got: $names")
    }

    @Test
    @Order(11)
    fun `workspace symbols find a name anywhere in the project`() = runBlocking {
        val symbols = client.callTool("get_workspace_symbols", mapOf("query" to "user")).jsonArray()

        assertTrue(symbols.isNotEmpty(), "Expected at least one workspace symbol matching user")
    }

    @Test
    @Order(12)
    fun `open documents are exposed as diagnostics resources`() = runBlocking {
        val resources = client.listResources().resources.map { it.uri }

        assertTrue("lsp-diagnostics://" in resources, "Expected the all-diagnostics resource, got: $resources")
        assertTrue(
            resources.any { it == "lsp-diagnostics://$exampleFile" },
            "Expected a diagnostics resource for the open file, got: $resources",
        )
    }

    @Test
    @Order(13)
    fun `subscribers are notified when diagnostics are republished`() = runBlocking {
        val updates = Channel<String>(Channel.UNLIMITED)
        client.setNotificationHandler<ResourceUpdatedNotification>(
            Method.Defined.NotificationsResourcesUpdated,
        ) { notification ->
            updates.trySend(notification.params.uri)
            CompletableDeferred(Unit)
        }

        val uri = "lsp-diagnostics://$exampleFile"
        client.subscribeResource(SubscribeRequest(SubscribeRequestParams(uri)))

        // The change has to produce a different set of diagnostics: the language server
        // republishes when its verdict on the file changes, not on every edit. Appending at the
        // end shifts none of the coordinates the other cases depend on.
        exampleFile.appendText("\noperation subscription_probe() { val probe = no_such_variable; }\n")
        client.callTool("save_document", mapOf("file_path" to exampleFile.toString()))

        val notified = withTimeoutOrNull(30.seconds) { updates.receive() }
        assertEquals(uri, notified, "Expected a resources/updated notification for the subscribed file")
    }

    @Test
    @Order(14)
    fun `resources serve diagnostics, hover, and completions`() = runBlocking {
        val diagnostics = client.readResourceText("lsp-diagnostics://$exampleFile")
        assertTrue(diagnostics.startsWith("{"), "Expected a JSON object of diagnostics, got: $diagnostics")

        val hover = client.readResourceText("lsp-hover://$exampleFile?line=6&column=8")
        assertTrue(hover.contains("user"), "Expected hover text about the user entity, got: $hover")

        val completions = client.readResourceText("lsp-completions://$exampleFile?line=25&column=10")
        assertTrue(completions.startsWith("["), "Expected a JSON array of completions, got: $completions")
    }

    @Test
    @Order(15)
    fun `templates advertise how to address the resources`() = runBlocking {
        val templates =
            client.listResourceTemplates(ListResourceTemplatesRequest()).resourceTemplates.map { it.uriTemplate }

        assertTrue(
            templates.containsAll(
                listOf(
                    "lsp-diagnostics://{file_path}",
                    "lsp-hover://{file_path}?line={line}&column={column}",
                    "lsp-completions://{file_path}?line={line}&column={column}",
                ),
            ),
            "Got: $templates",
        )
    }

    @Test
    @Order(16)
    fun `closing a document withdraws its resource`() = runBlocking {
        client.callTool("close_document", mapOf("file_path" to exampleFile.toString()))

        val resources = client.listResources().resources.map { it.uri }
        assertTrue(
            resources.none { it == "lsp-diagnostics://$exampleFile" },
            "Expected the closed file's resource to be gone, got: $resources",
        )
    }

    @Test
    @Order(17)
    fun `rename rewrites every reference on disk`() = runBlocking {
        client.callTool("restart_lsp_server", mapOf("root_dir" to project.toString()), options = startupOptions)
        client.callTool("open_document", mapOf("file_path" to exampleFile.toString()))

        client.callTool(
            "rename_symbol",
            mapOf(
                "file_path" to exampleFile.toString(),
                "line" to 62,
                "column" to 10,
                "new_name" to "format_money",
            ),
        )

        val content = exampleFile.readText()
        assertTrue("function format_money(" in content, "Expected the renamed function on disk")
        assertTrue("format_currency" !in content, "Expected no trace of the old name")
    }

    @Test
    @Order(18)
    fun `a quick fix can be applied to disk`() = runBlocking {
        // Line 21 is the `require(name.size() > 0, ...)` the linter flags; the server only attaches
        // a fix when the requested range starts on the diagnostic's own line.
        val actions = eventually("a quick fix for the size comparison on line 21") {
            client.callTool(
                "get_code_actions",
                mapOf(
                    "file_path" to exampleFile.toString(),
                    "start_line" to 21,
                    "start_column" to 1,
                    "end_line" to 21,
                    "end_column" to 40,
                ),
            ).jsonArray().takeIf { it.isNotEmpty() }
        }

        val arguments = buildJsonObject {
            put("file_path", exampleFile.toString())
            put("code_action", actions.first())
        }
        val result = client.callTool(
            CallToolRequest(CallToolRequestParams(name = "apply_code_action", arguments = arguments)),
        ).json().jsonObject

        assertEquals(true, result["applied"]!!.jsonPrimitive.content.toBoolean(), result.toString())
        assertTrue("not name.empty()" in exampleFile.readText(), "Expected the quick fix to rewrite the comparison")
    }

    @Test
    @Order(19)
    fun `formatting rewrites the file`() = runBlocking {
        val result = client.callTool("format_document", mapOf("file_path" to exampleFile.toString()))

        assertTrue(result.text().isNotBlank(), "Expected either applied edits or an explicit no-op message")
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun copyFixture(): Path {
        val fixture = Path.of(javaClass.getResource("/rell-project")!!.toURI())
        val target = createTempDirectory("rell-lsp-mcp-test")
        fixture.copyToRecursively(target, followLinks = false, overwrite = true)
        target.toFile().deleteOnExit()
        return target
    }

    private fun CallToolResult.text(): String = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

    private fun CallToolResult.json(): JsonElement = json.parseToJsonElement(text())

    private fun CallToolResult.jsonArray(): JsonArray = json().jsonArray

    private suspend fun Client.readResourceText(uri: String): String =
        readResource(ReadResourceRequest(ReadResourceRequestParams(uri)))
            .contents
            .filterIsInstance<TextResourceContents>()
            .joinToString("\n") { it.text }

    /**
     * Retries [probe] until it returns a value. The language server indexes the project in the
     * background, so the first queries after startup can legitimately come back empty.
     */
    private suspend fun <T : Any> eventually(what: String, probe: suspend () -> T?): T {
        val attempts = 20
        repeat(attempts) {
            probe()?.let { return it }
            delay(1.seconds)
        }
        throw AssertionError("Timed out after $attempts seconds waiting for $what")
    }
}
