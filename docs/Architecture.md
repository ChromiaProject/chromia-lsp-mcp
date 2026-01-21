# Technical Architecture & Codebase

## High-Level Architecture Description

LSP MCP Server for Rell follows a modular architecture with separation between protocol layer, tool/resource handlers, LSP client layer, and external process management

**Architecture Layers:**

1. **MCP Protocol Layer** - Handles MCP protocol communication (stdio), tool/resource/prompt registration, and request/response formatting
2. **Handler Layer** - Routes tool calls and resource requests to appropriate handler implementations
3. **LSP Client Layer** - Manages LSP protocol communication, document state, and diagnostic subscriptions
4. **Process Management Layer** - Handles LSP server JAR download, caching, and Java subprocess spawning
5. **External Process Layer** - Rell LSP server (Java process) that provides language-specific features

**Key Architectural:**

- **Stateless design** - No server-side persistence except cached JAR files. Each session is independent.
- **Modular handler pattern** - Each tool/resource has a dedicated handler, making features easy to add and test independently.
- **LSP protocol abstraction** - LSPClient abstracts LSP protocol details, providing clean API for handlers.
- **Automatic dependency management** - LSP server JAR is automatically downloaded and cached, eliminating manual setup.
- **Resource and tool management** - Supports both tool-based and resource-based access patterns for flexibility.

## Major Components and Responsibilities

### 1. Entry Point (`index.ts`)

**Responsibility:** Application bootstrap, MCP server initialization, and protocol setup.

**Key Functions:**
- `runServer()`: Creates MCP server, sets up request handlers, and connects via stdio transport
- Server configuration: Defines server name, version, and capabilities (tools, resources, prompts, logging)
- Request handler registration: Registers handlers for tools, resources, prompts, and logging
- Process lifecycle: Handles cleanup on exit and uncaught exceptions

**Why it matters:** Centralizes server configuration and protocol setup. Single entry point for MCP protocol communication.

**Key Dependencies:**
- `LSPClient` - Provides LSP protocol communication
- `getToolHandlers` - Provides tool implementations
- `getResourceHandlers` - Provides resource implementations
- `getPromptHandlers` - Provides prompt implementations

### 2. LSP Client (`src/lspClient.ts`)

**Responsibility:** Manages LSP protocol communication and document state.

**Key Components:**
- **Process Management:** Spawns and manages Java subprocess for Rell LSP server
- **Message Handling:** Parses LSP protocol messages (JSON-RPC over stdio)
- **Document State:** Tracks open documents, versions, and diagnostics
- **Request/Response:** Sends LSP requests and handles responses with timeout support
- **Notification Handling:** Processes LSP notifications (diagnostics, etc.)
- **Subscription Management:** Manages diagnostic update callbacks

**Key Methods:**
- `initialize(rootDirectory)`: Starts LSP server process and sends initialize request
- `openDocument(uri, text)`: Opens or updates document in LSP server
- `closeDocument(uri)`: Closes document in LSP server
- `getInfoOnLocation(uri, position)`: Gets hover information at position
- `getCompletion(uri, position)`: Gets code completions at position
- `getCodeActions(uri, range)`: Gets code actions for range
- `getDiagnostics(uri)`: Gets cached diagnostics for file
- `subscribeToDiagnostics(callback)`: Subscribes to diagnostic updates
- `restart(rootDirectory?)`: Restarts LSP server process

**Why it matters:** Encapsulates all LSP protocol complexity. Provides clean API for tool/resource handlers.

**Protocol Details:**
- Uses JSON-RPC 2.0 over stdio
- Handles message buffering and parsing
- Converts between 1-based (tool parameters) and 0-based (LSP protocol) positions

### 3. Tool Handlers (`src/tools/index.ts`)

**Responsibility:** Implements MCP tool handlers for LSP features.

**Key Tool Handlers:**

**`get_info_on_location`** - Gets hover information at location
- Extracts `file_path`, `line`, `column` parameters
- Reads file content, opens document if needed
- Calls `lspClient.getInfoOnLocation()`
- Returns hover text

**`get_completions`** - Gets completion suggestions
- Extracts `file_path`, `line`, `column` parameters
- Reads file content, opens document if needed
- Calls `lspClient.getCompletion()`
- Returns completion items as JSON

**`get_code_actions`** - Gets code actions for range
- Extracts `file_path`, `start_line`, `start_column`, `end_line`, `end_column` parameters
- Reads file content, opens document if needed
- Calls `lspClient.getCodeActions()`
- Returns code actions as JSON

**`start_lsp`** - Starts LSP server
- Extracts `root_dir` parameter
- Creates LSPClient if needed
- Calls `lspClient.initialize()`
- Returns success message

**`restart_lsp_server`** - Restarts LSP server
- Extracts optional `root_dir` parameter
- Calls `lspClient.restart()`
- Returns success message

**`open_document`** - Opens file in LSP server
- Extracts `file_path` parameter
- Reads file content
- Calls `lspClient.openDocument()`
- Returns success message

**`save_document`** - Saves file in LSP server
- Extracts `file_path` parameter
- Reads file content
- Calls `lspClient.saveDocument()`
- Returns success message

**`close_document`** - Closes file in LSP server
- Extracts `file_path` parameter
- Calls `lspClient.closeDocument()`
- Returns success message

**`get_diagnostics`** - Gets diagnostics
- Extracts optional `file_path` parameter
- If file_path provided, verifies file is open
- Calls `lspClient.getDiagnostics()` or `getAllDiagnostics()`
- Returns diagnostics as JSON

**`set_log_level`** - Sets logging level
- Extracts `level` parameter
- Calls `setLogLevel()` from logging module
- Returns success message

**Why it matters:** Separation of concerns. Each tool's logic is isolated, making it easy to modify or test individual tools.

**Parameter Validation:**
- Uses Zod schemas for type-safe parameter validation
- Schemas are converted to JSON Schema for MCP tool definitions
- Invalid parameters result in clear error messages

### 4. Resource Handlers (`src/resources/index.ts`)

**Responsibility:** Implements MCP resource handlers for URI-based LSP access.

**Key Resource Handlers:**

**`lsp-diagnostics://`** - Diagnostic resources
- Parses URI to extract file path
- If file path provided, verifies file is open
- Returns diagnostics as JSON
- Supports subscriptions for real-time updates

**`lsp-hover://`** - Hover information resources
- Parses URI to extract file path, line, column from query parameters
- Reads and opens file if needed
- Calls `lspClient.getInfoOnLocation()`
- Returns hover text

**`lsp-completions://`** - Completion resources
- Parses URI to extract file path, line, column from query parameters
- Reads and opens file if needed
- Calls `lspClient.getCompletion()`
- Returns completions as JSON

**Subscription Handling:**
- Diagnostic subscriptions create callbacks that send MCP `notifications/resources/update`
- Callbacks are stored in subscription context for unsubscription
- Subscriptions persist until explicitly unsubscribed

### 5. LSP Server Downloader (`src/downloader/index.ts`)

**Responsibility:** Manages Rell LSP server JAR download and caching.

**Key Functions:**
- `getLspServerPath(version?)`: Gets path to LSP server JAR, downloading if needed
- `fetchLatestVersion()`: Queries GitLab Maven registry for latest version
- `downloadVersion(version)`: Downloads specific version if not cached
- `getLocalVersions()`: Lists all cached versions
- `getLatestLocalVersion()`: Gets latest cached version

**Download Process:**
1. Check if version is specified
2. If specified, check cache for that version
3. If not cached, download from GitLab Maven registry
4. If no version specified, check for cached versions
5. If no cached versions, query GitLab for latest and download

**Caching:**
- JAR files cached in `~/.chromia/lsp-mcp/`
- Filename format: `rell-language-server-{version}-all.jar`
- Cache persists across sessions

**Why it matters:** Eliminates manual LSP server setup. Automatically manages dependencies.

### 6. Logging System (`src/logging/index.ts`)

**Responsibility:** Provides comprehensive logging with multiple severity levels.

**Key Features:**
- **8 Severity Levels:** debug, info, notice, warning, error, critical, alert, emergency
- **Color-coded Console Output:** Different colors for each severity level
- **MCP Notifications:** Sends log messages to MCP clients via notifications
- **Runtime Configuration:** Log level can be changed via `set_log_level` tool
- **Console Override:** Overrides console.log/warn/error to use logging system

**Log Level Priority:**
- Messages are filtered based on current log level
- Higher priority levels (error, critical, etc.) are always shown
- Lower priority levels (debug) are filtered when level is set higher

### 7. Prompt Handlers (`src/prompts/index.ts`)

**Responsibility:** Provides helpful prompts for AI assistants.

**Key Prompts:**

**`lsp_guide`** - Guide on using LSP functions
- Explains how to use LSP tools
- Provides workflow examples
- Documents tool usage patterns

**Why it matters:** Helps AI assistants understand how to use the server effectively.

## How Components Communicate

### Request Flow

```
MCP Client (AI Assistant)
    ↓
MCP Protocol (JSON-RPC over stdio)
    ↓
index.ts (Server Setup)
    ↓
Request Handler (Tool/Resource/Prompt)
    ↓
Handler Implementation (tools/index.ts or resources/index.ts)
    ↓
Parameter Extraction & Validation (Zod schemas)
    ↓
LSPClient (lspClient.ts)
    ↓
LSP Protocol (JSON-RPC over stdio)
    ↓
Rell LSP Server (Java process)
    ↓
Response flows back through chain
    ↓
MCP Client receives structured response
```

### Component Interaction Details

**Tool Execution:**
1. MCP client sends `CallToolRequest` with tool name and arguments
2. Server routes to tool handler based on tool name
3. Handler validates arguments using Zod schema
4. Handler calls LSPClient method with validated parameters
5. LSPClient sends LSP request to Rell LSP server
6. LSP server processes request and sends response
7. LSPClient parses response and returns to handler
8. Handler formats response as `CallToolResult`
9. Server sends response to MCP client

**Resource Access:**
1. MCP client sends `ReadResourceRequest` with URI
2. Server routes to resource handler based on URI scheme
3. Handler parses URI to extract parameters
4. Handler calls LSPClient method
5. Response is formatted as resource content
6. Server sends response to MCP client

**Resource Subscription:**
1. MCP client sends `SubscribeRequest` with URI
2. Server routes to subscription handler
3. Handler creates callback function
4. Handler subscribes callback to LSPClient diagnostic notifications
5. When LSP server sends diagnostic update, callback is invoked
6. Callback sends MCP `notifications/resources/update` to client
7. Client receives real-time updates

**LSP Server Initialization:**
1. Tool handler calls `lspClient.initialize(rootDir)`
2. LSPClient checks if process exists, spawns if needed
3. LSPClient calls downloader to get JAR path
4. Downloader checks cache, downloads if needed
5. LSPClient spawns Java process with JAR
6. LSPClient sends LSP `initialize` request
7. LSP server responds with capabilities
8. LSPClient sends `initialized` notification
9. LSP server is ready for use

## Key Frameworks, Libraries, and Versions

### Core Dependencies

- **Node.js:** v16 or later (runtime requirement)
- **TypeScript:** ^5.3.3 (compilation)
- **MCP TypeScript SDK:** ^0.5.0 (`@modelcontextprotocol/sdk`)
- **Zod:** ^3.22.4 (schema validation)
- **zod-to-json-schema:** ^3.24.5 (schema conversion)
- **axios:** ^1.12.0 (HTTP client for downloads)
- **fs-extra:** ^11.3.1 (file system utilities)
- **fast-xml-parser:** ^5.2.5 (Maven metadata parsing)

### Key Libraries Purpose

- **MCP TypeScript SDK:** Provides MCP protocol implementation, server infrastructure, tool/resource registration, stdio transport
- **Zod:** Runtime type validation for tool parameters, schema definitions
- **axios:** HTTP client for downloading LSP server JAR from GitLab Maven registry
- **fs-extra:** File system operations for reading files and managing cache directory
- **fast-xml-parser:** Parses Maven metadata XML to find latest LSP server version
