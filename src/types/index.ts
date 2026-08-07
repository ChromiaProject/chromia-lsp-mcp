// Type definitions

import { z } from "zod";

// Define a type for diagnostic subscribers
export type DiagnosticUpdateCallback = (uri: string, diagnostics: any[]) => void;

// Define a type for subscription context
export interface SubscriptionContext {
  callback: DiagnosticUpdateCallback;
}

// Logging level type
export type LoggingLevel = 'debug' | 'info' | 'notice' | 'warning' | 'error' | 'critical' | 'alert' | 'emergency';

// Resource handler type
export type ResourceHandler = (uri: string) => Promise<{ contents: Array<{ type: string, text: string, uri: string }> }>;

// Subscription handler type
export type SubscriptionHandler = (uri: string) => Promise<{ ok: boolean, context?: SubscriptionContext, error?: string }>;

// Unsubscription handler type
export type UnsubscriptionHandler = (uri: string, context: any) => Promise<{ ok: boolean, error?: string }>;

// Schema definitions
export const GetInfoOnLocationArgsSchema = z.object({
  file_path: z.string().describe("Path to the file"),
  language_id: z.string().describe("The programming language the file is written in"),
  line: z.number().describe(`Line number`),
  column: z.number().describe(`Column position`),
});

export const GetCompletionsArgsSchema = z.object({
  file_path: z.string().describe(`Path to the file`),
  language_id: z.string().describe(`The programming language the file is written in`),
  line: z.number().describe(`Line number`),
  column: z.number().describe(`Column position`),
});

export const GetCodeActionsArgsSchema = z.object({
  file_path: z.string().describe(`Path to the file`),
  language_id: z.string().describe(`The programming language the file is written in`),
  start_line: z.number().describe(`Start line number`),
  start_column: z.number().describe(`Start column position`),
  end_line: z.number().describe(`End line number`),
  end_column: z.number().describe(`End column position`),
});

export const OpenDocumentArgsSchema = z.object({
  file_path: z.string().describe(`Path to the file to open`)
});

export const CloseDocumentArgsSchema = z.object({
  file_path: z.string().describe(`Path to the file to close`),
});

export const SaveDocumentArgsSchema = z.object({
  file_path: z.string().describe(`Path to the file to save`),
});

export const GetDiagnosticsArgsSchema = z.object({
  file_path: z.string().optional().describe(`Path to the file to get diagnostics for. If not provided, returns diagnostics for all open files.`),
});

export const SetLogLevelArgsSchema = z.object({
  level: z.enum(['debug', 'info', 'notice', 'warning', 'error', 'critical', 'alert', 'emergency'])
    .describe("The logging level to set")
});

export const RestartLSPServerArgsSchema = z.object({
  root_dir: z.string().optional().describe("The root directory for the LSP server. If not provided, the server will not be initialized automatically."),
});

export const StartLSPArgsSchema = z.object({
  root_dir: z.string().describe("The root directory for the LSP server"),
});

export const GetDefinitionArgsSchema = z.object({
  file_path: z.string().describe(`Path to the file`),
  line: z.number().describe(`Line number`),
  column: z.number().describe(`Column position`),
});

export const GetReferencesArgsSchema = z.object({
  file_path: z.string().describe(`Path to the file`),
  line: z.number().describe(`Line number`),
  column: z.number().describe(`Column position`),
  include_declaration: z.boolean().optional().describe(`Whether to include the declaration itself in the results. Defaults to true.`),
});

export const GetDocumentSymbolsArgsSchema = z.object({
  file_path: z.string().describe(`Path to the file`),
});

export const GetWorkspaceSymbolsArgsSchema = z.object({
  query: z.string().describe(`Symbol name query to search for across the whole workspace`),
});

export const RenameSymbolArgsSchema = z.object({
  file_path: z.string().describe(`Path to the file`),
  line: z.number().describe(`Line number`),
  column: z.number().describe(`Column position`),
  new_name: z.string().describe(`The new name for the symbol`),
});

export const FormatDocumentArgsSchema = z.object({
  file_path: z.string().describe(`Path to the file`),
  start_line: z.number().optional().describe(`Start line number of the range to format. Omit together with the other range fields to format the whole document.`),
  start_column: z.number().optional().describe(`Start column position of the range to format.`),
  end_line: z.number().optional().describe(`End line number of the range to format.`),
  end_column: z.number().optional().describe(`End column position of the range to format.`),
});

export const ApplyCodeActionArgsSchema = z.object({
  file_path: z.string().describe(`Path to the file the code action was requested for`),
  code_action: z.record(z.string(), z.any()).describe(`The code action object exactly as returned by get_code_actions`),
});
