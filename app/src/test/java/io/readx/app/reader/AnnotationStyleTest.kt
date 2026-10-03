package io.readx.app.reader

import io.readx.app.data.AnnotationIdentity
import io.readx.app.data.MarkColor
import io.readx.app.ui.ThemeAccent
import io.readx.app.ui.accentScheme
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test

class AnnotationStyleTest {
    @Test fun repeatSelectionHasSameKeyButRepeatedWordsAtOtherOffsetsDoNot() {
        assertEquals(AnnotationIdentity.text(0,10,14,"原书文字"),AnnotationIdentity.text(0,10,14,"原书文字"))
        assertNotEquals(AnnotationIdentity.text(0,10,14,"原书文字"),AnnotationIdentity.text(0,30,34,"原书文字"))
        assertNotEquals(AnnotationIdentity.text(0,10,14,"原书文字"),AnnotationIdentity.text(1,10,14,"原书文字"))
    }
    @Test fun onlySupportedOpaqueColorsArePersisted() {
        assertEquals("#75BEFF",MarkColor.normalize("#75beff"));assertEquals("#FFD240",MarkColor.normalize("url(evil)"))
    }
    @Test fun themeChangesPrimaryAndSurfacesAndContainersAreOpaque() {
        val blue=accentScheme(ThemeAccent.BLUE,"",false);val purple=accentScheme(ThemeAccent.PURPLE,"",false)
        assertNotEquals(blue.primary,purple.primary);assertNotEquals(blue.primaryContainer,purple.primaryContainer)
        assertNotEquals(blue.surface,purple.surface);assertEquals(1f,purple.primaryContainer.alpha)
        assertEquals(0xFFB04080.toInt(),accentScheme(ThemeAccent.BLUE,"#B04080",false).primary.toArgb())
    }
}
