package io.readx.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance(); val y = b.luminance()
        return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
    }
    private fun readable(label: String, foreground: Color, background: Color) {
        assertTrue("$label contrast=${contrast(foreground, background)}", contrast(foreground, background) >= 4.5f)
    }
    @Test fun accentAndCustomPalettesHaveReadableSemanticPairs() {
        for (dark in listOf(false, true)) {
            for (accent in ThemeAccent.entries) {
                for (custom in listOf("", "#FFFFFF", "#000000", "#FFFF00", "#00FF00", "#0000FF", "#7756AE")) {
                    val c = accentScheme(accent, custom, dark)
                    val label = "$accent / $custom / dark=$dark"
                    readable("$label primary", c.onPrimary, c.primary)
                    readable("$label secondary", c.onSecondary, c.secondary)
                    readable("$label tertiary", c.onTertiary, c.tertiary)
                    readable("$label primaryContainer", c.onPrimaryContainer, c.primaryContainer)
                    readable("$label secondaryContainer", c.onSecondaryContainer, c.secondaryContainer)
                    readable("$label tertiaryContainer", c.onTertiaryContainer, c.tertiaryContainer)
                    readable("$label surface", c.onSurface, c.surface)
                    readable("$label supporting", c.onSurfaceVariant, c.surfaceContainerLow)
                    readable("$label snackbar", c.inverseOnSurface, c.inverseSurface)
                }
            }
        }
    }
    @Test fun readingPaperRemainsReadableWithoutChangingAccentRoles() {
        for (theme in ReadingTheme.entries) {
            for (systemDark in listOf(false, true)) {
                val dark = theme == ReadingTheme.NIGHT || theme == ReadingTheme.BLACK || (theme == ReadingTheme.SYSTEM && systemDark)
                val c = readingScheme(accentScheme(ThemeAccent.BLUE, "", dark), theme, dark)
                readable("$theme ink", c.onSurface, c.surface)
                readable("$theme panel", c.onSurface, c.surfaceContainerHigh)
                readable("$theme supporting", c.onSurfaceVariant, c.surfaceContainerHigh)
            }
        }
    }
}
