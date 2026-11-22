package com.aimusicstudio.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Extracts the audio stream from imported video files (MP4, MKV, MOV, WEBM)
 * directly on Android into an uncompressed stereo WAV file ready for AI separation.
 */
object VideoAudioExtractor {

    private val videoExtensions = setOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "m4v")

    fun isVideoFile(file: File): Boolean {
        return file.extension.lowercase() in videoExtensions
    }

    suspend fun extractAudioToWav(videoFile: File, outputWavFile: File): File =
        withContext(Dispatchers.Default) {
            val decoded = WavCodec.decodeAudioToStereoFloat(videoFile, maxSeconds = 600)
            WavCodec.writeStereoWav(outputWavFile, decoded.left, decoded.right, decoded.sampleRate)
            outputWavFile
        }
}
