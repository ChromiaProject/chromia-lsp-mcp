# LSP MCP Server for Rell

<!--toc:start-->

- [LSP MCP Server for Rell](#lsp-mcp-server-for-rell)
  - [Overview](#overview)
  - [Configuration](#configuration)
    - [Local build](#local-build)
    - [NPM from repository - (Not recommended)](#npm-from-repository-not-recommended)
  - [Features](#features)
    - [MCP Tools](#mcp-tools)
    - [MCP Resources](#mcp-resources)
    - [Additional Features](#additional-features)
  - [Prerequisites](#prerequisites)
  - [Installation](#installation)
    - [Building the MCP Server](#building-the-mcp-server)
  - [Testing](#testing)
    - [Running Tests](#running-tests)
    - [Test Coverage](#test-coverage)
  - [Usage](#usage)
    - [Important: Starting the LSP Server](#important-starting-the-lsp-server)
    - [Logging](#logging)
      - [Viewing Debug Logs](#viewing-debug-logs)
  - [API](#api)
    - [get_info_on_location](#getinfoonlocation)
    - [get_completions](#getcompletions)
    - [get_code_actions](#getcodeactions)
    - [start_lsp](#startlsp)
    - [restart_lsp_server](#restartlspserver)
    - [open_document](#opendocument)
    - [close_document](#closedocument)
    - [get_diagnostics](#getdiagnostics)
    - [set_log_level](#setloglevel)
  - [MCP Resources](#mcp-resources)
    - [Diagnostic Resources](#diagnostic-resources)
    - [Hover Information Resources](#hover-information-resources)
    - [Code Completion Resources](#code-completion-resources)
    - [Listing Available Resources](#listing-available-resources)
    - [Subscribing to Resource Updates](#subscribing-to-resource-updates)
    - [Working with Resources vs. Tools](#working-with-resources-vs-tools)
  - [Troubleshooting](#troubleshooting)
  - [License](#license)
  - [Acknowledgments](#acknowledgments)
  <!--toc:end-->

An MCP (Model Context Protocol) server for interacting with the Rell Language Server Protocol (LSP) interface.
This server acts as a bridge that allows LLMs to query LSP Hover and Completion providers for Rell projects.

**Note**: This project is based on [@Tritlo/lsp-mcp](https://github.com/Tritlo/lsp-mcp) with slight modifications to remove unused code and use it specifically with the Rell language server.
The Rell LSP is automatically downloaded and managed by this server.

## Overview

The MCP Server works by:

1. Starting an LSP client that connects to a LSP server
2. Exposing MCP tools that send requests to the LSP server
3. Returning the results in a format that LLMs can understand and use

This enables LLMs to utilize the Rell LSP for more accurate code suggestions and analysis.

## Configuration

### Local build

```json
{
  "mcpServers": {
    "rell-lsp-mcp": {
      "type": "stdio",
      "command": "node",
      "args": ["/path/to/this/project/dist/index.js"]
    }
  }
}
```

### NPM from repository - (Not recommended)

```sh
npm install git+https://github.com/peaceful-wanderer/lsp-mcp-chromia.git -g
```

- Claude configuration will look like :

```json
{
  "mcpServers": {
    "lsp-mcp": {
      "command": "npx",
      "args": ["chromia-lsp-mcp"]
    }
  }
}
```

Parameters:

- `Rell LSP version`: optional argument to explicitly set which Rell LSP version it should be used, otherwise, it will look for cached LSP jars, if not found it will download the latest version

## Features

### MCP Tools

- `get_info_on_location`: Get hover information at a specific location in a file
- `get_completions`: Get completion suggestions at a specific location in a file
- `get_code_actions`: Get code actions for a specific range in a file
- `open_document`: Open a file in the LSP server for analysis
- `close_document`: Close a file in the LSP server
- `get_diagnostics`: Get diagnostic messages (errors, warnings) for open files
- `start_lsp`: Start the LSP server with a specified root directory
- `restart_lsp_server`: Restart the LSP server without restarting the MCP server
- `set_log_level`: Change the server's logging verbosity level at runtime

### MCP Resources

- `lsp-diagnostics://` resources for accessing diagnostic messages with real-time updates via subscriptions
- `lsp-hover://` resources for retrieving hover information at specific file locations
- `lsp-completions://` resources for getting code completion suggestions at specific positions

### Additional Features

- Comprehensive logging system with multiple severity levels
- Colorized console output for better readability
- Runtime-configurable log level
- Detailed error handling and reporting
- Simple command-line interface

## Prerequisites

- Node.js (v16 or later)
- npm
- Java JDK (for running the Rell LSP server)

## Installation

### Building the MCP Server

1. Clone this repository:

   ```
   git clone ...
   cd lsp-mcp
   ```

2. Install dependencies:

   ```
   npm install
   ```

3. Build the MCP server:

   ```
   npm run build
   ```

## Testing

The project includes integration tests for the Rell LSP support. These tests verify that the LSP-MCP server correctly handles LSP operations like hover information, completions, diagnostics, and code actions with the Rell language server.

### Running Tests

To run the Rell LSP tests:

```
npm test
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

```
node dist/index.js
```

The server automatically downloads and manages the Rell LSP server JAR file, so no additional configuration is needed. The Rell LSP server will be downloaded to `~/.chromia/lsp-mcp/` on first use.

### Important: Starting the LSP Server

You must explicitly start the LSP server by calling the `start_lsp` tool before using any LSP functionality. This ensures proper initialization with the correct root directory for your Rell project:

```json
{
  "tool": "start_lsp",
  "arguments": {
    "root_dir": "/path/to/your/project"
  }
}
```

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

2. Change the log level at runtime using the `set_log_level` tool:

   ```json
   {
     "tool": "set_log_level",
     "arguments": {
       "level": "debug"
     }
   }
   ```

The default log level is `info`, which shows moderate operational detail while filtering out verbose debug messages.

## API

The server provides the following MCP tools:

### get_info_on_location

Gets hover information at a specific location in a file.

Parameters:

- `file_path`: Path to the file
- `line`: Line number
- `column`: Column position

Example:

```json
{
  "tool": "get_info_on_location",
  "arguments": {
    "file_path": "/path/to/your/file.rell",
    "line": 3,
    "column": 5
  }
}
```

### get_completions

Gets completion suggestions at a specific location in a file.

Parameters:

- `file_path`: Path to the file
- `line`: Line number
- `column`: Column position

Example:

```json
{
  "tool": "get_completions",
  "arguments": {
    "file_path": "/path/to/your/file.rell",
    "line": 3,
    "column": 10
  }
}
```

### get_code_actions

Gets code actions for a specific range in a file.

Parameters:

- `file_path`: Path to the file
- `start_line`: Start line number
- `start_column`: Start column position
- `end_line`: End line number
- `end_column`: End column position

Example:

```json
{
  "tool": "get_code_actions",
  "arguments": {
    "file_path": "/path/to/your/file.rell",
    "start_line": 3,
    "start_column": 5,
    "end_line": 3,
    "end_column": 10
  }
}
```

### start_lsp

Starts the LSP server with a specified root directory. This must be called before using any other LSP-related tools.

Parameters:

- `root_dir`: The root directory for the LSP server (absolute path recommended)

Example:

```json
{
  "tool": "start_lsp",
  "arguments": {
    "root_dir": "/path/to/your/project"
  }
}
```

### restart_lsp_server

Restarts the LSP server process without restarting the MCP server.
This is useful for recovering from LSP server issues or for applying changes to the LSP server configuration.

Parameters:

- `root_dir`: (Optional) The root directory for the LSP server. If provided, the server will be initialized with this directory after restart.

Example without root_dir (uses previously set root directory):

```json
{
  "tool": "restart_lsp_server",
  "arguments": {}
}
```

Example with root_dir:

```json
{
  "tool": "restart_lsp_server",
  "arguments": {
    "root_dir": "/path/to/your/project"
  }
}
```

### open_document

Opens a file in the LSP server for analysis. This must be called before accessing diagnostics or performing other operations on the file.

Parameters:

- `file_path`: Path to the file to open

Example:

```json
{
  "tool": "open_document",
  "arguments": {
    "file_path": "/path/to/your/file.rell"
  }
}
```

### close_document

Closes a file in the LSP server when you're done working with it. This helps manage resources and cleanup.

Parameters:

- `file_path`: Path to the file to close

Example:

```json
{
  "tool": "close_document",
  "arguments": {
    "file_path": "/path/to/your/file"
  }
}
```

### get_diagnostics

Gets diagnostic messages (errors, warnings) for one or all open files.

Parameters:

- `file_path`: (Optional) Path to the file to get diagnostics for. If not provided, returns diagnostics for all open files.

Example for a specific file:

```json
{
  "tool": "get_diagnostics",
  "arguments": {
    "file_path": "/path/to/your/file"
  }
}
```

Example for all open files:

```json
{
  "tool": "get_diagnostics",
  "arguments": {}
}
```

### set_log_level

Sets the server's logging level to control verbosity of log messages.

Parameters:

- `level`: The logging level to set. One of: `debug`, `info`, `notice`, `warning`, `error`, `critical`, `alert`, `emergency`.

Example:

```json
{
  "tool": "set_log_level",
  "arguments": {
    "level": "debug"
  }
}
```

## MCP Resources

In addition to tools, the server provides resources for accessing LSP features including diagnostics, hover information, and code completions:

### Diagnostic Resources

The server exposes diagnostic information via the `lsp-diagnostics://` resource scheme. These resources can be subscribed to for real-time updates when diagnostics change.

Resource URIs:

- `lsp-diagnostics://` - Diagnostics for all open files
- `lsp-diagnostics:///path/to/file` - Diagnostics for a specific file

Important: Files must be opened using the `open_document` tool before diagnostics can be accessed.

### Hover Information Resources

The server exposes hover information via the `lsp-hover://` resource scheme. This allows you to get information about code elements at specific positions in files.

Resource URI format:

```
lsp-hover:///path/to/file?line={line}&column={column}
```

Parameters:

- `line`: Line number (1-based)
- `column`: Column position (1-based)

Example:

```
lsp-hover:///home/user/project/src/main.rell?line=42&column=10
```

### Code Completion Resources

The server exposes code completion suggestions via the `lsp-completions://` resource scheme. This allows you to get completion candidates at specific positions in files.

Resource URI format:

```
lsp-completions:///path/to/file?line={line}&column={column}
```

Parameters:

- `line`: Line number (1-based)
- `column`: Column position (1-based)

Example:

```
lsp-completions:///home/user/project/src/main.rell?line=42&column=10
```

### Listing Available Resources

To discover available resources, use the MCP `resources/list` endpoint. The response will include all available resources for currently open files, including:

- Diagnostics resources for all open files
- Hover information templates for all open files
- Code completion templates for all open files

### Subscribing to Resource Updates

Diagnostic resources support subscriptions to receive real-time updates when diagnostics change (e.g., when files are modified and new errors or warnings appear). Subscribe to diagnostic resources using the MCP `resources/subscribe` endpoint.

Note: Hover and completion resources don't support subscriptions as they represent point-in-time queries.

### Working with Resources vs. Tools

You can choose between two approaches for accessing LSP features:

1. Tool-based approach: Use the `get_diagnostics`, `get_info_on_location`, and `get_completions` tools for a simple, direct way to fetch information.
2. Resource-based approach: Use the `lsp-diagnostics://`, `lsp-hover://`, and `lsp-completions://` resources for a more RESTful approach.

Both approaches provide the same data in the same format and enforce the same requirement that files must be opened first.

## Troubleshooting

- If the server fails to start, make sure the path to the LSP executable is correct
- Check the log file (if configured) for detailed error messages

## License

MIT License

## Acknowledgments

- [@Tritlo/lsp-mcp](https://github.com/Tritlo/lsp-mcp) for the original implementation
- Anthropic for the Model Context Protocol specification
