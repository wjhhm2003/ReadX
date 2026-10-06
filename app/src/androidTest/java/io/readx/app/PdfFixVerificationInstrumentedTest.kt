package io.readx.app

import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.pdf.view.PdfView
import io.readx.app.pdf.PdfActivity
import io.readx.app.pdf.ReadXPdfFragment
import io.readx.app.ui.ReaderPreferences
import io.readx.app.ui.ReaderSettings
import io.readx.app.ui.PdfReadingLayout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfFixVerificationInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun verticalPdfSliderJumpsAndUpdatesPage() {
        val app = ApplicationProvider.getApplicationContext<ReadXApplication>()
        val prefs = ReaderPreferences(app)
        val old = prefs.settings.value
        prefs.update(ReaderSettings(pdfLayout = PdfReadingLayout.VERTICAL))

        val file = File(app.cacheDir, "pdf-slider-${System.nanoTime()}.pdf")
        val pdf = PdfDocument()
        for (i in 0..4) {
            val p = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            p.canvas.drawText("ReadX Verification Page ${i + 1}", 50f, 100f, Paint().apply { textSize = 24f })
            p.canvas.drawText("Testing slider jump in vertical scroll mode.", 50f, 160f, Paint().apply { textSize = 18f })
            pdf.finishPage(p)
        }
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()

        val book = runBlocking { app.repository.import(Uri.fromFile(file)) }
        try {
            ActivityScenario.launch<PdfActivity>(Intent(app, PdfActivity::class.java).putExtra("bookId", book.id)).use { scenario ->
                // Wait until PDF document is loaded with 5 pages
                compose.waitUntil(30000) {
                    var ready = false
                    scenario.onActivity {
                        val fragment = it.supportFragmentManager.findFragmentByTag("pdf") as? ReadXPdfFragment
                        ready = fragment?.currentDocumentPageCount() == 5
                    }
                    ready
                }

                // Tap to show controls
                tapNative(scenario, 0.5f, 0.5f)
                compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("跳转页码").fetchSemanticsNodes().isNotEmpty() }

                // Initial page should be 1 / 5
                compose.onNodeWithText("1 / 5").assertIsDisplayed()

                // Test clicking "下一章节或原文页"
                compose.onNodeWithContentDescription("下一章节或原文页").performClick()

                // Page should immediately update to 2 / 5
                compose.waitUntil(5000) { compose.onAllNodesWithText("2 / 5").fetchSemanticsNodes().isNotEmpty() }
                scenario.onActivity { assertEquals(1, it.page) }

                // Test dragging reading-progress-slider thumb towards the right
                compose.onNodeWithTag("reading-progress-slider").performTouchInput {
                    down(center)
                    moveTo(Offset(width * 0.9f, centerY))
                    up()
                }

                // Verify page updated to a higher page (e.g. 5 / 5)
                compose.waitUntil(10000) {
                    var cur = 0
                    scenario.onActivity { cur = it.page }
                    cur >= 3
                }

                // Verify database saved chapterIndex >= 3 with debounce wait
                compose.waitUntil(10000) {
                    val saved = runBlocking { app.repository.dao.book(book.id)?.chapterIndex ?: 0 }
                    saved >= 3
                }

                TestScreenshots.capture("pdf-050-vertical-jump-success")
            }
        } finally {
            runBlocking { app.repository.delete(book) }
            file.delete()
            prefs.update(old)
        }
    }

    private fun tapNative(scenario: ActivityScenario<PdfActivity>, x: Float, y: Float) {
        scenario.onActivity { activity ->
            val view = findPdf(activity.window.decorView) ?: activity.window.decorView
            val time = SystemClock.uptimeMillis()
            for ((i, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).withIndex()) {
                val e = MotionEvent.obtain(time, time + i * 50, action, view.width * x, view.height * y, 0)
                view.dispatchTouchEvent(e)
                e.recycle()
            }
        }
    }

    private fun findPdf(view: View): PdfView? {
        if (view is PdfView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findPdf(view.getChildAt(i))?.let { return it }
        return null
    }
}
