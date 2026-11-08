package com.hallide.weartubeplus.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

private val WearTubePlusColors = Colors(
    primary = Color(0xFFFF4E45),
    primaryVariant = Color(0xFFC4302B),
    secondary = Color(0xFFB0B0B8),
    background = Color.Black,
    surface = Color(0xFF1C1C1F),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun WearTubePlusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = WearTubePlusColors, content = content)
}
