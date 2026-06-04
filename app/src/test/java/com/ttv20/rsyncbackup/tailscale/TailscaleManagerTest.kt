package com.ttv20.rsyncbackup.tailscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TailscaleManagerTest {
    @Test
    fun archiveRoundTripsNestedStateFiles() {
        val source = Files.createTempDirectory("tailscale-source").toFile()
        val restored = Files.createTempDirectory("tailscale-restored").toFile()
        try {
            source.resolve("tailscaled.state").writeText("state")
            source.resolve("logs").mkdirs()
            source.resolve("logs/tailscaled.log.conf").writeText("log-conf")

            val archive = TailscaleManager.archiveDirectory(source)
            TailscaleManager.restoreDirectory(archive, restored)

            assertEquals("state", restored.resolve("tailscaled.state").readText())
            assertEquals("log-conf", restored.resolve("logs/tailscaled.log.conf").readText())
        } finally {
            source.deleteRecursively()
            restored.deleteRecursively()
        }
    }

    @Test
    fun restoreRejectsPathTraversalEntries() {
        val restored = Files.createTempDirectory("tailscale-restored").toFile()
        try {
            val archive = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry("../outside"))
                    zip.write("bad".toByteArray())
                    zip.closeEntry()
                }
            }.toByteArray()

            val failed = runCatching {
                TailscaleManager.restoreDirectory(archive, restored)
            }.isFailure

            assertTrue(failed)
        } finally {
            restored.deleteRecursively()
        }
    }

    @Test
    fun extractsTailscaleBrowserAuthUrlFromHelperLogLine() {
        val line = "2026/06/04 12:00:00 To start this tsnet server, restart with TS_AUTHKEY set, or go to: https://login.tailscale.com/a/example"

        assertEquals("https://login.tailscale.com/a/example", TailscaleManager.extractTailscaleAuthUrl(line))
    }

    @Test
    fun ignoresNonAuthUrlsInHelperOutput() {
        val line = "tsnet status state=Running selfIPs=100.64.0.1"

        assertEquals(null, TailscaleManager.extractTailscaleAuthUrl(line))
    }
}
