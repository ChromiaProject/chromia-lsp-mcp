package com.chromia.lspmcp.lsp

import com.google.gson.Gson
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler

/**
 * Gson carrying LSP4J's type adapters, so `Either`, enums, and `Object`-typed payloads round-trip
 * the way the protocol defines them. Plain Gson silently mangles all three.
 */
private val lspGson: Gson = MessageJsonHandler(emptyMap()).gson

private val prettyLspGson: Gson = lspGson.newBuilder().setPrettyPrinting().create()

/** Renders an LSP payload as the JSON text a tool returns to the MCP client. */
fun lspToJson(value: Any?): String = prettyLspGson.toJson(value)

/** Parses JSON text that a client passed back to us, such as a code action from `get_code_actions`. */
fun <T> lspFromJson(json: String, type: Class<T>): T = lspGson.fromJson(json, type)
