package io.readx.app.conversion

import android.content.Context
import android.net.Uri
import androidx.work.*
import io.readx.app.data.LibraryDatabase
import io.readx.app.data.LibraryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PdfConversionRepository(private val context: Context, private val db: LibraryDatabase, private val library: LibraryRepository, val models: OcrModelManager) {
    val dao = db.conversions()
    val root = File(context.filesDir,"pdf-conversions").apply {mkdirs()}
    fun observe(id: String) = dao.observe(id)
    fun observeAll() = dao.observeAll()
    suspend fun start(sourceBookId: String, options: PdfConversionOptions = models.snapshot("chi_sim+eng")): String = withContext(Dispatchers.IO) {
        val source=library.dao.book(sourceBookId) ?: error("原书已移除")
        require(source.format=="PDF")
        val id=digest(source.fingerprint+":"+options.key())
        dao.insert(PdfConversion(id,source.id,source.fingerprint,options.key(),options.json()))
        val task=dao.get(id)!!
        if(task.stage=="COMPLETE" && task.resultBookId?.let {library.dao.book(it)}!=null) return@withContext id
        enqueue(id)
        id
    }
    private suspend fun enqueue(id: String) = enqueueLock.withLock {
        val task=dao.get(id) ?: return@withLock
        val manager=WorkManager.getInstance(context)
        if(manager.getWorkInfosForUniqueWork("pdf-conversion-$id").get().any { !it.state.isFinished }) return@withLock
        if(!task.active) dao.progress(id,"QUEUED",task.completedPages,task.totalPages,task.imagePages)
        val request=OneTimeWorkRequestBuilder<PdfConversionWorker>().setInputData(workDataOf("conversionId" to id)).addTag("pdf-conversion").build()
        dao.setRun(id,request.id.toString())
        manager.enqueueUniqueWork("pdf-conversion-$id",ExistingWorkPolicy.KEEP,request).result.get()
    }
    suspend fun resume(id: String): String = withContext(Dispatchers.IO) {
        val task=dao.get(id) ?: error("任务不存在")
        val old=PdfConversionOptions.parse(task.optionsJson)
        val latest=models.snapshot(old.languages)
        if(latest==old) {enqueue(id);return@withContext id}
        this@PdfConversionRepository.cancel(id)
        val source=task.sourceBookId ?: error("原书已移除")
        val book=library.dao.book(source) ?: error("原书已移除")
        val newId=digest(book.fingerprint+":"+latest.key())
        dao.insert(PdfConversion(newId,source,book.fingerprint,latest.key(),latest.json()))
        lease(source).withLock {
            val oldFiles=ConversionFiles(root,id);val newFiles=ConversionFiles(root,newId)
            for(index in 0 until task.completedPages) {
                ensureActive();val page=oldFiles.load(index) ?: break
                if(page.ocr) break
                if(page.image || index==0) oldFiles.image(index).takeIf {it.isFile}?.copyTo(newFiles.image(index),overwrite=true)
                newFiles.save(page)
            }
        }
        enqueue(newId);newId
    }
    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(id.hashCode() xor 0x40000000)
        WorkManager.getInstance(context).cancelUniqueWork("pdf-conversion-$id").result.get()
        dao.get(id)?.takeIf {it.stage!="COMPLETE"}?.let {dao.progress(id,"CANCELLED",it.completedPages,it.totalPages,it.imagePages)}
    }
    suspend fun stopSource(sourceId: String) = withContext(Dispatchers.IO) {
        val tasks=dao.forSource(sourceId)
        tasks.forEach {this@PdfConversionRepository.cancel(it.id)}
        lease(sourceId).withLock {tasks.forEach {ConversionFiles(root,it.id).cleanup()}}
    }
    suspend fun export(resultBookId: String, destinationUri: Uri) = withContext(Dispatchers.IO) {
        require(dao.sourceOf(resultBookId)!=null) { "不是转换版 EPUB" }
        val book=library.dao.book(resultBookId) ?: error("书籍已移除")
        require(book.format=="EPUB")
        context.contentResolver.openOutputStream(destinationUri,"wt")?.use {out->library.source(book).inputStream().use {input->
            val buffer=ByteArray(65536)
            while(true) {ensureActive();val n=input.read(buffer);if(n<0) break;out.write(buffer,0,n)}
        }} ?: error("无法保存 EPUB")
    }
    companion object {
        private val enqueueLock=Mutex()
        internal val serial=Mutex()
        private val leases=ConcurrentHashMap<String,Mutex>()
        internal fun lease(source: String) = leases.getOrPut(source) {Mutex()}
    }
}
