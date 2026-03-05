package com.yamaha.sxstyleplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.yamaha.sxstyleplayer.ui.*

private val DarkColorScheme = darkColorScheme(
    primary = AccentYellow,
    onPrimary = Color.Black,
    secondary = AccentBlue,
    onSecondary = Color.White,
    tertiary = AccentGreen,
    background = DarkBackground,
    surface = SurfaceCard,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = AccentRed
)

@Composable
fun SXStylePlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
