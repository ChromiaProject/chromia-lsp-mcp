package com.chromia.lspmcp.lsp

import com.chromia.lspmcp.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream

private val HEADER = "Content-Length:".toByteArray(Charsets.US_ASCII)

/**
 * Drops everything the language server writes to stdout before its first LSP message.
 *
 * The Rell server prints a logging banner there, which would otherwise desync LSP4J's header
 * parser. Bytes up to the first `Content-Length:` are discarded and logged; from then on the
 * stream is passed through untouched.
 */
class PreambleFilteringInputStream(private val source: InputStream) : InputStream() {
    private var buffered: ByteArray? = null
    private var offset = 0
    private var headerSeen = false
    private var exhausted = false

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xFF
    }

    override fun read(destination: ByteArray, destinationOffset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!seekHeader()) return -1

        val pending = buffered
        if (pending != null) {
            val available = minOf(length, pending.size - offset)
            pending.copyInto(destination, destinationOffset, offset, offset + available)
            offset += available
            if (offset == pending.size) buffered = null
            return available
        }

        return source.read(destination, destinationOffset, length)
    }

    override fun close() = source.close()

    /** Reads until the first LSP header is buffered. Returns false if the stream ended first. */
    private fun seekHeader(): Boolean {
        if (headerSeen) return !exhausted || buffered != null
        val accumulated = ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = source.read(chunk)
            if (read == -1) {
                exhausted = true
                headerSeen = true
                logPreamble(accumulated.toByteArray())
                return false
            }
            accumulated.write(chunk, 0, read)
            val bytes = accumulated.toByteArray()
            val headerStart = bytes.indexOfSequence(HEADER)
            if (headerStart >= 0) {
                logPreamble(bytes.copyOfRange(0, headerStart))
                buffered = bytes
                offset = headerStart
                headerSeen = true
                return true
            }
        }
    }

    private fun logPreamble(preamble: ByteArray) {
        val text = String(preamble, Charsets.UTF_8).trim()
        if (text.isNotEmpty()) Log.debug { "Skipping non-LSP output from server: $text" }
    }
}

private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
    outer@ for (start in 0..size - needle.size) {
        for (i in needle.indices) {
            if (this[start + i] != needle[i]) continue@outer
        }
        return start
    }
    return -1
}
