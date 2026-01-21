# Functional Overview

## High-Level Features

LSP MCP Server for Rell provides three primary feature sets through MCP protocol:

### 1. LSP Tool Access

Direct access to Rell LSP features through MCP tools:

- **Hover Information** - Get type information, documentation, and contextual details about symbols at specific locations
  - Implementation: `getToolHandlers` in `src/tools/index.ts` (get_info_on_location handler), `getInfoOnLocation` in `src/lspClient.ts`
- **Code Completions** - Get completion suggestions based on current context (variables, functions, properties)
  - Implementation: `getToolHandlers` in `src/tools/index.ts` (get_completions handler), `getCompletion` in `src/lspClient.ts`
- **Code Actions** - Get available refactorings, quick fixes, and code modifications for selected ranges
  - Implementation: `getToolHandlers` in `src/tools/index.ts` (get_code_actions handler), `getCodeActions` in `src/lspClient.ts`
- **Diagnostics** - Get errors, warnings, and other diagnostic messages for open files
  - Implementation: `getToolHandlers` in `src/tools/index.ts` (get_diagnostics handler), `getDiagnostics` and `getAllDiagnostics` in `src/lspClient.ts`
- **Document Management** - Open, save, and close files in the LSP server for analysis
  - Implementation: `getToolHandlers` in `src/tools/index.ts` (open_document, save_document, close_document handlers), `openDocument`, `saveDocument`, `closeDocument` in `src/lspClient.ts`
- **Server Control** - Start, restart, and manage the LSP server lifecycle
  - Implementation: `getToolHandlers` in `src/tools/index.ts` (start_lsp handler), `getToolHandlers` in `src/tools/index.ts` (restart_lsp_server handler), `initialize` in `src/lspClient.ts`, `restart` in `src/lspClient.ts`

### 2. Resource-Based Access

URI-based access to LSP features:

- **Diagnostic Resources** - Access diagnostics via `lsp-diagnostics://` URIs with support for real-time subscriptions
  - Implementation: `getResourceHandlers` in `src/resources/index.ts` (lsp-diagnostics:// handler), `getSubscriptionHandlers` in `src/resources/index.ts`
- **Hover Resources** - Access hover information via `lsp-hover://` URIs with line/column parameters
  - Implementation: `getResourceHandlers` in `src/resources/index.ts` (lsp-hover:// handler)
- **Completion Resources** - Access completions via `lsp-completions://` URIs with line/column parameters
  - Implementation: `getResourceHandlers` in `src/resources/index.ts` (lsp-completions:// handler)

### 3. Server Management

Automatic LSP server lifecycle management:

- **Automatic Download** - Downloads Rell LSP server JAR from GitLab Maven registry if not cached
  - Implementation: `getLspServerPath` in `src/downloader/index.ts`, `downloadJarFile` in `src/downloader/index.ts`
- **Version Management** - Supports explicit version specification or automatic latest version detection
  - Implementation: `fetchLatestVersion` in `src/downloader/index.ts`, `getLatestLocalVersion` in `src/downloader/index.ts`
- **Caching** - Caches downloaded JAR files in `~/.chromia/lsp-mcp/` for faster subsequent starts
  - Implementation: `downloadVersion` in `src/downloader/index.ts`
- **Process Management** - Spawns and manages Java subprocess for Rell LSP server
  - Implementation: `startProcess` in `src/lspClient.ts`, `findJavaPath` in `src/lspClient.ts`

## Primary User/System Flows

### LSP Server Initialization Flow

**If a user wants to use LSP features:**

1. AI assistant calls `start_lsp` tool with `root_dir` parameter
   - Implementation: `getToolHandlers` in `src/tools/index.ts` (start_lsp handler)
2. Server checks if LSP JAR is cached locally
   - Implementation: `downloadVersion` in `src/downloader/index.ts`
3. If not cached and version specified, downloads that version from GitLab Maven registry
   - Implementation: `downloadJarFile` in `src/downloader/index.ts`
4. If not cached and no version specified, checks for latest cached version
   - Implementation: `getLatestLocalVersion` in `src/downloader/index.ts`
5. If no cached versions, queries GitLab Maven registry for latest version and downloads it
   - Implementation: `fetchLatestVersion` in `src/downloader/index.ts`, `downloadJarFile` in `src/downloader/index.ts`
6. Server spawns Java process with LSP JAR
   - Implementation: `startProcess` in `src/lspClient.ts`
7. Server sends LSP `initialize` request with root directory
   - Implementation: `initialize` in `src/lspClient.ts`
8. Server sends LSP `initialized` notification
   - Implementation: `initialize` in `src/lspClient.ts`
9. LSP server is ready for use

**If Java is not found:**
- Server throws error: "Java JDK required"
- User must install Java and ensure it's in PATH

**If LSP server fails to start:**
- Server throws error with details
- User can try restarting with `restart_lsp_server` tool

### File Analysis Flow

**If a user wants to analyze a file:**

1. **Step 1:** AI assistant calls `start_lsp` with project root directory (if not already started)
   - Implementation: `getToolHandlers` in `src/tools/index.ts` (start_lsp handler)
2. **Step 2:** AI assistant calls `open_document` with file path
   - Implementation: `getToolHandlers` in `src/tools/index.ts` (open_document handler)
3. **Step 3:** Server reads file content from filesystem
   - Implementation: `getToolHandlers` in `src/tools/index.ts` (fs.readFile)
4. **Step 4:** Server sends LSP `textDocument/didOpen` notification with file content
   - Implementation: `openDocument` in `src/lspClient.ts`
5. **Step 5:** LSP server analyzes file and may send diagnostic notifications
   - Implementation: `handleMessage` in `src/lspClient.ts` (textDocument/publishDiagnostics handling)
6. **Step 6:** AI assistant can now call tools like `get_diagnostics`, `get_info_on_location`, `get_completions`
   - Implementation: `getToolHandlers` in `src/tools/index.ts` (get_diagnostics, get_info_on_location, get_completions)

**If file is already open:**
- Server detects document is already open
  - Implementation: `openDocument` in `src/lspClient.ts`
- Server sends `textDocument/didChange` notification instead of `didOpen`
  - Implementation: `openDocument` in `src/lspClient.ts`
- Document version is automatically incremented
  - Implementation: `openDocument` in `src/lspClient.ts`

**If file doesn't exist:**
- Server throws error: "Failed to open document: ENOENT: no such file or directory"

### Hover Information Flow

**If a user wants hover information at a location:**

1. AI assistant calls `get_info_on_location` with file path, line, and column
2. Server checks if LSP client is initialized (throws error if not)
3. Server reads file content (if file not already open)
4. Server opens document in LSP server (if not already open)
5. Server sends LSP `textDocument/hover` request with position (converted to 0-based)
6. LSP server returns hover information
7. Server formats and returns response to AI assistant

### Code Completion Flow

**If AI Assistant wants code completions:**

1. AI assistant calls `get_completions` with file path, line, and column
2. Server checks if LSP client is initialized
3. Server reads and opens file if needed
4. Server sends LSP `textDocument/completion` request
5. LSP server returns completion items
6. Server normalizes response format and returns to AI assistant
7. Response is used to refactor Rell code etc

### Diagnostic Flow

**If AI Assistant wants diagnostics:**

1. AI assistant calls `get_diagnostics` with optional file path
2. Server checks if LSP client is initialized
3. If file path provided:
   - Server verifies file is open (throws error if not)
   - Server retrieves cached diagnostics for that file
4. If no file path provided:
   - Server retrieves diagnostics for all open files
5. Server returns diagnostics as JSON
6. AI Assistant uses the diagnostics to further fix Rell code

**Diagnostic updates:**
- LSP server sends `textDocument/publishDiagnostics` notifications automatically
- Server caches diagnostics and notifies subscribers
- Diagnostic resources support subscriptions for real-time updates

### Code Actions Flow

**If AI Assistant wants code actions:**

1. AI assistant calls `get_code_actions` with file path and range (start/end line/column)
2. Server checks if LSP client is initialized
3. Server reads and opens file if needed
4. Server sends LSP `textDocument/codeAction` request with range and context
5. LSP server returns available code actions
6. Server returns actions as JSON array
7. AI Assistant uses the response to further fix Rell code

### Resource Subscription Flow

**If a user subscribes to diagnostic resources:**

1. AI assistant calls MCP `resources/subscribe` with `lsp-diagnostics://` URI
   - Implementation: `setRequestHandler` for `SubscribeRequestSchema` in `index.ts`
2. Server checks if LSP client is initialized
   - Implementation: `getSubscriptionHandlers` in `src/resources/index.ts`
3. Server creates callback function for diagnostic updates
   - Implementation: `getSubscriptionHandlers` in `src/resources/index.ts`
4. Server subscribes callback to LSP client's diagnostic notifications
   - Implementation: `subscribeToDiagnostics` in `src/lspClient.ts`
5. When LSP server sends diagnostic updates, callback is invoked
   - Implementation: `handleMessage` in `src/lspClient.ts` (textDocument/publishDiagnostics), `notifyDiagnosticUpdate` in `src/lspClient.ts`
6. Server sends MCP `notifications/resources/update` to client
   - Implementation: `getSubscriptionHandlers` in `src/resources/index.ts`
7. Client receives real-time diagnostic updates

**Unsubscription:**
- AI assistant calls MCP `resources/unsubscribe` with URI and context
  - Implementation: `setRequestHandler` for `UnsubscribeRequestSchema` in `index.ts`
- Server removes callback from subscription set
  - Implementation: `getUnsubscriptionHandlers` in `src/resources/index.ts`, `unsubscribeFromDiagnostics` in `src/lspClient.ts`
- No further updates are sent

## Important Assumptions

### LSP Server Assumptions

- **LSP server must be explicitly started** - `start_lsp` must be called before any LSP operations
- **LSP protocol is JSON-RPC over stdio** - All communication uses standard LSP protocol format
- **Position conversion** - Tool parameters use 1-based positions, but LSP uses 0-based positions (conversion handled automatically)

## Non-Obvious or Surprising Behavior

### Document Versioning

**Automatic version increment:**
- When a document is already open and `open_document` is called again, server sends `didChange` notification instead of `didOpen`
- Document version is automatically incremented
- This allows updating LSP server with file changes without explicitly closing/reopening

### Position Conversion

**1-based to 0-based conversion:**
- Tool parameters use 1-based positions (first line is 1, first character is 1)
- LSP protocol uses 0-based positions (first line is 0, first character is 0)
- Server automatically converts positions when calling LSP methods
- This matches common editor conventions (1-based) while respecting LSP protocol (0-based)

### Diagnostic Caching

**Diagnostics are cached:**
- Diagnostics are stored in memory when received from LSP server
- `get_diagnostics` returns cached diagnostics, not fresh queries
- Diagnostics are updated automatically via `publishDiagnostics` notifications
- This provides fast access but may show stale data if LSP server hasn't sent updates

### LSP Server Restart Behavior

**Restart preserves root directory:**
- `restart_lsp_server` can be called with optional `root_dir` parameter
- If `root_dir` not provided, uses current directory
- All open documents are closed during restart
- Diagnostic subscribers are cleared during restart

### Resource URI Parsing

**URI path normalization:**
- Resource URIs use `file://` scheme internally
- Paths are normalized to handle different URI formats
- Query parameters are parsed for hover and completion resources
- File paths in URIs must match opened document URIs exactly

## Error Handling Behavior

### LSP Client Not Initialized

**If LSP operations are called before `start_lsp`:**
- Server throws error: "LSP server not started. Call start_lsp first with a root directory."
- Error is returned to AI assistant as tool error response

### File Not Found

**If file doesn't exist:**
- Server throws error: "Failed to open document: ENOENT: no such file or directory"
- Error is returned to AI assistant

### File Not Open

**If diagnostics requested for unopened file:**
- Server throws error: "File {path} is not open. Please open the file with open_document before requesting diagnostics."
- Error is returned to AI assistant

### LSP Request Timeout

**If LSP request times out:**
- Server throws error: "Timeout waiting for response to {method} request"
- Error is returned to AI assistant
- LSP client continues to function (timeout only affects specific request)

### Java Not Found

**If Java is not in PATH:**
- Server throws error: "Java JDK required"
- Error is returned to AI assistant
- Server cannot start LSP process

### LSP Server Download Failure

**If download fails:**
- Server throws error with details (network error, 404, etc.)
- Error is returned to AI assistant
- Server checks for cached versions before attempting download

### Error Response Format

All errors are returned as tool error responses with error messages. Error messages are plain text strings describing the failure reason.
