package com.chromia.lspmcp

import com.chromia.lspmcp.lsp.ChromiaSettings
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChromiaSettingsTest {
    private fun blockchainsYml(rellVersion: String) = """
        blockchains:
          main:
            module: main
        compile:
          rellVersion: $rellVersion
    """.trimIndent()

    @Test
    fun `a lone chromia yml needs no explicit config file`() {
        val root = Files.createTempDirectory("chromia-settings")
        (root / ChromiaSettings.CHROMIA_YML).writeText(blockchainsYml("0.16.5"))

        assertTrue(ChromiaSettings.nonDefaultConfigFileUris(root).isEmpty())
    }

    @Test
    fun `alternately named settings files without chromia yml are surfaced, oldest name wins ties`() {
        val root = Files.createTempDirectory("chromia-settings")
        val contracts = (root / "contracts").createDirectories()
        (contracts / "atbash.yml").writeText(blockchainsYml("0.14.15"))
        (contracts / "atbash_dev.yml").writeText(blockchainsYml("0.14.15"))

        val uris = ChromiaSettings.nonDefaultConfigFileUris(root)
        assertEquals(1, uris.size)
        assertTrue(uris.single().endsWith("atbash.yml"))
    }

    @Test
    fun `a higher declared version wins over the alphabetically earlier file`() {
        val root = Files.createTempDirectory("chromia-settings")
        (root / "atbash.yml").writeText(blockchainsYml("0.14.15"))
        (root / "atbash_dev.yml").writeText(blockchainsYml("0.16.0"))

        val uris = ChromiaSettings.nonDefaultConfigFileUris(root)
        assertEquals(1, uris.size)
        assertTrue(uris.single().endsWith("atbash_dev.yml"))
    }

    @Test
    fun `an unrelated yml without a blockchains section is ignored`() {
        val root = Files.createTempDirectory("chromia-settings")
        (root / "qodana.yml").writeText("profile:\n  name: qodana.starter\n")

        assertTrue(ChromiaSettings.nonDefaultConfigFileUris(root).isEmpty())
    }
}
