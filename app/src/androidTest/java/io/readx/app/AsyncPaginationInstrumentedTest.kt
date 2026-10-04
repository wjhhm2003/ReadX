package io.readx.app

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.readx.app.data.Book
import io.readx.app.reader.*
import io.readx.app.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class AsyncPaginationInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun actualHtmlCountsCacheReopenAndReflowAnchorAgree() {
        val app = ApplicationProvider.getApplicationContext<ReadXApplication>()
        val preferences = ReaderPreferences(app)
        val old = preferences.settings.value
        preferences.update(ReaderSettings())
        val title = "异步分页精确验收 ${System.nanoTime()}"
        val file = File(app.cacheDir, "$title.epub")
        val image = "<svg xmlns='http://www.w3.org/2000/svg' width='320' height='180'><rect width='320' height='180' fill='#80b080'/><text x='10' y='90'>Generated layout image</text></svg>"
        val entries = linkedMapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
            "OPS/book.opf" to "<package xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>$title</dc:title></metadata><manifest>" + (0..3).joinToString("") { "<item id='c$it' href='c$it.xhtml'/>" } + "<item id='style' href='book.css'/><item id='image' href='image.svg'/></manifest><spine>" + (0..3).joinToString("") { "<itemref idref='c$it'/>" } + "</spine></package>",
            "OPS/book.css" to "h2 { margin: 3em 0 1em; } .quote { padding: 12px; border-left: 2px solid #888; }",
            "OPS/image.svg" to image,
        )
        (0..3).forEach { chapter ->
            val paragraphs = (1..(80 - chapter * 18)).joinToString("") { paragraph ->
                "<p>章节${chapter}第${paragraph}段，唯一文字定位编号 ${chapter}_${paragraph}。这是自生成 EPUB 排版样书，不含私人内容。中文、English words、12345 与 emoji 📖 混排，改变字号后仍应显示原文字附近的页面。</p>" + if (paragraph % 13 == 0) "<h2>分节标题 $paragraph</h2><p class='quote'>带样式引用。</p><img src='image.svg'/>" else ""
            }
            entries["OPS/c$chapter.xhtml"] = "<html><head><link rel='stylesheet' href='book.css'/></head><body><h1>第${chapter + 1}章 精确验证</h1>$paragraphs<script>document.body.textContent='BAD SCRIPT'</script></body></html>"
        }
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
        val book = runBlocking { app.repository.import(Uri.fromFile(file)) }
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                lateinit var model: LibraryViewModel
                scenario.onActivity { model = ViewModelProvider(it)[LibraryViewModel::class.java]; model.open(book) }
                waitReady(scenario)
                val original = waitCache(app, scenario, book, preferences.settings.value)
                val measured = mutableListOf<Int>()
                for (chapter in 0..3) {
                    scenario.onActivity { model.chapter(chapter) }
                    waitReady(scenario) { it.url.orEmpty().endsWith("c$chapter.xhtml") }
                    scenario.onActivity { activity ->
                        val views = readers(activity.window.decorView)
                        assertEquals("A complete cache must not leave a hidden WebView", 1, views.size)
                        measured += views.single().pageInfo().second
                    }
                }
                assertEquals(measured, original.counts)
                assertEquals(measured.sum(), original.total)
                scenario.onActivity { model.chapter(0) }
                waitReady(scenario) { it.url.orEmpty().endsWith("c0.xhtml") }
                scenario.onActivity { foreground(it.window.decorView)!!.jumpToPage(6) }
                var anchor: TextAnchor? = null
                scenario.onActivity { foreground(it.window.decorView)!!.captureViewportAnchor { anchor = it } }
                compose.waitUntil(5000) { anchor != null }
                var generation = 0L
                scenario.onActivity { generation = foreground(it.window.decorView)!!.loadGeneration; preferences.update(preferences.settings.value.copy(fontSize = 32f, margin = 36f)) }
                waitReady(scenario) { it.loadGeneration > generation }
                var expectedPage = 0
                scenario.onActivity { foreground(it.window.decorView)!!.trusted("JSON.stringify(window.ReadX.navigate(${anchor!!.json()},true))") { result ->
                    val value = JSONObject(JSONTokener(result).nextValue() as String)
                    assertTrue(value.getBoolean("found")); expectedPage = value.getInt("page")
                } }
                compose.waitUntil(5000) { expectedPage > 0 }
                scenario.onActivity { assertEquals("Reflow must keep the old visible text, not its old page fraction", expectedPage, foreground(it.window.decorView)!!.pageInfo().first) }
                val large = waitCache(app, scenario, book, preferences.settings.value)
                assertTrue(large.total!! > original.total!!)
                scenario.onActivity { generation = foreground(it.window.decorView)!!.loadGeneration; preferences.update(preferences.settings.value.copy(fontSize = 14f)) }
                compose.waitUntil(5000) { var started = false; scenario.onActivity { started = foreground(it.window.decorView)?.loadGeneration?.let { it > generation } == true }; started }
                scenario.onActivity { preferences.update(preferences.settings.value.copy(fontSize = 24f, margin = 24f)) }
                waitReady(scenario) { it.loadGeneration > generation }
                val final = waitCache(app, scenario, book, preferences.settings.value)
                scenario.onActivity { assertEquals(final.counts[0], foreground(it.window.decorView)!!.pageInfo().second) }
                var body = ""
                scenario.onActivity { foreground(it.window.decorView)!!.trusted("document.body.textContent") { body = it } }
                compose.waitUntil(5000) { body.isNotEmpty() }; assertFalse(body.contains("BAD SCRIPT"))
                TestScreenshots.capture("reader-032-async-pages")
                scenario.onActivity { model.close() }
                compose.waitUntil(5000) { var closed = false; scenario.onActivity { closed = readers(it.window.decorView).isEmpty() }; closed }
                scenario.onActivity { model.open(book) }
                waitReady(scenario)
                waitCache(app, scenario, book, preferences.settings.value)
                scenario.onActivity { assertEquals(1, readers(it.window.decorView).size) }
            }
        } finally { runBlocking { app.repository.delete(book) }; file.delete(); preferences.update(old) }
    }

    @Test fun txtGesturesModesSearchAndSavedPositionRemainUsable() {
        val app = ApplicationProvider.getApplicationContext<ReadXApplication>()
        val preferences = ReaderPreferences(app)
        val old = preferences.settings.value
        preferences.update(ReaderSettings())
        val title = "分页切换手势验收 ${System.nanoTime()}"
        val file = File(app.cacheDir, "$title.txt")
        val body = (1..60).joinToString("\n\n") { "第${it}段是自生成的阅读器测试内容。中文长段落与 English text 混排，用于验证左右滑动、三分屏点击、滚动模式切换和原有阅读进度兼容。编号 $it。" }
        file.writeText("第一章 手势测试\n$body\n\n第二章 搜索测试\n$body\n独有检索词杏花春雨 $title")
        val book = runBlocking { app.repository.import(Uri.fromFile(file)) }
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                lateinit var model: LibraryViewModel
                scenario.onActivity { model = ViewModelProvider(it)[LibraryViewModel::class.java]; model.open(book) }
                waitReady(scenario)
                compose.onNodeWithTag("reader-content").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .85f, height * .5f)) }
                waitReady(scenario) { !it.isTurning && it.pageInfo().first == 2 }
                compose.onNodeWithTag("reader-content").performTouchInput { swipeLeft() }
                waitReady(scenario) { !it.isTurning && it.pageInfo().first == 3 }
                var anchor: TextAnchor? = null
                scenario.onActivity { foreground(it.window.decorView)!!.captureViewportAnchor { anchor = it } }
                compose.waitUntil(5000) { anchor != null }
                scenario.onActivity { preferences.update(preferences.settings.value.copy(layout = ReadingLayout.SCROLL)) }
                waitReady(scenario) { !it.paged }
                scenario.onActivity { assertTrue(foreground(it.window.decorView)!!.scrollY > 0); assertEquals(1, readers(it.window.decorView).size) }
                var scrollAnchor: TextAnchor? = null
                scenario.onActivity { foreground(it.window.decorView)!!.captureViewportAnchor { scrollAnchor = it } }
                compose.waitUntil(5000) { scrollAnchor != null }
                assertEquals("Switching to scroll mode must keep the exact text anchor", anchor!!.start, scrollAnchor!!.start)
                scenario.onActivity { preferences.update(preferences.settings.value.copy(layout = ReadingLayout.PAGED)) }
                waitReady(scenario) { it.paged }
                var expectedPage = 0
                scenario.onActivity { foreground(it.window.decorView)!!.trusted("JSON.stringify(window.ReadX.navigate(${anchor!!.json()},true))") { result -> expectedPage = JSONObject(JSONTokener(result).nextValue() as String).getInt("page") } }
                compose.waitUntil(5000) { expectedPage > 0 }
                scenario.onActivity { assertEquals(expectedPage, foreground(it.window.decorView)!!.pageInfo().first); foreground(it.window.decorView)!!.jumpToPage(foreground(it.window.decorView)!!.pageInfo().second) }
                compose.onNodeWithTag("reader-content").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .85f, height * .5f)) }
                waitReady(scenario) { it.url.orEmpty().contains("chapter-1") }
                compose.onNodeWithTag("reader-content").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .15f, height * .5f)) }
                waitReady(scenario) { it.url.orEmpty().contains("chapter-0") && it.pageInfo().first == it.pageInfo().second }
                scenario.onActivity { model.search("独有检索词杏花春雨", book.id) }
                compose.waitUntil(10000) { !model.searching.value && model.hits.value.size == 1 }
                scenario.onActivity { model.openHit(model.hits.value.single()) }
                waitReady(scenario) { it.url.orEmpty().contains("chapter-1") }
                var searchFound = false
                compose.waitUntil(10000) { scenario.onActivity { searchFound = foreground(it.window.decorView)!!.pageInfo().first > 1 }; searchFound }
                var savedPage = 0
                var savedFraction = 0f
                var jumpCommitted = false
                scenario.onActivity {
                    val view = foreground(it.window.decorView)!!
                    view.jumpToPage(3)
                    view.postVisualStateCallback(System.nanoTime(), object : android.webkit.WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) { jumpCommitted = true }
                    })
                }
                compose.waitUntil(5000) { jumpCommitted }
                waitReady(scenario) { it.pageInfo().first == 3 }
                compose.waitForIdle()
                scenario.onActivity { val view = foreground(it.window.decorView)!!; savedPage = view.pageInfo().first; savedFraction = view.fraction(); model.close() }
                // Let Compose dispose the old reader and finish its final-position callback before reopening.
                // Checking any positive historical fraction can otherwise succeed on the previous search position.
                compose.waitUntil(5000) { compose.onAllNodesWithTag("reader-content").fetchSemanticsNodes().isEmpty() }
                try {
                    compose.waitUntil(5000) { runBlocking { app.repository.dao.book(book.id) }?.let { it.chapterIndex == 1 && kotlin.math.abs(it.scrollFraction - savedFraction) < .001f } == true }
                } catch (error: Throwable) {
                    val stored = runBlocking { app.repository.dao.book(book.id) }
                    android.util.Log.e("ReadXUiTest", "close/reopen target page=$savedPage fraction=$savedFraction, stored chapter=${stored?.chapterIndex} fraction=${stored?.scrollFraction}")
                    throw error
                }
                scenario.onActivity { model.open(book) }
                waitReady(scenario) { it.url.orEmpty().contains("chapter-1") && it.pageInfo().first == savedPage }
            }
        } finally { runBlocking { app.repository.delete(book) }; file.delete(); preferences.update(old) }
    }

    private fun waitCache(app: ReadXApplication, scenario: ActivityScenario<MainActivity>, book: Book, settings: ReaderSettings): BookPageIndex {
        lateinit var config: LayoutConfig
        scenario.onActivity { activity ->
            val view = foreground(activity.window.decorView)!!
            val resources = view.resources
            val web = WebView.getCurrentWebViewPackage()!!
            config = LayoutConfig(book.fingerprint, runBlocking { app.repository.dao.chapters(book.id) }.map { it.href }, view.width, view.height,
                resources.displayMetrics.density, resources.configuration.fontScale, settings.fontSize, settings.lineHeight, settings.margin, settings.serif,
                "${web.packageName}:${web.versionName}", android.os.Build.FINGERPRINT, resources.configuration.locales.toLanguageTags())
        }
        val cache = PageIndexCache(app.cacheDir)
        var result: BookPageIndex? = null
        compose.waitUntil(30000) { result = runBlocking { cache.load(book.id, config.generateKey(), 4) }; result?.complete == true }
        // Cache persistence and Compose releasing the disposable counter may complete on adjacent frames.
        compose.waitUntil(5000) { var released = false; scenario.onActivity { released = readers(it.window.decorView).size == 1 }; released }
        return result!!
    }
    private fun waitReady(scenario: ActivityScenario<MainActivity>, condition: (LocalWebReader) -> Boolean = { true }) {
        compose.waitUntil(15000) { var ready = false; scenario.onActivity { ready = foreground(it.window.decorView)?.let { !it.restoring && it.alpha >= .99f && condition(it) } == true }; ready }
    }
    private fun foreground(view: View) = readers(view).firstOrNull { it.isEnabled }
    private fun readers(view: View): List<LocalWebReader> = when (view) {
        is LocalWebReader -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { readers(view.getChildAt(it)) }
        else -> emptyList()
    }
}
