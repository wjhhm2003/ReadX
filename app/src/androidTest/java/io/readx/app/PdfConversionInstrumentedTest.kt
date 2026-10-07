package io.readx.app

import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.readx.app.conversion.*
import io.readx.app.data.Book
import io.readx.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class PdfConversionInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val app get()=ApplicationProvider.getApplicationContext<ReadXApplication>()

    @Before fun cleanOnlyFailedGeneratedFixtures() = runBlocking {
        val books=app.repository.dao.observeBooks().first()
        for(book in books.filter {it.format=="PDF" && it.sourceName.startsWith("转换验收-")}) {
            val tasks=app.conversions.dao.forSource(book.id)
            for(task in tasks) task.resultBookId?.let {app.repository.dao.book(it)}?.let {app.repository.delete(it)}
            app.repository.delete(book)
            tasks.forEach {app.conversions.dao.discard(it.id)}
        }
    }

    @Test fun textPdfBackgroundConversionDedupExportAndOriginalDeletion() {
        withHost {scenario->
            val source=generatedPdf(false,3)
            val book=runBlocking {app.repository.import(Uri.fromFile(source))}
            var conversion:String?=null;var result:Book?=null
            try {
                val id=runBlocking {app.conversions.start(book.id)};conversion=id
                assertEquals(id,runBlocking {app.conversions.start(book.id)})
                val task=waitTask(id) {it.stage in listOf("COMPLETE","FAILED","WAITING_MODEL")}
                assertEquals("COMPLETE",task.stage);assertEquals(3,task.completedPages)
                result=runBlocking {app.repository.dao.book(task.resultBookId!!) }!!
                assertTrue(result!!.title.endsWith("（转换版）"));assertEquals("EPUB",result!!.format)
                assertNotEquals(book.id,result!!.id)
                val chapters=runBlocking {app.repository.dao.chapters(result!!.id)}
                val text=runBlocking {app.repository.dao.textChunk(result!!.id,chapters.first().ordinal,0,200000)}.orEmpty()
                assertTrue("English text layer changed",text.contains("ReadX conversion paragraph"));assertTrue("Chinese text layer changed",text.contains("原始正文中文测试"))
                val output=File(app.cacheDir,"export-${System.nanoTime()}.epub")
                try {
                    runBlocking {app.conversions.export(result!!.id,Uri.fromFile(output))}
                    ZipFile(output).use {assertNotNull(it.getEntry("OEBPS/content.opf"));assertEquals("mimetype",it.entries().nextElement().name)}
                    assertEquals(result!!.id,runBlocking {app.repository.import(Uri.fromFile(output))}.id)
                } finally {output.delete()}
                lateinit var model:LibraryViewModel
                scenario.onActivity {model=ViewModelProvider(it)[LibraryViewModel::class.java];model.open(result!!)}
                compose.waitUntil(20000) {compose.onAllNodesWithTag("reader-content").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
                scenario.onActivity {model.search("conversion paragraph",result!!.id)}
                compose.waitUntil(10000) {!model.searching.value && model.hits.value.isNotEmpty()}
                runBlocking {app.repository.delete(book)}
                assertNotNull(runBlocking {app.repository.dao.book(result!!.id)})
                assertNull(runBlocking {app.conversions.dao.get(id)}!!.sourceBookId)
                assertTrue(source.isFile)
            } finally {
                result?.let {runBlocking {app.repository.delete(it)}}
                runBlocking {if(app.repository.dao.book(book.id)!=null) app.repository.delete(book);conversion?.let {app.conversions.dao.discard(it)}}
                source.delete()
            }
        }
    }

    @Test fun scannedMixedPagesWaitForModelsResumeAndOcrOffline() {
        val modelPreferences=app.getSharedPreferences("ocr-models",0)
        val previous=modelPreferences.all.toMap()
        val imported=mutableListOf<File>();val ids=mutableListOf<String>();var result:Book?=null
        val source=generatedPdf(true,4)
        val book=runBlocking {app.repository.import(Uri.fromFile(source))}
        try {
            modelPreferences.edit().clear().commit();app.ocrModels.reload()
            withHost {
                val first=runBlocking {app.conversions.start(book.id)};ids+=first
                val waiting=waitTask(first) {it.stage in listOf("WAITING_MODEL","FAILED")}
                assertEquals("WAITING_MODEL",waiting.stage);assertTrue(waiting.error.contains("模型"))
                val holder=File(app.filesDir,"books/${UUID.randomUUID()}").apply {mkdirs()}
                try {
                    for(name in listOf("chi_sim","eng")) {
                        val input=File(app.getExternalFilesDir(null),"qa-models/$name.traineddata")
                        assumeTrue("Official OCR test model not supplied",input.isFile)
                        val file=File(holder,"$name.traineddata");input.copyTo(file)
                        runBlocking {app.ocrModels.import(FileProvider.getUriForFile(app,app.packageName+".files",file))}
                        imported+=app.ocrModels.model(name,app.ocrModels.models.value.getValue(name))
                    }
                    val resumed=runBlocking {app.conversions.resume(first)};ids+=resumed
                    assertNotEquals(first,resumed)
                    val complete=waitTask(resumed,90000) {it.stage in listOf("COMPLETE","FAILED","WAITING_MODEL")}
                    assertEquals("COMPLETE",complete.stage);assertEquals(4,complete.completedPages)
                    result=runBlocking {app.repository.dao.book(complete.resultBookId!!)}!!
                    val chapters=runBlocking {app.repository.dao.chapters(result!!.id)}
                    val text=runBlocking {app.repository.dao.textChunk(result!!.id,chapters.first().ordinal,0,200000)}.orEmpty()
                    assertTrue("Must actually recognise the image, not just create an image-only EPUB",text.contains("OFFLINE",true))
                    assertTrue("Chinese OCR should produce actual Han text",text.contains("这是离线识别的中文"))
                    assertEquals(resumed,runBlocking {app.conversions.start(book.id)} )
                } finally {holder.listFiles()?.forEach {it.delete()};holder.delete()}
            }
        } finally {
            runBlocking {ids.forEach {app.conversions.cancel(it)};result?.let {app.repository.delete(it)};app.repository.delete(book);ids.distinct().forEach {app.conversions.dao.discard(it)}}
            source.delete()
            modelPreferences.edit().clear().apply {previous.forEach {(k,v)->putString(k,v as String)}}.commit();app.ocrModels.reload()
            imported.filter {it.parentFile!!.parentFile!!.name !in previous.values}.forEach {it.delete();it.parentFile!!.delete();it.parentFile!!.parentFile!!.delete()}
        }
    }

    @Test fun damagedModelDoesNotReplaceWorkingPreferencesAndCancellationCanResume() {
        val prefs=app.getSharedPreferences("ocr-models",0);val prior=prefs.all.toMap()
        val holder=File(app.filesDir,"books/${UUID.randomUUID()}").apply {mkdirs()}
        try {
            val invalid=File(holder,"eng.traineddata").apply {writeBytes(ByteArray(16))}
            var failed=false
            runBlocking {try {app.ocrModels.import(FileProvider.getUriForFile(app,app.packageName+".files",invalid))} catch(_: Exception) {failed=true}}
            assertTrue(failed);assertEquals(prior,prefs.all)
        } finally {holder.listFiles()?.forEach {it.delete()};holder.delete()}
        withHost {scenario->
            val source=generatedPdf(false,24);val book=runBlocking {app.repository.import(Uri.fromFile(source))};var taskId:String?=null;var result:Book?=null
            try {
                val id=runBlocking {app.conversions.start(book.id)};taskId=id
                waitTask(id) {it.completedPages>=1}
                runBlocking {app.conversions.cancel(id)}
                val resumed=runBlocking {app.conversions.resume(id)}
                assertEquals(id,resumed)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                val task=waitTask(id,90000) {it.stage in listOf("COMPLETE","FAILED")}
                assertEquals("COMPLETE",task.stage);result=runBlocking {app.repository.dao.book(task.resultBookId!!)}
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            } finally {runBlocking {result?.let {app.repository.delete(it)};app.repository.delete(book);taskId?.let {app.conversions.dao.discard(it)}};source.delete()}
        }
    }

    @Test fun globalSwitchPersistsAndStartsOnlyWhenPdfIsOpened() {
        val preferences=ReaderPreferences(app);val old=preferences.settings.value
        val source=generatedPdf(false,1);val book=runBlocking {app.repository.import(Uri.fromFile(source))};var taskId:String?=null;var result:Book?=null
        try {
            preferences.update(old.copy(pdfToEpubEnabled=false))
            withHost {scenario->
                compose.onNode(hasText("设置") and hasClickAction()).performClick()
                compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("pdf-to-epub-switch"))
                compose.onNodeWithTag("pdf-to-epub-switch").performClick()
                assertTrue(ReaderPreferences(app).settings.value.pdfToEpubEnabled)
                assertTrue(runBlocking {app.conversions.dao.forSource(book.id)}.isEmpty())
                compose.onNode(hasText("书库") and hasClickAction()).performClick()
                compose.waitUntil(10000) {compose.onAllNodesWithText(book.title).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
                compose.onAllNodesWithText(book.title).onLast().performClick()
                compose.waitUntil(10000) {runBlocking {app.conversions.dao.forSource(book.id)}.isNotEmpty()}
                taskId=runBlocking {app.conversions.dao.forSource(book.id).single().id}
                val task=waitTask(taskId!!){it.stage in listOf("COMPLETE","FAILED")};assertEquals("COMPLETE",task.stage)
                result=runBlocking {app.repository.dao.book(task.resultBookId!!)}
                compose.waitUntil(20000) {compose.onAllNodesWithTag("reader-content").fetchSemanticsNodes().isNotEmpty()}
                scenario.onActivity {ViewModelProvider(it)[LibraryViewModel::class.java].close()}
            }
        } finally {
            preferences.update(old);runBlocking {result?.let {app.repository.delete(it)};app.repository.delete(book);taskId?.let {app.conversions.dao.discard(it)}};source.delete()}
    }

    @Test fun malformedProtectedAndFigureOnlyPdfsNeverPublishPartialBooks() {
        withHost {
            val cases=mutableListOf<File>()
            val malformed=File(app.cacheDir,"转换验收-malformed-${System.nanoTime()}.pdf").apply {writeText("%PDF-1.7 invalid document")};cases+=malformed
            val protected=File(app.cacheDir,"转换验收-password-${System.nanoTime()}.pdf")
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(app)
            com.tom_roush.pdfbox.pdmodel.PDDocument().use {document->
                document.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
                val permissions=com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission()
                document.protect(com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy("generated-owner","generated-password",permissions))
                document.save(protected)
            };cases+=protected
            val figure=File(app.cacheDir,"转换验收-figure-${System.nanoTime()}.pdf")
            val pdf=PdfDocument();val page=pdf.startPage(PdfDocument.PageInfo.Builder(600,850,1).create())
            page.canvas.drawRect(100f,100f,400f,400f,Paint().apply {color=Color.BLUE});pdf.finishPage(page)
            figure.outputStream().use {pdf.writeTo(it)};pdf.close();cases+=figure
            for(file in cases) {
                val book=runBlocking {app.repository.import(Uri.fromFile(file))};var conversion:String?=null
                try {
                    val options=if(file==figure) PdfConversionOptions("eng",app.ocrModels.models.value.filterKeys {it=="eng"}) else PdfConversionOptions()
                    // Ordinary CI has no OCR model. Test the genuine WAITING_MODEL state, without downloading or skipping.
                    val actual=options
                    val id=runBlocking {app.conversions.start(book.id,actual)};conversion=id
                    val task=waitTask(id) {it.stage in listOf("FAILED","COMPLETE","WAITING_MODEL")}
                    assertEquals(if(file==figure && actual.models.isEmpty()) "WAITING_MODEL" else "FAILED",task.stage);assertNull(task.resultBookId)
                    assertTrue(runBlocking {app.repository.dao.chapters(book.id)}.isEmpty())
                    if(file==protected) assertTrue(task.error.contains("密码"))
                    if(file==figure && actual.models.isNotEmpty()) assertTrue(task.error.contains("正文"))
                } finally {runBlocking {app.repository.delete(book);conversion?.let {app.conversions.dao.discard(it)}};file.delete()}
            }
        }
    }

    @Test fun suppliedPdfConvertsAndExportsWithoutLoggingItsContents() {
        val input=File(app.getExternalFilesDir(null),"qa-input/local-acceptance.pdf")
        assumeTrue("Optional local PDF not supplied",input.isFile)
        val priorBooks=runBlocking {app.repository.dao.observeBooks().first().map {it.id}.toSet()}
        val book=runBlocking {app.repository.import(Uri.fromFile(input))}
        assumeTrue("Do not change an existing library book",book.id !in priorBooks)
        val modelPreferences=app.getSharedPreferences("ocr-models",0);val oldModels=modelPreferences.all.toMap()
        val holder=File(app.filesDir,"books/${UUID.randomUUID()}").apply {mkdirs()}
        var conversion:String?=null;var result:Book?=null
        try {
            for(name in listOf("chi_sim","eng")) {
                val model=File(app.getExternalFilesDir(null),"qa-models/$name.traineddata")
                assumeTrue("Official test model missing",model.isFile)
                model.copyTo(File(holder,"$name.traineddata"))
                runBlocking {app.ocrModels.import(FileProvider.getUriForFile(app,app.packageName+".files",File(holder,"$name.traineddata")))}
            }
            withHost {scenario->
                val start=SystemClock.elapsedRealtime()
                val id=runBlocking {app.conversions.start(book.id)};conversion=id
                var previous=-1;var lastProgress=start
                var task:PdfConversion?=null
                compose.waitUntil(480000) {
                    task=runBlocking {app.conversions.dao.get(id)}
                    if(task!!.completedPages!=previous) {previous=task!!.completedPages;lastProgress=SystemClock.elapsedRealtime()}
                    check(SystemClock.elapsedRealtime()-lastProgress<45000 || task!!.stage in listOf("PACKAGING","IMPORTING")) {"Conversion stopped making real page progress"}
                    task!!.stage in listOf("COMPLETE","FAILED","WAITING_MODEL")
                }
                assertEquals("COMPLETE",task!!.stage);assertEquals(152,task!!.totalPages)
                result=runBlocking {app.repository.dao.book(task!!.resultBookId!!)}!!
                val exported=File(app.getExternalFilesDir(null),"qa/local-converted.epub")
                runBlocking {app.conversions.export(result!!.id,Uri.fromFile(exported))}
                assertTrue(exported.length()>0)
                lateinit var model:LibraryViewModel
                scenario.onActivity {model=ViewModelProvider(it)[LibraryViewModel::class.java];model.open(result!!)}
                compose.waitUntil(30000) {var ready=false;scenario.onActivity {ready=findWeb(it.window.decorView)?.let {!it.restoring && it.alpha>.99f}==true};ready}
                compose.onNodeWithTag("reader-content").performTouchInput {click(center)}
                compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("进度").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithContentDescription("进度").performClick()
                compose.onNodeWithText("查看原 PDF").assertIsDisplayed();compose.onNodeWithText("导出 EPUB").assertIsDisplayed()
                android.util.Log.i("ReadXLocalConversion","pages=${task!!.totalPages} image_pages=${task!!.imagePages} elapsed_ms=${SystemClock.elapsedRealtime()-start} epub_bytes=${exported.length()}")
            }
        } finally {
            runBlocking {result?.let {app.repository.delete(it)};app.repository.delete(book);conversion?.let {app.conversions.dao.discard(it)}}
            modelPreferences.edit().clear().apply {oldModels.forEach {(k,v)->putString(k,v as String)}}.commit();app.ocrModels.reload()
            holder.listFiles()?.forEach {it.delete()};holder.delete();input.delete()
        }
    }
    private fun findWeb(view:android.view.View):io.readx.app.reader.LocalWebReader? {
        if(view is io.readx.app.reader.LocalWebReader && view.isEnabled) return view
        if(view is android.view.ViewGroup) for(i in 0 until view.childCount) findWeb(view.getChildAt(i))?.let {return it}
        return null
    }

    private fun waitTask(id: String, timeout:Long=60000, predicate:(PdfConversion)->Boolean): PdfConversion {
        var row:PdfConversion?=null
        compose.waitUntil(timeout) {row=runBlocking {app.conversions.dao.get(id)};row?.let(predicate)==true}
        return row!!
    }
    private fun withHost(action:(ActivityScenario<MainActivity>)->Unit) {
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {scenario->
            compose.waitUntil(15000) {runCatching {compose.onAllNodesWithText("ReadX").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
            action(scenario)
        }
    }
    private fun generatedPdf(scan: Boolean, pages: Int): File {
        val file=File(app.cacheDir,"转换验收-${System.nanoTime()}.pdf")
        val pdf=PdfDocument()
        try {
            for(index in 0 until pages) {
                val page=pdf.startPage(PdfDocument.PageInfo.Builder(600,850,index+1).create())
                if(scan && index>0) {
                    val image=Bitmap.createBitmap(1200,1700,Bitmap.Config.ARGB_8888);val canvas=Canvas(image);canvas.drawColor(Color.WHITE)
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=42f}
                    for(line in 0..12) {canvas.drawText(if(line%2==0) "OFFLINE READER OCR EXAMPLE" else "这是离线识别的中文扫描测试。",80f,150f+line*95,paint)}
                    page.canvas.drawBitmap(image,null,Rect(0,0,600,850),null);image.recycle()
                } else {
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=15f}
                    for(line in 0..22) page.canvas.drawText("ReadX conversion paragraph $line page $index",45f,70f+line*25,paint)
                    page.canvas.drawText("原始正文中文测试，这是正常文本层。",45f,700f,paint)
                }
                pdf.finishPage(page)
            }
            file.outputStream().use {pdf.writeTo(it)}
        } finally {pdf.close()}
        return file
    }
}
