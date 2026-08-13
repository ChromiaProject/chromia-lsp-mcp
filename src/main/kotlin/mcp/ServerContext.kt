package com.chromia.lspmcp.mcp

import com.chromia.lspmcp.lsp.RellLspClient
import com.chromia.lspmcp.lsp.fileUri
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.readText

/** A document pushed to the language server: its URI and the text the server now holds. */
data class OpenedDocument(val uri: String, val content: String)

/** Mutable state shared by the tool and resource handlers. */
class ServerContext(val lsp: RellLspClient, initialRoot: Path) {
    /** Project root the language server was last started with. */
    @Volatile
    var rootDir: Path = initialRoot

    /**
     * Reads [filePath] from disk and pushes it to the language server, returning its URI.
     * Re-reading on every call is what keeps the server's view in step with edits made outside
     * this process.
     */
    fun openFile(filePath: String): OpenedDocument {
        val path = Path.of(filePath).toAbsolutePath().normalize()
        val content = try {
            path.readText()
        } catch (failure: IOException) {
            throw IllegalArgumentException("Cannot read $path: ${failure.message}", failure)
        }
        val uri = fileUri(path)
        lsp.openDocument(uri, content)
        return OpenedDocument(uri, content)
    }
}
