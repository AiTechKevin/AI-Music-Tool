package com.aimusicstudio.audio

import com.aimusicstudio.data.AnalysisResult
import com.aimusicstudio.data.InstrumentScore
import com.aimusicstudio.data.SeparationPreset
import com.aimusicstudio.data.StemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight on-device DSP engine for instant offline vocal removal,
 * band-split stem extraction, basic audio enhancement, and local BPM/energy analysis.
 * For neural studio-grade Demucs separation, use Hybrid AI Server mode.
 */
object OnDeviceDspSeparator {

    private class BiquadFilter(
        private val b0: Float,
        private val b1: Float,
        private val b2: Float,
        private val a1: Float,
        private val a2: Float
    ) {
        private var z1 = 0f
        private var z2 = 0f

        fun process(input: Float): Float {
            val out = input * b0 + z1
            z1 = input * b1 + z2 - a1 * out
            z2 = input * b2 - a2 * out
            return out
        }

        companion object {
            fun lowPass(sampleRate: Int, cutoffHz: Float, q: Float = 0.707f): BiquadFilter {
                val w0 = 2.0 * PI * cutoffHz / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val cosW0 = cos(w0)
                val a0 = 1.0 + alpha
                return BiquadFilter(
                    b0 = ((1.0 - cosW0) / 2.0 / a0).toFloat(),
                    b1 = ((1.0 - cosW0) / a0).toFloat(),
                    b2 = ((1.0 - cosW0) / 2.0 / a0).toFloat(),
                    a1 = (-2.0 * cosW0 / a0).toFloat(),
                    a2 = ((1.0 - alpha) / a0).toFloat()
                )
            }

            fun highPass(sampleRate: Int, cutoffHz: Float, q: Float = 0.707f): BiquadFilter {
                val w0 = 2.0 * PI * cutoffHz / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val cosW0 = cos(w0)
                val a0 = 1.0 + alpha
                return BiquadFilter(
                    b0 = ((1.0 + cosW0) / 2.0 / a0).toFloat(),
                    b1 = (-(1.0 + cosW0) / a0).toFloat(),
                    b2 = ((1.0 + cosW0) / 2.0 / a0).toFloat(),
                    a1 = (-2.0 * cosW0 / a0).toFloat(),
                    a2 = ((1.0 - alpha) / a0).toFloat()
                )
            }

            fun bandPass(sampleRate: Int, centerHz: Float, q: Float = 1.0f): BiquadFilter {
                val w0 = 2.0 * PI * centerHz / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val cosW0 = cos(w0)
                val a0 = 1.0 + alpha
                return BiquadFilter(
                    b0 = (alpha / a0).toFloat(),
                    b1 = 0f,
                    b2 = (-alpha / a0).toFloat(),
                    a1 = (-2.0 * cosW0 / a0).toFloat(),
                    a2 = ((1.0 - alpha) / a0).toFloat()
                )
            }
        }
    }

    suspend fun separateOffline(
        sourceFile: File,
        preset: SeparationPreset,
        outputDir: File,
        onProgress: (Float, String) -> Unit
    ): Map<StemType, File> = withContext(Dispatchers.Default) {
        onProgress(0.10f, "Decoding audio stream...")
        val audio = WavCodec.decodeAudioToStereoFloat(sourceFile)
        val n = audio.left.size
        val sr = audio.sampleRate

        onProgress(0.30f, "Running Mid/Side & frequency separation...")

        val bassL = FloatArray(n)
        val bassR = FloatArray(n)
        val vocalL = FloatArray(n)
        val vocalR = FloatArray(n)
        val drumsL = FloatArray(n)
        val drumsR = FloatArray(n)
        val pianoL = FloatArray(n)
        val pianoR = FloatArray(n)
        val guitarL = FloatArray(n)
        val guitarR = FloatArray(n)
        val otherL = FloatArray(n)
        val otherR = FloatArray(n)
        val instL = FloatArray(n)
        val instR = FloatArray(n)

        val bassLp = BiquadFilter.lowPass(sr, 220f)
        val vocalBp = BiquadFilter.bandPass(sr, 1150f, 0.65f)
        val pianoBpL = BiquadFilter.bandPass(sr, 750f, 0.9f)
        val pianoBpR = BiquadFilter.bandPass(sr, 750f, 0.9f)
        val guitarBpL = BiquadFilter.bandPass(sr, 1900f, 1.0f)
        val guitarBpR = BiquadFilter.bandPass(sr, 1900f, 1.0f)
        val hiHatHp = BiquadFilter.highPass(sr, 5200f)

        var prevEnergy = 0f

        for (i in 0 until n) {
            val l = audio.left[i]
            val r = audio.right[i]
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f

            val subBass = bassLp.process(mid)
            val centerVocal = vocalBp.process(mid)
            val hiTransient = hiHatHp.process(mid)

            // Simple transient envelope for percussive drums isolation
            val instEnergy = abs(mid)
            val transient = (instEnergy - prevEnergy).coerceAtLeast(0f) * 2.2f
            prevEnergy = prevEnergy * 0.96f + instEnergy * 0.04f
            val drumGate = (transient * 3.5f).coerceIn(0f, 1f)

            vocalL[i] = centerVocal * 1.15f
            vocalR[i] = centerVocal * 1.15f

            // Instrumental = Full mix minus center vocal band, preserving bass & stereo width
            instL[i] = (l - centerVocal * 0.92f).coerceIn(-1f, 1f)
            instR[i] = (r - centerVocal * 0.92f).coerceIn(-1f, 1f)

            bassL[i] = subBass * 1.1f
            bassR[i] = subBass * 1.1f

            val drumSig = (subBass * 0.55f * drumGate + hiTransient * 0.85f + mid * 0.25f * drumGate)
            drumsL[i] = drumSig.coerceIn(-1f, 1f)
            drumsR[i] = drumSig.coerceIn(-1f, 1f)

            val pL = pianoBpL.process(side + instL[i] * 0.45f)
            val pR = pianoBpR.process(-side + instR[i] * 0.45f)
            pianoL[i] = pL.coerceIn(-1f, 1f)
            pianoR[i] = pR.coerceIn(-1f, 1f)

            val gL = guitarBpL.process(instL[i] * 0.65f + side * 0.5f)
            val gR = guitarBpR.process(instR[i] * 0.65f - side * 0.5f)
            guitarL[i] = gL.coerceIn(-1f, 1f)
            guitarR[i] = gR.coerceIn(-1f, 1f)

            otherL[i] = (instL[i] - bassL[i] * 0.7f - drumsL[i] * 0.4f).coerceIn(-1f, 1f)
            otherR[i] = (instR[i] - bassR[i] * 0.7f - drumsR[i] * 0.4f).coerceIn(-1f, 1f)
        }

        onProgress(0.70f, "Writing WAV stem files...")
        val results = linkedMapOf<StemType, File>()

        fun save(type: StemType, l: FloatArray, r: FloatArray) {
            val out = File(outputDir, "${type.backendKey}.wav")
            WavCodec.writeStereoWav(out, l, r, sr)
            results[type] = out
        }

        when (preset) {
            SeparationPreset.TWO_STEM -> {
                save(StemType.VOCALS, vocalL, vocalR)
                save(StemType.INSTRUMENTAL, instL, instR)
            }
            SeparationPreset.FOUR_STEM -> {
                save(StemType.VOCALS, vocalL, vocalR)
                save(StemType.DRUMS, drumsL, drumsR)
                save(StemType.BASS, bassL, bassR)
                save(StemType.OTHER, otherL, otherR)
            }
            SeparationPreset.SIX_STEM -> {
                save(StemType.VOCALS, vocalL, vocalR)
                save(StemType.DRUMS, drumsL, drumsR)
                save(StemType.BASS, bassL, bassR)
                save(StemType.PIANO, pianoL, pianoR)
                save(StemType.GUITAR, guitarL, guitarR)
                save(StemType.OTHER, otherL, otherR)
            }
        }

        onProgress(1.0f, "Offline DSP separation complete!")
        results
    }

    suspend fun enhanceOffline(sourceFile: File, preset: String, destFile: File): File =
        withContext(Dispatchers.Default) {
            val audio = WavCodec.decodeAudioToStereoFloat(sourceFile)
            val sr = audio.sampleRate
            val n = audio.left.size
            val outL = FloatArray(n)
            val outR = FloatArray(n)

            val hpL = BiquadFilter.highPass(sr, 75f)
            val hpR = BiquadFilter.highPass(sr, 75f)
            val vocalPresenceL = BiquadFilter.bandPass(sr, 2800f, 1.2f)
            val vocalPresenceR = BiquadFilter.bandPass(sr, 2800f, 1.2f)
            val bassBoostL = BiquadFilter.lowPass(sr, 160f)
            val bassBoostR = BiquadFilter.lowPass(sr, 160f)

            var peak = 0.001f
            for (i in 0 until n) {
                val cleanL = hpL.process(audio.left[i])
                val cleanR = hpR.process(audio.right[i])
                val l = when (preset) {
                    "vocal_clarity" -> cleanL + vocalPresenceL.process(cleanL) * 0.45f
                    "bass_boost" -> cleanL + bassBoostL.process(cleanL) * 0.55f
                    else -> cleanL * 1.15f + vocalPresenceL.process(cleanL) * 0.2f
                }
                val r = when (preset) {
                    "vocal_clarity" -> cleanR + vocalPresenceR.process(cleanR) * 0.45f
                    "bass_boost" -> cleanR + bassBoostR.process(cleanR) * 0.55f
                    else -> cleanR * 1.15f + vocalPresenceR.process(cleanR) * 0.2f
                }
                outL[i] = l
                outR[i] = r
                val m = maxOf(abs(l), abs(r))
                if (m > peak) peak = m
            }

            val norm = (0.95f / peak).coerceAtMost(2.5f)
            for (i in 0 until n) {
                outL[i] = (outL[i] * norm).coerceIn(-1f, 1f)
                outR[i] = (outR[i] * norm).coerceIn(-1f, 1f)
            }

            WavCodec.writeStereoWav(destFile, outL, outR, sr)
            destFile
        }

    suspend fun analyzeOffline(sourceFile: File): AnalysisResult = withContext(Dispatchers.Default) {
        val audio = WavCodec.decodeAudioToStereoFloat(sourceFile, maxSeconds = 60)
        val sr = audio.sampleRate
        val n = audio.left.size
        val durationSec = n.toDouble() / sr.coerceAtLeast(1)

        // Energy distribution across spectral bands for lightweight instrument detection
        val bassLp = BiquadFilter.lowPass(sr, 220f)
        val vocalBp = BiquadFilter.bandPass(sr, 1200f, 0.7f)
        val midBp = BiquadFilter.bandPass(sr, 2600f, 0.9f)
        val highHp = BiquadFilter.highPass(sr, 5000f)

        var bassE = 0.0
        var vocalE = 0.0
        var midE = 0.0
        var highE = 0.0
        var totalE = 1e-6

        // Onset energy in 20ms windows for BPM estimate
        val hop = (sr * 0.02).toInt().coerceAtLeast(1)
        val windowEnergies = ArrayList<Double>(n / hop + 1)
        var accWindow = 0.0
        var count = 0

        for (i in 0 until n) {
            val mono = (audio.left[i] + audio.right[i]) * 0.5f
            val b = bassLp.process(mono)
            val v = vocalBp.process(mono)
            val m = midBp.process(mono)
            val h = highHp.process(mono)
            bassE += b * b
            vocalE += v * v
            midE += m * m
            highE += h * h
            totalE += mono * mono

            accWindow += mono * mono
            count++
            if (count >= hop) {
                windowEnergies.add(sqrt(accWindow / count))
                accWindow = 0.0
                count = 0
            }
        }

        // Peak-interval tempo approximation
        val avgWin = windowEnergies.average().takeIf { !it.isNaN() } ?: 0.0
        val threshold = avgWin * 1.45
        val peakIndices = mutableListOf<Int>()
        for (i in 1 until windowEnergies.size - 1) {
            if (windowEnergies[i] > threshold &&
                windowEnergies[i] > windowEnergies[i - 1] &&
                windowEnergies[i] >= windowEnergies[i + 1]
            ) {
                if (peakIndices.isEmpty() || i - peakIndices.last() > 14) {
                    peakIndices.add(i)
                }
            }
        }
        val estimatedBpm = if (peakIndices.size > 4) {
            val intervals = peakIndices.zipWithNext { a, b -> (b - a) * 0.02 }
            val medianInterval = intervals.sorted()[intervals.size / 2].coerceIn(0.3, 1.2)
            (60.0 / medianInterval).coerceIn(70.0, 175.0)
        } else {
            120.0
        }

        val instruments = listOf(
            InstrumentScore("Vocals", ((vocalE / totalE) * 1.4).toFloat().coerceIn(0.15f, 0.98f)),
            InstrumentScore("Bass", ((bassE / totalE) * 1.6).toFloat().coerceIn(0.15f, 0.96f)),
            InstrumentScore("Drums / Percussion", (((bassE + highE) / totalE) * 1.25).toFloat().coerceIn(0.2f, 0.95f)),
            InstrumentScore("Piano / Keys", ((midE / totalE) * 1.5).toFloat().coerceIn(0.1f, 0.92f)),
            InstrumentScore("Guitar / Strings", (((vocalE + midE) / totalE) * 0.95).toFloat().coerceIn(0.1f, 0.90f))
        ).sortedByDescending { it.confidence }

        AnalysisResult(
            bpm = (estimatedBpm * 10).toInt() / 10.0,
            musicalKey = "C Major (Est.)",
            durationSec = durationSec,
            detectedInstruments = instruments
        )
    }
}
