package io.readx.app

import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.readx.app.pdf.PdfActivity
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
class PdfAnnotationInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun horizontalPdfSwipesSelectsAndPersistsNotes() {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>()
        val prefs=ReaderPreferences(app);val old=prefs.settings.value;prefs.update(ReaderSettings(pdfLayout=PdfReadingLayout.HORIZONTAL))
        val file=File.createTempFile("readx-pdf-annotation-",".pdf",app.cacheDir)
        val pdf=PdfDocument()
        for(i in 0..2) {
            val p=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,i+1).create())
            p.canvas.drawText("ReadX annotation page ${i+1}",50f,100f,Paint().apply {textSize=24f})
            p.canvas.drawText("Select this paragraph for offline notes.",50f,160f,Paint().apply {textSize=18f})
            pdf.finishPage(p)
        }
        file.outputStream().use {pdf.writeTo(it)};pdf.close()
        val book=runBlocking {app.repository.import(Uri.fromFile(file))}
        try {
            ActivityScenario.launch<PdfActivity>(Intent(app,PdfActivity::class.java).putExtra("bookId",book.id)).use { scenario ->
                compose.waitUntil(30000) {compose.onAllNodesWithText("1 / 3").fetchSemanticsNodes().isNotEmpty()}
                compose.waitUntil(15000) {compose.onAllNodesWithTag("pdf-page-0").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("pdf-page-0").performTouchInput {swipeLeft()}
                compose.waitUntil(10000) {compose.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty()}
                compose.waitUntil(15000) {compose.onAllNodesWithTag("pdf-page-1").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("pdf-page-1").performTouchInput {
                    // Include the actual centered page offset when reaching the generated text.
                    val scale=minOf(width/595f,height/842f)
                    val left=(width-595f*scale)/2f;val top=(height-842f*scale)/2f
                    down(Offset(left+70f*scale,top+155f*scale));advanceEventTime(700)
                    moveTo(Offset(left+400f*scale,top+155f*scale),500);up()
                }
                compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("高亮").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithContentDescription("写笔记").performClick()
                compose.onNode(hasSetTextAction()).performTextInput("离线 PDF 笔记")
                compose.onNodeWithText("保存").performClick()
                compose.waitUntil(8000) {runBlocking {app.repository.dao.annotations(book.id).any {it.note=="离线 PDF 笔记"}}}
                val annotation=runBlocking {app.repository.dao.annotations(book.id).single()}
                assertEquals(1,annotation.chapter);assertTrue("Text PDF should provide a real selected quote",annotation.quote.isNotBlank());assertTrue(annotation.locator.contains("rects"))
                compose.waitForIdle()
                TestScreenshots.capture("pdf-030-horizontal-annotation")
                compose.showPdfControls(scenario)
                compose.onNodeWithContentDescription("切换 PDF 横向或纵向阅读").performClick()
                compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("PDF 搜索").fetchSemanticsNodes().isNotEmpty()}
                TestScreenshots.capture("pdf-030-vertical-annotation")
            }
        } finally {runBlocking {app.repository.delete(book)};file.delete();prefs.update(old)}
    }
}
