# Setup & Local Development

## Prerequisites

### Required Tools and Versions

**Node.js v16 or later**
**pnpm (Package Manager) v8 or later**
**Java JDK (Java Development Kit) v21 or later**

### Optional Tools

**MCP Inspector** (Optional, for debugging)
- **Purpose:** Web-based tool for testing and debugging MCP servers
- **Installation:** `pnpm install -g @modelcontextprotocol/inspector` or use `pnpm exec`
- **Documentation:** https://www.npmjs.com/package/@modelcontextprotocol/inspector

## Step-by-Step Setup Instructions

### 1. Clone the Repository

```bash
git clone https://gitlab.com/chromaway/core-tools/chromia-lsp-mcp
cd chromia-lsp-mcp
```

### 3. Install Dependencies

```bash
pnpm install
```

### 4. Build the Project

```bash
pnpm run build
```

**What this does:**
- Compiles TypeScript source files to JavaScript
- Outputs compiled files to `dist/` directory
- Creates `dist/index.js` as the main entry point

## Running Locally

### Option 1: Run from Built Files (Recommended)

**Run the MCP server:**
```bash
node dist/index.js
```

**What this does:**
- Starts MCP server in stdio mode
- Reads from `stdin` and writes to `stdout`
- Waits for MCP protocol messages
- LSP server is not started automatically - must be started via `start_lsp` tool

**Note:** In stdio mode, the server reads from `stdin` and writes to `stdout`. This is typically used when the MCP client launches the server as a subprocess.

**To test with a specific Rell LSP version:**
```bash
node dist/index.js 0.16.2
```

This will use Rell LSP version 0.16.2 (or download it if not cached).

### Option 2: Run via NPM Scripts

**Run:**
```bash
node dist/index.js
```

**Watch mode (for development):**
```bash
pnpm run watch
```

This runs TypeScript compiler in watch mode, automatically recompiling on file changes.

### Running the Published Package

The package is published to this project's GitLab npm registry (not npmjs.org), so first map the `@chromia` scope to it:

```bash
npm config set @chromia:registry https://gitlab.com/api/v4/projects/74441198/packages/npm/
```

This is a one-time step: it writes to `~/.npmrc`, which npx, pnpm, and bun all read. On a machine with only Bun installed (no `npm` binary — Bun has no equivalent command), open `~/.npmrc` in an editor instead and add the line by hand:

```
@chromia:registry=https://gitlab.com/api/v4/projects/74441198/packages/npm/
```

The project is public, so no auth token is needed.

**npx:**
```bash
npx -y @chromia/chromia-lsp-mcp
```

**pnpm:**
```bash
pnpm dlx @chromia/chromia-lsp-mcp
```

**bunx:**
```bash
bunx @chromia/chromia-lsp-mcp
```

**Or with a specific Rell LSP version:**
```bash
npx -y @chromia/chromia-lsp-mcp 0.16.2
```

## Testing

### Running Tests

```bash
pnpm run test
```

**What this does:**
- Runs integration tests in `test/rell-lsp.test.js`
- Tests LSP server download, initialization, and basic LSP operations
- Uses test Rell project in `test/rell-project/`

### Test Coverage

The tests verify the following functionality:

- Automatic downloading and initialization of the Rell LSP server
- Opening Rell files for analysis
- Getting hover information for functions and types
- Getting code completion suggestions
- Getting diagnostic error messages
- Getting code actions for errors

### Manual Testing

**1. Use MCP Inspector (Recommended for tool testing)**

**In stdio mode:**
1. Start server: `node dist/index.js`
2. In another terminal, start inspector:
   ```bash
   pnpm exec @modelcontextprotocol/inspector
   ```
3. Open browser to URL shown by inspector (usually `http://localhost:5173`)
4. In the MCP Inspector interface:
   - Choose the transport type to be **stdio**
   - Write the command: `node /path/to/chromia-lsp-mcp/dist/index.js`
   - Press **Connect**
5. Test tools in the web interface:
   - Call `start_lsp` with a root directory
   - Call `open_document` with a Rell file path
   - Call `get_diagnostics` to see errors/warnings
   - Call `get_info_on_location` with line/column
   - Call `get_completions` with line/column

**2. Test with actual MCP client**

Configure your MCP client (Claude Desktop, etc.) to use local server:

**Claude Desktop:**
1. Edit `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or `%APPDATA%/Claude/claude_desktop_config.json` (Windows)
2. Add configuration:
   ```json
   {
     "mcpServers": {
       "chromia-lsp-mcp": {
         "command": "node",
         "args": ["/absolute/path/to/chromia-lsp-mcp/dist/index.js"]
       }
     }
   }
   ```

## How to Use the LSP Server

### Using the MCP Server

Once the Chromia-lsp-mcp MCP server is configured in your MCP client (Claude Desktop, Claude Code, etc.), you simply need to tell the AI assistant to use the Chromia-lsp-mcp MCP server. The AI assistant will automatically use the available LSP tools as needed.

**If you need to reference a different project directory** (other than the current working directory), you can specify the root directory path when asking the AI assistant to start the LSP server. For example:

- "Start the Rell LSP server with root directory `/path/to/your/project`"
- "Use the start_lsp tool with root_dir `/path/to/your/project`"

The AI assistant will automatically call the `start_lsp` tool with your specified root directory when needed.

### Using with Chromia-mcp Server for Enhanced Code Assistance

For better code refactoring and suggestions from the AI, it is recommended to use Chromia-lsp-mcp together with the **Chromia-mcp server**, which contains documentation RAG (Retrieval-Augmented Generation). 

The Chromia-mcp server provides:
- **Documentation RAG** - Access to comprehensive Rell documentation and examples
- **Enhanced context** - Better understanding of Rell language patterns and best practices
- **Improved suggestions** - More accurate code refactoring recommendations based on documentation

When both MCP servers are configured, the AI assistant can:
1. Use Chromia-lsp-mcp for real-time code analysis, diagnostics, and LSP features
2. Use Chromia-mcp for documentation lookups and RAG-based suggestions
3. Combine both sources of information for more accurate and context-aware code assistance

**Configuration:** Add both servers to your MCP client configuration:
```json
{
  "mcpServers": {
    "chromia-lsp-mcp": {
      "command": "node",
      "args": ["/absolute/path/to/chromia-lsp-mcp/dist/index.js"]
    },
    "chromia-mcp": {
      "url": "mcp.chromia.dev"
    }
  }
}
```

## Development Workflow

### Making Code Changes

1. **Edit code** in `src/` directory
2. **Rebuild** (if not using watch mode): `pnpm run build`
3. **Test changes** using MCP Inspector or MCP client
4. **Run tests:** `pnpm test`

### Adding New Tools

1. **Define tool schema** in `src/types/index.ts` (Zod schema)
2. **Add tool handler** in `src/tools/index.ts`
3. **Add tool definition** in `getToolDefinitions()` function
4. **Rebuild:** `pnpm run build`
5. **Test** with MCP Inspector

### Adding New Resources

1. **Add resource handler** in `src/resources/index.ts`
2. **Add resource template** in `getResourceTemplates()` function
3. **Add subscription handler** (if resource supports subscriptions)
4. **Rebuild:** `pnpm run build`
5. **Test** with MCP Inspector

### Debugging

**Enable debug logging:**
- Use `set_log_level` tool with level `debug`
- Or set `LOG_LEVEL=debug` environment variable before starting server

**Log levels** (in increasing severity, default `info`):

`debug`, `info`, `notice`, `warning`, `error`, `critical`, `alert`, `emergency`

Messages at or above the current level go to the console and to the client as `notifications/message`.

**Use MCP Inspector:**
- Provides web interface to test tools
- Shows request/response details
- Helps identify parameter issues

**Check server logs:**
- Server logs all tool requests and responses
- Look for error messages in console output
- Check for exception stack traces

## Additional Development Resources

- **MCP Protocol Specification:** https://modelcontextprotocol.io/
- **MCP TypeScript SDK:** https://github.com/modelcontextprotocol/typescript-sdk
- **Language Server Protocol Specification:** https://microsoft.github.io/language-server-protocol/
- **Rell Documentation:** https://docs.chromia.com/rell/
