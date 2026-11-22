package com.aimusicstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aimusicstudio.editor.StudioViewModel
import com.aimusicstudio.ui.screens.AiToolsScreen
import com.aimusicstudio.ui.screens.HomeScreen
import com.aimusicstudio.ui.screens.ProjectsScreen
import com.aimusicstudio.ui.screens.SeparationScreen
import com.aimusicstudio.ui.screens.StudioScreen
import com.aimusicstudio.ui.theme.AIMusicStudioTheme
import com.aimusicstudio.ui.theme.StudioBg
import com.aimusicstudio.ui.theme.StudioCard
import com.aimusicstudio.ui.theme.StudioSurface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AIMusicStudioTheme {
                AiMusicStudioApp()
            }
        }
    }
}

private data class NavTab(val label: String, val icon: ImageVector)

@Composable
fun AiMusicStudioApp(viewModel: StudioViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    val tabs = listOf(
        NavTab("Home", Icons.Default.Home),
        NavTab("Separate", Icons.Default.AutoAwesome),
        NavTab("Studio", Icons.Default.Tune),
        NavTab("AI Tools", Icons.Default.Build),
        NavTab("Projects", Icons.Default.Folder)
    )

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importMediaFile(uri) {
                selectedTab = 1
            }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importMediaFile(uri) {
                selectedTab = 1
            }
        }
    }

    LaunchedEffect(uiState.statusBanner) {
        val message = uiState.statusBanner
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearBanner()
        }
    }

    Scaffold(
        containerColor = StudioBg,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = StudioSurface) {
                tabs.forEachIndexed { idx, tab ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, maxLines = 1) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> HomeScreen(
                    uiState = uiState,
                    onPickAudio = { audioPicker.launch(arrayOf("audio/*")) },
                    onPickVideo = { videoPicker.launch(arrayOf("video/*")) },
                    onOpenProject = { project ->
                        viewModel.openProject(project)
                        selectedTab = 2
                    },
                    onSaveServerUrl = { viewModel.updateServerUrl(it) },
                    onCheckServer = { viewModel.checkBackendHealth() }
                )

                1 -> SeparationScreen(
                    uiState = uiState,
                    onPickMedia = { audioPicker.launch(arrayOf("audio/*", "video/*")) },
                    onSelectPreset = { viewModel.setSeparationPreset(it) },
                    onSelectEngine = { viewModel.setProcessingEngine(it) },
                    onStartSeparation = {
                        viewModel.runSeparation {
                            selectedTab = 2
                        }
                    }
                )

                2 -> StudioScreen(
                    uiState = uiState,
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    onStop = { viewModel.stopPlayback() },
                    onSeek = { viewModel.seekTo(it) },
                    onVolumeChange = { id, vol -> viewModel.updateStemVolume(id, vol) },
                    onToggleMute = { viewModel.toggleStemMute(it) },
                    onToggleSolo = { viewModel.toggleStemSolo(it) },
                    onToggleRemove = { viewModel.toggleStemRemove(it) },
                    onReplaceInstrument = { id, target -> viewModel.replaceInstrument(id, target) },
                    onSpeedChange = { viewModel.updateSpeed(it) },
                    onPitchChange = { viewModel.updatePitch(it) },
                    onIsolateVocals = { viewModel.isolateVocalsQuickAction() },
                    onRemoveVocals = { viewModel.removeVocalsQuickAction() },
                    onExportMix = { viewModel.exportCurrentMix() },
                    onExportStem = { viewModel.exportIndividualStem(it) }
                )

                3 -> AiToolsScreen(
                    uiState = uiState,
                    onAnalyzeTrack = { viewModel.analyzeActiveProject() },
                    onEnhanceTrack = { viewModel.enhanceActiveTrack(it) },
                    onExtractVideo = { videoPicker.launch(arrayOf("video/*")) },
                    onGenerateArrangement = { genre, key, bpm ->
                        viewModel.generateAiArrangement(genre, key, bpm) {
                            selectedTab = 2
                        }
                    }
                )

                4 -> ProjectsScreen(
                    uiState = uiState,
                    onOpenInStudio = { project ->
                        viewModel.openProject(project)
                        selectedTab = 2
                    },
                    onExportProject = { project ->
                        viewModel.openProject(project, autoLoadPlayer = false)
                        viewModel.exportCurrentMix()
                    },
                    onShareFile = { viewModel.shareExport(it) },
                    onDeleteProject = { viewModel.deleteProject(it) }
                )
            }

            // Busy Progress Overlay
            if (uiState.isBusy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = StudioCard)
                    ) {
                        Column(
                            modifier = Modifier.padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Processing Audio",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = uiState.progressMessage.ifBlank { "Working..." },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            LinearProgressIndicator(
                                progress = { uiState.progress.coerceIn(0.05f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
