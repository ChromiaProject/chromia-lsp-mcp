package com.chromia.lspmcp.lsp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isExecutable

/** How to start the Rell language server: a Java executable and the server JAR to hand it. */
data class LspLaunch(val javaPath: Path, val jarPath: Path, val jvmArgs: List<String>) {
    fun command(): List<String> = buildList {
        add(javaPath.toString())
        addAll(jvmArgs)
        add("-jar")
        add(jarPath.toString())
    }

    companion object {
        /** Where the image bakes the Rell language server. */
        private const val DEFAULT_JAR = "/opt/rell-lsp/language-server.jar"

        /**
         * Resolves the launch spec from the environment. `RELL_LSP_JAR` points at the server JAR,
         * `RELL_LSP_JAVA_OPTS` adds JVM arguments (heap limits, for instance), and the Java that
         * runs it is the one running this process — inside the image there is exactly one.
         */
        fun fromEnvironment(): LspLaunch {
            val jar = Path.of(System.getenv("RELL_LSP_JAR") ?: DEFAULT_JAR)
            require(Files.isRegularFile(jar)) {
                "Rell language server JAR not found at $jar. Set RELL_LSP_JAR to its location."
            }

            val java = Path.of(System.getProperty("java.home"), "bin", javaBinaryName())
            require(java.isExecutable()) { "No usable Java executable at $java" }

            val opts = System.getenv("RELL_LSP_JAVA_OPTS")?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
            return LspLaunch(java, jar, opts)
        }

        private fun javaBinaryName() =
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
    }
}
