package com.aimusicstudio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun WaveformView(
    peaks: List<Float>,
    progress: Float,
    accentColor: Color,
    isDimmed: Boolean = false,
    modifier: Modifier = Modifier
) {
    val safePeaks = if (peaks.isEmpty()) List(48) { 0.3f } else peaks
    val playedColor = if (isDimmed) accentColor.copy(alpha = 0.25f) else accentColor
    val unplayedColor = if (isDimmed) Color(0xFF334155).copy(alpha = 0.35f) else accentColor.copy(alpha = 0.32f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
    ) {
        val barCount = safePeaks.size
        if (barCount == 0) return@Canvas
        val gap = 3f
        val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(2f)
        val centerY = size.height / 2f

        safePeaks.forEachIndexed { idx, amp ->
            val fraction = idx.toFloat() / barCount.toFloat()
            val barHeight = (amp.coerceIn(0.08f, 1f) * size.height).coerceAtLeast(4f)
            val x = idx * (barWidth + gap)
            drawRoundRect(
                color = if (fraction <= progress) playedColor else unplayedColor,
                topLeft = Offset(x, centerY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
