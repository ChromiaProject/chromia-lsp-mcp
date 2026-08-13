package com.chromia.lspmcp.mcp

import com.chromia.lspmcp.Log
import com.chromia.lspmcp.lsp.fileUri
import com.chromia.lspmcp.lsp.lspFromJson
import com.chromia.lspmcp.lsp.lspToJson
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Path

private val FILE_PATH = stringField("Path to the file")

/** Registers every MCP tool this server exposes. */
fun Server.registerTools(context: ServerContext) {
    addTool(
        name = "start_lsp",
        description = "Start the Rell language server for a project. IMPORTANT: this must be called " +
                "before any other LSP tool. The root directory should be the project's base folder — the one " +
                "holding chromia.yml and the src/ directory. Defaults to the server's working directory, " +
                "which is the directory mounted into the container.",
        inputSchema = toolSchema(
            "root_dir" to stringField("The root directory for the LSP server"),
            required = emptyList(),
        ),
    ) { request ->
        val root = request.arguments.stringOrNull("root_dir")?.let { Path.of(it) } ?: context.rootDir
        context.rootDir = root.toAbsolutePath().normalize()
        context.lsp.initialize(context.rootDir)
        textResult("LSP server successfully started with root directory: ${context.rootDir}")
    }

    addTool(
        name = "restart_lsp_server",
        description = "Restart the language server process. Use this to reset the server when it becomes " +
                "unresponsive or holds stale state, or to switch to a different project root.",
        inputSchema = toolSchema(
            "root_dir" to stringField("Root directory to reinitialize with. Defaults to the current one."),
            required = emptyList(),
        ),
    ) { request ->
        val root = request.arguments.stringOrNull("root_dir")?.let { Path.of(it).toAbsolutePath().normalize() }
        root?.let { context.rootDir = it }
        context.lsp.restart(context.rootDir)
        this@registerTools.syncDocumentResources(context)
        textResult("LSP server successfully restarted with root directory: ${context.rootDir}")
    }

    addTool(
        name = "open_document",
        description = "Open a file in the language server for analysis. Diagnostics, hover, and completions " +
                "all require the file to be open. It stays open until explicitly closed.",
        inputSchema = toolSchema(
            "file_path" to stringField("Path to the file to open"),
            required = listOf("file_path")
        ),
    ) { request ->
        val filePath = request.arguments.string("file_path")
        context.openFile(filePath)
        this@registerTools.syncDocumentResources(context)
        textResult("File successfully opened: $filePath")
    }

    addTool(
        name = "save_document",
        description = "Notify the language server that a file was saved, so it refreshes its diagnostics " +
                "for the version currently on disk.",
        inputSchema = toolSchema(
            "file_path" to stringField("Path to the file to save"),
            required = listOf("file_path")
        ),
    ) { request ->
        val filePath = request.arguments.string("file_path")
        val document = context.openFile(filePath)
        context.lsp.saveDocument(document.uri, document.content)
        textResult("File successfully saved: $filePath")
    }

    addTool(
        name = "close_document",
        description = "Close a file in the language server to free the resources it holds. Good practice " +
                "for files no longer being analyzed, especially in long sessions or large codebases.",
        inputSchema = toolSchema(
            "file_path" to stringField("Path to the file to close"),
            required = listOf("file_path")
        ),
    ) { request ->
        val filePath = request.arguments.string("file_path")
        context.lsp.closeDocument(fileUri(Path.of(filePath).toAbsolutePath().normalize()))
        this@registerTools.syncDocumentResources(context)
        textResult("File successfully closed: $filePath")
    }

    addTool(
        name = "get_diagnostics",
        description = "Get diagnostics (errors, warnings) for open files: syntax errors, type mismatches, " +
                "and anything else the language server reports. Without a file path, returns diagnostics for " +
                "every open file. Requires the files to be opened first.",
        inputSchema = toolSchema(
            "file_path" to stringField(
                "Path to the file to get diagnostics for. If omitted, returns diagnostics for all open files.",
            ),
            required = emptyList(),
        ),
    ) { request ->
        val filePath = request.arguments.stringOrNull("file_path")
        if (filePath == null) {
            textResult(lspToJson(context.lsp.openDocumentDiagnostics()))
        } else {
            val uri = fileUri(Path.of(filePath).toAbsolutePath().normalize())
            require(context.lsp.isDocumentOpen(uri)) {
                "File $filePath is not open. Open it with open_document before requesting diagnostics."
            }
            textResult(lspToJson(mapOf(uri to context.lsp.diagnosticsFor(uri))))
        }
    }

    addTool(
        name = "get_info_on_location",
        description = "Get hover information at a location in a file: type information, documentation, and " +
                "other context about the symbol there. Use it to understand what a function or variable is in " +
                "that context. Requires the file to be opened first.",
        inputSchema = toolSchema(
            "file_path" to FILE_PATH,
            "line" to integerField("Line number"),
            "column" to integerField("Column position"),
            required = listOf("file_path", "line", "column"),
        ),
    ) { request ->
        val uri = context.openFile(request.arguments.string("file_path")).uri
        val info = context.lsp.hover(uri, position(request.arguments.int("line"), request.arguments.int("column")))
        textResult(info)
    }

    addTool(
        name = "get_completions",
        description = "Get completion suggestions at a location in a file: names in scope, members, and " +
                "callables valid at that point. Useful for discovering what a library or module offers. " +
                "Requires the file to be opened first.",
        inputSchema = toolSchema(
            "file_path" to FILE_PATH,
            "line" to integerField("Line number"),
            "column" to integerField("Column position"),
            required = listOf("file_path", "line", "column"),
        ),
    ) { request ->
        val uri = context.openFile(request.arguments.string("file_path")).uri
        val completions =
            context.lsp.completion(uri, position(request.arguments.int("line"), request.arguments.int("column")))
        textResult(lspToJson(completions))
    }

    addTool(
        name = "get_code_actions",
        description = "Get the code actions available for a range in a file: refactorings and quick fixes " +
                "such as adding an import or fixing an error. Pass a result to apply_code_action to apply it. " +
                "Requires the file to be opened first.",
        inputSchema = toolSchema(
            "file_path" to FILE_PATH,
            "start_line" to integerField("Start line number"),
            "start_column" to integerField("Start column position"),
            "end_line" to integerField("End line number"),
            "end_column" to integerField("End column position"),
            required = listOf("file_path", "start_line", "start_column", "end_line", "end_column"),
        ),
    ) { request ->
        val uri = context.openFile(request.arguments.string("file_path")).uri
        val range = Range(
            position(request.arguments.int("start_line"), request.arguments.int("start_column")),
            position(request.arguments.int("end_line"), request.arguments.int("end_column")),
        )
        textResult(lspToJson(context.lsp.codeActions(uri, range)))
    }

    addTool(
        name = "get_definition",
        description = "Get the definition location(s) of the symbol at a location in a file. Use it to jump " +
                "to where a function, variable, entity, or type is declared. Requires the file to be opened first.",
        inputSchema = toolSchema(
            "file_path" to FILE_PATH,
            "line" to integerField("Line number"),
            "column" to integerField("Column position"),
            required = listOf("file_path", "line", "column"),
        ),
    ) { request ->
        val uri = context.openFile(request.arguments.string("file_path")).uri
        val locations =
            context.lsp.definition(uri, position(request.arguments.int("line"), request.arguments.int("column")))
        textResult(lspToJson(locations))
    }

    addTool(
        name = "get_references",
        description = "Find every reference to the symbol at a location in a file, across the workspace. " +
                "Requires the file to be opened first.",
        inputSchema = toolSchema(
            "file_path" to FILE_PATH,
            "line" to integerField("Line number"),
            "column" to integerField("Column position"),
            "include_declaration" to booleanField("Whether to include the declaration itself. Defaults to true."),
            required = listOf("file_path", "line", "column"),
        ),
    ) { request ->
        val uri = context.openFile(request.arguments.string("file_path")).uri
        val references = context.lsp.references(
            uri,
            position(request.arguments.int("line"), request.arguments.int("column")),
            request.arguments.booleanOrNull("include_declaration") ?: true,
        )
        textResult(lspToJson(references))
    }

    addTool(
        name = "get_document_symbols",
        description = "Get an outline of the symbols declared in a file — functions, entities, structs, and " +
                "the rest — without reading the whole file. Requires the file to be opened first.",
        inputSchema = toolSchema("file_path" to FILE_PATH, required = listOf("file_path")),
    ) { request ->
        val uri = context.openFile(request.arguments.string("file_path")).uri
        textResult(lspToJson(context.lsp.documentSymbols(uri)))
    }

    addTool(
        name = "get_workspace_symbols",
        description = "Search for symbols by name across the whole workspace. Use it to locate a function, " +
                "entity, or type when you do not know which file holds it.",
        inputSchema = toolSchema(
            "query" to stringField("Symbol name query to search for across the whole workspace"),
            required = listOf("query"),
        ),
    ) { request ->
        textResult(lspToJson(context.lsp.workspaceSymbols(request.arguments.string("query"))))
    }

    addTool(
        name = "rename_symbol",
        description = "Rename the symbol at a location and write the resulting edit to every file it " +
                "touches. Use this instead of find-and-replace for renaming functions, variables, entities, or " +
                "types. Requires the file to be opened first.",
        inputSchema = toolSchema(
            "file_path" to FILE_PATH,
            "line" to integerField("Line number"),
            "column" to integerField("Column position"),
            "new_name" to stringField("The new name for the symbol"),
            required = listOf("file_path", "line", "column", "new_name"),
        ),
    ) { request ->
        val uri = context.openFile(request.arguments.string("file_path")).uri
        val edit = context.lsp.rename(
            uri,
            position(request.arguments.int("line"), request.arguments.int("column")),
            request.arguments.string("new_name"),
        ) ?: return@addTool textResult(
            "Rename failed: the server returned no edit. The position may not be a renameable symbol.",
        )
        textResult(lspToJson(context.lsp.applyWorkspaceEdit(edit)))
    }

    addTool(
        name = "format_document",
        description = "Format a file, or a range within it, and write the result to disk. Provide all four " +
                "range fields to format just that range; omit them all to format the whole document. Requires " +
                "the file to be opened first.",
        inputSchema = toolSchema(
            "file_path" to FILE_PATH,
            "start_line" to integerField("Start line of the range to format. Omit all four to format the file."),
            "start_column" to integerField("Start column of the range to format"),
            "end_line" to integerField("End line of the range to format"),
            "end_column" to integerField("End column of the range to format"),
            required = listOf("file_path"),
        ),
    ) { request ->
        val uri = context.openFile(request.arguments.string("file_path")).uri
        val startLine = request.arguments.intOrNull("start_line")
        val startColumn = request.arguments.intOrNull("start_column")
        val endLine = request.arguments.intOrNull("end_line")
        val endColumn = request.arguments.intOrNull("end_column")

        val edits = if (startLine != null && startColumn != null && endLine != null && endColumn != null) {
            context.lsp.formatRange(uri, Range(position(startLine, startColumn), position(endLine, endColumn)))
        } else {
            context.lsp.formatDocument(uri)
        }

        if (edits.isEmpty()) {
            textResult("No formatting changes were returned by the server.")
        } else {
            val edit = WorkspaceEdit(mapOf(uri to edits))
            textResult(lspToJson(context.lsp.applyWorkspaceEdit(edit)))
        }
    }

    addTool(
        name = "apply_code_action",
        description = "Apply a code action returned by get_code_actions: resolves it if the server needs " +
                "that, writes any resulting edit to disk, and runs its command if it has one. Pass the action " +
                "object through unchanged. Requires the file to be opened first.",
        inputSchema = toolSchema(
            "file_path" to stringField("Path to the file the code action was requested for"),
            "code_action" to objectField("The code action object exactly as returned by get_code_actions"),
            required = listOf("file_path", "code_action"),
        ),
    ) { request ->
        context.openFile(request.arguments.string("file_path"))
        val action = parseCodeAction(request.arguments.jsonObject("code_action"))
        textResult(lspToJson(context.lsp.applyCodeAction(action)))
    }

    addTool(
        name = "set_log_level",
        description = "Set the server's logging verbosity. Levels from least to most verbose: emergency, " +
                "alert, critical, error, warning, notice, info, debug. More verbose helps when troubleshooting " +
                "but produces a lot of output.",
        inputSchema = toolSchema(
            "level" to stringField(
                "One of: debug, info, notice, warning, error, critical, alert, emergency",
            ),
            required = listOf("level"),
        ),
    ) { request ->
        val name = request.arguments.string("level")
        val level = requireNotNull(Log.parseLevel(name)) {
            "Unknown log level: $name. Expected one of ${LoggingLevel.entries.joinToString { it.name.lowercase() }}."
        }
        Log.setLevel(level)
        textResult("Log level set to: ${level.name.lowercase()}")
    }
}

/**
 * Reads back a code action the client got from `get_code_actions`. A bare `Command` has a string
 * `command` field, whereas a `CodeAction` carries an object there, which is how the protocol
 * distinguishes the two.
 */
private fun parseCodeAction(action: JsonObject): Either<Command, CodeAction> {
    val json = action.toString()

    return if (action["command"] is JsonPrimitive) {
        Either.forLeft(lspFromJson(json, Command::class.java))
    } else {
        Either.forRight(lspFromJson(json, CodeAction::class.java))
    }
}
