package io.readx.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.readx.app.pdf.*
import io.readx.app.data.Book
import io.readx.app.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class PdfInteractionRegressionInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val app get()=ApplicationProvider.getApplicationContext<ReadXApplication>()

    @Test fun basicVerticalSliderMovesTheActualListInBothDirections()=withPdf(true,false,PdfReadingLayout.VERTICAL) {scenario,book->slider(scenario,book)}
    @Test fun croppedVerticalSliderMovesTheActualListInBothDirections()=withPdf(false,true,PdfReadingLayout.VERTICAL) {scenario,book->slider(scenario,book)}
    @Test fun horizontalLongPressSelectsRealTextAndHandlesExtendIt()=withPdf(false,false,PdfReadingLayout.HORIZONTAL) {_,book->selectText("pdf-page-0",book)}
    @Test fun croppedLongPressSelectsRealTextAndHandlesExtendIt()=withPdf(false,true,PdfReadingLayout.HORIZONTAL) {_,book->selectText("pdf-cropped-page-0",book)}
    @Test fun basicNightModeInvertsAndSurvivesRecreation()=withPdf(true,false,PdfReadingLayout.VERTICAL) {scenario,_->night(scenario,"basic")}
    @Test fun advancedNightModeInvertsAndSurvivesRecreation()=withPdf(false,false,PdfReadingLayout.VERTICAL) {scenario,_->night(scenario,"advanced")}
    @Test fun horizontalNightModeInvertsAndSurvivesRecreation()=withPdf(false,false,PdfReadingLayout.HORIZONTAL) {scenario,_->night(scenario,"horizontal")}

    @Test fun scanWithoutTextLayerDoesNotCreateARegionSelection()=withPdf(false,false,PdfReadingLayout.HORIZONTAL) {scenario,book->
        scenario.onActivity {it.requestJump(4)}
        waitTag("pdf-page-4")
        compose.onNodeWithTag("pdf-page-4").performTouchInput {down(center);advanceEventTime(700);up()}
        compose.waitForIdle()
        compose.onNodeWithTag("pdf-text-handle-1").assertDoesNotExist()
        compose.onNodeWithContentDescription("复制").assertDoesNotExist()
        assertTrue(runBlocking {app.repository.dao.annotations(book.id).isEmpty()})
    }

    private fun slider(scenario:ActivityScenario<PdfActivity>,book:Book) {
        waitTag("pdf-cropped-page-0")
        compose.onNodeWithTag("pdf-cropped-page-0").performTouchInput {click(center)}
        waitTag("reading-progress-slider")
        compose.onNodeWithTag("reading-progress-slider").performTouchInput {swipe(Offset(width*.05f,centerY),Offset(width*.95f,centerY),500)}
        compose.waitUntil(10000) {compose.onAllNodesWithTag("pdf-cropped-page-4").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("pdf-cropped-page-4").assertIsDisplayed()
        compose.waitUntil(5000) {runBlocking {app.repository.dao.book(book.id)?.chapterIndex}==4}
        scenario.onActivity {assertEquals(4,it.page)}
        compose.onNodeWithTag("reading-progress-slider").performTouchInput {swipe(Offset(width*.95f,centerY),Offset(width*.05f,centerY),500)}
        compose.waitUntil(10000) {compose.onAllNodesWithTag("pdf-cropped-page-0").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("pdf-cropped-page-0").assertIsDisplayed()
        compose.waitUntil(5000) {runBlocking {app.repository.dao.book(book.id)?.chapterIndex}==0}
        TestScreenshots.capture("pdf-073-slider-generated")
    }
    private fun selectText(tag:String,book:Book) {
        waitTag(tag)
        compose.onNodeWithTag(tag).performTouchInput {
            val fit=min(width/600f,height/850f)
            val left=(width-600*fit)/2;val top=(height-850*fit)/2
            down(Offset(left+100*fit,top+94*fit));advanceEventTime(700);up()
        }
        waitTag("pdf-text-handle-1")
        compose.onNodeWithContentDescription("复制").assertExists()
        val before=compose.onNodeWithTag("pdf-text-handle-1").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        compose.onNodeWithTag("pdf-text-handle-1").performTouchInput {swipe(center,center+Offset(200f,0f),500)}
        compose.waitUntil(5000) {
            val handles=compose.onAllNodesWithTag("pdf-text-handle-1").fetchSemanticsNodes()
            handles.isNotEmpty() && handles.single().config[SemanticsProperties.StateDescription].length>before.length
        }
        TestScreenshots.capture("pdf-073-text-$tag-generated")
        compose.onNodeWithContentDescription("高亮").performClick()
        compose.waitUntil(5000) {runBlocking {app.repository.dao.annotations(book.id).isNotEmpty()}}
        val mark=runBlocking {app.repository.dao.annotations(book.id).single()}
        assertTrue(mark.quote.length>before.length)
        assertTrue(mark.quote.contains("ReadX") || mark.quote.contains("selection") || mark.quote.contains("ordinary"))
        assertTrue(PdfLocators.decode(mark.locator).isNotEmpty())
        assertEquals(0,mark.chapter)
        compose.waitUntil(5000) {compose.onAllNodesWithTag("pdf-text-handle-1").fetchSemanticsNodes().isEmpty()}
    }
    private fun night(scenario:ActivityScenario<PdfActivity>,name:String) {
        compose.waitUntil(10000) {sample()>220}
        scenario.onActivity {it.toggleChrome()}
        compose.onNodeWithContentDescription("标记颜色").performClick()
        waitTag("pdf-night-switch")
        compose.onNodeWithTag("pdf-night-switch").performClick()
        TestScreenshots.capture("pdf-073-night-$name-toggle-generated")
        compose.waitUntil(5000) {sample()<35}
        assertTrue(ReaderPreferences(app).settings.value.pdfInverted)
        scenario.onActivity {it.toggleChrome()}
        TestScreenshots.capture("pdf-073-night-$name-generated")
        scenario.recreate()
        compose.waitUntil(15000) {sample()<35 && ReaderPreferences(app).settings.value.pdfInverted}
    }
    private fun sample():Int {
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return -1
        return try {Color.red(image.getPixel(image.width/4,image.height/2))} finally {image.recycle()}
    }
    private fun waitTag(tag:String) {compose.waitUntil(20000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun withPdf(basic:Boolean,crop:Boolean,layout:PdfReadingLayout,block:(ActivityScenario<PdfActivity>,Book)->Unit) {
        val preferences=ReaderPreferences(app);val previous=preferences.settings.value
        preferences.update(ReaderSettings(pdfLayout=layout))
        val file=File(app.cacheDir,"interaction-073-${System.nanoTime()}.pdf")
        val pdf=PdfDocument()
        for(i in 0..4) {
            val page=pdf.startPage(PdfDocument.PageInfo.Builder(600,850,i+1).create())
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=24f;color=Color.BLACK}
            if(i==4) {
                val image=Bitmap.createBitmap(600,850,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(image);canvas.drawColor(Color.WHITE)
                canvas.drawText("SCAN HAS NO TEXT LAYER",50f,425f,paint)
                page.canvas.drawBitmap(image,0f,0f,null);image.recycle()
            } else {
                page.canvas.drawText("ReadX ordinary text selection page ${i+1}",50f,100f,paint)
                page.canvas.drawText("Second line for handle extension and notes.",50f,150f,paint)
            }
            pdf.finishPage(page)
        }
        file.outputStream().use {pdf.writeTo(it)};pdf.close()
        val book=runBlocking {app.repository.import(Uri.fromFile(file))}
        try {
            if(crop)runBlocking {app.repository.dao.savePdfCrop(book.id,PdfCropConfig(enabled=true).json())}
            ActivityScenario.launch<PdfActivity>(Intent(app,PdfActivity::class.java).putExtra("bookId",book.id).putExtra("forceBasicForTest",basic)).use {scenario->
                compose.waitUntil(20000) {var ready=false;scenario.onActivity {ready=it.page>=0};ready && (compose.onAllNodesWithText("1 / 5").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("pdf-page-0").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("pdf-cropped-page-0").fetchSemanticsNodes().isNotEmpty())}
                block(scenario,book)
            }
        } finally {runBlocking {app.repository.delete(book)};file.delete();preferences.update(previous)}
    }
}
