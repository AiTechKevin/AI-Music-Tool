package com.aimusicstudio.mixer

import com.aimusicstudio.audio.WavCodec
import com.aimusicstudio.data.StemTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tanh

/**
 * Low-memory streaming multi-track PCM audio mixer.
 * Mixes active WAV stems in 8192-frame chunks (< 1 MB RAM) with soft-clipping limiting.
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

        val chunkSize = 8192

        // Fast streaming path for a single active stem (works for MP3/AAC/WAV)
        if (activeStems.size == 1) {
            val stem = activeStems.first()
            val gain = stem.volume
            var writer: WavCodec.StreamingWavWriter? = null
            val outL = FloatArray(chunkSize)
            val outR = FloatArray(chunkSize)
            try {
                WavCodec.streamDecodeStereoChunks(
                    file = File(stem.filePath),
                    chunkFrames = chunkSize,
                    maxSeconds = 600,
                    onHeader = { sr -> writer = WavCodec.StreamingWavWriter(outputFile, sr) },
                    onChunk = { l, r, count, _ ->
                        for (i in 0 until count) {
                            val ml = l[i] * gain
                            val mr = r[i] * gain
                            outL[i] = if (abs(ml) > 0.95f) tanh(ml.toDouble()).toFloat() else ml
                            outR[i] = if (abs(mr) > 0.95f) tanh(mr.toDouble()).toFloat() else mr
                        }
                        writer?.writeChunk(outL, outR, count)
                    }
                )
            } finally {
                writer?.close()
            }
            return@withContext outputFile
        }

        // Streaming multi-stem WAV mix
        val readers = activeStems.map { stem ->
            stem.volume to WavCodec.StreamingWavReader(File(stem.filePath))
        }
        val validReaders = readers.filter { it.second.isValidPcm16 }
        if (validReaders.isEmpty()) {
            readers.forEach { it.second.close() }
            throw IllegalStateException("Selected stems are not valid PCM WAV files.")
        }

        val sampleRate = validReaders.first().second.sampleRate
        val mixL = FloatArray(chunkSize)
        val mixR = FloatArray(chunkSize)
        val tmpL = FloatArray(chunkSize)
        val tmpR = FloatArray(chunkSize)

        try {
            WavCodec.StreamingWavWriter(outputFile, sampleRate).use { writer ->
                while (true) {
                    var maxRead = 0
                    mixL.fill(0f)
                    mixR.fill(0f)

                    for ((gain, reader) in validReaders) {
                        val n = reader.readChunk(tmpL, tmpR, chunkSize)
                        if (n > 0) {
                            maxRead = max(maxRead, n)
                            for (i in 0 until n) {
                                mixL[i] += tmpL[i] * gain
                                mixR[i] += tmpR[i] * gain
                            }
                        }
                    }

                    if (maxRead == 0) break

                    for (i in 0 until maxRead) {
                        if (abs(mixL[i]) > 0.95f) mixL[i] = tanh(mixL[i].toDouble()).toFloat()
                        if (abs(mixR[i]) > 0.95f) mixR[i] = tanh(mixR[i].toDouble()).toFloat()
                    }
                    writer.writeChunk(mixL, mixR, maxRead)
                }
            }
        } finally {
            readers.forEach {
                try {
                    it.second.close()
                } catch (_: Exception) {
                }
            }
        }

        outputFile
    }
}
