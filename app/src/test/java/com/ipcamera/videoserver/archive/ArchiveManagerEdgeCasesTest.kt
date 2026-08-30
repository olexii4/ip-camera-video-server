package com.ipcamera.videoserver.archive

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveManagerEdgeCasesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun files(dir: File, count: Int): List<File> =
        (1..count).map { i ->
            File(dir, "2026-08-30_00-00-0${i}_main.mp4").also { f ->
                f.writeText("x")
                f.setLastModified(i * 1_000L)
            }
        }

    // ── enforceRotationByCount boundary ──────────────────────────────────────

    @Test
    fun `enforceRotationByCount keeps all files when count equals maxFiles exactly`() {
        val dir = tempFolder.newFolder("a1")
        files(dir, 5)

        enforceRotationByCount(dir, maxFiles = 5)

        assertEquals("boundary: count == maxFiles should delete nothing", 5, dir.listFiles()!!.size)
    }

    @Test
    fun `enforceRotationByCount on empty directory does not throw`() {
        val dir = tempFolder.newFolder("a2")
        enforceRotationByCount(dir, maxFiles = 3) // must not throw
        assertEquals(0, dir.listFiles()!!.size)
    }

    // ── enforceRotationBySize ─────────────────────────────────────────────────

    @Test
    fun `enforceRotationBySize on empty directory does not throw`() {
        val dir = tempFolder.newFolder("a3")
        enforceRotationBySize(dir, maxSizeBytes = 1_000L) // must not throw
        assertEquals(0, dir.listFiles()!!.size)
    }

    // ── combined count + size ─────────────────────────────────────────────────

    @Test
    fun `count rotation followed by size rotation applies both constraints`() {
        val dir = tempFolder.newFolder("a4")
        // 6 files of 100 bytes each — count limit 5, size limit 400 bytes
        (1..6).forEach { i ->
            File(dir, "vid$i.mp4").also { f ->
                f.writeBytes(ByteArray(100))
                f.setLastModified(i * 1_000L)
            }
        }

        enforceRotationByCount(dir, maxFiles = 5)    // removes 1 oldest → 5 remain, 500 bytes
        enforceRotationBySize(dir, maxSizeBytes = 400) // removes 1 more oldest → 4 remain

        val remaining = dir.listFiles()!!
        assertEquals(4, remaining.size)
        assertTrue(remaining.sumOf { it.length() } <= 400)
    }

    // ── segmentFileName format ────────────────────────────────────────────────

    @Test
    fun `segmentFileName produces an mp4 filename with seconds in the timestamp`() {
        // The date format includes seconds: yyyy-MM-dd_HH-mm-ss
        // Regex: 4 digit year, month, day, hour, minute, second, then _ source .mp4
        val name = File("archive/2026-08-30_13-05-42_main.mp4").name
        val pattern = Regex("""\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}_(main|front|usb)\.mp4""")
        assertTrue("segment filename must include seconds", pattern.matches(name))
    }

    @Test
    fun `audioSegmentFileName produces an m4a extension`() {
        val name = File("archive/2026-08-30_13-05-42_audio.m4a").name
        assertTrue("audio segment must have .m4a extension", name.endsWith(".m4a"))
    }
}
