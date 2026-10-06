package io.readx.app

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.withTransaction
import io.readx.app.reader.*
import io.readx.app.data.Annotation
import io.readx.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit local-only stress fixture; no private text/title appears in diagnostics. */
@RunWith(AndroidJUnit4::class)
class PrivateTxtStressInstrumentedTest {
    @Test fun fullStatisticsMiddleAndThousandMarks() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("privateTxtStress")=="true")
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>();val input=File(app.cacheDir,"qa-private.txt");Assume.assumeTrue(input.isFile)
        val prefs=ReaderPreferences(app);val old=prefs.settings.value
        val before=runBlocking {app.repository.dao.observeBooks().first()}.map {it.id}.toSet();val book=runBlocking {app.repository.import(Uri.fromFile(input))}
        // Never add test marks to a previously existing user book with identical contents.
        Assume.assumeTrue("Stress test requires a fresh test-only library copy",book.id !in before)
        prefs.update(ReaderSettings())
        val output=File(app.getExternalFilesDir(null),"qa/private-txt-stress.csv").apply {parentFile!!.mkdirs()}
        val metrics=mutableListOf("scenario,ready_ms,turn_p95_ms,statistics_ms,heap_bytes,pss_kib")
        try {ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {scenario->
            lateinit var model:LibraryViewModel;scenario.onActivity {model=ViewModelProvider(it)[LibraryViewModel::class.java]}
            var source:NativeTextSource?=null
            for(stage in listOf("statistics","middle_restore","1000_annotations")) {
                if(stage!="statistics") {
                    val text=source!!;val at=text.length/2
                    val anchor=runBlocking(Dispatchers.IO) {text.anchor(at,(at+32).coerceAtMost(text.length))!!}
                    runBlocking {app.repository.dao.saveTextPosition(book.id,0,.5f,System.currentTimeMillis(),anchor.json().toString())}
                    if(stage=="1000_annotations") runBlocking(Dispatchers.IO) {app.database.withTransaction {
                        for(i in 0 until 1000) {
                            ensureActive();val start=(i.toLong()* (text.length-64)/1000).toInt();val locator=text.anchor(start,start+20) ?: continue
                            app.repository.dao.upsertAnnotation(Annotation(UUID.randomUUID().toString(),book.id,"HIGHLIGHT",0,start.toFloat()/text.length,"性能测试",locator.quote,locator=locator.json().toString()))
                        }
                    }}
                } else runBlocking {app.repository.dao.saveTextPosition(book.id,0,0f,0,null)}
                val start=SystemClock.elapsedRealtime();scenario.onActivity {model.open(book)}
                lateinit var view:NativeReaderView
                await(30_000) {var ok=false;scenario.onActivity {find(it.window.decorView)?.let {v->if(v.ready) {view=v;ok=true}}};ok}
                val ready=SystemClock.elapsedRealtime()-start;source=view.engine!!.source
                val turns=ArrayList<Long>()
                for(i in 0 until 20) {
                    val offset=view.drawnOffset;val turn=SystemClock.elapsedRealtime();scenario.onActivity {view.turn(1)}
                    await(5_000) {view.drawnOffset>offset};turns+=SystemClock.elapsedRealtime()-turn
                }
                if(stage=="statistics") await(240_000) {ReaderPerformance.snapshot()["statistics"]!=null}
                val stages=ReaderPerformance.snapshot();val runtime=Runtime.getRuntime()
                metrics+="$stage,$ready,${turns.sorted()[18]},${stages["statistics"] ?: -1},${runtime.totalMemory()-runtime.freeMemory()},${android.os.Debug.getPss()}"
                output.writeText(metrics.joinToString("\n"))
                scenario.onActivity {model.close()}
                await(10_000) {var gone=false;scenario.onActivity {gone=find(it.window.decorView)==null};gone}
                // Wait for the captured close position to reach Room before the next explicit fixture reset.
                runBlocking {app.repository.dao.book(book.id)}
            }
        }} finally {prefs.update(old);runBlocking {app.repository.delete(book)}}
    }
    private fun find(v:View):NativeReaderView? {if(v is NativeReaderView)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let {return it};return null}
    private fun await(timeout:Long,test:()->Boolean) {val until=SystemClock.elapsedRealtime()+timeout;while(!test()) {check(SystemClock.elapsedRealtime()<until) {"TXT stress condition timeout"};SystemClock.sleep(30)}}
}
