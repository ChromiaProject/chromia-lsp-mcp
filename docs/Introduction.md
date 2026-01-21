# Introduction

**Project Name:** LSP MCP Server for Rell

**Repository URL:** https://gitlab.com/chromaway/core-tools/chromia-lsp-mcp 

**NPM Package:** `@chromia/chromia-lsp-mcp`

## Project Summary

LSP MCP Server for Rell is a **Model Context Protocol (MCP)** server that provides LSP information to AI assistants that don't have built-in LSP support, such as Claude (via Claude Desktop or Claude Code) and other MCP-compatible tools. It functions as a bridge between these tools and the Rell LSP server, exposing LSP features through a standardized MCP protocol interface.

The service provides three primary capabilities:

1. **LSP Tool Access** - Query hover information, code completions, diagnostics, and code actions through MCP tools that communicate with the Rell LSP server.

2. **Resource-Based Access** - Access LSP features via URI-based resources (`lsp-diagnostics://`, `lsp-hover://`, `lsp-completions://`) with support for real-time subscriptions.

3. **Automatic LSP Server Management** - Automatically downloads and manages the Rell LSP server JAR file from GitLab Maven registry, handling version selection and caching.

The server uses stdio (standard input/output) transport for MCP protocol communication, running as a subprocess launched by MCP clients.

## Value Creation

LSP MCP Server for Rell enables AI assistants to interact with Rell code by:

- **Eliminating LSP protocol knowledge requirements** - AI assistants can query LSP features using natural language through MCP tools, and the server translates requests into appropriate LSP protocol messages.

- **Providing automatic LSP server management** - The server automatically downloads, caches, and manages the Rell LSP server JAR file, eliminating manual setup steps.

- **Enabling resource-based access patterns** - Supports both tool-based and resource-based access to LSP features, allowing flexibility in how AI assistants interact with the language server.

- **Offering real-time diagnostic updates** - Diagnostic resources support subscriptions for real-time updates when code changes.

- **Providing comprehensive logging** - Built-in logging system with 8 severity levels and runtime-configurable verbosity.

## Upstream and Downstream Projects

### Upstream Dependencies (What LSP MCP Server Consumes)

**1. Rell Language Server (LSP Server)**
- **Role:** Provides language-specific features (hover, completions, diagnostics, code actions) for Rell code
- **Source:** GitLab Maven registry (`net.postchain/rell/toolbox/rell-language-server`)
- **Relationship:** LSP MCP Server downloads and spawns the Rell LSP server as a Java subprocess, communicating via LSP protocol (JSON-RPC over stdio)
- **Critical Dependency:** If Rell LSP server is unavailable or fails to start, all LSP functionality will fail. The server automatically downloads the LSP JAR if not cached locally.

**2. MCP Protocol SDK**
- **Role:** TypeScript SDK for implementing MCP server functionality
- **Package:** `@modelcontextprotocol/sdk` (version ^0.5.0)
- **Relationship:** Provides server infrastructure, tool registration, resource handling, and stdio transport layer
- **Critical Dependency:** Core dependency for MCP protocol implementation.

**3. Java JDK**
- **Role:** Required to run the Rell LSP server JAR file
- **Relationship:** LSP MCP Server spawns Java process to execute the Rell LSP server

## Data and Control Flow

### Request Flow

```
1. AI Assistant Client (Claude Desktop, Claude Code etc.)
   ↓
   MCP Protocol Request (JSON-RPC over stdio)
   ↓
2. LSP MCP Server (index.ts)
   ↓
   Tool/Resource Handler Routing
   ↓
3. Tool/Resource Handler (tools/index.ts or resources/index.ts)
   ↓
   Parameter Extraction & Validation
   ↓
4. LSPClient (lspClient.ts)
   ↓
   LSP Protocol Request (JSON-RPC over stdio)
   ↓
5. Rell LSP Server (Java process)
   ↓
   Language Analysis & Response
   ↓
6. Response flows back through the chain
   ↓
7. AI Assistant receives structured response
```

### Control Flow Details

**Tool Execution:**
- AI assistant sends tool call request with tool name and parameters
- Server routes to appropriate tool handler based on tool name
- Handler extracts and validates parameters using Zod schemas
- Handler calls LSPClient method
- LSPClient sends LSP protocol request to Rell LSP server
- Response is formatted and returned to AI assistant

**LSP Server Initialization:**
- LSP server is not started automatically - must be explicitly started via `start_lsp` tool
- `start_lsp` tool downloads LSP JAR if needed (checks cache first, then GitLab Maven registry)
- LSP server is spawned as Java subprocess
- LSP client sends `initialize` request with root directory
- Files must be opened via `open_document` before accessing diagnostics or other features

**Resource Access:**
- Resources use URI-based access patterns (e.g., `lsp-diagnostics:///path/to/file`)
- Resource handlers parse URIs and extract parameters
- Diagnostic resources support subscriptions for real-time updates
- Hover and completion resources are point-in-time queries (no subscriptions)

**LSP Server Download and Caching:**
- LSP JAR files are cached in `~/.chromia/lsp-mcp/`
- If version is specified, that version is downloaded (if not cached)
- If no version specified, checks for cached versions first
- If no cached versions found, queries GitLab Maven registry for latest version
- Downloads are cached for future use

### Data Persistence

LSP MCP Server maintains minimal state:

- **LSP Server Process** - Running Java subprocess for Rell LSP server
- **Open Documents** - Set of file URIs that are currently open in LSP server
- **Document Versions** - Map tracking version numbers for open documents
- **Diagnostics Cache** - Map storing current diagnostics for open files
- **Diagnostic Subscribers** - Set of callbacks for diagnostic update notifications

The only persistent data is:
- **Cached LSP JAR files** - Stored in `~/.chromia/lsp-mcp/` directory
- **No database or configuration files** - All configuration is passed via tool arguments

## External References and Resources

### MCP Protocol Documentation
- **MCP Protocol Specification:** https://modelcontextprotocol.io/
- **MCP TypeScript SDK:** https://github.com/modelcontextprotocol/typescript-sdk

### LSP Protocol Documentation
- **Language Server Protocol Specification:** https://microsoft.github.io/language-server-protocol/
- **LSP JSON-RPC Specification:** https://www.jsonrpc.org/specification

### Rell Language Documentation
- **Rell Documentation:** https://docs.chromia.com/rell/

### Package Registries
- **NPM Registry:** https://www.npmjs.com/package/@chromia/chromia-lsp-mcp
