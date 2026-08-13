package com.chromia.lspmcp

import com.chromia.lspmcp.lsp.PreambleFilteringInputStream
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.*
import kotlin.test.assertEquals

class PreambleFilteringInputStreamTest {
    private val message = "Content-Length: 2\r\n\r\n{}"

    @Test
    fun `logging banner before the first message is dropped`() {
        val source = "12:00:00 INFO Rell language server starting\n$message"

        assertEquals(message, filter(source.byteInputStream()))
    }

    @Test
    fun `a stream that already starts with a message is untouched`() {
        assertEquals(message, filter(message.byteInputStream()))
    }

    @Test
    fun `the header is found even when it straddles two reads`() {
        val chunks = listOf("noise Content-Len".byteInputStream(), "gth: 2\r\n\r\n{}".byteInputStream())

        assertEquals("Content-Length: 2\r\n\r\n{}", filter(concat(chunks)))
    }

    @Test
    fun `a stream that never sends a message reads as empty`() {
        assertEquals("", filter("only noise, no message\n".byteInputStream()))
    }

    private fun filter(source: InputStream): String =
        PreambleFilteringInputStream(source).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun concat(streams: List<InputStream>): InputStream =
        SequenceInputStream(Collections.enumeration(streams))
}
