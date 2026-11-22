package com.aimusicstudio.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aimusicstudio.audio.ArrangementGenerator
import com.aimusicstudio.audio.OnDeviceDspSeparator
import com.aimusicstudio.audio.VideoAudioExtractor
import com.aimusicstudio.audio.WavCodec
import com.aimusicstudio.data.AnalysisResult
import com.aimusicstudio.data.BackendApiClient
import com.aimusicstudio.data.ExportResult
import com.aimusicstudio.data.MusicProject
import com.aimusicstudio.data.ProcessingEngine
import com.aimusicstudio.data.ProjectRepository
import com.aimusicstudio.data.SeparationPreset
import com.aimusicstudio.data.StemTrack
import com.aimusicstudio.data.StemType
import com.aimusicstudio.export.AudioExporter
import com.aimusicstudio.player.MultiTrackPlayerManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class StudioUiState(
    val projects: List<MusicProject> = emptyList(),
    val activeProject: MusicProject? = null,
    val selectedPreset: SeparationPreset = SeparationPreset.FOUR_STEM,
    val processingEngine: ProcessingEngine = ProcessingEngine.HYBRID_SERVER,
    val serverUrl: String = "http://192.168.1.10:8000",
    val isServerOnline: Boolean = false,
    val serverStatusText: String = "Not checked",
    val isBusy: Boolean = false,
    val progress: Float = 0f,
    val progressMessage: String = "",
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lastExport: ExportResult? = null,
    val statusBanner: String? = null
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)
    private val apiClient = BackendApiClient()
    private val playerManager = MultiTrackPlayerManager(application)
    private val exporter = AudioExporter(application)

    private val _uiState = MutableStateFlow(
        StudioUiState(
            projects = repository.getProjects(),
            serverUrl = repository.getServerUrl()
        )
    )
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private var positionTickerJob: Job? = null

    init {
        val saved = repository.getProjects()
        if (saved.isNotEmpty()) {
            openProject(saved.first(), autoLoadPlayer = true)
        }
        checkBackendHealth()
    }

    fun clearBanner() {
        _uiState.update { it.copy(statusBanner = null) }
    }

    fun updateServerUrl(newUrl: String) {
        repository.saveServerUrl(newUrl)
        _uiState.update { it.copy(serverUrl = newUrl.trim().removeSuffix("/")) }
    }

    fun setProcessingEngine(engine: ProcessingEngine) {
        _uiState.update { it.copy(processingEngine = engine) }
    }

    fun setSeparationPreset(preset: SeparationPreset) {
        _uiState.update { it.copy(selectedPreset = preset) }
    }

    fun checkBackendHealth() {
        viewModelScope.launch {
            val url = _uiState.value.serverUrl
            _uiState.update { it.copy(serverStatusText = "Checking server...") }
            val result = apiClient.checkHealth(url)
            result.fold(
                onSuccess = { health ->
                    val detail = if (health.demucs_available) "Online (Demucs AI Ready)" else "Online (DSP Fallback)"
                    _uiState.update {
                        it.copy(
                            isServerOnline = true,
                            serverStatusText = detail,
                            processingEngine = ProcessingEngine.HYBRID_SERVER
                        )
                    }
                },
                onFailure = {
                    _uiState.update {
                        it.copy(
                            isServerOnline = false,
                            serverStatusText = "Offline — On-Device DSP Mode available"
                        )
                    }
                }
            )
        }
    }

    fun importMediaFile(uri: Uri, onImported: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(isBusy = true, progress = 0.2f, progressMessage = "Importing media file...")
                }
                val (title, importedFile) = repository.importMediaUri(uri)
                val projectId = importedFile.parentFile?.name ?: UUID.randomUUID().toString().take(8)
                val projectDir = repository.getProjectDirectory(projectId)

                val audioFile = if (VideoAudioExtractor.isVideoFile(importedFile)) {
                    _uiState.update {
                        it.copy(progress = 0.55f, progressMessage = "Extracting audio from video...")
                    }
                    val wavOut = File(projectDir, "${title.replace(" ", "_")}_extracted.wav")
                    VideoAudioExtractor.extractAudioToWav(importedFile, wavOut)
                } else {
                    importedFile
                }

                _uiState.update { it.copy(progress = 0.85f, progressMessage = "Generating waveform...") }
                val peaks = WavCodec.computeWaveformPeaks(audioFile)

                val initialStem = StemTrack(
                    id = UUID.randomUUID().toString().take(8),
                    type = StemType.FULL_MIX,
                    label = "Original Track",
                    filePath = audioFile.absolutePath,
                    waveformPeaks = peaks
                )

                val project = MusicProject(
                    id = projectId,
                    name = title,
                    sourceFilePath = audioFile.absolutePath,
                    stems = listOf(initialStem)
                )

                repository.saveProject(project)
                refreshProjects(selectProject = project)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        progress = 0f,
                        progressMessage = "",
                        statusBanner = "Imported '$title'. Ready for AI Separation or Studio!"
                    )
                }
                onImported()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        progress = 0f,
                        progressMessage = "",
                        statusBanner = "Import failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun runSeparation(onComplete: () -> Unit = {}) {
        val project = _uiState.value.activeProject ?: run {
            _uiState.update { it.copy(statusBanner = "Please import an audio or video file first.") }
            return
        }

        viewModelScope.launch {
            pausePlayback()
            val preset = _uiState.value.selectedPreset
            val useServer = _uiState.value.processingEngine == ProcessingEngine.HYBRID_SERVER &&
                _uiState.value.isServerOnline
            val sourceFile = File(project.sourceFilePath)
            val stemsDir = File(repository.getProjectDirectory(project.id), "stems_${preset.backendMode}")
                .apply { mkdirs() }

            _uiState.update {
                it.copy(
                    isBusy = true,
                    progress = 0.05f,
                    progressMessage = if (useServer) "Connecting to Hybrid AI Server..." else "Starting On-Device DSP Separation..."
                )
            }

            try {
                val stemFiles: Map<StemType, File> = if (useServer) {
                    val serverRes = apiClient.separateAudio(
                        baseUrl = _uiState.value.serverUrl,
                        sourceFile = sourceFile,
                        preset = preset,
                        outputDir = stemsDir
                    ) { p, msg ->
                        _uiState.update { it.copy(progress = p, progressMessage = msg) }
                    }
                    serverRes.getOrElse { err ->
                        _uiState.update {
                            it.copy(progressMessage = "Server unavailable (${err.message}). Falling back to On-Device DSP...")
                        }
                        OnDeviceDspSeparator.separateOffline(sourceFile, preset, stemsDir) { p, msg ->
                            _uiState.update { it.copy(progress = p, progressMessage = msg) }
                        }
                    }
                } else {
                    OnDeviceDspSeparator.separateOffline(sourceFile, preset, stemsDir) { p, msg ->
                        _uiState.update { it.copy(progress = p, progressMessage = msg) }
                    }
                }

                val newStems = stemFiles.map { (type, file) ->
                    StemTrack(
                        id = UUID.randomUUID().toString().take(8),
                        type = type,
                        label = type.displayName,
                        filePath = file.absolutePath,
                        waveformPeaks = WavCodec.computeWaveformPeaks(file)
                    )
                }

                val updatedProject = project.copy(stems = newStems)
                repository.saveProject(updatedProject)
                refreshProjects(selectProject = updatedProject)

                _uiState.update {
                    it.copy(
                        isBusy = false,
                        progress = 0f,
                        progressMessage = "",
                        statusBanner = "Separated into ${newStems.size} stems! Opening Studio..."
                    )
                }
                onComplete()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        progress = 0f,
                        progressMessage = "",
                        statusBanner = "Separation error: ${e.message}"
                    )
                }
            }
        }
    }

    fun openProject(project: MusicProject, autoLoadPlayer: Boolean = true) {
        pausePlayback()
        _uiState.update {
            it.copy(
                activeProject = project,
                currentPositionMs = 0L,
                durationMs = 0L
            )
        }
        if (autoLoadPlayer && project.stems.isNotEmpty()) {
            playerManager.loadTracks(project.stems) {
                _uiState.update { it.copy(isPlaying = false, currentPositionMs = 0L) }
                stopPositionTicker()
            }
            playerManager.setSpeedAndPitch(project.speed, project.pitchSemitones)
            viewModelScope.launch {
                delay(250)
                _uiState.update { it.copy(durationMs = playerManager.getDurationMs()) }
            }
        }
    }

    fun deleteProject(projectId: String) {
        if (_uiState.value.activeProject?.id == projectId) {
            playerManager.release()
            _uiState.update { it.copy(activeProject = null, isPlaying = false) }
        }
        repository.deleteProject(projectId)
        val remaining = repository.getProjects()
        _uiState.update { it.copy(projects = remaining, statusBanner = "Project deleted.") }
    }

    // --- Playback & Mixer Controls ---

    fun togglePlayPause() {
        val project = _uiState.value.activeProject ?: return
        if (project.stems.isEmpty()) return
        if (_uiState.value.isPlaying) {
            pausePlayback()
        } else {
            playerManager.play()
            _uiState.update {
                it.copy(
                    isPlaying = true,
                    durationMs = playerManager.getDurationMs().coerceAtLeast(it.durationMs)
                )
            }
            startPositionTicker()
        }
    }

    fun pausePlayback() {
        playerManager.pause()
        stopPositionTicker()
        _uiState.update { it.copy(isPlaying = false) }
    }

    fun stopPlayback() {
        playerManager.stop()
        stopPositionTicker()
        _uiState.update { it.copy(isPlaying = false, currentPositionMs = 0L) }
    }

    fun seekTo(ms: Long) {
        playerManager.seekTo(ms)
        _uiState.update { it.copy(currentPositionMs = ms) }
    }

    fun updateStemVolume(stemId: String, volume: Float) {
        modifyActiveStems { stem ->
            if (stem.id == stemId) stem.copy(volume = volume.coerceIn(0f, 1.5f)) else stem
        }
    }

    fun toggleStemMute(stemId: String) {
        modifyActiveStems { stem ->
            if (stem.id == stemId) stem.copy(isMuted = !stem.isMuted) else stem
        }
    }

    fun toggleStemSolo(stemId: String) {
        modifyActiveStems { stem ->
            if (stem.id == stemId) stem.copy(isSolo = !stem.isSolo) else stem
        }
    }

    fun toggleStemRemove(stemId: String) {
        modifyActiveStems { stem ->
            if (stem.id == stemId) stem.copy(isRemoved = !stem.isRemoved) else stem
        }
    }

    fun isolateVocalsQuickAction() {
        val project = _uiState.value.activeProject ?: return
        val hasVocals = project.stems.any { it.type == StemType.VOCALS }
        if (!hasVocals) {
            _uiState.update { it.copy(statusBanner = "Separate tracks first to isolate vocals.") }
            return
        }
        modifyActiveStems { stem ->
            stem.copy(
                isSolo = stem.type == StemType.VOCALS,
                isMuted = false,
                isRemoved = false
            )
        }
        _uiState.update { it.copy(statusBanner = "Vocals isolated (Solo enabled on Vocals stem).") }
    }

    fun removeVocalsQuickAction() {
        val project = _uiState.value.activeProject ?: return
        val hasVocals = project.stems.any { it.type == StemType.VOCALS }
        if (!hasVocals) {
            _uiState.update { it.copy(statusBanner = "Separate tracks first to remove vocals.") }
            return
        }
        modifyActiveStems { stem ->
            if (stem.type == StemType.VOCALS) {
                stem.copy(isMuted = true, isSolo = false)
            } else {
                stem.copy(isMuted = false, isSolo = false)
            }
        }
        _uiState.update { it.copy(statusBanner = "Vocals removed — Karaoke/Instrumental mix active.") }
    }

    fun updateSpeed(speed: Float) {
        val project = _uiState.value.activeProject ?: return
        val clamped = speed.coerceIn(0.5f, 2.0f)
        val updated = project.copy(speed = clamped)
        playerManager.setSpeedAndPitch(clamped, updated.pitchSemitones)
        repository.saveProject(updated)
        _uiState.update { it.copy(activeProject = updated) }
    }

    fun updatePitch(semitones: Int) {
        val project = _uiState.value.activeProject ?: return
        val clamped = semitones.coerceIn(-12, 12)
        val updated = project.copy(pitchSemitones = clamped)
        playerManager.setSpeedAndPitch(updated.speed, clamped)
        repository.saveProject(updated)
        _uiState.update { it.copy(activeProject = updated) }
    }

    // --- AI Tools ---

    fun analyzeActiveProject() {
        val project = _uiState.value.activeProject ?: run {
            _uiState.update { it.copy(statusBanner = "Open or import a project to analyze.") }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, progress = 0.35f, progressMessage = "Analyzing BPM, key & instruments...")
            }
            val sourceFile = File(project.sourceFilePath)
            val analysis: AnalysisResult = try {
                if (_uiState.value.isServerOnline) {
                    apiClient.analyzeTrack(_uiState.value.serverUrl, sourceFile)
                        .getOrElse { OnDeviceDspSeparator.analyzeOffline(sourceFile) }
                } else {
                    OnDeviceDspSeparator.analyzeOffline(sourceFile)
                }
            } catch (_: Exception) {
                OnDeviceDspSeparator.analyzeOffline(sourceFile)
            }

            val updated = project.copy(analysis = analysis)
            repository.saveProject(updated)
            refreshProjects(selectProject = updated, reloadPlayer = false)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    progress = 0f,
                    progressMessage = "",
                    statusBanner = "Detected ${analysis.bpm} BPM | Key: ${analysis.musicalKey}"
                )
            }
        }
    }

    fun enhanceActiveTrack(preset: String) {
        val project = _uiState.value.activeProject ?: run {
            _uiState.update { it.copy(statusBanner = "Open or import a project to enhance.") }
            return
        }
        viewModelScope.launch {
            pausePlayback()
            _uiState.update {
                it.copy(isBusy = true, progress = 0.4f, progressMessage = "Applying AI Audio Enhancement ($preset)...")
            }
            try {
                val dir = repository.getProjectDirectory(project.id)
                val outFile = File(dir, "enhanced_${preset}_${System.currentTimeMillis() % 10000}.wav")
                val srcFile = File(project.sourceFilePath)
                val enhancedFile = if (_uiState.value.isServerOnline) {
                    apiClient.enhanceAudio(_uiState.value.serverUrl, srcFile, preset, outFile)
                        .getOrElse { OnDeviceDspSeparator.enhanceOffline(srcFile, preset, outFile) }
                } else {
                    OnDeviceDspSeparator.enhanceOffline(srcFile, preset, outFile)
                }

                val enhancedStem = StemTrack(
                    id = UUID.randomUUID().toString().take(8),
                    type = StemType.FULL_MIX,
                    label = "Enhanced (${preset.replace("_", " ")})",
                    filePath = enhancedFile.absolutePath,
                    waveformPeaks = WavCodec.computeWaveformPeaks(enhancedFile)
                )
                val updated = project.copy(
                    sourceFilePath = enhancedFile.absolutePath,
                    stems = listOf(enhancedStem)
                )
                repository.saveProject(updated)
                refreshProjects(selectProject = updated)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        progress = 0f,
                        progressMessage = "",
                        statusBanner = "Audio enhancement applied!"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        progress = 0f,
                        progressMessage = "",
                        statusBanner = "Enhancement failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun replaceInstrument(stemId: String, targetInstrument: String) {
        val project = _uiState.value.activeProject ?: return
        val stem = project.stems.firstOrNull { it.id == stemId } ?: return

        viewModelScope.launch {
            pausePlayback()
            _uiState.update {
                it.copy(
                    isBusy = true,
                    progress = 0.45f,
                    progressMessage = "Replacing ${stem.label} with $targetInstrument..."
                )
            }
            try {
                val dir = repository.getProjectDirectory(project.id)
                val outFile = File(dir, "replaced_${stem.id}_${targetInstrument.lowercase().replace(" ", "_")}.wav")
                val srcFile = File(stem.filePath)
                val replaced = if (_uiState.value.isServerOnline) {
                    apiClient.replaceInstrument(_uiState.value.serverUrl, srcFile, targetInstrument, outFile)
                        .getOrElse { ArrangementGenerator.synthesizeReplacementStem(srcFile, targetInstrument, outFile) }
                } else {
                    ArrangementGenerator.synthesizeReplacementStem(srcFile, targetInstrument, outFile)
                }

                val peaks = WavCodec.computeWaveformPeaks(replaced)
                val updatedStems = project.stems.map {
                    if (it.id == stemId) {
                        it.copy(
                            filePath = replaced.absolutePath,
                            replacementInstrument = targetInstrument,
                            label = "${it.type.displayName} → $targetInstrument",
                            waveformPeaks = peaks
                        )
                    } else it
                }
                val updated = project.copy(stems = updatedStems)
                repository.saveProject(updated)
                refreshProjects(selectProject = updated)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        progress = 0f,
                        progressMessage = "",
                        statusBanner = "Replaced ${stem.type.displayName} with $targetInstrument!"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        progress = 0f,
                        progressMessage = "",
                        statusBanner = "Replacement failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun generateAiArrangement(genre: String, key: String, bpm: Int, onCreated: () -> Unit = {}) {
        viewModelScope.launch {
            pausePlayback()
            _uiState.update {
                it.copy(isBusy = true, progress = 0.4f, progressMessage = "Generating $genre arrangement in $key ($bpm BPM)...")
            }
            try {
                val projectId = UUID.randomUUID().toString().take(8)
                val dir = repository.getProjectDirectory(projectId)
                val outFile = File(dir, "ai_${genre.lowercase()}_${key}_${bpm}bpm.wav")

                val generatedFile = if (_uiState.value.isServerOnline) {
                    apiClient.generateArrangement(_uiState.value.serverUrl, genre, key, bpm, 16, outFile)
                        .getOrElse {
                            ArrangementGenerator.generateInstrumentalArrangement(outFile, genre, key, bpm, 16)
                        }
                } else {
                    ArrangementGenerator.generateInstrumentalArrangement(outFile, genre, key, bpm, 16)
                }

                val peaks = WavCodec.computeWaveformPeaks(generatedFile)
                val stem = StemTrack(
                    id = UUID.randomUUID().toString().take(8),
                    type = StemType.INSTRUMENTAL,
                    label = "AI $genre Backing ($key)",
                    filePath = generatedFile.absolutePath,
                    waveformPeaks = peaks
                )
                val project = MusicProject(
                    id = projectId,
                    name = "AI $genre — $key ($bpm BPM)",
                    sourceFilePath = generatedFile.absolutePath,
                    stems = listOf(stem)
                )
                repository.saveProject(project)
                refreshProjects(selectProject = project)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        progress = 0f,
                        progressMessage = "",
                        statusBanner = "Generated AI instrumental arrangement!"
                    )
                }
                onCreated()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        progress = 0f,
                        progressMessage = "",
                        statusBanner = "Generation failed: ${e.message}"
                    )
                }
            }
        }
    }

    // --- Export ---

    fun exportCurrentMix() {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, progress = 0.5f, progressMessage = "Rendering multi-track WAV mix...")
            }
            try {
                val result = exporter.exportMixedProject(project)
                val destMsg = if (result.savedToDownloads) {
                    "Exported to Downloads/AI-Music-Studio/${result.displayName}"
                } else {
                    "Exported ${result.displayName}"
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        progress = 0f,
                        progressMessage = "",
                        lastExport = result,
                        statusBanner = destMsg
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        progress = 0f,
                        progressMessage = "",
                        statusBanner = "Export failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun exportIndividualStem(stem: StemTrack) {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, progress = 0.5f, progressMessage = "Exporting ${stem.label}...")
            }
            try {
                val result = exporter.exportSingleStem(project.name, stem)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        progress = 0f,
                        progressMessage = "",
                        lastExport = result,
                        statusBanner = "Saved stem: ${result.displayName}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        progress = 0f,
                        progressMessage = "",
                        statusBanner = "Stem export failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun shareExport(filePath: String) {
        exporter.shareAudioFile(filePath)
    }

    private fun modifyActiveStems(transform: (StemTrack) -> StemTrack) {
        val project = _uiState.value.activeProject ?: return
        val updatedStems = project.stems.map(transform)
        val updatedProject = project.copy(stems = updatedStems)
        playerManager.applyMixState(updatedStems)
        repository.saveProject(updatedProject)
        _uiState.update { state ->
            state.copy(
                activeProject = updatedProject,
                projects = state.projects.map { if (it.id == updatedProject.id) updatedProject else it }
            )
        }
    }

    private fun refreshProjects(selectProject: MusicProject? = null, reloadPlayer: Boolean = true) {
        val all = repository.getProjects()
        _uiState.update { it.copy(projects = all) }
        if (selectProject != null) {
            openProject(selectProject, autoLoadPlayer = reloadPlayer)
        }
    }

    private fun startPositionTicker() {
        stopPositionTicker()
        positionTickerJob = viewModelScope.launch {
            while (isActive && _uiState.value.isPlaying) {
                _uiState.update {
                    it.copy(
                        currentPositionMs = playerManager.getCurrentPositionMs(),
                        durationMs = playerManager.getDurationMs().coerceAtLeast(it.durationMs)
                    )
                }
                delay(200)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPositionTicker()
        playerManager.release()
    }
}
