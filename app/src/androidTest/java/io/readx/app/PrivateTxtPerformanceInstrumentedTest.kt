package io.readx.app

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import java.util.Collections
import kotlin.math.ceil
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.readx.app.reader.LocalWebReader
import io.readx.app.reader.NativeReaderView
import io.readx.app.reader.ReaderPerformance
import io.readx.app.ui.LibraryViewModel
import io.readx.app.ui.ReaderPreferences
import io.readx.app.ui.ReaderSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Opt-in private local input. Not bundled into tests; output contains only numeric metrics. */
@RunWith(AndroidJUnit4::class)
class PrivateTxtPerformanceInstrumentedTest {
    @Test fun localInput() {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>()
        val input=File(app.cacheDir,"qa-private.txt")
        org.junit.Assume.assumeTrue("Push the explicitly authorized local TXT first",input.isFile)
        val prefs=ReaderPreferences(app); val previous=prefs.settings.value
        val before=runBlocking {app.repository.dao.observeBooks().first()}.map {it.id}.toSet()
        val book=runBlocking {app.repository.import(Uri.fromFile(input))}
        val prefix=MessageDigest.getInstance("SHA-256").digest(book.id.toByteArray()).joinToString("") {"%02x".format(it)}
        val output=File(app.getExternalFilesDir(null),"qa/private-txt-metrics.csv").apply {parentFile!!.mkdirs()}
        val lines=mutableListOf("cache,attempt,ready_ms,turn_ms,chapter_prepared_ms,text_index_ms,layout_ms,annotations_ms,restore_ms,statistics_ms,heap_bytes,pss_kib,frame_p95_ms,main_gap_max_ms")
        val frames=Collections.synchronizedList(ArrayList<Double>())
        val thread=HandlerThread("ReadXQAFrames").apply {start()}
        val handler=Handler(thread.looper)
        val listener=Window.OnFrameMetricsAvailableListener {_,metrics,_->frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION)/1e6)}
        prefs.update(ReaderSettings())
        try {
            ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {scenario ->
                lateinit var model:LibraryViewModel
                scenario.onActivity {model=ViewModelProvider(it)[LibraryViewModel::class.java];it.window.addOnFrameMetricsAvailableListener(listener,handler)}
                for(mode in listOf("cold_cache","warm_cache")) for(attempt in 1..10) {
                    if(mode=="cold_cache") { File(app.cacheDir,"native-text-v1/${book.id}").deleteRecursively() }
                    if(mode=="cold_cache") File(app.cacheDir,"page-indices-v2").listFiles().orEmpty().filter {it.name.startsWith(prefix+"-")}.forEach {it.delete()}
                    runBlocking { app.repository.dao.saveTextPosition(book.id,0,0f,0,null) }
                    frames.clear()
                    var gap=0L;var last=SystemClock.uptimeMillis();var monitoring=true
                    val main=Handler(android.os.Looper.getMainLooper())
                    val pulse=object:Runnable {override fun run() {val now=SystemClock.uptimeMillis();gap=maxOf(gap,(now-last-16).coerceAtLeast(0));last=now;if(monitoring)main.postDelayed(this,16)}}
                    main.post(pulse)
                    val start=SystemClock.elapsedRealtime()
                    scenario.onActivity {model.open(book)}
                    var ready=0L
                    waitFor(120_000) {scenario.onActivity {activity -> find(activity.window.decorView)?.let {view -> if(isReady(view)) ready=SystemClock.elapsedRealtime()-start} }; ready>0}
                    var beforeOffset=0;scenario.onActivity {beforeOffset=offset(find(it.window.decorView)!!)}
                    val turn=SystemClock.elapsedRealtime();var turned=false
                    scenario.onActivity {activity -> turn(find(activity.window.decorView)!!)}
                    waitFor(30_000) {scenario.onActivity {activity -> find(activity.window.decorView)?.let {turned=offset(it)>beforeOffset}};turned}
                    val turnMs=SystemClock.elapsedRealtime()-turn
                    monitoring=false;main.removeCallbacks(pulse)
                    val frameSnapshot=synchronized(frames) {frames.toList().sorted()}
                    val frameP95=if(frameSnapshot.isEmpty()) -1.0 else frameSnapshot[(ceil(frameSnapshot.size*.95).toInt()-1).coerceAtLeast(0)]
                    val stages=ReaderPerformance.snapshot()
                    val runtime=Runtime.getRuntime()
                    lines+="$mode,$attempt,$ready,$turnMs,${stages["chapter_prepared"] ?: -1},${stages["text_index"] ?: -1},${stages["layout"] ?: -1},${stages["annotations"] ?: -1},${stages["restore"] ?: -1},${stages["statistics"] ?: -1},${runtime.totalMemory()-runtime.freeMemory()},${android.os.Debug.getPss()},$frameP95,$gap"
                    output.writeText(lines.joinToString("\n"))
                    scenario.onActivity {model.close()}
                    waitFor(10_000) { var closed=false;scenario.onActivity {closed=find(it.window.decorView)==null};closed }
                }
                scenario.onActivity {it.window.removeOnFrameMetricsAvailableListener(listener)}
            }
        } finally {thread.quitSafely();prefs.update(previous); if(book.id !in before) runBlocking {app.repository.delete(book)} else runBlocking {app.repository.dao.saveTextPosition(book.id,book.chapterIndex,book.scrollFraction,book.lastReadAt,book.readingAnchor)} }
    }
    private fun waitFor(timeout:Long, test:()->Boolean) {val until=SystemClock.elapsedRealtime()+timeout;while(!test()) {check(SystemClock.elapsedRealtime()<until) {"Reader condition timed out"};SystemClock.sleep(40)} }
    private fun isReady(view:View)=when(view) {is NativeReaderView->view.ready;is LocalWebReader->!view.restoring && view.alpha>=.99f;else->false}
    private fun turn(view:View) {when(view) {is NativeReaderView->view.turn(1);is LocalWebReader->view.turn(1)}}
    private fun offset(view:View)=when(view) {is NativeReaderView->if(view.ready)view.drawnOffset else 0;is LocalWebReader->if(!view.isTurning)view.scrollX else 0;else->0}
    private fun page(view:View)=when(view) {is NativeReaderView->view.pageInfo().first;is LocalWebReader->if(!view.isTurning)view.pageInfo().first else 0;else->0}
    private fun find(view:View):View? {if(view is NativeReaderView || view is LocalWebReader && view.isEnabled)return view;if(view is ViewGroup) for(i in 0 until view.childCount)find(view.getChildAt(i))?.let {return it};return null}
}

