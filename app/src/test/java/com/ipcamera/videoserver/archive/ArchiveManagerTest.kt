package com.ipcamera.videoserver.archive

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createFakeFiles(dir: File, count: Int): List<File> =
        (1..count).map { i ->
            File(dir, "2026-08-29_0${i}-00_main.mp4").also { f ->
                f.writeText("fake-video-$i")
                f.setLastModified(1_000L + i * 1_000L)
            }
        }

    @Test
    fun `enforceRotationByCount keeps only the newest files`() {
        val dir = tempFolder.newFolder("archive")
        createFakeFiles(dir, 5)

        enforceRotationByCount(dir, maxFiles = 3)

        val remaining = dir.listFiles()!!
        assertEquals(3, remaining.size)
        assertTrue(remaining.none { it.name.contains("1-00") })
        assertTrue(remaining.none { it.name.contains("2-00") })
    }

    @Test
    fun `enforceRotationByCount does nothing when under limit`() {
        val dir = tempFolder.newFolder("archive2")
        createFakeFiles(dir, 3)

        enforceRotationByCount(dir, maxFiles = 5)

        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test
    fun `enforceRotationBySize deletes oldest when over limit`() {
        val dir = tempFolder.newFolder("archive3")
        (1..4).forEach { i ->
            File(dir, "vid$i.mp4").also { f ->
                f.writeBytes(ByteArray(100))
                f.setLastModified(1_000L + i * 1_000L)
            }
        }

        enforceRotationBySize(dir, maxSizeBytes = 250)

        val remaining = dir.listFiles()!!
        assertTrue(remaining.sumOf { it.length() } <= 250)
    }

    @Test
    fun `enforceRotationBySize does nothing when under limit`() {
        val dir = tempFolder.newFolder("archive4")
        createFakeFiles(dir, 3)

        enforceRotationBySize(dir, maxSizeBytes = 100_000_000L)

        assertEquals(3, dir.listFiles()!!.size)
    }
}
