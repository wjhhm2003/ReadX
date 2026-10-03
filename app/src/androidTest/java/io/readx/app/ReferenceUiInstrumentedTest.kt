package io.readx.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.readx.app.data.Book
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ReferenceUiInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun bookshelfUsesRealPdfAndEpubCovers() {
        val app = ApplicationProvider.getApplicationContext<ReadXApplication>()
        val prefs = app.getSharedPreferences("reader-settings", 0)
        val old = prefs.getString("theme", null)
        prefs.edit().putString("theme", "DAY").commit()
        val files = File(app.cacheDir, "reference-" + UUID.randomUUID()).apply { mkdirs() }
        val books = mutableListOf<Book>()
        try {
            val titles = listOf("山间读书笔记", "城市与日常", "纸上旅行", "阅读的时间")
            titles.forEachIndexed { index, title ->
                val file = File(files, "$title.pdf")
                val document = PdfDocument()
                try {
                    for (pageNo in 0..9) {
                        val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, pageNo + 1).create())
                        if (pageNo == 0) drawCover(page.canvas, title, index)
                        else page.canvas.drawText("ReadX layout fixture / page " + (pageNo + 1), 40f, 60f, Paint().apply { textSize = 16f })
                        document.finishPage(page)
                    }
                    file.outputStream().use { document.writeTo(it) }
                } finally { document.close() }
                val book = runBlocking { app.repository.import(Uri.fromFile(file)) }
                books += book
                runBlocking { app.repository.dao.edit(book.id, title, "界面测试样书", "测试"); app.repository.dao.savePosition(book.id, index + 1, 0f, System.currentTimeMillis() - index * 1000) }
            }
            val title = "自然观察手册"
            val cover = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            drawCover(Canvas(cover), title, 4)
            val bytes = ByteArrayOutputStream().use { out -> cover.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
            cover.recycle()
            val epub = File(files, "$title.epub")
            val entries = mapOf(
                "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
                "OPS/book.opf" to "<package xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>$title</dc:title><dc:creator>界面测试样书</dc:creator></metadata><manifest><item id='c1' href='chapter.xhtml'/><item id='cover' href='cover.png' properties='cover-image'/></manifest><spine><itemref idref='c1'/></spine></package>",
                "OPS/chapter.xhtml" to "<html><body><h1>观察与记录</h1>" + (1..40).joinToString("") { "<p>第 $it 段。这是界面与排版测试样书，并非真实出版内容。</p>" } + "</body></html>",
            )
            ZipOutputStream(epub.outputStream()).use { zip ->
                entries.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
                zip.putNextEntry(ZipEntry("OPS/cover.png")); zip.write(bytes); zip.closeEntry()
            }
            val epubBook = runBlocking { app.repository.import(Uri.fromFile(epub)) }
            books += epubBook
            runBlocking {
                val bitmap = app.repository.cover(epubBook)
                assertNotNull("EPUB must load its real embedded cover", bitmap)
                bitmap?.recycle()
                app.repository.dao.savePosition(epubBook.id, 0, .35f, System.currentTimeMillis() - 6000)
            }
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
                compose.waitUntil(15000) { compose.onAllNodesWithText("继续阅读").fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("山间读书笔记 封面").fetchSemanticsNodes().isNotEmpty() }
                compose.waitForIdle(); Thread.sleep(500)
                TestScreenshots.capture("bookshelf-reference")
                compose.onAllNodesWithText("书库", useUnmergedTree = true).onLast().performClick()
                compose.onNodeWithText("全部书籍").assertIsDisplayed()
                compose.onNodeWithText("EPUB").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(title).assertIsDisplayed()
            }
        } finally {
            runBlocking { books.forEach { app.repository.delete(it) } }
            files.deleteRecursively()
            prefs.edit().apply { if (old == null) remove("theme") else putString("theme", old) }.commit()
        }
    }
    private fun drawCover(canvas: Canvas, title: String, index: Int) {
        val backgrounds = intArrayOf(0xFFDFE6D9.toInt(), 0xFFEDDCC8.toInt(), 0xFFD8E2ED.toInt(), 0xFFF1E6CF.toInt(), 0xFFE3E4D5.toInt())
        canvas.drawColor(backgrounds[index % backgrounds.size])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF435C52.toInt() }
        canvas.drawCircle(200f, 220f, 120f, paint)
        paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
        for (i in 0..4) canvas.drawLine(110f, 170f + i * 24f, 290f, 170f + i * 24f, paint)
        paint.style = Paint.Style.FILL; paint.color = 0xFF20342B.toInt(); paint.textSize = 40f; paint.isFakeBoldText = true
        canvas.drawText(title, 38f, 430f, paint)
        paint.textSize = 20f; paint.isFakeBoldText = false
        canvas.drawText("READX / LOCAL READING", 38f, 485f, paint)
        paint.textSize = 18f; canvas.drawText("界面测试样书", 38f, 535f, paint)
    }
}
