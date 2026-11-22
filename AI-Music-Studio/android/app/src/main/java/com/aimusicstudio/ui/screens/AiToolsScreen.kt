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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aimusicstudio.editor.StudioUiState
import com.aimusicstudio.ui.theme.StudioAccentCyan
import com.aimusicstudio.ui.theme.StudioCard
import com.aimusicstudio.ui.theme.StudioTextMuted
import kotlin.math.roundToInt

@Composable
fun AiToolsScreen(
    uiState: StudioUiState,
    onAnalyzeTrack: () -> Unit,
    onEnhanceTrack: (String) -> Unit,
    onExtractVideo: () -> Unit,
    onGenerateArrangement: (genre: String, key: String, bpm: Int) -> Unit
) {
    var selectedGenre by remember { mutableStateOf("Pop") }
    var selectedKey by remember { mutableStateOf("C") }
    var selectedBpm by remember { mutableIntStateOf(110) }

    val genres = listOf("Pop", "Lo-Fi", "EDM", "Rock")
    val keys = listOf("C", "D", "E", "F", "G", "A")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "AI Audio Tools",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Instrument detection, audio mastering, video extraction & AI backing generation.",
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextMuted
            )
        }

        // 1. Instrument Detection & Key/BPM Analysis
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = StudioCard)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Analytics, contentDescription = null, tint = StudioAccentCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Instrument Detection & Key/BPM",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val analysis = uiState.activeProject?.analysis
                    if (analysis != null) {
                        Text(
                            text = "Tempo: ${analysis.bpm} BPM  •  Key: ${analysis.musicalKey}  •  Duration: ${analysis.durationSec.toInt()}s",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        analysis.detectedInstruments.forEach { inst ->
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(inst.name, style = MaterialTheme.typography.bodySmall)
                                    Text("${(inst.confidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = StudioTextMuted)
                                }
                                LinearProgressIndicator(
                                    progress = { inst.confidence.coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Identify active instruments, BPM, and musical key in your active song.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextMuted
                        )
                    }

                    Button(
                        onClick = onAnalyzeTrack,
                        enabled = !uiState.isBusy && uiState.activeProject != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Analyze Active Track")
                    }
                }
            }
        }

        // 2. AI Audio Enhancement
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = StudioCard)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Equalizer, contentDescription = null, tint = StudioAccentCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AI Audio Enhancer",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Clean noise, boost vocal clarity, or master loudness on your active track.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextMuted
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { onEnhanceTrack("studio_master") },
                            enabled = !uiState.isBusy && uiState.activeProject != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Master")
                        }
                        FilledTonalButton(
                            onClick = { onEnhanceTrack("vocal_clarity") },
                            enabled = !uiState.isBusy && uiState.activeProject != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Vocal Fix")
                        }
                        FilledTonalButton(
                            onClick = { onEnhanceTrack("bass_boost") },
                            enabled = !uiState.isBusy && uiState.activeProject != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Bass+")
                        }
                    }
                }
            }
        }

        // 3. Video-to-Audio Extractor
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = StudioCard)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Movie, contentDescription = null, tint = StudioAccentCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Video-to-Audio Extractor",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Pick an MP4/MKV/MOV video to extract high-quality WAV audio directly into a project.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextMuted
                    )
                    FilledTonalButton(
                        onClick = onExtractVideo,
                        enabled = !uiState.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pick Video & Extract Audio")
                    }
                }
            }
        }

        // 4. AI Music Arrangement Generator
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = StudioCard)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Piano, contentDescription = null, tint = StudioAccentCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AI Instrumental Generator",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text("Style / Genre", style = MaterialTheme.typography.labelMedium, color = StudioTextMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        genres.forEach { g ->
                            FilterChip(
                                selected = selectedGenre == g,
                                onClick = { selectedGenre = g },
                                label = { Text(g) }
                            )
                        }
                    }

                    Text("Musical Key", style = MaterialTheme.typography.labelMedium, color = StudioTextMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        keys.forEach { k ->
                            FilterChip(
                                selected = selectedKey == k,
                                onClick = { selectedKey = k },
                                label = { Text(k) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Tempo", style = MaterialTheme.typography.labelMedium, color = StudioTextMuted)
                        Text("$selectedBpm BPM", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = selectedBpm.toFloat(),
                        onValueChange = { selectedBpm = it.roundToInt() },
                        valueRange = 70f..160f
                    )

                    Button(
                        onClick = { onGenerateArrangement(selectedGenre, selectedKey, selectedBpm) },
                        enabled = !uiState.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Generate Instrumental Track")
                    }
                }
            }
        }
    }
}
