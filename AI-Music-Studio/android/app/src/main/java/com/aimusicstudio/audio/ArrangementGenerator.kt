package com.aimusicstudio.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Built-in polyphonic synthesizer & rhythm generator so users can generate
 * instrumental backing arrangements or replacement instrument layers completely offline.
 */
object ArrangementGenerator {

    private val keyOffsets = mapOf(
        "C" to 0, "C#" to 1, "D" to 2, "Eb" to 3, "E" to 4, "F" to 5,
        "F#" to 6, "G" to 7, "Ab" to 8, "A" to 9, "Bb" to 10, "B" to 11
    )

    private fun midiToFreq(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

    suspend fun generateInstrumentalArrangement(
        outputFile: File,
        genre: String = "Pop",
        rootKey: String = "C",
        bpm: Int = 110,
        durationSec: Int = 16
    ): File = withContext(Dispatchers.Default) {
        val sr = 44100
        val totalSamples = sr * durationSec
        val left = FloatArray(totalSamples)
        val right = FloatArray(totalSamples)

        val rootMidi = 60 + (keyOffsets[rootKey] ?: 0)
        // I - V - vi - IV progression
        val chords = when (genre.lowercase()) {
            "lo-fi" -> listOf(
                listOf(rootMidi, rootMidi + 4, rootMidi + 7, rootMidi + 11),
                listOf(rootMidi + 9, rootMidi + 12, rootMidi + 16, rootMidi + 19),
                listOf(rootMidi + 2, rootMidi + 5, rootMidi + 9, rootMidi + 12),
                listOf(rootMidi + 7, rootMidi + 11, rootMidi + 14, rootMidi + 17)
            )
            "edm" -> listOf(
                listOf(rootMidi, rootMidi + 3, rootMidi + 7),
                listOf(rootMidi + 8, rootMidi + 12, rootMidi + 15),
                listOf(rootMidi + 3, rootMidi + 7, rootMidi + 10),
                listOf(rootMidi + 10, rootMidi + 14, rootMidi + 17)
            )
            else -> listOf(
                listOf(rootMidi, rootMidi + 4, rootMidi + 7),
                listOf(rootMidi + 7, rootMidi + 11, rootMidi + 14),
                listOf(rootMidi + 9, rootMidi + 12, rootMidi + 16),
                listOf(rootMidi + 5, rootMidi + 9, rootMidi + 12)
            )
        }

        val beatDuration = 60.0 / bpm.coerceIn(60, 180)
        val barDuration = beatDuration * 4.0
        val rng = Random(42)

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sr
            val barIdx = (t / barDuration).toInt()
            val barPos = t % barDuration
            val beatIdx = (t / beatDuration).toInt()
            val beatPos = t % beatDuration
            val chord = chords[barIdx % chords.size]

            // Warm electric piano / pad chord
            val chordEnv = exp(-barPos * 1.1) * (1.0 - exp(-barPos * 40.0))
            var pad = 0.0
            for (note in chord) {
                val freq = midiToFreq(note)
                pad += sin(2.0 * PI * freq * t) * 0.65 + sin(4.0 * PI * freq * t) * 0.2
            }
            pad = (pad / chord.size) * chordEnv * 0.35

            // Deep bass on root note
            val bassFreq = midiToFreq(chord.first() - 12)
            val bassEnv = exp(-beatPos * 2.8)
            val bass = sin(2.0 * PI * bassFreq * t) * bassEnv * 0.35

            // Kick drum on beats 0 & 2
            var drums = 0.0
            if (beatIdx % 2 == 0 && beatPos < 0.16) {
                val kickFreq = 52.0 + 95.0 * exp(-beatPos * 35.0)
                drums += sin(2.0 * PI * kickFreq * beatPos) * exp(-beatPos * 18.0) * 0.5
            }
            // Snare on beats 1 & 3
            if (beatIdx % 2 == 1 && beatPos < 0.14) {
                val noise = (rng.nextDouble() * 2.0 - 1.0)
                drums += noise * exp(-beatPos * 26.0) * 0.25
            }
            // Hi-hat on eighth notes
            val eighthPos = t % (beatDuration / 2.0)
            if (eighthPos < 0.03) {
                drums += (rng.nextDouble() * 2.0 - 1.0) * exp(-eighthPos * 95.0) * 0.08
            }

            val stereoDrift = sin(2.0 * PI * 0.4 * t) * 0.08
            left[i] = (pad * (1.0 + stereoDrift) + bass + drums).toFloat().coerceIn(-0.98f, 0.98f)
            right[i] = (pad * (1.0 - stereoDrift) + bass + drums).toFloat().coerceIn(-0.98f, 0.98f)
        }

        WavCodec.writeStereoWav(outputFile, left, right, sr)
        outputFile
    }

    /**
     * Synthesizes a replacement instrument layer matched to the target duration and timbre.
     */
    suspend fun synthesizeReplacementStem(
        sourceStemFile: File,
        instrumentName: String,
        outputFile: File
    ): File = withContext(Dispatchers.Default) {
        val original = WavCodec.decodeAudioToStereoFloat(sourceStemFile, maxSeconds = 120)
        val n = original.left.size
        val sr = original.sampleRate
        val outL = FloatArray(n)
        val outR = FloatArray(n)

        val baseFreq = when (instrumentName.lowercase()) {
            "piano" -> 261.63
            "synth pad" -> 220.0
            "electric bass" -> 82.41
            "acoustic guitar" -> 196.0
            "strings" -> 329.63
            else -> 261.63
        }

        // Envelope follower from original stem drives synthesized replacement instrument
        var env = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val amp = (kotlin.math.abs(original.left[i]) + kotlin.math.abs(original.right[i])) * 0.5f
            env = if (amp > env) env * 0.7f + amp * 0.3f else env * 0.992f + amp * 0.008f

            val wave = when (instrumentName.lowercase()) {
                "piano" -> sin(2.0 * PI * baseFreq * t) * 0.6 +
                        sin(4.0 * PI * baseFreq * t) * 0.25 +
                        sin(6.0 * PI * baseFreq * t) * 0.12
                "electric bass" -> sin(2.0 * PI * baseFreq * t) * 0.8 +
                        sin(4.0 * PI * baseFreq * t) * 0.2
                "strings" -> sin(2.0 * PI * baseFreq * t) * 0.5 +
                        sin(2.0 * PI * (baseFreq * 1.5) * t) * 0.3 +
                        sin(2.0 * PI * (baseFreq * 2.0) * t) * 0.2
                else -> sin(2.0 * PI * baseFreq * t) * 0.55 +
                        sin(2.0 * PI * (baseFreq * 1.25) * t) * 0.35
            }

            val shaped = (wave * env * 1.65).toFloat().coerceIn(-0.98f, 0.98f)
            outL[i] = shaped
            outR[i] = shaped
        }

        WavCodec.writeStereoWav(outputFile, outL, outR, sr)
        outputFile
    }
}
