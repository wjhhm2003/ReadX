package io.readx.app

import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.readx.app.data.Book
import io.readx.app.pdf.PdfActivity
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun advancedPdfLoadsOpensSearchJumpsAndRestores() = withPdf { app, book ->
        ActivityScenario.launch<PdfActivity>(Intent(app, PdfActivity::class.java).putExtra("bookId", book.id)).use { scenario ->
            waitForPage(1)
            TestScreenshots.capture("pdf-portrait")
            compose.onNodeWithContentDescription("跳转页码").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("3")
            compose.onNodeWithText("跳转", useUnmergedTree = true).performClick()
            waitForPage(3)
            runBlocking { withTimeout(5000) {
                while (app.repository.dao.book(book.id)?.chapterIndex != 2) delay(50)
            } }
            scenario.recreate()
            waitForPage(3)
            compose.onNodeWithContentDescription("PDF 搜索").performClick()
            scenario.onActivity { activity ->
                val viewer = activity.supportFragmentManager.findFragmentByTag("pdf") as io.readx.app.pdf.ReadXPdfFragment
                org.junit.Assert.assertTrue(viewer.isTextSearchActive)
            }
        }
        runBlocking { withTimeout(5000) {
            while (app.repository.dao.book(book.id)?.chapterIndex != 2) delay(50)
        } }
    }
    @Test fun pdfViewportStartsBelowCompactToolbar() = withPdf(landscape = true) { app, book ->
        ActivityScenario.launch<PdfActivity>(Intent(app, PdfActivity::class.java).putExtra("bookId", book.id)).use { scenario ->
            waitForPage(1)
            scenario.onActivity { activity ->
                val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val linear = root.getChildAt(0) as android.widget.LinearLayout
                val toolbar = linear.getChildAt(0)
                val viewer = linear.getChildAt(1)
                val density = activity.resources.displayMetrics.density
                org.junit.Assert.assertTrue("Toolbar must be compact", toolbar.height <= 57 * density)
                org.junit.Assert.assertEquals(toolbar.bottom, viewer.top)
                org.junit.Assert.assertTrue("PDF must use most of the window", viewer.height > linear.height * .7f)
            }
            Thread.sleep(500)
            TestScreenshots.capture("pdf-landscape-page")
        }
    }
    @Test fun basicPdfRendersAndTurnsPages() = withPdf { app, book ->
        val intent = Intent(app, PdfActivity::class.java).putExtra("bookId", book.id).putExtra("forceBasicForTest", true)
        ActivityScenario.launch<PdfActivity>(intent).use {
            waitForPage(1)
            compose.waitUntil(15000) {compose.onAllNodesWithTag("pdf-page-0").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("pdf-page-0").performTouchInput { swipeLeft() }
            waitForPage(2)
            compose.onNodeWithText("左右滑页 · 双指缩放 · 长按拖动选字或区域批注（扫描件无 OCR）").assertIsDisplayed()
        }
    }
    private fun waitForPage(number: Int) {
        compose.waitUntil(30000) { compose.onAllNodesWithText("第${number}/3页").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun withPdf(landscape: Boolean = false, block: (ReadXApplication, Book) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<ReadXApplication>()
        val file = File.createTempFile("readx-pdf-test-", ".pdf", app.cacheDir)
        val document = PdfDocument()
        try {
            for (index in 0..2) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(if (landscape) 842 else 595, if (landscape) 595 else 1300, index + 1).create())
                page.canvas.drawText("ReadX PDF test page " + (index + 1), 60f, 100f, Paint().apply { textSize = 24f })
                page.canvas.drawText("Searchable offline content", 60f, 150f, Paint().apply { textSize = 18f })
                document.finishPage(page)
            }
            file.outputStream().use { document.writeTo(it) }
        } finally { document.close() }
        val book = runBlocking { app.repository.import(Uri.fromFile(file)) }
        try { block(app, book) }
        finally { runBlocking { app.repository.delete(book) }; file.delete() }
    }
}
