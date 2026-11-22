package com.aimusicstudio.data

enum class StemType(val displayName: String, val backendKey: String, val colorHex: Long) {
    VOCALS("Vocals", "vocals", 0xFFF472B6),
    DRUMS("Drums", "drums", 0xFFFB923C),
    BASS("Bass", "bass", 0xFF38BDF8),
    PIANO("Piano", "piano", 0xFFA78BFA),
    GUITAR("Guitar", "guitar", 0xFF34D399),
    OTHER("Other / Synth", "other", 0xFFFACC15),
    INSTRUMENTAL("Instrumental", "no_vocals", 0xFF60A5FA),
    FULL_MIX("Original Mix", "original", 0xFF94A3B8)
}

enum class SeparationPreset(
    val title: String,
    val description: String,
    val backendMode: String,
    val stems: List<StemType>
) {
    TWO_STEM(
        title = "2 Stems (Vocals + Instrumental)",
        description = "Fast vocal remover & vocal isolation",
        backendMode = "2stems",
        stems = listOf(StemType.VOCALS, StemType.INSTRUMENTAL)
    ),
    FOUR_STEM(
        title = "4 Stems (Vocals, Drums, Bass, Other)",
        description = "Balanced separation for remixing & practice",
        backendMode = "4stems",
        stems = listOf(StemType.VOCALS, StemType.DRUMS, StemType.BASS, StemType.OTHER)
    ),
    SIX_STEM(
        title = "6 Stems (+ Piano & Guitar)",
        description = "Full Demucs 6-stem studio separation",
        backendMode = "6stems",
        stems = listOf(
            StemType.VOCALS,
            StemType.DRUMS,
            StemType.BASS,
            StemType.PIANO,
            StemType.GUITAR,
            StemType.OTHER
        )
    )
}

enum class ProcessingEngine(val label: String) {
    HYBRID_SERVER("Hybrid AI Server (Demucs)"),
    ON_DEVICE_DSP("On-Device Quick DSP (Offline)")
}

data class StemTrack(
    val id: String,
    val type: StemType,
    val label: String,
    val filePath: String,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val isSolo: Boolean = false,
    val isRemoved: Boolean = false,
    val replacementInstrument: String? = null,
    val waveformPeaks: List<Float> = emptyList()
)

data class AnalysisResult(
    val bpm: Double = 0.0,
    val musicalKey: String = "Unknown",
    val durationSec: Double = 0.0,
    val detectedInstruments: List<InstrumentScore> = emptyList()
)

data class InstrumentScore(
    val name: String,
    val confidence: Float
)

data class MusicProject(
    val id: String,
    val name: String,
    val sourceFilePath: String,
    val createdAt: Long = System.currentTimeMillis(),
    val stems: List<StemTrack> = emptyList(),
    val speed: Float = 1.0f,
    val pitchSemitones: Int = 0,
    val analysis: AnalysisResult? = null
)

data class ExportResult(
    val filePath: String,
    val displayName: String,
    val savedToDownloads: Boolean
)
