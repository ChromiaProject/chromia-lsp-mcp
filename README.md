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

## Installation

### Option 1: Install from NPM (Recommended)

Install the package globally using pnpm:

```sh
pnpm install -g @chromia/chromia-lsp-mcp
```

### Option 2: Build from Source

1. Clone this repository:

   ```sh
   git clone https://gitlab.com/chromaway/core-tools/chromia-lsp-mcp
   cd chromia-lsp-mcp
   ```

2. Install dependencies:

   ```sh
   pnpm install
   ```

3. Build the MCP server:

   ```sh
   pnpm run build
   ```

## Configuration

After installation, you need to configure Claude to use the MCP server.

### Claude Configuration for NPM Installation

```json
{
  "mcpServers": {
    "lsp-mcp": {
      "command": "pnpm",
      "args": [
        "exec",
        "chromia-lsp-mcp",
        "0.16.2" // optional Rell LSP version
      ]
    }
  }
}
```

### Claude Configuration for Local Build

```json
{
  "mcpServers": {
    "chromia-lsp-mcp": {
      "command": "node",
      "args": ["/path/to/this/project/dist/index.js"]
    }
  }
}
```

> **Parameters** :
>
> - `Rell LSP version`:
>   optional argument to explicitly set which Rell LSP version it should be used, otherwise, it will look for cached LSP jars, if not found it will download the latest version e.g: `0.16.2`

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

### Additional Features

- Comprehensive logging system with multiple severity levels
- Colorized console output for better readability
- Runtime-configurable log level
- Detailed error handling and reporting
- Automatic LSP server download and caching
- Simple command-line interface

## Testing

The project includes integration tests for the Rell LSP support. These tests verify that the LSP-MCP server correctly handles LSP operations like hover information, completions, diagnostics, and code actions with the Rell language server.

### Running Tests

To run the Rell LSP tests:

```bash
pnpm test
```

### Test Coverage

The tests verify the following functionality:

- Automatic downloading and initialization of the Rell LSP server
- Opening Rell files for analysis
- Getting hover information for functions and types
- Getting code completion suggestions
- Getting diagnostic error messages
- Getting code actions for errors

## Usage

Run the MCP server directly with Node.js:

```bash
node dist/index.js
```

The server automatically downloads and manages the Rell LSP server JAR file, so no additional configuration is needed. The Rell LSP server will be downloaded to `~/.chromia/lsp-mcp/` on first use.

### Logging

The server includes a comprehensive logging system with 8 severity levels:

- `debug`: Detailed information for debugging purposes
- `info`: General informational messages about system operation
- `notice`: Significant operational events
- `warning`: Potential issues that might need attention
- `error`: Error conditions that affect operation but don't halt the system
- `critical`: Critical conditions requiring immediate attention
- `alert`: System is in an unstable state
- `emergency`: System is unusable

By default, logs are sent to:

1. Console output with color-coding for better readability
2. MCP notifications to the client (via the `notifications/message` method)

#### Viewing Debug Logs

For detailed debugging, you can:

1. Use the `claude --mcp-debug` flag when running Claude to see all MCP traffic between Claude and the server:

   ```
   claude --mcp-debug
   ```

2. Ask your AI assistant to change the log level at runtime using the `set_log_level` tool. For example: "Set the log level to debug" or "Use the set_log_level tool with level debug"

   The AI assistant will automatically call the `set_log_level` tool with the specified log level when you make this request.

The default log level is `info`, which shows moderate operational detail while filtering out verbose debug messages.

## Troubleshooting

- **If the server fails to start**, make sure Node.js is installed and in your PATH
- **If LSP server fails to start**, ensure Java JDK is installed and in your PATH
- Check the log file (if configured) for detailed error messages

For more detailed troubleshooting information, see [Setup & Development](./docs/Setup.md).

## License

MIT License

## Acknowledgments

- [@Tritlo/lsp-mcp](https://github.com/Tritlo/lsp-mcp) for the original implementation
- Anthropic for the Model Context Protocol specification
- Microsoft for the Language Server Protocol specification
