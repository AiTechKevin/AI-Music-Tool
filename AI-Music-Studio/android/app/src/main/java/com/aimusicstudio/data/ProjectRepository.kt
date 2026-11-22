package com.aimusicstudio.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ProjectRepository(private val context: Context) {

    private val gson = Gson()
    private val prefs = context.getSharedPreferences("ai_music_studio_prefs", Context.MODE_PRIVATE)
    private val projectsDir: File = File(context.filesDir, "projects").apply { mkdirs() }
    private val metadataFile: File = File(context.filesDir, "projects_index.json")

    fun getServerUrl(): String {
        return prefs.getString("server_url", "http://192.168.1.10:8000") ?: "http://192.168.1.10:8000"
    }

    fun saveServerUrl(url: String) {
        prefs.edit().putString("server_url", url.trim().removeSuffix("/")).apply()
    }

    fun getProjects(): List<MusicProject> {
        if (!metadataFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<MusicProject>>() {}.type
            gson.fromJson<List<MusicProject>>(metadataFile.readText(), type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveProject(project: MusicProject) {
        val current = getProjects().toMutableList()
        val index = current.indexOfFirst { it.id == project.id }
        if (index >= 0) {
            current[index] = project
        } else {
            current.add(0, project)
        }
        metadataFile.writeText(gson.toJson(current))
    }

    fun deleteProject(projectId: String) {
        val current = getProjects().filterNot { it.id == projectId }
        metadataFile.writeText(gson.toJson(current))
        File(projectsDir, projectId).deleteRecursively()
    }

    fun getProjectDirectory(projectId: String): File {
        return File(projectsDir, projectId).apply { mkdirs() }
    }

    fun importMediaUri(uri: Uri): Pair<String, File> {
        val projectId = UUID.randomUUID().toString().take(8)
        val dir = getProjectDirectory(projectId)
        val originalName = queryDisplayName(uri) ?: "imported_track_${projectId}.mp3"
        val safeName = originalName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val destFile = File(dir, safeName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Unable to read selected file.")

        val title = originalName.substringBeforeLast(".")
        return title to destFile
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(index)
            }
        }
        return name
    }
}
