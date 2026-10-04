package io.readx.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.readx.app.ui.ReaderPreferences
import io.readx.app.ui.ReaderSettings
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GestureInsetsInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun keyboardDoesNotCoverCompactNavigationOrLeaveDuplicateBottomSpace() {
        val app = ApplicationProvider.getApplicationContext<ReadXApplication>()
        val preferences = ReaderPreferences(app); val old = preferences.settings.value
        val input = File(app.cacheDir, "insets-${UUID.randomUUID()}.txt")
        input.writeText("第一章 安全区验收\n这本书仅用于系统手势条和输入法验收。")
        val book = runBlocking { app.repository.import(Uri.fromFile(input)) }
        preferences.update(ReaderSettings())
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                compose.onNode(hasText("书库") and hasClickAction()).performClick()
                compose.waitUntil(10000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
                val field = compose.onNode(hasSetTextAction())
                field.performTextInput("手势安全区验收专用关键词")
                field.performClick()
                fun imeVisible(): Boolean {
                    var visible = false
                    scenario.onActivity { visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                    return visible
                }
                compose.waitUntil(5000) { imeVisible() }
                compose.onNodeWithTag("library-navigation").assertIsDisplayed()
                compose.onNode(hasText("设置") and hasClickAction()).assertIsDisplayed()
                compose.waitForIdle(); TestScreenshots.capture("md3-keyboard")
                val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
                compose.waitUntil(5000) { !imeVisible() }
                compose.onNodeWithTag("library-navigation").assertIsDisplayed()
            }
        } finally {
            runBlocking { app.repository.delete(book) }
            input.delete(); preferences.update(old)
        }
    }
}
