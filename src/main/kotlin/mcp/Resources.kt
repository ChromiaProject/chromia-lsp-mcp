package com.chromia.lspmcp.mcp

import com.chromia.lspmcp.Log
import com.chromia.lspmcp.lsp.fileUri
import com.chromia.lspmcp.lsp.lspToJson
import com.chromia.lspmcp.lsp.uriToPath
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.utils.MatchResult
import io.modelcontextprotocol.kotlin.sdk.utils.ResourceTemplateMatcher
import io.modelcontextprotocol.kotlin.sdk.utils.ResourceTemplateMatcherFactory
import java.nio.file.Path

internal const val DIAGNOSTICS_SCHEME = "lsp-diagnostics://"
private const val JSON = "application/json"

/** URI variable holding everything after the scheme, as produced by [SchemePrefixMatcher]. */
private const val TARGET = "target"

/**
 * Matches any URI in a template's scheme, handing the handler everything after `://` as [TARGET].
 *
 * The SDK's default matcher compares `/`-separated segments and requires the counts to be equal,
 * which no file-path URI can satisfy: `lsp-diagnostics://{file_path}` would only ever match a
 * one-segment path. These resources are addressed by absolute file path plus a query string, so
 * matching is a scheme prefix check and the handlers parse the rest.
 */
private class SchemePrefixMatcher(override val resourceTemplate: ResourceTemplate) : ResourceTemplateMatcher {
    private val scheme = resourceTemplate.uriTemplate.substringBefore("://") + "://"

    override fun match(resourceUri: String): MatchResult? =
        if (resourceUri.startsWith(scheme)) {
            MatchResult(mapOf(TARGET to resourceUri.removePrefix(scheme)), score = scheme.length)
        } else {
            null
        }

    companion object {
        val factory = ResourceTemplateMatcherFactory { SchemePrefixMatcher(it) }
    }
}

/** The matcher factory the server must be configured with for these resources to resolve. */
val resourceTemplateMatcherFactory: ResourceTemplateMatcherFactory = SchemePrefixMatcher.factory

/** Registers the LSP resources and the templates that describe how to address them. */
fun Server.registerResources(context: ServerContext) {
    addResource(
        uri = DIAGNOSTICS_SCHEME,
        name = "All diagnostics",
        description = "Diagnostics for every open file",
        mimeType = JSON,
    ) { request ->
        jsonResource(request.uri, lspToJson(context.lsp.openDocumentDiagnostics()))
    }

    addResourceTemplate(
        uriTemplate = "lsp-diagnostics://{file_path}",
        name = "lsp-diagnostics",
        description = "Diagnostics (errors, warnings) for one file, or for every open file when the path " +
                "is omitted. Supports subscriptions: subscribe to be notified whenever the language server " +
                "republishes diagnostics for it.",
        mimeType = JSON,
    ) { request, variables ->
        val filePath = variables.getValue(TARGET)
        if (filePath.isEmpty()) {
            jsonResource(request.uri, lspToJson(context.lsp.openDocumentDiagnostics()))
        } else {
            val uri = fileUri(Path.of(filePath))
            require(context.lsp.isDocumentOpen(uri)) {
                "File $filePath is not open. Open it with open_document before requesting diagnostics."
            }
            jsonResource(request.uri, lspToJson(mapOf(uri to context.lsp.diagnosticsFor(uri))))
        }
    }

    addResourceTemplate(
        uriTemplate = "lsp-hover://{file_path}?line={line}&column={column}",
        name = "lsp-hover",
        description = "Hover information at a position in a file: type information, documentation, and " +
                "other context about the symbol there.",
        mimeType = "text/plain",
    ) { request, variables ->
        val location = parseLocation(variables.getValue(TARGET))
        val uri = context.openFile(location.filePath).uri
        val hover = context.lsp.hover(uri, position(location.line, location.column))
        ReadResourceResult(listOf(TextResourceContents(hover, request.uri, "text/plain")))
    }

    addResourceTemplate(
        uriTemplate = "lsp-completions://{file_path}?line={line}&column={column}",
        name = "lsp-completions",
        description = "Completion suggestions at a position in a file: names in scope, members, and " +
                "callables valid at that point.",
        mimeType = JSON,
    ) { request, variables ->
        val location = parseLocation(variables.getValue(TARGET))
        val uri = context.openFile(location.filePath).uri
        val completions = context.lsp.completion(uri, position(location.line, location.column))
        jsonResource(request.uri, lspToJson(completions))
    }
}

/**
 * Brings the per-file diagnostics resources in line with the set of open documents, so a client
 * listing resources sees exactly the files the language server currently holds.
 */
fun Server.syncDocumentResources(context: ServerContext) {
    val wanted = context.lsp.openDocumentUris().associateBy { DIAGNOSTICS_SCHEME + uriToPath(it) }
    val registered = resources.keys.filter { it.startsWith(DIAGNOSTICS_SCHEME) && it != DIAGNOSTICS_SCHEME }

    for (uri in registered - wanted.keys) {
        removeResource(uri)
        Log.debug { "Removed diagnostics resource: $uri" }
    }

    for ((resourceUri, documentUri) in wanted.filterKeys { it !in registered }) {
        addResource(
            uri = resourceUri,
            name = "Diagnostics for ${uriToPath(documentUri).fileName}",
            description = "Diagnostics for ${uriToPath(documentUri)}",
            mimeType = JSON,
        ) { request ->
            jsonResource(request.uri, lspToJson(mapOf(documentUri to context.lsp.diagnosticsFor(documentUri))))
        }
        Log.debug { "Added diagnostics resource: $resourceUri" }
    }
}

/** The diagnostics resource URIs a change to [fileUri] should notify subscribers of. */
fun diagnosticsResourceUris(fileUri: String): List<String> =
    listOf(DIAGNOSTICS_SCHEME, DIAGNOSTICS_SCHEME + uriToPath(fileUri))

private data class Location(val filePath: String, val line: Int, val column: Int)

/**
 * Splits `path?line=6&column=8` into its parts. Deliberately textual: these URIs carry raw
 * absolute paths, and running them through a URI parser would percent-decode paths that were
 * never encoded in the resource listing the client is reading back to us.
 */
private fun parseLocation(target: String): Location {
    val filePath = target.substringBefore('?')
    val query = target.substringAfter('?', "")
        .split('&')
        .mapNotNull { parameter ->
            val name = parameter.substringBefore('=')
            val value = parameter.substringAfter('=', "")
            if (name.isEmpty()) null else name to value
        }
        .toMap()

    require(filePath.isNotEmpty()) { "Resource URI is missing a file path" }
    val line = query["line"]?.toIntOrNull()
    val column = query["column"]?.toIntOrNull()
    requireNotNull(line) { "Resource URI needs a numeric line parameter" }
    requireNotNull(column) { "Resource URI needs a numeric column parameter" }
    return Location(filePath, line, column)
}

private fun jsonResource(uri: String, json: String) =
    ReadResourceResult(listOf(TextResourceContents(json, uri, JSON)))
