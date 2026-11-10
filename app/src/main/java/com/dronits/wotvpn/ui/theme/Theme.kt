package com.dronits.wotvpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WotColors = darkColorScheme(
    primary = Color(0xFFB8E66C),
    onPrimary = Color(0xFF243500),
    primaryContainer = Color(0xFF334B17),
    onPrimaryContainer = Color(0xFFD5F7AE),
    secondary = Color(0xFF8DD5B8),
    onSecondary = Color(0xFF003828),
    background = Color(0xFF101612),
    onBackground = Color(0xFFE1E9DF),
    surface = Color(0xFF19211C),
    onSurface = Color(0xFFE1E9DF),
    surfaceVariant = Color(0xFF29362D),
    onSurfaceVariant = Color(0xFFBBC9BD),
    outline = Color(0xFF718175),
    outlineVariant = Color(0xFF3C4B40),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF452523),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
internal fun WotVpnTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WotColors, content = content)
}
