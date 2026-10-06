package io.readx.app

import android.app.ActivityManager
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.googlecode.tesseract.android.TessBaseAPI
import io.readx.app.conversion.OcrParallelism
import io.readx.app.conversion.PdfPageExtractor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic bitmap only: compare warmed serial and page-parallel OCR, not private books. */
@RunWith(AndroidJUnit4::class)
class OcrParallelPerformanceInstrumentedTest {
    @Test fun warmedSerialAndParallelRecognitionRemainAccurate()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(app)
        val manager=app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory=ActivityManager.MemoryInfo().also {manager.getMemoryInfo(it)}
        val cores=Runtime.getRuntime().availableProcessors()
        val count=OcrParallelism.workers(cores,manager.memoryClass,memory.availMem,memory.lowMemory,6)
        val model=File(app.getExternalFilesDir(null),"qa-models/eng.traineddata")
        assumeTrue("Supply fixed official English model in qa-models on the dedicated AVD",model.isFile)
        // A forcibly interrupted test can leave only its own model session, never user books.
        app.cacheDir.listFiles()?.filter {it.name.matches(Regex("ocr-perf-[0-9a-f-]{36}")) && it.isDirectory}?.forEach {orphan->
            val data=File(orphan,"tessdata")
            File(data,"eng.traineddata").delete();data.delete();orphan.delete()
        }
        val root=File(app.cacheDir,"ocr-perf-${UUID.randomUUID()}").apply {mkdirs()}
        val tessdata=File(root,"tessdata").apply {mkdirs()}
        model.copyTo(File(tessdata,"eng.traineddata"))
        val engines=mutableListOf<TessBaseAPI>()
        val bitmap=Bitmap.createBitmap(1694,2400,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=44f}
        for(line in 0..21)canvas.drawText("READX OFFLINE READER PERFORMANCE TEST",70f,120f+line*96,paint)
        val running=AtomicInteger();val peak=AtomicInteger()
        fun recognize(engine:TessBaseAPI) {
            val active=running.incrementAndGet();peak.updateAndGet {maxOf(it,active)}
            try {
                engine.setImage(bitmap)
                val flow=PdfPageExtractor.ocr(engine.getHOCRText(0),bitmap.width.toFloat())
                assertTrue(flow.paragraphs.joinToString(" ").contains("OFFLINE READER"))
                assertTrue(engine.meanConfidence()>=40)
            } finally {engine.clear();running.decrementAndGet()}
        }
        try {
            repeat(count) {
                val engine=TessBaseAPI();engines+=engine
                assertTrue(engine.init(root.absolutePath,"eng",TessBaseAPI.OEM_LSTM_ONLY))
                engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                withContext(Dispatchers.Default) {recognize(engine)}
            }
            val serial=mutableListOf<Long>();val parallel=mutableListOf<Long>()
            repeat(2) {round->
                suspend fun measure(parallelRun:Boolean):Long {
                    val start=SystemClock.elapsedRealtime()
                    if(parallelRun)coroutineScope {
                        for(offset in 0 until 6 step count) {
                            (offset until minOf(6,offset+count)).map {i->async(Dispatchers.Default) {recognize(engines[i%count])}}.awaitAll()
                        }
                    } else withContext(Dispatchers.Default) {repeat(6) {recognize(engines[0])}}
                    return SystemClock.elapsedRealtime()-start
                }
                if(round==0) {serial+=measure(false);parallel+=measure(true)}
                else {parallel+=measure(true);serial+=measure(false)}
            }
            if(count>1)assertTrue("Independent recognizers should really overlap",peak.get()>1)
            File(app.getExternalFilesDir(null),"qa/ocr-073-performance.txt").apply {parentFile!!.mkdirs();writeText("Synthetic 1694x2400, 22 lines, English tessdata_fast\ncores=$cores workers=$count memoryClassMb=${manager.memoryClass} peak=${peak.get()}\nserial6PagesMs=$serial\nparallel6PagesMs=$parallel\nratio=${serial.average()/parallel.average()}\n")}
        } finally {
            engines.forEach {it.recycle()};bitmap.recycle()
            tessdata.listFiles()?.forEach {it.delete()};tessdata.delete();root.delete()
        }
        Unit
    }
}
