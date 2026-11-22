package com.aimusicstudio.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.aimusicstudio.data.ExportResult
import com.aimusicstudio.data.MusicProject
import com.aimusicstudio.data.StemTrack
import com.aimusicstudio.mixer.AudioMixerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class AudioExporter(private val context: Context) {

    private val exportsDir: File = File(context.filesDir, "exports").apply { mkdirs() }

    suspend fun exportMixedProject(project: MusicProject): ExportResult =
        withContext(Dispatchers.IO) {
            val safeName = project.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val fileName = "${safeName}_mix_${System.currentTimeMillis() % 10000}.wav"
            val internalFile = File(exportsDir, fileName)

            AudioMixerEngine.mixStemsToWav(project.stems, internalFile)
            val savedToDownloads = copyToPublicDownloads(internalFile, fileName, "audio/wav")

            ExportResult(
                filePath = internalFile.absolutePath,
                displayName = fileName,
                savedToDownloads = savedToDownloads
            )
        }

    suspend fun exportSingleStem(projectName: String, stem: StemTrack): ExportResult =
        withContext(Dispatchers.IO) {
            val src = File(stem.filePath)
            if (!src.exists()) throw IllegalStateException("Stem file not found.")
            val safeProject = projectName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val fileName = "${safeProject}_${stem.type.backendKey}.wav"
            val internalFile = File(exportsDir, fileName)
            src.copyTo(internalFile, overwrite = true)

            val savedToDownloads = copyToPublicDownloads(internalFile, fileName, "audio/wav")
            ExportResult(
                filePath = internalFile.absolutePath,
                displayName = fileName,
                savedToDownloads = savedToDownloads
            )
        }

    fun shareAudioFile(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(shareIntent, "Share Audio Track").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun copyToPublicDownloads(sourceFile: File, displayName: String, mimeType: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/AI-Music-Studio"
                    )
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return false

                context.contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out)
                    }
                }
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
