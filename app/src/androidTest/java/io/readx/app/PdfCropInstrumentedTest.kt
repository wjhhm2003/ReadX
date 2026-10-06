package io.readx.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.readx.app.data.Book
import io.readx.app.pdf.*
import io.readx.app.ui.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfCropInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun lowResolutionDetectionAndCacheBudgetBasicSevenFixtures()=withBook {app,book->runBlocking {
        val huge=renderSize(1_000_000,1_000_000)
        assertEquals(1280,huge.width);assertEquals(1280,huge.height)
        val source=CroppedPdfSource(null,app.repository.source(book))
        try {
            for(page in 0..6) {
                val image=source.render(page,true);val b=image.bitmap
                val pixels=IntArray(b.width*b.height);b.getPixels(pixels,0,b.width,0,0,b.width,b.height)
                val crop=AutoPdfCrop.detect(pixels,b.width,b.height)
                if(page==3 || page==6)assertEquals(CropRect.FULL,crop) else assertTrue(crop.width>0)
                if(page==5) {assertTrue("page number retained",crop.bottom>.95f);assertTrue("side note retained",crop.right>.9f)}
                source.render(page,false);assertTrue(source.cacheBytes<=32*1024*1024)
            }
        } finally {source.close()}
    }}
    @Test fun advancedCropRetainsSearchAndOriginalCoordinateAnnotation()=withBook {app,book->
        val config=PdfCropConfig(true,true)
        runBlocking {app.repository.dao.savePdfCrop(book.id,config.json())}
        ActivityScenario.launch<PdfActivity>(Intent(app,PdfActivity::class.java).putExtra("bookId",book.id)).use {scenario->
            waitTag("pdf-cropped-page-0")
            compose.onNodeWithTag("pdf-cropped-page-0").performTouchInput {click(center)}
            compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("PDF 搜索").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("PDF 搜索").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Searchable")
            compose.waitUntil(20000) {compose.onAllNodesWithText("第 1 页 · 命中 1").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("第 1 页 · 命中 1").performClick()
            waitTag("pdf-cropped-page-0")
            val boxes=listOf(PdfBox(0,.16f,.3f,.6f,.34f))
            runBlocking {app.repository.dao.upsertAnnotation(io.readx.app.data.Annotation(java.util.UUID.randomUUID().toString(),book.id,"NOTE",0,0f,"测试","引用","笔记",PdfLocators.encode(boxes)))}
            scenario.recreate();waitTag("pdf-cropped-page-0")
            assertEquals(boxes,PdfLocators.decode(runBlocking {app.repository.dao.annotations(book.id)}.single().locator))
            capture(app,"pdf-crop-advanced-generated.png")
        }
    }
    private fun waitTag(tag:String) {compose.waitUntil(20000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun waitFor(test:()->Boolean) {val until=SystemClock.elapsedRealtime()+15000;while(!test()) {check(SystemClock.elapsedRealtime()<until);SystemClock.sleep(40)}}
    private fun withBook(block:(ReadXApplication,Book)->Unit) {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>();val prefs=ReaderPreferences(app);val old=prefs.settings.value
        prefs.update(ReaderSettings())
        val file=File(app.cacheDir,"crop-fixture-${System.nanoTime()}.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("crop-fixtures.pdf").use {i->file.outputStream().use(i::copyTo)}
        val book=runBlocking {app.repository.import(Uri.fromFile(file))}
        try {block(app,book)} finally {prefs.update(old);runBlocking {app.repository.delete(book)};file.delete()}
    }
    private fun capture(app:ReadXApplication,name:String) {val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();val f=File(app.getExternalFilesDir(null),"qa/$name").apply {parentFile!!.mkdirs()};f.outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
}
