package io.readx.app

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.readx.app.reader.*
import io.readx.app.ui.*
import io.readx.app.data.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeTxtInstrumentedTest {
    @Test fun realGesturesAnchorsEngineSwitchReflowAndLargeChapterRestore() {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>();val prefs=ReaderPreferences(app);val old=prefs.settings.value
        val file=File(app.cacheDir,"native-fixture-${System.nanoTime()}.txt").apply {writeText("第一章 原生测试\n"+(1..2000).joinToString("\n") {"第 $it 段 中文 😀原生排版，重复原文用于验证上下文定位。English mixed words。段落测试 🧑‍💻 end。"}+"\n第二章 衔接\n"+"这是第二章正文。".repeat(500))}
        val book=runBlocking {app.repository.import(Uri.fromFile(file))}
        prefs.update(ReaderSettings())
        try {ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {scenario->
            lateinit var model:LibraryViewModel;scenario.onActivity {model=ViewModelProvider(it)[LibraryViewModel::class.java];model.open(book)}
            lateinit var v:NativeReaderView
            waitFor {var ok=false;scenario.onActivity {native(it.window.decorView)?.let {view->if(view.ready) {v=view;ok=true}}};ok}
            val first=v.currentOffset()
            tap(scenario,v,.83f);waitFor {v.currentOffset()>first}
            val second=v.currentOffset();tap(scenario,v,.15f);waitFor {v.currentOffset()<second}
            assertEquals(first,v.currentOffset())
            for(i in 1..8) {val at=v.currentOffset();tap(scenario,v,.83f);waitFor {v.currentOffset()>at}}
            val source=v.engine!!.source
            val anchor=runBlocking(Dispatchers.IO) {source.anchor(v.currentOffset(),v.currentOffset()+10)!!}
            scenario.onActivity {model.textAnchor(anchor);model.addBookmark()}
            waitFor {runBlocking {app.repository.dao.annotations(book.id)}.any {it.kind=="BOOKMARK" && it.locator.isNotEmpty()}}
            scenario.onActivity {model.switchTextEngine("WEBVIEW",anchor)}
            lateinit var web:LocalWebReader
            waitFor(30_000) {var ok=false;scenario.onActivity {findWeb(it.window.decorView)?.let {view->if(!view.restoring && view.alpha>.99f) {web=view;ok=true}}};ok}
            var captured:TextAnchor?=null;scenario.onActivity {web.captureViewportAnchor {captured=it}}
            waitFor {captured!=null}
            assertNotNull(runBlocking {source.resolve(captured!!)})
            scenario.onActivity {model.switchTextEngine("NATIVE",captured)}
            waitFor {var ready=false;scenario.onActivity {native(it.window.decorView)?.let {view->if(view.ready) {v=view;ready=true}}};ready}
            val quoteBefore=v.viewportAnchor!!
            prefs.update(prefs.settings.value.copy(fontSize=24f))
            waitFor {v.ready && v.engine!!.paint.textSize>23*v.resources.displayMetrics.density}
            val target=runBlocking {v.engine!!.source.resolve(quoteBefore)}!!.first
            assertTrue(target in v.currentOffset() until v.currentOffset()+v.engine!!.source.read(v.currentOffset()).length)
            prefs.update(prefs.settings.value.copy(layout=ReadingLayout.SCROLL))
            waitFor {!v.paged && v.ready}
            prefs.update(prefs.settings.value.copy(layout=ReadingLayout.PAGED))
            waitFor {v.paged && v.ready}
            val middle=source.length/2
            val middleAnchor=runBlocking {source.anchor(middle,middle+20)!!}
            scenario.onActivity {model.close()};waitFor {var closed=false;scenario.onActivity {closed=native(it.window.decorView)==null};closed}
            runBlocking {app.repository.dao.saveTextPosition(book.id,0,.5f,System.currentTimeMillis(),middleAnchor.json().toString())}
            scenario.onActivity {model.open(book)}
            waitFor {var ready=false;scenario.onActivity {native(it.window.decorView)?.let {view->if(view.ready) {v=view;ready=true}}};ready}
            val resolved=runBlocking {v.engine!!.source.resolve(middleAnchor)}!!.first
            assertTrue("Restored text must be on the displayed real page",resolved>=v.currentOffset() && resolved<v.currentOffset()+v.engine!!.source.read(v.currentOffset()).length)
            // Colour/note changes don't replace the bounded page's layout or reading offset.
            val at=v.currentOffset();val engine=v.engine
            scenario.onActivity {model.addTextAnnotation("NOTE",middleAnchor,"测试笔记",.5f)}
            waitFor {runBlocking {app.repository.dao.annotations(book.id)}.any {it.note=="测试笔记"}}
            scenario.onActivity {model.addTextAnnotation("NOTE",middleAnchor,"编辑后笔记",.5f)}
            waitFor {runBlocking {app.repository.dao.annotations(book.id)}.any {it.note=="编辑后笔记"}}
            assertEquals(1,runBlocking {app.repository.dao.annotations(book.id)}.count {it.kind=="NOTE"})
            assertSame(engine,v.engine);assertEquals(at,v.currentOffset())
            scenario.recreate();waitFor {var ready=false;scenario.onActivity {native(it.window.decorView)?.let {view->if(view.ready) {v=view;ready=true}}};ready}
            capture(app,"native-txt-generated.png")
            // A cross-chapter global page request is an engine-local real page boundary, not text percent.
            scenario.onActivity {model.chapter(1,requestedPage=3)}
            waitFor {var ok=false;scenario.onActivity {native(it.window.decorView)?.let {view->if(view.ready && view.chapterOrdinal==1 && view.pageInfo().first==3) {v=view;ok=true}}};ok}
            scenario.onActivity {model.chapter(0,fraction=.5f)}
            waitFor {var ok=false;scenario.onActivity {native(it.window.decorView)?.let {view->if(view.ready && view.chapterOrdinal==0) {v=view;ok=true}}};ok}
            // Real long press, then move the active endpoint into the next page within this chapter.
            var picked:ReaderSelection?=null;var selectionUpdates=0
            scenario.onActivity {val original=v.onSelection;v.onSelection={picked=it;selectionUpdates++;original?.invoke(it)}
                val t=SystemClock.uptimeMillis();val down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,v.width*.45f,v.height*.35f,0);v.dispatchTouchEvent(down);down.recycle()}
            waitFor {picked!=null}
            val beforeSnap=selectionUpdates
            scenario.onActivity {val t=SystemClock.uptimeMillis();val up=MotionEvent.obtain(t-600,t,MotionEvent.ACTION_UP,v.width*.45f,v.height*.35f,0);v.dispatchTouchEvent(up);up.recycle()}
            waitFor {selectionUpdates>beforeSnap}
            val selectionStart=picked!!.anchor.start
            val oldPageEnd=v.currentPageEnd
            scenario.onActivity {v.turn(1)}
            waitFor {picked?.anchor?.start==selectionStart && (picked?.anchor?.end ?: 0)>v.engine!!.source.toCanonical(oldPageEnd)}
            assertNotNull(runBlocking {v.engine!!.source.resolve(picked!!.anchor)})
            scenario.onActivity {v.clearSelection();model.chapter(1)}
            waitFor {var ok=false;scenario.onActivity {native(it.window.decorView)?.let {view->if(view.ready && view.chapterOrdinal==1) {v=view;ok=true}}};ok}
            tap(scenario,v,.15f)
            waitFor {var ok=false;scenario.onActivity {native(it.window.decorView)?.let {view->if(view.ready && view.chapterOrdinal==0) {v=view;ok=true}}};ok}
            assertEquals("Back from chapter start must show the preceding chapter's last real page",v.engine!!.source.length,v.currentPageEnd)
            scenario.onActivity {model.close()}
        }} finally {prefs.update(old);runBlocking {app.repository.delete(book)};file.delete()}
    }
    private fun tap(s:ActivityScenario<MainActivity>,v:NativeReaderView,x:Float) {s.onActivity {val t=SystemClock.uptimeMillis();val a=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,v.width*x,v.height*.45f,0);v.dispatchTouchEvent(a);a.recycle();val b=MotionEvent.obtain(t,t+50,MotionEvent.ACTION_UP,v.width*x,v.height*.45f,0);v.dispatchTouchEvent(b);b.recycle()}}
    private fun native(v:View):NativeReaderView? {if(v is NativeReaderView)return v;if(v is ViewGroup)for(i in 0 until v.childCount)native(v.getChildAt(i))?.let {return it};return null}
    private fun findWeb(v:View):LocalWebReader? {if(v is LocalWebReader && v.isEnabled)return v;if(v is ViewGroup)for(i in 0 until v.childCount)findWeb(v.getChildAt(i))?.let {return it};return null}
    private fun waitFor(timeout:Long=15_000,test:()->Boolean) {val until=SystemClock.elapsedRealtime()+timeout;while(!test()) {check(SystemClock.elapsedRealtime()<until) {"Reader condition timed out"};SystemClock.sleep(30)}}
    private fun capture(app:ReadXApplication,name:String) {
        val ui=InstrumentationRegistry.getInstrumentation().uiAutomation
        val until=SystemClock.elapsedRealtime()+5000
        while(true) {
            val bitmap=ui.takeScreenshot()
            var ink=0
            for(y in bitmap.height/8 until bitmap.height*3/4 step 4) for(x in bitmap.width/10 until bitmap.width*9/10 step 4) {val c=bitmap.getPixel(x,y);if(android.graphics.Color.red(c)<110 && android.graphics.Color.green(c)<110 && android.graphics.Color.blue(c)<110)ink++}
            if(ink>1000) {val f=File(app.getExternalFilesDir(null),"qa/$name").apply {parentFile!!.mkdirs()};f.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();return}
            bitmap.recycle();check(SystemClock.elapsedRealtime()<until) {"Generated reader screenshot has no committed text"};SystemClock.sleep(30)
        }
    }
}
