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
import io.readx.app.ui.*
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.graphics.toArgb
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfTapRegressionInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private var testBookId=""
    @Test fun scrollingAnyPageTapAndPagedThreeZonesReachControls() {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>()
        val prefs=ReaderPreferences(app);val old=prefs.settings.value;prefs.update(ReaderSettings())
        val file=File(app.cacheDir,"pdf-tap-${System.nanoTime()}.pdf");val pdf=PdfDocument()
        for(i in 0..2) {val p=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,i+1).create());p.canvas.drawText("ReadX generated PDF tap page ${i+1}",40f,100f,Paint().apply {textSize=24f});pdf.finishPage(p)}
        file.outputStream().use {pdf.writeTo(it)};pdf.close()
        val book=runBlocking {app.repository.import(Uri.fromFile(file))};testBookId=book.id
        try {
            ActivityScenario.launch<PdfActivity>(Intent(app,PdfActivity::class.java).putExtra("bookId",book.id)).use {scenario->
                compose.waitUntil(30000) {var ready=false;scenario.onActivity {ready=(it.supportFragmentManager.findFragmentByTag("pdf") as? ReadXPdfFragment)?.currentDocumentPageCount()==3};ready}
                scenario.onActivity {findPdf(it.window.decorView)!!.scrollBy(0,findPdf(it.window.decorView)!!.height)}
                compose.waitUntil(10000) {var ready=false;scenario.onActivity {val v=findPdf(it.window.decorView);ready=v?.scrollY?.let {y->y>0}==true;if(!ready && v!=null) v.scrollBy(0,v.height)};ready}
                tapNative(scenario,.1f)
                compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("跳转页码").fetchSemanticsNodes().isNotEmpty()}
                tapNative(scenario,.9f)
                compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("跳转页码").fetchSemanticsNodes().isEmpty()}
                scenario.onActivity {(it.supportFragmentManager.findFragmentByTag("pdf") as ReadXPdfFragment).go(2)}
                compose.waitUntil(10000) {var ready=false;scenario.onActivity {val v=findPdf(it.window.decorView);ready=v?.scrollY?.let {y->y>0}==true;if(!ready && v!=null) v.scrollBy(0,v.height)};ready}
                tapNative(scenario,.8f)
                compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("跳转页码").fetchSemanticsNodes().isNotEmpty()}
                TestScreenshots.capture("pdf-040-scrolled-controls")
                compose.onNodeWithContentDescription("切换 PDF 横向或纵向阅读").performClick()
                compose.onNodeWithContentDescription("跳转页码").performClick()
                compose.onNode(hasSetTextAction()).performTextInput("3")
                compose.onNodeWithText("跳转",useUnmergedTree = true).performClick()
                waitPage(2)
                compose.onNodeWithTag("pdf-page-2").performTouchInput {click(center)}
                compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("跳转页码").fetchSemanticsNodes().isEmpty()}
                compose.onNodeWithTag("pdf-page-2").performTouchInput {click(Offset(width*.15f,height*.5f))};waitPage(1)
                compose.onNodeWithTag("pdf-page-1").performTouchInput {click(Offset(width*.15f,height*.5f))};waitPage(0)
                compose.onNodeWithTag("pdf-page-0").performTouchInput {click(Offset(width*.85f,height*.5f))};waitPage(1)
                compose.onNodeWithTag("pdf-page-1").performTouchInput {click(center)}
                compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("跳转页码").fetchSemanticsNodes().isNotEmpty()}
                compose.waitForIdle()
                TestScreenshots.capture("pdf-040-paged-controls")
                val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
                try {
                    val expected = readingScheme(accentScheme(ThemeAccent.BLUE,"",false),ReadingTheme.SYSTEM,false).surfaceContainerHigh.toArgb()
                    assertEquals("PDF gesture strip must continue its visible dock surface",expected,image.getPixel(8,image.height-8))
                } finally { image.recycle() }
            }
        } finally {runBlocking {app.repository.delete(book)};file.delete();prefs.update(old)}
    }
    private fun waitPage(index:Int) {compose.waitUntil(15000) {compose.onAllNodesWithTag("pdf-page-$index").fetchSemanticsNodes().isNotEmpty() && runBlocking {ApplicationProvider.getApplicationContext<ReadXApplication>().repository.dao.book(testBookId)}?.chapterIndex==index}}
    private fun tapNative(scenario:ActivityScenario<PdfActivity>,x:Float) {scenario.onActivity {activity->val view=findPdf(activity.window.decorView)!!;val time=SystemClock.uptimeMillis();for((i,action) in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP).withIndex()) {val e=MotionEvent.obtain(time,time+i*50,action,view.width*x,view.height*.45f,0);view.dispatchTouchEvent(e);e.recycle()}}}
    private fun findPdf(view:View):PdfView? {if(view is PdfView) return view;if(view is ViewGroup) for(i in 0 until view.childCount) findPdf(view.getChildAt(i))?.let {return it};return null}
}
