package io.readx.app

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.readx.app.data.Book
import io.readx.app.pdf.PdfActivity
import io.readx.app.pdf.ReadXPdfFragment
import io.readx.app.reader.*
import io.readx.app.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in local acceptance inputs. Neither books nor their contents are bundled, logged or committed. */
@RunWith(AndroidJUnit4::class)
class LocalBooksAcceptanceInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun localEpubOpensCountsReflowsAndReopens() = withLocalBook("epub") { app, book, preferences ->
        val chapters = runBlocking { app.repository.dao.chapters(book.id) }
        assertTrue(chapters.isNotEmpty())
        val sample = chapters.size / 2
        runBlocking { app.repository.dao.savePosition(book.id, sample, 0f, System.currentTimeMillis()) }
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it)[LibraryViewModel::class.java] }
            val coldStart = SystemClock.elapsedRealtime()
            scenario.onActivity { model.open(book) }
            waitReady(scenario)
            val coldMs = SystemClock.elapsedRealtime() - coldStart
            val original = waitIndex(app, scenario, book, chapters.map { it.href }, preferences.settings.value)
            val totalMs = SystemClock.elapsedRealtime() - coldStart
            for (ordinal in listOf(sample, 0, chapters.lastIndex).distinct()) {
                scenario.onActivity { model.chapter(ordinal) }
                waitReady(scenario) { it.url.orEmpty().endsWith(chapters[ordinal].href.split('/').joinToString("/") { path -> Uri.encode(path) }) }
                scenario.onActivity { assertEquals(original.counts[ordinal], reader(it.window.decorView)!!.pageInfo().second) }
            }
            scenario.onActivity { model.chapter(sample) }
            waitReady(scenario) { it.url.orEmpty().endsWith(chapters[sample].href.split('/').joinToString("/") { path -> Uri.encode(path) }) }
            var anchor: TextAnchor? = null
            var generation = 0L
            scenario.onActivity { activity ->
                val view = reader(activity.window.decorView)!!
                view.jumpToPage(minOf(4, view.pageInfo().second))
                view.captureViewportAnchor { anchor = it }
                generation = view.loadGeneration
            }
            compose.waitUntil(5000) { anchor != null }
            scenario.onActivity { preferences.update(preferences.settings.value.copy(fontSize = 24f)) }
            waitReady(scenario) { it.loadGeneration > generation }
            var expected = 0
            scenario.onActivity { reader(it.window.decorView)!!.trusted("JSON.stringify(window.ReadX.navigate(${anchor!!.json()},true))") { result ->
                val value = JSONObject(JSONTokener(result).nextValue() as String)
                assertTrue(value.getBoolean("found")); expected = value.getInt("page")
            } }
            compose.waitUntil(5000) { expected > 0 }
            scenario.onActivity { assertEquals(expected, reader(it.window.decorView)!!.pageInfo().first) }
            val changed = waitIndex(app, scenario, book, chapters.map { it.href }, preferences.settings.value)
            scenario.onActivity { assertEquals(changed.counts[sample], reader(it.window.decorView)!!.pageInfo().second); model.close() }
            compose.waitUntil(5000) { var closed = false; scenario.onActivity { closed = reader(it.window.decorView) == null }; closed }
            val warmStart = SystemClock.elapsedRealtime()
            scenario.onActivity { model.open(book) }
            waitReady(scenario)
            val warmMs = SystemClock.elapsedRealtime() - warmStart
            waitIndex(app, scenario, book, chapters.map { it.href }, preferences.settings.value)
            android.util.Log.i("ReadXLocalAcceptance", "EPUB chapters=${chapters.size} cold_ready_ms=$coldMs exact_total_ms=$totalMs warm_ready_ms=$warmMs pages_before=${original.total} pages_after=${changed.total}")
        }
    }

    @Test fun localPdfRendersTurnsRestoresAndOpensAdvancedSearch() = withLocalBook("pdf") { app, book, preferences ->
        preferences.update(preferences.settings.value.copy(pdfLayout = PdfReadingLayout.HORIZONTAL))
        val start = SystemClock.elapsedRealtime()
        ActivityScenario.launch<PdfActivity>(Intent(app, PdfActivity::class.java).putExtra("bookId", book.id)).use { scenario ->
            compose.waitUntil(30000) { compose.onAllNodesWithTag("pdf-page-0").fetchSemanticsNodes().isNotEmpty() }
            val readyMs = SystemClock.elapsedRealtime() - start
            val count = runBlocking { app.repository.dao.book(book.id) }!!.totalUnits
            assertTrue(count > 1)
            compose.onNodeWithTag("pdf-page-0").performTouchInput { swipeLeft() }
            compose.waitUntil(15000) { compose.onAllNodesWithTag("pdf-page-1").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5000) { runBlocking { app.repository.dao.book(book.id) }?.chapterIndex == 1 }
            scenario.recreate()
            compose.waitUntil(30000) { compose.onAllNodesWithTag("pdf-page-1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pdf-page-1").performTouchInput { click(center) }
            compose.onNodeWithContentDescription("切换 PDF 横向或纵向阅读").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("PDF 搜索").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("PDF 搜索").performClick()
            scenario.onActivity { activity -> assertTrue((activity.supportFragmentManager.findFragmentByTag("pdf") as ReadXPdfFragment).isTextSearchActive) }
            android.util.Log.i("ReadXLocalAcceptance", "PDF pages=$count horizontal_render_ms=$readyMs turn_restore=passed advanced_search_entry=passed (not full-text accuracy or OCR)")
        }
    }

    private fun withLocalBook(format: String, action: (ReadXApplication, Book, ReaderPreferences) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<ReadXApplication>()
        val input = File(app.getExternalFilesDir(null), "qa-input/local-acceptance.$format")
        assumeTrue("Optional local input has not been supplied", input.isFile)
        val prior = runBlocking { app.repository.dao.observeBooks().first().map { it.id }.toSet() }
        val book = runBlocking { app.repository.import(Uri.fromFile(input)) }
        assumeTrue("Do not modify an already-present library book", book.id !in prior)
        val preferences = ReaderPreferences(app)
        val old = preferences.settings.value
        preferences.update(ReaderSettings())
        try { action(app, book, preferences) }
        finally { preferences.update(old); runBlocking { app.repository.delete(book) }; input.delete() }
    }
    private fun waitIndex(app: ReadXApplication, scenario: ActivityScenario<MainActivity>, book: Book, hrefs: List<String>, settings: ReaderSettings): BookPageIndex {
        lateinit var config: LayoutConfig
        scenario.onActivity { activity ->
            val view = reader(activity.window.decorView)!!
            val r = view.resources
            val web = WebView.getCurrentWebViewPackage()!!
            config = LayoutConfig(book.fingerprint, hrefs, view.width, view.height, r.displayMetrics.density, r.configuration.fontScale,
                settings.fontSize, settings.lineHeight, settings.margin, settings.serif, "${web.packageName}:${web.versionName}", android.os.Build.FINGERPRINT, r.configuration.locales.toLanguageTags())
        }
        var result: BookPageIndex? = null
        val cache = PageIndexCache(app.cacheDir)
        compose.waitUntil(120000) { result = runBlocking { cache.load(book.id, config.generateKey(), hrefs.size) }; result?.complete == true }
        return result!!
    }
    private fun waitReady(scenario: ActivityScenario<MainActivity>, condition: (LocalWebReader) -> Boolean = { true }) {
        compose.waitUntil(30000) { var ready = false; scenario.onActivity { ready = reader(it.window.decorView)?.let { !it.restoring && it.alpha >= .99f && condition(it) } == true }; ready }
    }
    private fun reader(view: View): LocalWebReader? {
        if (view is LocalWebReader && view.isEnabled) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) reader(view.getChildAt(i))?.let { return it }
        return null
    }
}
