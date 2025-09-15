#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
  ReadResourceRequestSchema,
  ListResourcesRequestSchema,
  SubscribeRequestSchema,
  UnsubscribeRequestSchema,
  SetLevelRequestSchema,
  ListPromptsRequestSchema,
  GetPromptRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";

import { LSPClient } from "./src/lspClient.js";
import { debug, info, notice, warning, logError, critical, alert, emergency, setLogLevel, setServer } from "./src/logging/index.js";
import { getToolHandlers, getToolDefinitions } from "./src/tools/index.js";
import { getPromptHandlers, getPromptDefinitions } from "./src/prompts/index.js";
import {
  getResourceHandlers,
  getSubscriptionHandlers,
  getUnsubscriptionHandlers,
  getResourceTemplates,
  generateResourcesList
} from "./src/resources/index.js";

// We'll create the LSP client but won't initialize it until start_lsp is called
let lspClient: LSPClient | null = null;
let rootDir = "."; // Default to current directory

// Set the LSP client function
const setLspClient = (client: LSPClient) => {
  lspClient = client;
};

/// Set Rell LSP version if found
const rellLspVersion = process.argv[2]

// Set the root directory function
const setRootDir = (dir: string) => {
  rootDir = dir;
};

// Server setup
const server = new Server(
  {
    name: "lsp-mcp-server",
    version: "0.3.0",
    description: "MCP server for Language Server Protocol (LSP) integration, providing hover information, code completions, diagnostics, and code actions with resource-based access and extensibility"
  },
  {
    capabilities: {
      tools: {
        description: "A set of tools for interacting with the Language Server Protocol (LSP). These tools provide access to language-specific features like code completion, hover information, diagnostics, and code actions. Before using any LSP features, you must first call start_lsp with the project root directory, then open the files you wish to analyze."
      },
      resources: {
        description: "URI-based access to Language Server Protocol (LSP) features. These resources provide a way to access language-specific features like diagnostics, hover information, and completions through a URI pattern. Before using these resources, you must first call the start_lsp tool with the project root directory, then open the files you wish to analyze using the open_document tool. Additional resources may be available through language-specific extensions.",
        templates: getResourceTemplates()
      },
      prompts: {
        description: "Helpful prompts related to using the LSP MCP server. These prompts provide guidance on how to use the LSP features and tools available in this server. Additional prompts may be available through language-specific extensions."
      },
      logging: {
        description: "Logging capabilities for the LSP MCP server. Use the set_log_level tool to control logging verbosity. The server sends notifications about important events, errors, and diagnostic updates."
      }
    },
  },
);

// Set the server instance for logging and tools
setServer(server);

// Tool handlers
server.setRequestHandler(ListToolsRequestSchema, async () => {
  debug("Handling ListTools request");
  const toolDefinitions = getToolDefinitions();
  return {
    tools: [...toolDefinitions],
  };
});

// Get the combined tool handlers from core and extensions
const getToolsHandlers = () => {
  // Get core handlers, passing the server instarce for notifications
  const coreHandlers = getToolHandlers(lspClient, setLspClient, rootDir, setRootDir, server);
  // Combine them (extensions take precedence in case of name conflicts)
  return { ...coreHandlers };
};

// Handle tool requests using the toolHandlers object
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  try {
    const { name, arguments: args } = request.params;
    debug(`Handling CallTool request for tool: ${name}`);

    // Get the latest tool handlers and look up the handler for this tool
    const toolHandlers = getToolsHandlers();

    // Check if it's a direct handler or an extension handler
    const toolHandler = toolHandlers[name as keyof typeof toolHandlers];

    if (!toolHandler) {
      throw new Error(`Unknown tool: ${name}`);
    }

    // Validate the arguments against the schema
    const parsed = toolHandler.schema.safeParse(args);
    if (!parsed.success) {
      throw new Error(`Invalid arguments for ${name}: ${parsed.error}`);
    }

    // Call the handler with the validated arguments
    return await toolHandler.handler(parsed.data);

  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logError(`Error handling tool request: ${errorMessage}`);
    return {
      content: [{ type: "text", text: `Error: ${errorMessage}` }],
      isError: true,
    };
  }
});

// Resource handler
server.setRequestHandler(ReadResourceRequestSchema, async (request) => {
  try {
    const uri = request.params.uri;
    debug(`Handling ReadResource request for URI: ${uri}`);

    // Get the core handlers
    const coreHandlers = getResourceHandlers(lspClient);

    const resourceHandlers = { ...coreHandlers };

    // Find the appropriate handler for this URI scheme
    const handlerKey = Object.keys(resourceHandlers).find(key => uri.startsWith(key));
    if (handlerKey) {
      return await resourceHandlers[handlerKey](uri);
    }

    throw new Error(`Unknown resource URI: ${uri}`);
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logError(`Error handling resource request: ${errorMessage}`);
    return {
      contents: [{ type: "text", text: `Error: ${errorMessage}`, uri: request.params.uri }],
      isError: true,
    };
  }
});

// Resource subscription handler
server.setRequestHandler(SubscribeRequestSchema, async (request) => {
  try {
    const { uri } = request.params;
    debug(`Handling SubscribeResource request for URI: ${uri}`);

    // Get the core and extension subscription handlers
    const coreHandlers = getSubscriptionHandlers(lspClient, server);

    const subscriptionHandlers = { ...coreHandlers };

    // Find the appropriate handler for this URI scheme
    const handlerKey = Object.keys(subscriptionHandlers).find(key => uri.startsWith(key));
    if (handlerKey) {
      return await subscriptionHandlers[handlerKey](uri);
    }

    throw new Error(`Unknown resource URI: ${uri}`);
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logError(`Error handling subscription request: ${errorMessage}`);
    return {
      ok: false,
      error: errorMessage
    };
  }
});

// Resource unsubscription handler
server.setRequestHandler(UnsubscribeRequestSchema, async (request) => {
  try {
    const { uri, context } = request.params;
    debug(`Handling UnsubscribeResource request for URI: ${uri}`);

    // Get the core and extension unsubscription handlers
    const coreHandlers = getUnsubscriptionHandlers(lspClient);

    const unsubscriptionHandlers = { ...coreHandlers };

    // Find the appropriate handler for this URI scheme
    const handlerKey = Object.keys(unsubscriptionHandlers).find(key => uri.startsWith(key));
    if (handlerKey) {
      return await unsubscriptionHandlers[handlerKey](uri, context);
    }

    throw new Error(`Unknown resource URI: ${uri}`);
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logError(`Error handling unsubscription request: ${errorMessage}`);
    return {
      ok: false,
      error: errorMessage
    };
  }
});

// Handle log level changes from client
server.setRequestHandler(SetLevelRequestSchema, async (request) => {
  try {
    const { level } = request.params;
    debug(`Received request to set log level to: ${level}`);

    // Set the log level
    setLogLevel(level);

    return {};
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logError(`Error handling set level request: ${errorMessage}`);
    return {
      ok: false,
      error: errorMessage
    };
  }
});

// Resource listing handler
server.setRequestHandler(ListResourcesRequestSchema, async () => {
  try {
    debug("Handling ListResource request");

    // Generate the core resources list
    const coreResources = generateResourcesList(lspClient);

    // Combine core resources and extension templates
    const resources = [...coreResources];

    return { resources };
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logError(`Error handling list resources request: ${errorMessage}`);
    return {
      resources: [],
      isError: true,
      error: errorMessage
    };
  }
});

// Prompt listing handler
server.setRequestHandler(ListPromptsRequestSchema, async () => {
  try {
    debug("Handling ListPrompts request");
    const corePrompts = getPromptDefinitions();
    return {
      prompts: [...corePrompts],
    };
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logError(`Error handling list prompts request: ${errorMessage}`);
    return {
      prompts: [],
      isError: true,
      error: errorMessage
    };
  }
});

// Get prompt handler
server.setRequestHandler(GetPromptRequestSchema, async (request) => {
  try {
    const { name, arguments: args } = request.params;
    debug(`Handling GetPrompt request for prompt: ${name}`);

    const coreHandlers = getPromptHandlers();

    const promptHandlers = { ...coreHandlers };

    const promptHandler = promptHandlers[name];

    if (!promptHandler) {
      throw new Error(`Unknown prompt: ${name}`);
    }

    // Call the handler with the arguments
    return await promptHandler(args);
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logError(`Error handling get prompt request: ${errorMessage}`);
    throw new Error(`Error handling get prompt request: ${errorMessage}`);
  }
});

// Clean up on process exit
process.on('exit', async () => {
  info("Shutting down MCP server...");
  try {
    // Only attempt shutdown if lspClient exists and is initialized
    if (lspClient) {
      await lspClient.shutdown();
    }
  } catch (error) {
    warning("Error during shutdown:", error);
  }
});

// Log uncaught exceptions
process.on('uncaughtException', (error) => {
  const errorMessage = error instanceof Error ? error.message : String(error);

  // Don't exit for "Not connected" errors during startup
  if (errorMessage === 'Not connected') {
    warning(`Uncaught exception (non-fatal): ${errorMessage}`, error);
    return;
  }

  critical(`Uncaught exception: ${errorMessage}`, error);
  // Exit with status code 1 to indicate error
  process.exit(1);
});

// Start server
async function runServer() {
  notice(`Starting LSP MCP Server`);

  const transport = new StdioServerTransport();
  await server.connect(transport);
  notice("LSP MCP Server running on stdio");

  // Create LSP client instance but don't start the process or initialize yet
  // Both will happen when start_lsp is called
  lspClient = new LSPClient(rellLspVersion);
  info("LSP client created. Use the start_lsp tool to start and initialize with a root directory.");
}

runServer().catch((error) => {
  emergency("Fatal error running server:", error);
  process.exit(1);
});
