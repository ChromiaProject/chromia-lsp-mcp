package com.chromia.lspmcp.lsp

import com.chromia.lspmcp.Log
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** A `major.minor.patch` Rell version, ordered the way `compile.rellVersion` values compare. */
data class RellVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<RellVersion> {
    override fun compareTo(other: RellVersion): Int =
        compareValuesBy(this, other, RellVersion::major, RellVersion::minor, RellVersion::patch)

    companion object {
        private val PATTERN = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

        fun parse(text: String): RellVersion? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch) = match.destructured
            return RellVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

/**
 * Finds the Chromia settings files ([chromia.yml][CHROMIA_YML], or any `*.yml` declaring a
 * top-level `blockchains:` section) governing a project, mirroring the resolution rules in
 * rell-jetbrains (`chromia.RellVersionResolver`/`ChromiaSettingsFiles`, see
 * `docs/COMPATIBILITY.md` there): the language server discovers `chromia.yml` by name on its own,
 * so only the *non-default* governing file of each directory needs to be surfaced explicitly, as
 * an initialization option, for it to analyse that directory in the right Rell compatibility
 * mode.
 */
object ChromiaSettings {
    const val CHROMIA_YML = "chromia.yml"

    private val TOP_LEVEL_BLOCKCHAINS = Regex("""(?m)^blockchains[ \t]*:""")
    private val RELL_VERSION = Regex("""(?m)^\s*rellVersion[ \t]*:[ \t]*['"]?(\S+?)['"]?\s*(?:#.*)?$""")

    /** Directories that never hold source under analysis and are expensive or wrong to descend into. */
    private val SKIP_DIRECTORIES = setOf(".git", ".gradle", ".kotlin", ".idea", "build", "target", "node_modules")

    fun isDefaultName(name: String): Boolean = CHROMIA_YML.equals(name, ignoreCase = true)

    fun isYmlName(name: String): Boolean = name.endsWith(".yml", ignoreCase = true)

    /**
     * The `file:` URIs of every directory's *non-default* governing settings file under [root] —
     * empty when every directory either has no settings file or is governed by its own
     * `chromia.yml`, since the server's own by-name discovery already covers that case.
     */
    fun nonDefaultConfigFileUris(root: Path): List<String> =
        candidatesByDirectory(root).mapNotNull { (_, files) ->
            governingFile(files)?.takeUnless { isDefaultName(it.fileName.toString()) }
        }.map(::fileUri)

    private fun governingFile(files: List<Path>): Path? {
        if (files.isEmpty()) return null
        files.firstOrNull { isDefaultName(it.fileName.toString()) }?.let { return it }
        return files.sortedWith(
            compareByDescending<Path> { declaredVersion(it) ?: RellVersion(0, 0, 0) }
                .thenBy { it.fileName.toString().lowercase() },
        ).firstOrNull()
    }

    private fun declaredVersion(configFile: Path): RellVersion? {
        val text = try {
            Files.readString(configFile)
        } catch (failure: IOException) {
            Log.debug { "Could not read $configFile: ${failure.message}" }
            return null
        }
        val declared = RELL_VERSION.find(text)?.groupValues?.get(1) ?: return null
        return RellVersion.parse(declared)
    }

    /** Every directory under [root] holding at least one qualifying settings file, mapped to those files. */
    private fun candidatesByDirectory(root: Path): Map<Path, List<Path>> {
        if (!Files.isDirectory(root)) return emptyMap()

        val byDirectory = LinkedHashMap<Path, MutableList<Path>>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && dir.fileName?.toString() in SKIP_DIRECTORIES) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.fileName.toString()
                if (isYmlName(name) && qualifies(file, name)) {
                    byDirectory.getOrPut(file.parent) { mutableListOf() }.add(file)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return byDirectory
    }

    private fun qualifies(file: Path, name: String): Boolean {
        if (isDefaultName(name)) return true
        val text = try {
            Files.readString(file)
        } catch (failure: IOException) {
            Log.debug { "Could not read $file: ${failure.message}" }
            return false
        }
        return TOP_LEVEL_BLOCKCHAINS.containsMatchIn(text)
    }
}
