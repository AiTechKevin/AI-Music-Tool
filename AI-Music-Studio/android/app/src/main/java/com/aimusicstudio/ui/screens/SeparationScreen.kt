package com.aimusicstudio.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aimusicstudio.data.ProcessingEngine
import com.aimusicstudio.data.SeparationPreset
import com.aimusicstudio.editor.StudioUiState
import com.aimusicstudio.ui.theme.StudioAccentCyan
import com.aimusicstudio.ui.theme.StudioCard
import com.aimusicstudio.ui.theme.StudioTextMuted

@Composable
fun SeparationScreen(
    uiState: StudioUiState,
    onPickMedia: () -> Unit,
    onSelectPreset: (SeparationPreset) -> Unit,
    onSelectEngine: (ProcessingEngine) -> Unit,
    onStartSeparation: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "AI Stem Separation",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Isolate vocals, drums, bass, piano, guitar & accompaniment.",
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextMuted
            )
        }

        // Selected Track Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = StudioCard)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Active Source Track",
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioTextMuted
                        )
                        Text(
                            text = uiState.activeProject?.name ?: "No track selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(onClick = onPickMedia) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Change")
                    }
                }
            }
        }

        // Processing Engine Selector (Hybrid vs Offline DSP)
        item {
            Text(
                text = "Processing Engine",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProcessingEngine.entries.forEach { engine ->
                    FilterChip(
                        selected = uiState.processingEngine == engine,
                        onClick = { onSelectEngine(engine) },
                        label = {
                            Text(
                                text = if (engine == ProcessingEngine.HYBRID_SERVER) "Hybrid AI (Demucs)" else "On-Device DSP"
                            )
                        }
                    )
                }
            }
            Text(
                text = if (uiState.processingEngine == ProcessingEngine.HYBRID_SERVER) {
                    if (uiState.isServerOnline) "Connected to Demucs AI backend — studio neural quality."
                    else "Hybrid server offline — will automatically fall back to On-Device DSP."
                } else {
                    "Runs 100% offline on your Android device using Mid/Side + multi-band filters."
                },
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextMuted
            )
        }

        item {
            Text(
                text = "Separation Mode",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }

        items(SeparationPreset.entries) { preset ->
            val selected = uiState.selectedPreset == preset
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectPreset(preset) },
                shape = RoundedCornerShape(16.dp),
                border = if (selected) BorderStroke(1.5.dp, StudioAccentCyan) else null,
                colors = CardDefaults.cardColors(containerColor = StudioCard)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = preset.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextMuted
                        )
                    }
                    if (selected) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StudioAccentCyan)
                    }
                }
            }
        }

        item {
            Button(
                onClick = onStartSeparation,
                enabled = !uiState.isBusy && uiState.activeProject != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Start AI Separation",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
