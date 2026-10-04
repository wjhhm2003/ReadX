package io.readx.app

import android.content.Intent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.readx.app.ui.ReaderPreferences
import io.readx.app.ui.ReaderSettings
import io.readx.app.ui.ThemeAccent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeSettingsInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun settingsPagePersistsAccentAndDynamicPaletteToggle() {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>()
        val prefs=ReaderPreferences(app);val old=prefs.settings.value;prefs.update(ReaderSettings())
        try {
            ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
                compose.onNode(hasText("设置") and hasClickAction()).performClick()
                compose.onNodeWithText("应用主题").assertIsDisplayed()
                compose.onNodeWithText("紫色").performClick()
                assertEquals(ThemeAccent.PURPLE,ReaderPreferences(app).settings.value.accent)
                compose.onNodeWithTag("dynamic-colors-switch").performClick()
                assertTrue(ReaderPreferences(app).settings.value.dynamicColors)
                compose.onNodeWithText("蓝色").assertIsNotEnabled()
                TestScreenshots.capture("theme-030-settings")
            }
        } finally {prefs.update(old)}
    }
}
