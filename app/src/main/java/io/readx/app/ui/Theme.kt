@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package io.readx.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Day = lightColorScheme(
    primary = Color(0xFF246BFC), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE7FF), onPrimaryContainer = Color(0xFF143D85),
    secondaryContainer = Color(0xFFE8EDF8), onSecondaryContainer = Color(0xFF52617B),
    background = Color(0xFFF2F4FA), surface = Color(0xFFF2F4FA),
    surfaceContainer = Color(0xFFE9EEF7), surfaceContainerLow = Color(0xFFFEFCFE),
    surfaceContainerHigh = Color(0xFFE3E9F4), onSurface = Color(0xFF090F16),
    onSurfaceVariant = Color(0xFF6C778B), outlineVariant = Color(0xFFD7DEEB),
)
private val Night = darkColorScheme(
    primary = Color(0xFFAACFB6), primaryContainer = Color(0xFF2B4F3B),
    background = Color(0xFF111A14), surface = Color(0xFF111A14),
    surfaceContainer = Color(0xFF1C281F), surfaceContainerLow = Color(0xFF19241C),
    onSurface = Color(0xFFDDE8DA), onSurfaceVariant = Color(0xFFB1BFAF),
)
private val Warm = Day.copy(background = Color(0xFFF6EBD5), surface = Color(0xFFF6EBD5), surfaceContainer = Color(0xFFEEE0C7), surfaceContainerLow = Color(0xFFF1E5D0), onSurface = Color(0xFF433D31))

@Composable
fun ReadXTheme(theme: ReadingTheme, content: @Composable () -> Unit) {
    val dark = theme == ReadingTheme.NIGHT || (theme == ReadingTheme.SYSTEM && isSystemInDarkTheme())
    MaterialExpressiveTheme(colorScheme = if (dark) Night else if (theme == ReadingTheme.WARM) Warm else Day, content = content)
}
