package com.aimusicstudio.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class HealthResponse(
    val status: String,
    val demucs_available: Boolean = false,
    val ffmpeg_available: Boolean = false
)

data class JobCreatedResponse(
    @SerializedName("job_id") val jobId: String,
    val status: String
)

data class JobStatusResponse(
    @SerializedName("job_id") val jobId: String,
    val status: String,
    val progress: Float = 0f,
    val message: String = "",
    val stems: Map<String, String> = emptyMap(),
    @SerializedName("output_url") val outputUrl: String? = null,
    val error: String? = null
)

data class BackendAnalysisResponse(
    val bpm: Double,
    val key: String,
    val duration_sec: Double,
    val instruments: List<BackendInstrumentItem>
)

data class BackendInstrumentItem(
    val name: String,
    val confidence: Float
)

class BackendApiClient {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun checkHealth(baseUrl: String): Result<HealthResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${baseUrl.removeSuffix("/")}/api/health")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string() ?: error("Empty health response")
                gson.fromJson(body, HealthResponse::class.java)
            }
        }
    }

    suspend fun separateAudio(
        baseUrl: String,
        sourceFile: File,
        preset: SeparationPreset,
        outputDir: File,
        onProgress: (Float, String) -> Unit
    ): Result<Map<StemType, File>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUrl = baseUrl.removeSuffix("/")
            onProgress(0.08f, "Uploading audio to Hybrid AI Server...")

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    sourceFile.name,
                    sourceFile.asRequestBody("application/octet-stream".toMediaType())
                )
                .addFormDataPart("mode", preset.backendMode)
                .build()

            val startReq = Request.Builder()
                .url("$cleanUrl/api/separate")
                .post(multipart)
                .build()

            val jobId = client.newCall(startReq).execute().use { resp ->
                if (!resp.isSuccessful) error("Upload failed: HTTP ${resp.code}")
                val created = gson.fromJson(resp.body?.string(), JobCreatedResponse::class.java)
                created.jobId
            }

            var finalStatus: JobStatusResponse? = null
            for (attempt in 0 until 240) {
                delay(1500)
                val pollReq = Request.Builder()
                    .url("$cleanUrl/api/jobs/$jobId")
                    .get()
                    .build()
                val status = client.newCall(pollReq).execute().use { resp ->
                    if (!resp.isSuccessful) error("Status check failed: HTTP ${resp.code}")
                    gson.fromJson(resp.body?.string(), JobStatusResponse::class.java)
                }
                onProgress(
                    0.15f + (status.progress.coerceIn(0f, 1f) * 0.65f),
                    status.message.ifBlank { "AI model processing (${(status.progress * 100).toInt()}%)..." }
                )
                if (status.status == "completed") {
                    finalStatus = status
                    break
                } else if (status.status == "failed") {
                    error(status.error ?: "Server separation failed")
                }
            }

            val completed = finalStatus ?: error("AI server timed out")
            val downloaded = linkedMapOf<StemType, File>()
            val totalStems = completed.stems.size.coerceAtLeast(1)
            var idx = 0

            completed.stems.forEach { (stemKey, relativeUrl) ->
                idx++
                onProgress(0.80f + (idx.toFloat() / totalStems) * 0.18f, "Downloading $stemKey stem...")
                val stemType = StemType.entries.firstOrNull { it.backendKey.equals(stemKey, ignoreCase = true) }
                    ?: StemType.OTHER
                val outFile = File(outputDir, "${stemKey}.wav")
                downloadFile("$cleanUrl$relativeUrl", outFile)
                downloaded[stemType] = outFile
            }

            onProgress(1.0f, "Separation complete!")
            downloaded
        }
    }

    suspend fun analyzeTrack(baseUrl: String, sourceFile: File): Result<AnalysisResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanUrl = baseUrl.removeSuffix("/")
                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        sourceFile.name,
                        sourceFile.asRequestBody("application/octet-stream".toMediaType())
                    )
                    .build()
                val req = Request.Builder()
                    .url("$cleanUrl/api/analyze")
                    .post(multipart)
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("Analyze failed: HTTP ${resp.code}")
                    val parsed = gson.fromJson(resp.body?.string(), BackendAnalysisResponse::class.java)
                    AnalysisResult(
                        bpm = parsed.bpm,
                        musicalKey = parsed.key,
                        durationSec = parsed.duration_sec,
                        detectedInstruments = parsed.instruments.map {
                            InstrumentScore(it.name, it.confidence)
                        }
                    )
                }
            }
        }

    suspend fun enhanceAudio(
        baseUrl: String,
        sourceFile: File,
        preset: String,
        destFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUrl = baseUrl.removeSuffix("/")
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    sourceFile.name,
                    sourceFile.asRequestBody("application/octet-stream".toMediaType())
                )
                .addFormDataPart("preset", preset)
                .build()

            val req = Request.Builder()
                .url("$cleanUrl/api/enhance")
                .post(multipart)
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Enhance failed: HTTP ${resp.code}")
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                } ?: error("Empty enhanced file")
            }
            destFile
        }
    }

    suspend fun replaceInstrument(
        baseUrl: String,
        stemFile: File,
        targetInstrument: String,
        destFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUrl = baseUrl.removeSuffix("/")
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    stemFile.name,
                    stemFile.asRequestBody("application/octet-stream".toMediaType())
                )
                .addFormDataPart("target_instrument", targetInstrument)
                .build()

            val req = Request.Builder()
                .url("$cleanUrl/api/replace-instrument")
                .post(multipart)
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Replacement failed: HTTP ${resp.code}")
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                } ?: error("Empty replaced file")
            }
            destFile
        }
    }

    suspend fun generateArrangement(
        baseUrl: String,
        genre: String,
        musicalKey: String,
        bpm: Int,
        durationSec: Int,
        destFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUrl = baseUrl.removeSuffix("/")
            val req = Request.Builder()
                .url("$cleanUrl/api/generate-music?genre=$genre&key=$musicalKey&bpm=$bpm&duration=$durationSec")
                .post("".toRequestBody(null))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Generate failed: HTTP ${resp.code}")
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                } ?: error("Empty generated track")
            }
            destFile
        }
    }

    private fun downloadFile(url: String, destination: File) {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Failed to download stem: HTTP ${resp.code}")
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            } ?: error("Empty stem stream")
        }
    }
}
