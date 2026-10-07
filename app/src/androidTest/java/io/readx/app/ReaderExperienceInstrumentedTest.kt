package io.readx.app

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.readx.app.reader.LocalWebReader
import io.readx.app.ui.ReaderPreferences
import io.readx.app.ui.ReaderSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One generated book covers same/cross-chapter notes, slider seeking, reset and black system bars. */
@RunWith(AndroidJUnit4::class)
class ReaderExperienceInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun notesStayAtTargetUntilReturnAndSeekingDoesNotReload() {
        val app = ApplicationProvider.getApplicationContext<ReadXApplication>()
        val preferences = ReaderPreferences(app)
        val oldSettings = preferences.settings.value
        preferences.update(ReaderSettings())
        val title = "阅读体验验收 ${System.nanoTime()}"
        val file = File(app.cacheDir, "$title.epub")
        val paragraphs = (1..80).joinToString("") { "<p>第${it}段。用于验证翻页与脚注导航。注释跳转后不应被旧页面恢复覆盖；返回之后应恢复阅读位置。正常阅读正文必须保留完整内容。</p>" }
        val entries = mapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
            "OPS/book.opf" to "<package xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>$title</dc:title></metadata><manifest><item id='c' href='chapter.xhtml'/><item id='n' href='notes.xhtml'/></manifest><spine><itemref idref='c'/><itemref idref='n'/></spine></package>",
            "OPS/chapter.xhtml" to "<html><body><p><a href='#note'>章内注释</a></p><p><a href='notes.xhtml#cross'>跨章注释</a></p>$paragraphs<p id='note'>章内注释目标：停留在这里，等待主动返回。</p></body></html>",
            "OPS/notes.xhtml" to "<html><body>$paragraphs<p id='cross'>跨章注释目标：停留在这里，等待主动返回。</p></body></html>",
        )
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
        } }
        val book = runBlocking { app.repository.import(Uri.fromFile(file)) }
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                compose.waitUntil(15000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodesWithText(title).onLast().performClick()
                waitReady(scenario)
                var source: LocalWebReader? = null
                scenario.onActivity { source = findReader(it.window.decorView); assertEquals(1, source!!.pageInfo().first) }
                TestScreenshots.capture("epub-note-source")
                tapLink(scenario, 35f)
                waitReady(scenario) { it !== source && it.pageInfo().first > 3 }
                compose.showTextReaderControls()
                compose.onNodeWithContentDescription("回到原处").assertIsDisplayed()
                var targetPage = 0
                scenario.onActivity { targetPage = findReader(it.window.decorView)!!.pageInfo().first }
                // Bounded regression window: older delayed restores used to overwrite the fragment.
                Thread.sleep(900)
                scenario.onActivity { assertEquals(targetPage, findReader(it.window.decorView)!!.pageInfo().first) }
                TestScreenshots.capture("epub-note-target")
                compose.onNodeWithContentDescription("回到原处").performClick()
                waitReady(scenario) { it.pageInfo().first == 1 }
                compose.onAllNodesWithContentDescription("回到原处").assertCountEquals(0)

                scenario.onActivity { source = findReader(it.window.decorView) }
                tapLink(scenario, 93f)
                waitReady(scenario) { it !== source && it.url.orEmpty().contains("notes.xhtml") && it.pageInfo().first > 3 }
                Thread.sleep(900)
                scenario.onActivity { assertTrue(findReader(it.window.decorView)!!.url.orEmpty().contains("notes.xhtml")); assertTrue(findReader(it.window.decorView)!!.pageInfo().first > 3) }
                compose.onNodeWithContentDescription("回到原处").performClick()
                waitReady(scenario) { it.url.orEmpty().contains("chapter.xhtml") && it.pageInfo().first == 1 }

                var total = 0
                var generation = 0L
                scenario.onActivity { source = findReader(it.window.decorView); total = source!!.pageInfo().second; generation = source!!.loadGeneration }
                compose.waitUntil(30000) { compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).performTouchInput { swipe(center, androidx.compose.ui.geometry.Offset(width - 1f, centerY), 500) }
                waitReady(scenario) { it.url.orEmpty().contains("notes.xhtml") && it.pageInfo().first==it.pageInfo().second }
                TestScreenshots.capture("reader-page-progress")

                compose.openFullReaderSettings()
                compose.onNodeWithText("纯黑").performClick()
                compose.onNodeWithText("完成").performClick()
                waitReady(scenario)
                scenario.onActivity { val reader = findReader(it.window.decorView)!!; assertEquals((reader.pageInfo().first - 1) * reader.width, reader.scrollX); assertFalse(WindowCompat.getInsetsController(it.window, it.window.decorView).isAppearanceLightNavigationBars) }
                TestScreenshots.capture("epub-black-reading")
                compose.openFullReaderSettings()
                compose.onNodeWithText("重置").performClick()
                compose.onNodeWithText("重置阅读设置？").assertIsDisplayed()
                compose.onAllNodesWithText("重置").onLast().performClick()
                assertEquals(ReaderSettings(), ReaderPreferences(app).settings.value)
                compose.onNodeWithText("完成").performClick()
                waitReady(scenario)
                compose.onNodeWithContentDescription("返回书架").performClick()
            }
        } finally {
            runBlocking { app.repository.delete(book) }
            file.delete()
            preferences.update(oldSettings)
        }
    }

    private fun tapLink(scenario: ActivityScenario<MainActivity>, cssY: Float) {
        var x = 0f
        var y = 0f
        scenario.onActivity { activity ->
            val reader = findReader(activity.window.decorView)!!
            val location = IntArray(2); reader.getLocationOnScreen(location)
            val density = reader.resources.displayMetrics.density
            x = location[0] + 64f * density; y = location[1] + cssY * density
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0)
            instrumentation.sendPointerSync(event); event.recycle()
        }
    }
    private fun waitReady(scenario: ActivityScenario<MainActivity>, condition: (LocalWebReader) -> Boolean = { true }) {
        try {
            compose.waitUntil(15000) {
                var ready = false
                scenario.onActivity { activity -> ready = findReader(activity.window.decorView)?.let { !it.restoring && it.alpha > .99f && condition(it) } == true }
                ready
            }
        } catch (error: Throwable) {
            scenario.onActivity { activity -> findReader(activity.window.decorView)?.let {
                android.util.Log.e("ReadXTest", "Reader timeout: url=${it.url}, page=${it.pageInfo()}, restoring=${it.restoring}, scroll=${it.scrollX},${it.scrollY}, size=${it.width},${it.height}, generation=${it.loadGeneration}")
            } }
            TestScreenshots.capture("reader-experience-failure")
            throw error
        }
    }
    private fun findReader(view: View): LocalWebReader? {
        if (view is LocalWebReader && view.isEnabled) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findReader(view.getChildAt(i))?.let { return it }
        return null
    }
}
