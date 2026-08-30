package com.ipcamera.videoserver.archive

import android.content.Context
import com.ipcamera.videoserver.camera.CameraSource
import com.ipcamera.videoserver.settings.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

fun enforceRotationByCount(dir: File, maxFiles: Int) {
    val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
    if (files.size > maxFiles) {
        files.take(files.size - maxFiles).forEach { it.delete() }
    }
}

fun enforceRotationBySize(dir: File, maxSizeBytes: Long) {
    val files = dir.listFiles()?.sortedBy { it.lastModified() }?.toMutableList() ?: return
    var total = files.sumOf { it.length() }
    while (total > maxSizeBytes && files.isNotEmpty()) {
        val oldest = files.removeAt(0)
        total -= oldest.length()
        oldest.delete()
    }
}

@Singleton
class ArchiveManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
) {
    private val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    val archiveDir: File
        get() = File(context.getExternalFilesDir(null), "archive").also { it.mkdirs() }

    fun listFiles(): List<File> =
        archiveDir.listFiles { f -> f.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    suspend fun enforceRotation() {
        val maxFiles = settings.archiveMaxFiles.first()
        val maxSizeGb = settings.archiveMaxSizeGb.first()
        enforceRotationByCount(archiveDir, maxFiles)
        enforceRotationBySize(archiveDir, maxSizeGb * 1024L * 1024L * 1024L)
    }

    fun segmentFileName(source: CameraSource): File =
        File(archiveDir, "${sdf.format(Date())}_${source.id}.mp4")
}
