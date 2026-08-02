# LSP MCP Server for Rell

A Model Context Protocol (MCP) server that provides access to Rell Language Server Protocol (LSP) capabilities through AI assistants.

## Documentation

- [Introduction](./docs/Introduction.md)
- [Architecture](./docs/Architecture.md)
- [Functionality](./docs/Functional.md)
- [Setup & Development](./docs/Setup.md)

## Overview

The LSP MCP Server for Rell enables AI agents like Claude to query and analyze Rell code by providing programmatic access to LSP features. This is only used for AI agents that don't have LSP built-in like Cursor:

- **Hover Information** - Get type information, documentation, and contextual details about symbols
- **Code Completions** - Get completion suggestions based on current context
- **Diagnostics** - Get errors, warnings, and other diagnostic messages
- **Code Actions** - Get available refactorings and quick fixes
- **Resource-Based Access** - Access LSP features via URI-based resources with real-time subscriptions

The server automatically downloads and manages the Rell LSP server, eliminating manual setup steps.

## Requirements

- Node.js 20 or later or Bun
- Java 21 or later on your `PATH` or in `JAVA_HOME` — only on platforms without a prebuilt runtime bundle. On Linux (x64/arm64), macOS (Intel/Apple Silicon), and Windows (x64/arm64) the server downloads a self-contained Java runtime automatically, so no Java installation is needed.

## Installation

The MCP server is published to npm as [`@chromia/chromia-lsp-mcp`](https://www.npmjs.com/package/@chromia/chromia-lsp-mcp).

### Claude Code

```sh
claude mcp add chromia-lsp -- npx -y @chromia/chromia-lsp-mcp
```

### Copilot in VS Code

```sh
code --add-mcp "{\"name\":\"chromia-lsp\",\"command\":\"npx\",\"args\":[\"-y\",\"@chromia/chromia-lsp-mcp\"]}"
```

### Cursor and other editors

Add the server to the editor's MCP config, which in Cursor's case is `~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "chromia-lsp": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@chromia/chromia-lsp-mcp"]
    }
  }
}
```

Some editors use `servers` instead of `mcpServers`; the entry itself is the same either way.

### Bun and version pinning

To run on Bun, use `"command": "bunx"` and drop the `-y` argument.

Two versions can be pinned independently: the MCP server the usual npm way, and the Rell LSP it drives as a positional argument, as in `"args": ["-y", "@chromia/chromia-lsp-mcp@0.0.4", "0.16.2"]`. Without the latter, the newest published Rell LSP is downloaded and cached.

### Building from source

Only needed to work on the server itself. See [Setup & Development](./docs/Setup.md).

## Features

### MCP Tools

- `start_lsp` - Start the LSP server with a specified root directory (required before using other tools)
- `get_info_on_location` - Get hover information at a specific location in a file
- `get_completions` - Get completion suggestions at a specific location in a file
- `get_code_actions` - Get code actions for a specific range in a file
- `open_document` - Open a file in the LSP server for analysis
- `save_document` - Save a file in the LSP server to refresh diagnostics
- `close_document` - Close a file in the LSP server
- `get_diagnostics` - Get diagnostic messages (errors, warnings) for open files
- `restart_lsp_server` - Restart the LSP server without restarting the MCP server
- `set_log_level` - Change the server's logging verbosity level at runtime

### MCP Resources

- `lsp-diagnostics://` resources for accessing diagnostic messages with real-time updates via subscriptions
- `lsp-hover://` resources for retrieving hover information at specific file locations
- `lsp-completions://` resources for getting code completion suggestions at specific positions

## Usage

Your MCP client launches the server; you never run it by hand. Ask the assistant to work on Rell code and it drives the tools itself, starting with `start_lsp`, which needs a project root. If it picks the wrong directory, name the right one:

> Start the Rell LSP server with root directory /path/to/my/dapp

The Rell language server is downloaded to `~/.chromia/lsp-mcp/` the first time it starts and reused afterwards.

### Logging

The server reports what it is doing to the client as MCP log notifications. To make it more verbose, ask the assistant to set the log level to `debug`, or start it that way from the client config:

```json
{
  "mcpServers": {
    "chromia-lsp": {
      "command": "npx",
      "args": ["-y", "@chromia/chromia-lsp-mcp"],
      "env": { "LOG_LEVEL": "debug" }
    }
  }
}
```

In Claude Code, `claude --mcp-debug` additionally shows the raw traffic between client and server.

## Troubleshooting

On supported platforms the server runs the Rell LSP with a self-contained runtime bundle downloaded to `~/.chromia/lsp-mcp/` and no local Java is involved. If no bundle exists for your platform or the chosen LSP version, it falls back to your own Java: `JAVA_HOME` first, then `PATH` (`which java` / `where java` on Windows). If startup fails asking for Java, check that `java -version` reports 21 or later. Editors launched from the desktop rather than a terminal do not inherit your shell's `PATH`, so a JDK installed through a version manager such as SDKMAN or jenv is often invisible to them; set `JAVA_HOME`, install a system-wide JDK, or start the client from a terminal.

`Version 0.16.x not found in GitLab registry` means the pinned Rell LSP version does not exist. Drop the version argument to take the latest release.

`LSP server not started. Call start_lsp first with a root directory.` is expected right after the client connects. Ask the assistant to start the LSP server on your project root.

If the language server itself misbehaves, delete `~/.chromia/lsp-mcp/`; the runtime bundle or jar is downloaded again on the next start.

## License

MIT License

## Acknowledgments

Built on [@Tritlo/lsp-mcp](https://github.com/Tritlo/lsp-mcp), the original LSP-over-MCP implementation.
