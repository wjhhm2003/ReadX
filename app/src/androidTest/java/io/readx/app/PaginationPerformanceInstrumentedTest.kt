package io.readx.app

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.readx.app.reader.LocalWebReader
import io.readx.app.ui.LibraryViewModel
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

/** End-to-end timings, not a guarantee about arbitrary books or device cold starts. */
@RunWith(AndroidJUnit4::class)
class PaginationPerformanceInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun generatedTxtAndEpubOpenTimings() {
        val app = ApplicationProvider.getApplicationContext<ReadXApplication>()
        val preferences = ReaderPreferences(app)
        val old = preferences.settings.value
        preferences.update(ReaderSettings())
        val metrics = mutableListOf<String>()
        try {
            for (format in listOf("txt", "epub")) {
                val title = "分页性能样书 ${System.nanoTime()}"
                val file = File(app.cacheDir, "$title.$format")
                val paragraphs = (1..55).map { "第${it}段。这里是自生成的分页性能测试正文，用于测量打开阅读页与全书逐章分页的耗时，不含用户的私人书籍、笔记或个人信息。中文内容与 English words 12345 混排，段落在实际视口内连续分栏。" }
                if (format == "txt") file.writeText((1..6).joinToString("\n\n") { "第${it}章 性能测试\n" + paragraphs.joinToString("\n\n") } + "\n$title")
                else ZipOutputStream(file.outputStream()).use { zip ->
                    val entries = linkedMapOf(
                        "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
                        "OPS/book.opf" to "<package xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>$title</dc:title></metadata><manifest>" + (1..6).joinToString("") { "<item id='c$it' href='c$it.xhtml'/>" } + "</manifest><spine>" + (1..6).joinToString("") { "<itemref idref='c$it'/>" } + "</spine></package>"
                    )
                    (1..6).forEach { chapter -> entries["OPS/c$chapter.xhtml"] = "<html><body><h1>第${chapter}章 性能测试</h1>" + paragraphs.joinToString("") { "<p>$it</p>" } + "</body></html>" }
                    entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
                }
                val book = runBlocking { app.repository.import(Uri.fromFile(file)) }
                try {
                    ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                        lateinit var model: LibraryViewModel
                        scenario.onActivity { model = ViewModelProvider(it)[LibraryViewModel::class.java] }
                        for (attempt in 0..1) {
                            val start = SystemClock.elapsedRealtime()
                            scenario.onActivity { model.open(book) }
                            var readyMs = 0L
                            var count = 0
                            compose.waitUntil(15000) {
                                scenario.onActivity { activity ->
                                    reader(activity.window.decorView)?.let { view ->
                                        if (!view.restoring && view.alpha >= .99f && readyMs == 0L) { readyMs = SystemClock.elapsedRealtime() - start; count = view.pageInfo().second }
                                    }
                                }
                                readyMs > 0
                            }
                            assertTrue(count > 1)
                            compose.waitUntil(30000) { compose.onAllNodes(SemanticsMatcher("exact page total") { node -> node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text.matches(Regex("[0-9]+ / [0-9]+")) } == true }).fetchSemanticsNodes().isNotEmpty() }
                            val totalMs = SystemClock.elapsedRealtime() - start
                            metrics += "$format attempt=$attempt ready_ms=$readyMs total_ms=$totalMs chapter_pages=$count"
                            scenario.onActivity { model.close() }
                            compose.waitUntil(5000) { var closed = false; scenario.onActivity { closed = reader(it.window.decorView) == null }; closed }
                        }
                    }
                } finally { runBlocking { app.repository.delete(book) }; file.delete() }
            }
        } finally { preferences.update(old) }
        val output = File(app.getExternalFilesDir(null), "qa/pagination-timings.txt").apply { parentFile!!.mkdirs() }
        output.writeText(metrics.joinToString("\n"))
        metrics.forEach { android.util.Log.i("ReadXPerformance", it) }
    }

    private fun reader(view: View): LocalWebReader? {
        if (view is LocalWebReader && view.isEnabled) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) reader(view.getChildAt(i))?.let { return it }
        return null
    }
}


