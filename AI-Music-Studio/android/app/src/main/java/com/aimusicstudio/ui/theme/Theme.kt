package com.aimusicstudio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val StudioBg = Color(0xFF0B0F1A)
val StudioSurface = Color(0xFF131A2B)
val StudioCard = Color(0xFF1A233A)
val StudioAccentCyan = Color(0xFF38BDF8)
val StudioAccentPurple = Color(0xFF818CF8)
val StudioAccentPink = Color(0xFFF472B6)
val StudioTextPrimary = Color(0xFFF8FAFC)
val StudioTextMuted = Color(0xFF94A3B8)

private val StudioDarkColorScheme = darkColorScheme(
    primary = StudioAccentCyan,
    onPrimary = Color(0xFF04131D),
    secondary = StudioAccentPurple,
    onSecondary = Color.White,
    tertiary = StudioAccentPink,
    background = StudioBg,
    onBackground = StudioTextPrimary,
    surface = StudioSurface,
    onSurface = StudioTextPrimary,
    surfaceVariant = StudioCard,
    onSurfaceVariant = StudioTextMuted
)

@Composable
fun AIMusicStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StudioDarkColorScheme,
        content = content
    )
}
