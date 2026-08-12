package com.chromia.lspmcp.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

private val GUIDE = """
    # Rell LSP MCP Server Guide

    This server exposes the Rell language server through MCP tools.

    ## Getting started

    1. Start the language server on your project root (the folder holding `chromia.yml`):
       `start_lsp(root_dir: "/path/to/your/project")`
    2. Open a file before asking anything about it:
       `open_document(file_path: "/path/to/your/project/src/main.rell")`
    3. After changing a file, push the new content so diagnostics refresh:
       `save_document(file_path: "/path/to/your/project/src/main.rell")`

    ## Reading code

    - `get_info_on_location` — hover: types, documentation, and context at a position
    - `get_completions` — what is valid at a position
    - `get_definition` — where a symbol is declared
    - `get_references` — every use of a symbol
    - `get_document_symbols` — outline of one file
    - `get_workspace_symbols` — find a symbol anywhere in the project
    - `get_diagnostics` — errors and warnings for open files

    ## Changing code

    - `get_code_actions` then `apply_code_action` — quick fixes and refactorings, written to disk
    - `rename_symbol` — rename across every file that references the symbol
    - `format_document` — format a file or a range within it

    ## Housekeeping

    - `close_document` when a file is no longer being analyzed
    - `restart_lsp_server` if the server goes stale or the project root changes
    - `set_log_level` to make the server more or less talkative

    Line and column arguments are 1-based, matching what an editor shows.
""".trimIndent()

/** Registers the prompts this server offers. */
fun Server.registerPrompts() {
    addPrompt(
        name = "lsp_guide",
        description = "How to use the Rell LSP tools this server provides",
    ) {
        GetPromptResult(
            description = "Rell LSP MCP usage guide",
            messages = listOf(
                PromptMessage(Role.User, TextContent("How do I use the LSP tools in this server?")),
                PromptMessage(Role.Assistant, TextContent(GUIDE)),
            ),
        )
    }
}
