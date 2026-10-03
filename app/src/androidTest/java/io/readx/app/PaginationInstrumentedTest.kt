package io.readx.app

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.readx.app.reader.LocalWebReader
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class PaginationInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun txtHasRealPagesSwipeScrollAndResume() = exercise("txt")
    @Test fun epubHasRealPagesSwipeScrollAndResume() = exercise("epub")

    private fun exercise(format: String) {
        val app = ApplicationProvider.getApplicationContext<ReadXApplication>()
        val prefs = app.getSharedPreferences("reader-settings", 0)
        val oldLayout = prefs.getString("layout", null)
        prefs.edit().putString("layout", "PAGED").commit()
        val title = "分页验收 " + format.uppercase()
        val text = (1..100).joinToString("\n\n") { "第 ${it} 段。真正的分页应该按屏幕排版，而不是一章只显示一页。改变字号会重新排版，滑动和按钮都可以逐页阅读。保持正文顺序，最后一段也不能丢失。" }
        val file = File(app.cacheDir, "$title.$format")
        if (format == "txt") file.writeText("第一章 分页测试\n$text\n\n第二章 章末测试\n下一章的正文。") else {
            val entries = mapOf(
                "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
                "OPS/book.opf" to "<package xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>$title</dc:title><dc:creator>测试作者</dc:creator></metadata><manifest><item id='c1' href='chapter.xhtml'/><item id='c2' href='next.xhtml'/></manifest><spine><itemref idref='c1'/><itemref idref='c2'/></spine></package>",
                "OPS/chapter.xhtml" to "<html><body><h1>第一章 分页测试</h1>" + text.split("\n\n").joinToString("") { "<p>$it</p>" } + "</body></html>",
                "OPS/next.xhtml" to "<html><body><h1>第二章 章末测试</h1><p>下一章的正文。</p></body></html>",
            )
            ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() } }
        }
        val book = runBlocking { app.repository.import(Uri.fromFile(file)) }
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                compose.waitUntil(15000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodesWithText(title).onLast().performClick()
                var total = 0
                compose.waitUntil(20000) { scenario.onActivity { total = findReader(it.window.decorView)?.takeUnless { it.restoring }?.pageInfo()?.second ?: 0 }; total > 3 }
                scenario.onActivity { assertEquals(1, findReader(it.window.decorView)!!.pageInfo().first); assertTrue("Pagination must use full-height pages, not one line per page", total < 100) }
                compose.onNodeWithText("下一页").performClick()
                compose.onNodeWithText("第 2 / $total 页").assertIsDisplayed()
                scenario.onActivity { val reader = findReader(it.window.decorView)!!; assertTrue(reader.scrollX > 0); assertEquals(0, reader.scrollY) }
                scenario.onActivity { activity ->
                    val reader = findReader(activity.window.decorView)!!
                    val now = android.os.SystemClock.uptimeMillis()
                    listOf(0 to (.8f * reader.width), 80 to (.4f * reader.width), 150 to (.15f * reader.width)).forEachIndexed { index, (time, x) ->
                        val action = if (index == 0) 0 else if (index == 2) 1 else 2
                        val event = android.view.MotionEvent.obtain(now, now + time, action, x, reader.height * .5f, 0)
                        reader.dispatchTouchEvent(event); event.recycle()
                    }
                }
                compose.onNodeWithText("第 3 / $total 页").assertIsDisplayed()
                Thread.sleep(250)
                scenario.onActivity { val r = findReader(it.window.decorView)!!; assertEquals((r.pageInfo().first - 1) * r.width, r.scrollX) }
                TestScreenshots.capture("$format-paged")

                compose.onNodeWithContentDescription("返回书架").performClick()
                runBlocking { withTimeout(5000) { while ((app.repository.dao.book(book.id)?.scrollFraction ?: 0f) == 0f) delay(30) } }
                compose.onAllNodesWithText(title).onLast().performClick()
                compose.waitUntil(15000) { var page = 0; scenario.onActivity { page = findReader(it.window.decorView)?.takeUnless { it.restoring }?.pageInfo()?.first ?: 0 }; page == 3 }
                compose.onNodeWithContentDescription("排版").performClick()
                compose.onNodeWithText("滚动", useUnmergedTree = true).performClick()
                compose.onNodeWithText("完成").performClick()
                compose.waitUntil(15000) { var scroll = false; scenario.onActivity { scroll = findReader(it.window.decorView)?.let { !it.paged && !it.restoring } ?: false }; scroll }
                scenario.onActivity { val reader = findReader(it.window.decorView)!!; reader.scrollBy(0, reader.height / 2); assertTrue(reader.scrollY > 0) }
                TestScreenshots.capture("$format-scroll")
                compose.onNodeWithContentDescription("排版").performClick()
                compose.onNodeWithText("分页", useUnmergedTree = true).performClick()
                compose.onNodeWithText("完成").performClick()
                compose.waitUntil(15000) { var ready = false; scenario.onActivity { ready = findReader(it.window.decorView)?.let { it.paged && !it.restoring && it.pageInfo().second > 3 } ?: false }; ready }
                scenario.onActivity { findReader(it.window.decorView)!!.restore(1f) }
                compose.onNodeWithText("下一页").performClick()
                compose.onNodeWithText("第二章 章末测试").assertIsDisplayed()
                compose.waitUntil(15000) { var ready = false; scenario.onActivity { ready = findReader(it.window.decorView)?.let { !it.restoring } ?: false }; ready }
                compose.onNodeWithText("上一页").performClick()
                compose.onNodeWithText("第一章 分页测试").assertIsDisplayed()
                compose.waitUntil(15000) { var end = false; scenario.onActivity { end = findReader(it.window.decorView)?.let { !it.restoring && it.pageInfo().first == it.pageInfo().second && it.pageInfo().second > 3 } ?: false }; end }
                compose.onNodeWithContentDescription("添加书签").performClick()
                compose.onNodeWithContentDescription("返回书架").performClick()
                compose.onNodeWithText("书签", useUnmergedTree = true).performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithText("第一章 分页测试").fetchSemanticsNodes().isNotEmpty() }
                compose.waitForIdle()
                TestScreenshots.capture("$format-bookmark-tab")
            }
        } finally {
            runBlocking { app.repository.delete(book) }; file.delete()
            prefs.edit().apply { if (oldLayout == null) remove("layout") else putString("layout", oldLayout) }.commit()
        }
    }
    private fun findReader(view: View): LocalWebReader? {
        if (view is LocalWebReader) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findReader(view.getChildAt(i))?.let { return it }
        return null
    }
}

internal object TestScreenshots {
    fun capture(name: String) {
        Thread.sleep(500) // Let Chromium commit the native scroll to a rendered frame.
        val app = ApplicationProvider.getApplicationContext<ReadXApplication>()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(app.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
