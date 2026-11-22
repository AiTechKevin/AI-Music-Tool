package com.aimusicstudio.mixer

import com.aimusicstudio.audio.WavCodec
import com.aimusicstudio.data.StemTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.tanh

/**
 * Multi-track offline PCM audio mixer.
 * Combines active stems using current volume, Mute, Solo, and Removed states
 * and applies gentle soft-clipping limiting before writing a studio WAV file.
 */
object AudioMixerEngine {

    suspend fun mixStemsToWav(
        stems: List<StemTrack>,
        outputFile: File
    ): File = withContext(Dispatchers.Default) {
        val anySolo = stems.any { it.isSolo && !it.isRemoved }
        val activeStems = stems.filter { stem ->
            !stem.isRemoved &&
                !stem.isMuted &&
                (!anySolo || stem.isSolo) &&
                stem.volume > 0.001f &&
                File(stem.filePath).exists()
        }

        if (activeStems.isEmpty()) {
            throw IllegalStateException("No active stems selected to export.")
        }

        val decodedList = activeStems.map { stem ->
            stem.volume to WavCodec.decodeAudioToStereoFloat(File(stem.filePath))
        }

        val sampleRate = decodedList.first().second.sampleRate
        val maxFrames = decodedList.maxOf { it.second.left.size }
        val mixL = FloatArray(maxFrames)
        val mixR = FloatArray(maxFrames)

        for ((gain, audio) in decodedList) {
            val len = audio.left.size
            for (i in 0 until len) {
                mixL[i] += audio.left[i] * gain
                mixR[i] += audio.right[i] * gain
            }
        }

        // Peak check + gentle soft-clipping limiter to prevent digital distortion
        var peak = 0f
        for (i in 0 until maxFrames) {
            peak = max(peak, kotlin.math.abs(mixL[i]))
            peak = max(peak, kotlin.math.abs(mixR[i]))
        }
        if (peak > 0.98f) {
            for (i in 0 until maxFrames) {
                mixL[i] = tanh(mixL[i].toDouble()).toFloat()
                mixR[i] = tanh(mixR[i].toDouble()).toFloat()
            }
        }

        WavCodec.writeStereoWav(outputFile, mixL, mixR, sampleRate)
        outputFile
    }
}
