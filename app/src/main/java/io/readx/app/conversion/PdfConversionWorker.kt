package io.readx.app.conversion

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import io.readx.app.MainActivity
import io.readx.app.R
import io.readx.app.ReadXApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.sqrt

class PdfConversionWorker(context: Context, parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    private val app = context.applicationContext as ReadXApplication
    private val conversions get() = app.conversions
    private val conversionId get() = inputData.getString("conversionId") ?: error("任务参数缺失")
    private var processingStage="QUEUED"
    private var processingPage=0
    private var completed=0;private var total=0;private var imagePages=0
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val initial=conversions.dao.get(conversionId) ?: return@withContext Result.failure()
        if(initial.runId!=id.toString() || initial.stage=="CANCELLED") return@withContext Result.success()
        completed=initial.completedPages;total=initial.totalPages
        val sourceId=initial.sourceBookId ?: return@withContext Result.failure()
        try {
            setForeground(foreground("QUEUED"))
            PdfConversionRepository.serial.withLock { PdfConversionRepository.lease(sourceId).withLock {
                runConversion(sourceId,initial)
            } }
            Result.success()
        } catch(e: CancellationException) {
            withContext(NonCancellable) {conversions.dao.get(conversionId)?.takeIf {it.stage!="COMPLETE" && it.stage!="WAITING_MODEL"}?.let {conversions.dao.progressForRun(conversionId,id.toString(),"CANCELLED",completed,total,imagePages)}}
            throw e
        } catch(e: MissingOcrModels) {progress("WAITING_MODEL","需要导入 ${e.names.joinToString("、")} .traineddata 模型后继续");Result.success()}
        catch(_: ProtectedPdf) {progress("FAILED","密码 PDF 首版不转换，请使用原版阅读");Result.failure()}
        catch(_: InvalidPasswordException) {progress("FAILED","密码 PDF 首版不转换，请使用原版阅读");Result.failure()}
        catch(_: NoReadableBody) {progress("FAILED","未获得可重排正文，请检查 OCR 模型或使用原 PDF；未生成图片冒充的转换版");Result.failure()}
        catch(_: OutOfMemoryError) {progress("FAILED","转换内存不足，请使用原版阅读");Result.failure()}
        catch(e: Exception) {
            val where=if(e is OcrPageFailure) "原文第 ${e.page+1} 页（OCR）" else if(processingStage in listOf("EXTRACTING","OCR")) "原文第 ${(processingPage+1).coerceAtMost(total.coerceAtLeast(1))} 页" else when(processingStage) {"PACKAGING"->"EPUB 打包";"IMPORTING"->"书库导入";else->"文档准备"}
            val reason=when(if(e is OcrPageFailure)e.cause else e) {is java.io.IOException->"文件读取/写入失败，可能损坏或存储空间不足";is IllegalArgumentException->"页面数据无效或资源超出限额";is IllegalStateException->"页面处理或本地识别引擎失败";else->"当前格式处理失败"}
            progress("FAILED","$where：$reason。已完成页检查点保留，继续时只处理缺失或损坏页。")
            Result.failure()
        }
    }
    private suspend fun progress(stage: String, error: String = "") {
        if(stage!="FAILED")processingStage=stage
        if(conversions.dao.progressForRun(conversionId,id.toString(),stage,completed,total,imagePages,error)==0) throw CancellationException()
        if(stage in listOf("EXTRACTING","OCR","PACKAGING","IMPORTING")) setForeground(foreground(stage))
    }
    private fun foreground(stage: String): ForegroundInfo {
        val manager=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("pdf-conversion","PDF 转换进度",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(applicationContext,conversionId.hashCode(),Intent(applicationContext,MainActivity::class.java).putExtra("conversionId",conversionId),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(applicationContext,"pdf-conversion").setSmallIcon(R.drawable.ic_readx).setContentTitle("PDF 转为电子书").setContentText("${when(stage) {"QUEUED"->"排队中";"OCR"->"离线 OCR";"PACKAGING"->"生成 EPUB";"IMPORTING"->"导入书库";else->"提取文字"}} · $completed/$total 页").setOngoing(true).setContentIntent(open).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).addAction(0,"取消",WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return ForegroundInfo(conversionId.hashCode(),notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    private suspend fun runConversion(sourceId: String, initial: PdfConversion) {
        val source=app.repository.dao.book(sourceId) ?: error("来源已移除")
        val file=app.repository.source(source)
        val options=PdfConversionOptions.parse(initial.optionsJson)
        val files=ConversionFiles(conversions.root,conversionId)
        files.cleanupTemporary()
        val manager=applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory=ActivityManager.MemoryInfo().also {manager.getMemoryInfo(it)}
        val workerCount=OcrParallelism.workers(Runtime.getRuntime().availableProcessors(),manager.memoryClass,memory.availMem,memory.lowMemory,Int.MAX_VALUE)
        val engines=arrayOfNulls<TessBaseAPI>(workerCount)
        var modelSession:File?=null
        try {
            PDFBoxResourceLoader.init(applicationContext)
            PDDocument.load(file,MemoryUsageSetting.setupMixed(8L*1024*1024,256L*1024*1024)).use { document ->
                if(document.isEncrypted) throw ProtectedPdf()
                total=document.numberOfPages;app.repository.dao.saveTotalUnits(sourceId,total);require(total>0 && total<9900) {"页数超过转换限额"}
                val outlines=linkedMapOf<Int,String>()
                fun outline(node: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode, depth:Int=0) {
                    if(depth>16) return
                    for(item in node.children()) {runCatching {item.findDestinationPage(document)?.let {page->val index=document.pages.indexOf(page);if(index in 0 until total && item.title.isNotBlank()) outlines.putIfAbsent(index,item.title.take(200))}};outline(item,depth+1)}
                }
                document.documentCatalog.documentOutline?.let {outline(it)}
                ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->PdfRenderer(fd).use {renderer->
                    val extractor=PdfPageExtractor()
                    var stagedBytes=0L
                    // PDFBox / PdfRenderer remain confined to this producer. Only recognition runs
                    // concurrently, each slot owns its native API. Batches cap all resident page images.
                    coroutineScope {
                        for(batchStart in 0 until total step workerCount) {
                            val pending=mutableListOf<Pair<Int,Deferred<Pair<FlowPage,Boolean>>?>>()
                            for(index in batchStart until minOf(total,batchStart+workerCount)) {
                                ensureActive()
                                if(files.load(index)!=null) {pending+=index to null;continue}
                                processingPage=index
                                progress("EXTRACTING")
                                val extracted=extractor.extract(document,index)
                                val needsOcr=extracted.paragraphs.isEmpty() || extracted.paragraphs.joinToString("").count {it=='\uFFFD'}>5
                                if(!needsOcr) {pending+=index to CompletableDeferred(extracted to false);continue}
                                val missing=options.languages.split('+').filter {name->options.models[name]?.let {app.ocrModels.model(name,it).isFile}!=true}
                                if(missing.isNotEmpty())throw MissingOcrModels(missing)
                                if(modelSession==null) {
                                    modelSession=File(files.directory,"recognizer").apply {mkdirs()}
                                    File(modelSession,"tessdata").mkdirs()
                                    for(name in options.languages.split('+')) {
                                        ensureActive()
                                        app.ocrModels.model(name,options.models.getValue(name)).copyTo(File(modelSession,"tessdata/$name.traineddata"),overwrite=true)
                                    }
                                }
                                progress("OCR")
                                val bitmap=render(renderer,index)
                                val slot=index%workerCount
                                // Enter finally before dispatch so even cancellation before CPU execution
                                // recycles this producer-owned bitmap.
                                val result=async(start=CoroutineStart.UNDISPATCHED) {
                                    try {withContext(Dispatchers.Default) {
                                        ensureActive()
                                        val engine=engines[slot] ?: TessBaseAPI().also {api->
                                            try {
                                                require(api.init(modelSession!!.absolutePath,options.languages,TessBaseAPI.OEM_LSTM_ONLY))
                                                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                                                engines[slot]=api
                                            } catch(e:Throwable) {api.recycle();throw e}
                                        }
                                        try {
                                            engine.setImage(bitmap)
                                            var recognized=PdfPageExtractor.ocr(engine.getHOCRText(index),bitmap.width.toFloat())
                                            if(engine.meanConfidence()<40)recognized=recognized.copy(unreliable=true)
                                            ensureActive()
                                            recognized to true
                                        } finally {engine.clear()}
                                    }} catch(e:CancellationException) {throw e}
                                    catch(e:Exception) {throw OcrPageFailure(index,e)}
                                    finally {bitmap.recycle()}
                                }
                                pending+=index to result
                            }
                            // Persist in source-page order: completedPages remains a contiguous durable
                            // prefix, so resume and model-change checkpoint compatibility are unchanged.
                            for((index,result) in pending) {
                                ensureActive()
                                val cached=if(result==null)files.load(index) else null
                                if(cached!=null) {
                                    stagedBytes+=cached.html.toByteArray().size+files.image(index).takeIf {it.isFile}?.length().orZero()
                                    require(stagedBytes<=160L*1024*1024)
                                    completed=index+1;if(cached.image)imagePages++
                                    continue
                                }
                                processingPage=index
                                val (flow,usedOcr)=requireNotNull(result).await()
                                val original=flow.unreliable || flow.paragraphs.isEmpty()
                                if(original || index==0)saveImage(renderer,index,files.image(index))
                                val content=if(original) "<p>本页版式或识别结果不可靠，保留原页图像。</p><img src='images/p$index.jpg' alt='PDF 原文第 ${index+1} 页'/>" else flow.paragraphs.joinToString("") {"<p>${EpubOutput.escape(it)}</p>"}
                                files.save(ConversionFiles.Page(index,"<section data-source-page='$index' id='src-p-$index'>$content</section>",!original,original,usedOcr,if(!original)flow.paragraphs.firstOrNull {PdfTextFlow.isHeading(it)}.orEmpty() else ""))
                                stagedBytes+=content.toByteArray().size+files.image(index).takeIf {it.isFile}?.length().orZero()
                                require(stagedBytes<=160L*1024*1024) {"转换资源超限"}
                                completed=index+1;if(original)imagePages++
                                progress(if(usedOcr) "OCR" else "EXTRACTING")
                                yield()
                            }
                        }
                    }
                }}
                if(!(0 until total).any {files.load(it)?.text==true}) throw NoReadableBody()
                progress("PACKAGING")
                val chapters=mutableListOf<EpubOutput.Chapter>();var html=StringBuilder();var chapterTitle="正文";var chapterStart=0
                fun flush() {
                    if(html.isEmpty()) return
                    val name=File(files.directory,"chapter-${chapters.size}.xhtml")
                    val body=EpubOutput.chapterHtml(chapterTitle,html.toString())
                    files.atomic(name,body.toByteArray());chapters+=EpubOutput.Chapter(chapterTitle,name);html=StringBuilder()
                }
                for(index in 0 until total) {
                    currentCoroutineContext().ensureActive()
                    val page=files.load(index) ?: error("检查点损坏")
                    if(outlines[index]!=null || page.title.isNotEmpty() || html.length>250000 || index-chapterStart>=24) {
                        flush();chapterTitle=outlines[index] ?: page.title.takeIf {it.isNotBlank()} ?: "正文 ${chapters.size+1}";chapterStart=index
                    }
                    html.append(page.html)
                }
                flush()
                val images=files.directory.listFiles()?.filter {it.extension=="jpg"}.orEmpty().sortedBy {it.name}
                val output=File(files.directory,"result.epub")
                val running=currentCoroutineContext()
                EpubOutput.write(output,conversionId,source.title+"（转换版）",source.author,chapters,images,source.importedAt) {running.ensureActive();if(isStopped) throw CancellationException()}
                EpubOutput.validate(output)
                progress("IMPORTING")
                currentCoroutineContext().ensureActive()
                app.repository.publishConvertedEpub(output,conversionId,id.toString())
            }
            files.cleanup()
            val manager=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val open=PendingIntent.getActivity(applicationContext,conversionId.hashCode(),Intent(applicationContext,MainActivity::class.java).setData(android.net.Uri.parse("readx://conversion/$conversionId")).putExtra("conversionId",conversionId),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notice=NotificationCompat.Builder(applicationContext,"pdf-conversion").setSmallIcon(R.drawable.ic_readx).setContentTitle("PDF 转换完成").setContentText("独立 EPUB 已加入书库").setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
            if(androidx.core.content.ContextCompat.checkSelfPermission(applicationContext,android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT<33) manager.notify(conversionId.hashCode() xor 0x40000000,notice)
        } finally {
            engines.forEach {it?.recycle()}
            modelSession?.let {session->File(session,"tessdata").listFiles()?.filter {it.isFile}?.forEach {it.delete()};File(session,"tessdata").delete();session.delete()}
            if(conversions.dao.get(conversionId)?.stage=="COMPLETE") files.directory.delete()
            withContext(NonCancellable) {app.ocrModels.pruneUnusedVersions()}
        }
    }
    private fun render(renderer: PdfRenderer,index: Int): Bitmap = renderer.openPage(index).use {page->
        val scale=minOf(2400f/maxOf(page.width,page.height),sqrt(4_000_000f/(page.width.toFloat()*page.height)))
        val bitmap=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        try {bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);bitmap} catch(e: Exception) {bitmap.recycle();throw e}
    }
    private fun saveImage(renderer: PdfRenderer,index: Int,file: File) {
        val bitmap=render(renderer,index)
        try {file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,85,it)}} finally {bitmap.recycle()}
    }
}

private class OcrPageFailure(val page:Int,cause:Exception):Exception(cause)

private class MissingOcrModels(val names:List<String>):Exception()

private class ProtectedPdf : Exception()

private class NoReadableBody : Exception()

private fun Long?.orZero(): Long = this ?: 0L
