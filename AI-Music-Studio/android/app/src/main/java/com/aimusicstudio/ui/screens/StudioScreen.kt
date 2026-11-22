package com.aimusicstudio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aimusicstudio.data.StemTrack
import com.aimusicstudio.editor.StudioUiState
import com.aimusicstudio.ui.components.StemTrackCard
import com.aimusicstudio.ui.theme.StudioCard
import com.aimusicstudio.ui.theme.StudioTextMuted
import kotlin.math.roundToInt

@Composable
fun StudioScreen(
    uiState: StudioUiState,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (String, Float) -> Unit,
    onToggleMute: (String) -> Unit,
    onToggleSolo: (String) -> Unit,
    onToggleRemove: (String) -> Unit,
    onReplaceInstrument: (String, String) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Int) -> Unit,
    onIsolateVocals: () -> Unit,
    onRemoveVocals: () -> Unit,
    onExportMix: () -> Unit,
    onExportStem: (StemTrack) -> Unit
) {
    val project = uiState.activeProject

    if (project == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Studio is Empty",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Import an audio or video file on Home, then separate stems to mix tracks here.",
                style = MaterialTheme.typography.bodyMedium,
                color = StudioTextMuted
            )
        }
        return
    }

    val duration = uiState.durationMs.coerceAtLeast(1L)
    val progress = (uiState.currentPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header + Export Button
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${project.stems.size} active track(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextMuted
                    )
                }
                Button(onClick = onExportMix, enabled = !uiState.isBusy) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export WAV")
                }
            }
        }

        // Transport + Scrub Bar
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = StudioCard)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Slider(
                        value = progress,
                        onValueChange = { frac -> onSeek((frac * duration).toLong()) }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(uiState.currentPositionMs), style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                        Text(formatTime(uiState.durationMs), style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onStop) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop")
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        FilledTonalIconButton(onClick = onTogglePlayPause) {
                            Icon(
                                imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play or Pause"
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = onRemoveVocals,
                            leadingIcon = { Icon(Icons.Default.VolumeOff, contentDescription = null) },
                            label = { Text("Remove Vocals") }
                        )
                        AssistChip(
                            onClick = onIsolateVocals,
                            leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null) },
                            label = { Text("Isolate Vocals") }
                        )
                    }
                }
            }
        }

        // Speed & Pitch Controls
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = StudioCard)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Audio Speed", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text("${"%.2f".format(project.speed)}x", style = MaterialTheme.typography.labelLarge)
                    }
                    Slider(
                        value = project.speed,
                        onValueChange = onSpeedChange,
                        valueRange = 0.5f..2.0f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Pitch / Key Shift", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        val sign = if (project.pitchSemitones > 0) "+" else ""
                        Text("$sign${project.pitchSemitones} semitones", style = MaterialTheme.typography.labelLarge)
                    }
                    Slider(
                        value = project.pitchSemitones.toFloat(),
                        onValueChange = { onPitchChange(it.roundToInt()) },
                        valueRange = -12f..12f,
                        steps = 23
                    )
                }
            }
        }

        // Individual Stem Mixer Tracks
        items(project.stems, key = { it.id }) { stem ->
            StemTrackCard(
                stem = stem,
                playbackProgress = progress,
                onVolumeChange = { vol -> onVolumeChange(stem.id, vol) },
                onToggleMute = { onToggleMute(stem.id) },
                onToggleSolo = { onToggleSolo(stem.id) },
                onToggleRemove = { onToggleRemove(stem.id) },
                onReplaceInstrument = { target -> onReplaceInstrument(stem.id, target) },
                onExportStem = { onExportStem(stem) }
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60
    val secs = totalSec % 60
    return "%d:%02d".format(mins, secs)
}
