package com.chromia.lspmcp.lsp

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextEdit

/**
 * Converts an LSP line/character position into an absolute string offset. Position and content
 * must come from the same snapshot: offsets computed against one version of the text are
 * meaningless against another.
 */
fun positionToOffset(content: String, position: Position): Int {
    val lines = content.split("\n")
    var offset = 0
    for (line in 0 until position.line) {
        offset += (lines.getOrNull(line)?.length ?: 0) + 1 // +1 for the newline consumed between lines
    }
    return offset + position.character
}

/**
 * Applies non-overlapping [edits] to [content]. Offsets are resolved against the original
 * content, then applied right to left so that earlier offsets stay valid as later edits mutate
 * the string.
 */
fun applyTextEdits(content: String, edits: List<TextEdit>): String {
    val resolved = edits
        .map { Triple(it, positionToOffset(content, it.range.start), positionToOffset(content, it.range.end)) }
        .sortedByDescending { (_, start, _) -> start }

    var result = content
    for ((edit, start, end) in resolved) {
        result = result.substring(0, start) + edit.newText + result.substring(end)
    }
    return result
}
