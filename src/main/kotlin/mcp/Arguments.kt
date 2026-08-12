package com.chromia.lspmcp.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import org.eclipse.lsp4j.Position

/**
 * Tool argument access and JSON Schema construction.
 *
 * Arguments arrive as an untyped [JsonObject]; every accessor here fails with a message naming
 * the offending argument, which the SDK turns into a tool error the model can act on.
 *
 * Line and column arguments are 1-based, the way an editor reports them. [position] is the one
 * place they become the 0-based coordinates LSP speaks.
 */
internal fun toolSchema(vararg properties: Pair<String, JsonObject>, required: List<String>): ToolSchema =
    ToolSchema(properties = JsonObject(properties.toMap()), required = required.ifEmpty { null })

internal fun stringField(description: String): JsonObject = field("string", description)

internal fun integerField(description: String): JsonObject = field("integer", description)

internal fun booleanField(description: String): JsonObject = field("boolean", description)

internal fun objectField(description: String): JsonObject = field("object", description)

private fun field(type: String, description: String) = buildJsonObject {
    put("type", type)
    put("description", description)
}

internal fun textResult(text: String) = CallToolResult(content = listOf(TextContent(text)))

internal fun position(line: Int, column: Int) = Position(line - 1, column - 1)

internal fun JsonObject?.string(name: String): String =
    requireNotNull(stringOrNull(name)) { "Missing required argument: $name" }

internal fun JsonObject?.stringOrNull(name: String): String? {
    val value = value(name) ?: return null
    return requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        "Argument $name must be a string"
    }
}

internal fun JsonObject?.int(name: String): Int =
    requireNotNull(intOrNull(name)) { "Missing required argument: $name" }

internal fun JsonObject?.intOrNull(name: String): Int? {
    val value = value(name) ?: return null
    return requireNotNull((value as? JsonPrimitive)?.content?.toIntOrNull()) {
        "Argument $name must be an integer"
    }
}

internal fun JsonObject?.booleanOrNull(name: String): Boolean? {
    val value = value(name) ?: return null
    return requireNotNull((value as? JsonPrimitive)?.content?.toBooleanStrictOrNull()) {
        "Argument $name must be a boolean"
    }
}

internal fun JsonObject?.jsonObject(name: String): JsonObject {
    val value = requireNotNull(value(name)) { "Missing required argument: $name" }
    return requireNotNull(value as? JsonObject) { "Argument $name must be an object" }
}

private fun JsonObject?.value(name: String): JsonElement? = this?.get(name)?.takeUnless { it is JsonNull }
