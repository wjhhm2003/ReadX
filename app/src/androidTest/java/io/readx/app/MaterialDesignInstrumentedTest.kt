package io.readx.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import io.readx.app.reader.LocalWebReader
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.readx.app.data.Annotation
import io.readx.app.data.Book
import io.readx.app.ui.ReaderPreferences
import io.readx.app.ui.ReaderSettings
import io.readx.app.ui.ThemeAccent
import io.readx.app.ui.ReadingTheme
import io.readx.app.ui.accentScheme
import io.readx.app.ui.readingScheme
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Only run on the project's dedicated emulator: shell configuration is restored in finally. */
@RunWith(AndroidJUnit4::class)
class MaterialDesignInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun capture(name: String) { compose.waitForIdle(); TestScreenshots.capture(name) }
    private fun assertGestureColor(expected: Int) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val image = automation.takeScreenshot() ?: error("Unable to capture system gesture region")
        try {
            // Away from the handle itself; sample the background in the bottom safe strip.
            assertEquals("Gesture handle strip must match its owning surface", expected, image.getPixel(8, image.height - 8))
        } finally { image.recycle() }
    }
    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction()).performClick()
    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText().trim() }
    }

    @Test fun destinationsFiltersReadingAndSettingsKeepWorking() {
        val app = ApplicationProvider.getApplicationContext<ReadXApplication>()
        val prefs = ReaderPreferences(app); val old = prefs.settings.value
        val file = File(app.cacheDir, "md3-${UUID.randomUUID()}.txt")
        file.writeText("第一章 设计验收\n" + (1..80).joinToString("\n") { "第 $it 段。此文本仅用于界面验收，不是真实用户书籍。" })
        val book = runBlocking { app.repository.import(Uri.fromFile(file)) }
        val title = "设计语言验收样书"
        val extraBooks = mutableListOf<Book>()
        try {
            runBlocking {
                app.repository.dao.edit(book.id, title, "ReadX 界面测试", "验收")
                app.repository.dao.savePosition(book.id, 0, .35f, System.currentTimeMillis())
                app.repository.dao.insertAnnotation(Annotation(UUID.randomUUID().toString(), book.id, "NOTE", 0, .35f,
                    "第一章 · 文字笔记", "此文本仅用于界面验收", "阅读与记录。", ""))
            }
            // Populate only self-generated fixtures above any existing library rows; never delete existing books.
            (1..4).forEach { number ->
                val extra = File(app.cacheDir, "md3-extra-${UUID.randomUUID()}.txt")
                extra.writeText("第一章 测试\n这是第 $number 本独立界面验收样书。")
                try {
                    runBlocking {
                        val row = app.repository.import(Uri.fromFile(extra))
                        extraBooks += row
                        app.repository.dao.edit(row.id, listOf("纸上漫步", "日常观察", "山间来信", "阅读的时间")[number - 1], "界面验收样书", "验收")
                        app.repository.dao.savePosition(row.id, 0, .1f * number, System.currentTimeMillis() - number * 100)
                        app.repository.dao.insertAnnotation(Annotation(UUID.randomUUID().toString(), row.id, "NOTE", 0, 0f,
                            "第一章 · 验收记录", "这段文字由测试生成，用于检查批注卡片的排版。", "第 $number 条独立测试笔记，不是用户私人内容。", ""))
                        row
                    }
                } finally { extra.delete() }
            }
            runBlocking { app.repository.dao.savePosition(book.id, 0, .35f, System.currentTimeMillis()) }
            prefs.update(ReaderSettings())
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                compose.waitUntil(15000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("library-navigation").assertIsDisplayed()
                compose.onNodeWithContentDescription("导入书籍").assertHasClickAction()
                capture("md3-home")
                assertGestureColor(accentScheme(ThemeAccent.BLUE, "", false).surfaceContainer.toArgb())
                tab("书库")
                compose.onNodeWithText("全部书籍").assertIsDisplayed()
                compose.onNodeWithText("PDF").performClick()
                compose.onAllNodesWithText(title).assertCountEquals(0)
                tab("首页")
                compose.onAllNodesWithText(title).onFirst().assertIsDisplayed()
                tab("书库")
                compose.onNodeWithText("TXT").performClick()
                compose.onNodeWithText(title).assertIsDisplayed()
                capture("md3-library")
                tab("批注")
                compose.onNodeWithTag("library-list").performScrollToNode(hasText("此文本仅用于界面验收"))
                compose.onNodeWithText("此文本仅用于界面验收").assertIsDisplayed()
                compose.onNodeWithTag("library-list").performScrollToIndex(0)
                capture("md3-annotations")
                tab("设置")
                compose.onNodeWithText("应用主题").assertIsDisplayed()
                compose.onNodeWithText("紫色").performClick()
                compose.onNodeWithTag("dynamic-colors-switch").performClick()
                compose.onNodeWithText("蓝色").assertIsNotEnabled()
                compose.onNodeWithTag("dynamic-colors-switch").performClick()
                compose.onNodeWithText("蓝色").performClick()
                compose.waitUntil(5000) { prefs.settings.value.accent == io.readx.app.ui.ThemeAccent.BLUE && !prefs.settings.value.dynamicColors }
                compose.waitForIdle()
                capture("md3-settings")
                compose.onNodeWithTag("library-list").performScrollToNode(hasText("PDF 转为电子书"))
                compose.onNodeWithText("PDF 转为电子书").assertIsDisplayed()
                compose.onNodeWithTag("library-list").performScrollToIndex(0)
                compose.onNodeWithText("阅读设置").performClick()
                compose.onNodeWithText("滚动").performClick()
                compose.onNodeWithText("分页").performClick()
                compose.waitUntil(5000) { prefs.settings.value.layout == io.readx.app.ui.ReadingLayout.PAGED }
                compose.onNodeWithText("分页").assertIsSelected()
                capture("md3-reading-settings")
                compose.onNodeWithText("完成").performClick()
                tab("书库")
                compose.onNodeWithText(title).performClick()
                var reader: LocalWebReader? = null
                compose.waitUntil(15000) {
                    scenario.onActivity { reader = findReader(it.window.decorView) }
                    reader?.let { !it.restoring && !it.isTurning && it.alpha > .99f && it.pageInfo().second > 1 } == true
                }
                capture("md3-reader")
                compose.onNodeWithTag("reader-content").performTouchInput { click(center) }
                compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("目录").assertIsDisplayed()
                capture("md3-reader-controls")
                assertGestureColor(readingScheme(accentScheme(ThemeAccent.BLUE, "", false), ReadingTheme.SYSTEM, false).surfaceContainerHigh.toArgb())
            }
        } finally {
            prefs.update(old)
            runBlocking { app.repository.delete(book); extraBooks.forEach { app.repository.delete(it) } }
            file.delete()
        }
    }

    private fun findReader(view: View): LocalWebReader? {
        if (view is LocalWebReader && view.isEnabled) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findReader(view.getChildAt(i))?.let { return it }
        return null
    }

    @Test fun wideLayoutLargeFontAndDarkSchemeRemainNavigable() {
        val app = ApplicationProvider.getApplicationContext<ReadXApplication>()
        val fontScale = shell("settings get system font_scale")
        val night = shell("cmd uimode night")
        try {
            shell("settings put system font_scale 1.5")
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                compose.onNodeWithTag("library-navigation").assertIsDisplayed()
                tab("设置")
                compose.onNodeWithTag("library-list").performScrollToNode(hasText("PDF 转为电子书"))
                compose.onNodeWithText("PDF 转为电子书").assertIsDisplayed()
                capture("md3-large-font")
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(15000) { compose.onAllNodesWithTag("library-rail").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("library-navigation").assertDoesNotExist()
                tab("书库")
                compose.onNodeWithText("全部书籍").assertIsDisplayed()
                tab("设置")
                compose.onNodeWithTag("library-list").performScrollToIndex(0)
                capture("md3-wide")
                shell("cmd uimode night yes")
                compose.waitUntil(10000) {
                    var isNight = false
                    scenario.onActivity { isNight = (it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES }
                    isNight
                }
                compose.waitForIdle()
                tab("设置")
                compose.onNodeWithTag("library-list").performScrollToIndex(0)
                capture("md3-dark")
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        } finally {
            if (fontScale == "null") shell("settings delete system font_scale") else shell("settings put system font_scale $fontScale")
            shell("cmd uimode night " + when { night.contains("yes") -> "yes"; night.contains("auto") -> "auto"; else -> "no" })
        }
    }
}
