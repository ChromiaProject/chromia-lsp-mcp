package com.chromia.lspmcp

import com.chromia.lspmcp.lsp.applyTextEdits
import com.chromia.lspmcp.lsp.positionToOffset
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TextEditsTest {
    private val content = "line one\nline two\nline three\n"

    @Test
    fun `offsets count the newline between lines`() {
        assertEquals(0, positionToOffset(content, Position(0, 0)))
        assertEquals(9, positionToOffset(content, Position(1, 0)))
        assertEquals(23, positionToOffset(content, Position(2, 5)))
    }

    @Test
    fun `edits apply right to left so earlier offsets stay valid`() {
        val edits = listOf(
            TextEdit(Range(Position(0, 5), Position(0, 8)), "ONE"),
            TextEdit(Range(Position(2, 5), Position(2, 10)), "THREE"),
        )

        assertEquals("line ONE\nline two\nline THREE\n", applyTextEdits(content, edits))
    }

    @Test
    fun `an insertion is an edit with an empty range`() {
        val edits = listOf(TextEdit(Range(Position(1, 0), Position(1, 0)), "new line\n"))

        assertEquals("line one\nnew line\nline two\nline three\n", applyTextEdits(content, edits))
    }

    @Test
    fun `edits given out of order still apply correctly`() {
        val edits = listOf(
            TextEdit(Range(Position(2, 0), Position(2, 4)), "LINE"),
            TextEdit(Range(Position(0, 0), Position(0, 4)), "LINE"),
        )

        assertEquals("LINE one\nline two\nLINE three\n", applyTextEdits(content, edits))
    }
}
