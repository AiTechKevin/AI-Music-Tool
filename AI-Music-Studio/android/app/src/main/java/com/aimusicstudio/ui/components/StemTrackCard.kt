package com.aimusicstudio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aimusicstudio.data.StemTrack
import com.aimusicstudio.ui.theme.StudioCard
import com.aimusicstudio.ui.theme.StudioTextMuted

@Composable
fun StemTrackCard(
    stem: StemTrack,
    playbackProgress: Float,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
    onToggleRemove: () -> Unit,
    onReplaceInstrument: (String) -> Unit,
    onExportStem: () -> Unit
) {
    val accent = Color(stem.type.colorHex)
    var showReplaceMenu by remember { mutableStateOf(false) }
    val replacementOptions = listOf("Piano", "Acoustic Guitar", "Synth Pad", "Electric Bass", "Strings")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (stem.isRemoved) StudioCard.copy(alpha = 0.45f) else StudioCard
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (stem.isRemoved) Color.Gray else accent)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stem.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (stem.isRemoved) {
                            Text(
                                text = "Instrument removed from mix",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFF87171)
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { showReplaceMenu = true }) {
                            Icon(
                                Icons.Default.SwapHoriz,
                                contentDescription = "Replace instrument",
                                tint = accent
                            )
                        }
                        DropdownMenu(
                            expanded = showReplaceMenu,
                            onDismissRequest = { showReplaceMenu = false }
                        ) {
                            replacementOptions.forEach { inst ->
                                DropdownMenuItem(
                                    text = { Text("Replace with $inst") },
                                    onClick = {
                                        showReplaceMenu = false
                                        onReplaceInstrument(inst)
                                    }
                                )
                            }
                        }
                    }

                    IconButton(onClick = onExportStem) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Export stem",
                            tint = StudioTextMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            WaveformView(
                peaks = stem.waveformPeaks,
                progress = playbackProgress,
                accentColor = accent,
                isDimmed = stem.isMuted || stem.isRemoved
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (stem.isMuted || stem.isRemoved) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = if (stem.isMuted || stem.isRemoved) StudioTextMuted else accent,
                    modifier = Modifier.size(18.dp)
                )
                Slider(
                    value = stem.volume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1.5f,
                    enabled = !stem.isRemoved,
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    text = "${(stem.volume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioTextMuted,
                    modifier = Modifier.width(42.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = stem.isMuted,
                    onClick = onToggleMute,
                    label = { Text("Mute") }
                )
                FilterChip(
                    selected = stem.isSolo,
                    onClick = onToggleSolo,
                    label = { Text("Solo") }
                )
                FilterChip(
                    selected = stem.isRemoved,
                    onClick = onToggleRemove,
                    label = { Text(if (stem.isRemoved) "Restore" else "Remove") }
                )
            }
        }
    }
}
