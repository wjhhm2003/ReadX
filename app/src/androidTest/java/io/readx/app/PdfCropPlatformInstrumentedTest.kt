package io.readx.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.readx.app.pdf.*
import io.readx.app.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Choreographer timing: Compose test-clock auto-advance must not drive an actively scrolling list. */
@RunWith(AndroidJUnit4::class)
class PdfCropPlatformInstrumentedTest {
    @Test fun basicVerticalProgressManualRulesAndReopen() {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>();val prefs=ReaderPreferences(app);val old=prefs.settings.value
        prefs.update(ReaderSettings())
        val file=File(app.cacheDir,"crop-platform-${System.nanoTime()}.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("crop-fixtures.pdf").use {i->file.outputStream().use(i::copyTo)}
        val book=runBlocking {app.repository.import(Uri.fromFile(file))}
        try {
            runBlocking {app.repository.dao.savePosition(book.id,0,0f,0)}
            val config=PdfCropConfig(true,true).manual(0,CropRect(.12f,.08f,.9f,.94f),CropScope.ODD).manual(0,CropRect(.16f,.1f,.86f,.9f),CropScope.PAGE)
            runBlocking {app.repository.dao.savePdfCrop(book.id,config.json())}
            val intent=Intent(app,PdfActivity::class.java).putExtra("bookId",book.id).putExtra("forceBasicForTest",true)
            ActivityScenario.launch<PdfActivity>(intent).use {scenario->
                await {node("1 / 7")!=null}
                repeat(2) {gesture(.5f,.8f,.5f,.22f,350)}
                capture(app,"pdf-after-gesture-generated.png")
                await {runBlocking {app.repository.dao.book(book.id)}!!.chapterIndex>0}
                // Stop the fling, then wait for a stable observed original-page position.
                val ui=InstrumentationRegistry.getInstrumentation().uiAutomation
                val display=ui.takeScreenshot().let {val result=it.width to it.height;it.recycle();result}
                val stopTime=SystemClock.uptimeMillis()
                for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_CANCEL)) {
                    val e=MotionEvent.obtain(stopTime,SystemClock.uptimeMillis(),action,display.first*.5f,display.second*.4f,0)
                    e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;ui.injectInputEvent(e,true);e.recycle()
                }
                var location=0 to 0f;var stable=0
                await {var next=location;scenario.onActivity {next=it.currentOriginalPosition};if(next.first==location.first && kotlin.math.abs(next.second-location.second)<.0001f)stable++ else stable=0;location=next;stable>=4}
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                await {runBlocking {app.repository.dao.book(book.id)!!}.chapterIndex==location.first}
                // Join the finite snapshot writer instead of racing its IO dispatch with a Room read.
                runBlocking {app.repository.persistPosition(book.id,location.first,location.second).join()}
                val saved=runBlocking {app.repository.dao.book(book.id)!!}
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                scenario.recreate()
                try {await {node("${saved.chapterIndex+1} / 7")!=null}} catch(error:Exception) {capture(app,"pdf-reopen-failure-generated.png");throw error}
                assertEquals(config,PdfCropConfig.parse(runBlocking {app.repository.dao.book(book.id)!!}.pdfCropConfig))
                capture(app,"pdf-crop-vertical-generated.png")
                tap(.5f,.45f);try {await {node("PDF 裁边")!=null}} catch(error:Exception) {capture(app,"pdf-controls-failure-generated.png");throw error};click("PDF 裁边")
                await {node("裁边 · 原文第 ${saved.chapterIndex+1} 页")!=null}
                await {node("原页裁边预览")!=null}
                capture(app,"pdf-crop-manual-generated.png")
                click("取消")
                assertEquals(config,PdfCropConfig.parse(runBlocking {app.repository.dao.book(book.id)!!}.pdfCropConfig))
                await {node("PDF 裁边")!=null}
                click("PDF 裁边");await {node("原页裁边预览")!=null}
                val rect=android.graphics.Rect();node("原页裁边预览")!!.getBoundsInScreen(rect)
                val handle=android.graphics.Rect();await {node("裁边左侧手柄")!=null};node("裁边左侧手柄")!!.getBoundsInScreen(handle)
                val screen=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {val result=it.width to it.height;it.recycle();result}
                gesture(handle.exactCenterX()/screen.first,handle.exactCenterY()/screen.second,(handle.exactCenterX()+rect.width()*.12f)/screen.first,handle.exactCenterY()/screen.second,400)
                await {node("应用")!=null}
                capture(app,"070-crop-handles-dragged.png")
                val applyBounds=android.graphics.Rect();node("应用")!!.getBoundsInScreen(applyBounds)
                tap(applyBounds.exactCenterX()/screen.first,applyBounds.exactCenterY()/screen.second)
                capture(app,"070-crop-after-drag.png")
                await {PdfCropConfig.parse(runBlocking {app.repository.dao.book(book.id)!!}.pdfCropConfig).pages[saved.chapterIndex]?.left?.let {it>.03f}==true}
                assertEquals(file.length(),app.repository.source(book).length())
            }
        } finally {prefs.update(old);runBlocking {app.repository.delete(book)};file.delete()}
    }
    private fun click(value:String) {
        var target=node(value) ?: error("Control not found: $value")
        while(!target.isClickable && target.parent!=null)target=target.parent
        assertTrue("Control must handle ACTION_CLICK",target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun node(value:String):AccessibilityNodeInfo? {
        fun find(v:AccessibilityNodeInfo?):AccessibilityNodeInfo? {if(v==null)return null;if(v.text?.toString()==value || v.contentDescription?.toString()==value)return v;for(i in 0 until v.childCount)find(v.getChild(i))?.let {return it};return null}
        return find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
    }
    private fun tap(x:Float,y:Float)=gesture(x,y,x,y,70)
    private fun gesture(x1:Float,y1:Float,x2:Float,y2:Float,duration:Int) {
        val ui=InstrumentationRegistry.getInstrumentation().uiAutomation;val size=ui.takeScreenshot().let {val s=it.width to it.height;it.recycle();s}
        val t=SystemClock.uptimeMillis()
        for(i in 0..12) {val action=if(i==0) MotionEvent.ACTION_DOWN else if(i==12) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE;val f=i/12f;val e=MotionEvent.obtain(t,SystemClock.uptimeMillis(),action,(x1+(x2-x1)*f)*size.first,(y1+(y2-y1)*f)*size.second,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;ui.injectInputEvent(e,true);e.recycle();if(i<12)SystemClock.sleep(duration/12L)}
    }
    private fun await(test:()->Boolean) {val until=SystemClock.elapsedRealtime()+20_000;while(!test()) {check(SystemClock.elapsedRealtime()<until) {"PDF platform condition timed out"};SystemClock.sleep(40)}}
    private fun capture(app:ReadXApplication,name:String) {val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();val f=File(app.getExternalFilesDir(null),"qa/$name").apply {parentFile!!.mkdirs()};f.outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
}
