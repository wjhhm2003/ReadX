package io.readx.app.conversion

import android.content.Context
import androidx.work.*
import io.readx.app.ReadXApplication
import io.readx.app.ui.ReaderPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** The sole network entry: fixed, distributable model artifacts, never book data. */
object OcrDownloadPolicy {
    internal val activationLock=Any()
    const val TAG="ocr-model-download"
    const val NAME="readx-model-download"
    const val COMMIT="65727574dfcd264acbb0c3e07860e4e9e9b22185"
    fun generation(context:Context)=context.getSharedPreferences("reader-settings",Context.MODE_PRIVATE).getLong("downloadGeneration",0)
    fun invalidate(context:Context) {synchronized(activationLock) {val p=context.getSharedPreferences("reader-settings",Context.MODE_PRIVATE);p.edit().putLong("downloadGeneration",p.getLong("downloadGeneration",0)+1).apply()}}
    fun allowed(context:Context,generation:Long)=context.getSharedPreferences("reader-settings",Context.MODE_PRIVATE).getBoolean("onlineModels",false) && generation(context)==generation
    fun url(name:String):URL {require(name in OcrModelManager.SUPPORTED);return URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/$COMMIT/$name.traineddata")}
    fun enqueue(context:Context,languages:String):java.util.UUID {
        check(ReaderPreferences(context).settings.value.onlineModels) {"在线下载已关闭"}
        val names=languages.split('+');require(names.isNotEmpty() && names.all {it in OcrModelManager.SUPPORTED})
        val request=OneTimeWorkRequestBuilder<OcrModelDownloadWorker>().setInputData(workDataOf("models" to names.toTypedArray(),"generation" to generation(context))).addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME,ExistingWorkPolicy.KEEP,request)
        return request.id
    }
    fun cancel(context:Context) {invalidate(context);WorkManager.getInstance(context).cancelAllWorkByTag(TAG)}
}

class OcrModelDownloadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val generation=inputData.getLong("generation",-1)
        val names=inputData.getStringArray("models")?.toList().orEmpty()
        if(names.isEmpty() || names.any {it !in OcrModelManager.SUPPORTED})return@withContext Result.failure(workDataOf("error" to "模型清单无效"))
        val manifest=applicationContext.assets.open("ocr-download-manifest.json").reader().use {JSONObject(it.readText())}
        check(manifest.getString("commit")==OcrDownloadPolicy.COMMIT)
        val models=manifest.getJSONObject("models")
        val total=names.sumOf {models.getJSONObject(it).getLong("bytes")}
        val app=applicationContext as ReadXApplication
        val directory=File(applicationContext.cacheDir,"ocr-downloads/$id").apply {mkdirs()}
        var completed=0L
        suspend fun gate() {ensureActive();if(!OcrDownloadPolicy.allowed(applicationContext,generation) || isStopped)throw CancellationException("在线下载已关闭")}
        try {
            gate()
            for(name in names) {
                val expected=models.getJSONObject(name);val bytes=expected.getLong("bytes");val hash=expected.getString("sha256")
                require(bytes in 1..64L*1024*1024 && hash.matches(Regex("[0-9a-f]{64}")))
                if(app.ocrModels.models.value[name]==hash && app.ocrModels.model(name,hash).isFile) {completed+=bytes;continue}
                gate();val connection=OcrDownloadPolicy.url(name).openConnection() as HttpsURLConnection
                // Only user-initiated work. Do not gate on OS internet validation: a partially
                // validated network may still reach the pinned host. Offline attempts fail explicitly.
                val closeWatcher=launch(Dispatchers.IO) {
                    try {ReaderPreferences(applicationContext).settings.filter {!OcrDownloadPolicy.allowed(applicationContext,generation)}.first()}
                    finally {connection.disconnect()}
                }
                val file=File(directory,"$name.traineddata")
                try {
                    connection.connectTimeout=15000;connection.readTimeout=5000;connection.instanceFollowRedirects=false
                    connection.setRequestProperty("Accept","application/octet-stream")
                    require(connection.responseCode==200) {"下载服务不可用（HTTP ${connection.responseCode}），可重试或本地导入"}
                    val declared=connection.contentLengthLong;require(declared<=0 || declared==bytes) {"模型大小与固定清单不符"}
                    val digest=MessageDigest.getInstance("SHA-256");var copied=0L;var reported=0L
                    connection.inputStream.use {input->file.outputStream().buffered().use {out->
                        val buffer=ByteArray(32768)
                        while(true) {
                            gate();val n=input.read(buffer);if(n<0)break;copied+=n;require(copied<=bytes) {"模型大小超出固定清单"};digest.update(buffer,0,n);out.write(buffer,0,n)
                            if(copied-reported>=131072 || copied==bytes) {setProgress(workDataOf("bytes" to completed+copied,"total" to total));reported=copied}
                        }
                    }}
                    require(copied==bytes && digest.digest().joinToString("") {"%02x".format(it)}==hash) {"模型 SHA-256 校验失败，未启用下载文件"}
                    gate();app.ocrModels.import(android.net.Uri.fromFile(file)) {OcrDownloadPolicy.allowed(applicationContext,generation) && !isStopped}
                    completed+=copied;setProgress(workDataOf("bytes" to completed,"total" to total))
                } finally {withContext(NonCancellable) {closeWatcher.cancelAndJoin();connection.disconnect();file.delete()}}
            }
            Result.success(workDataOf("bytes" to completed,"total" to total))
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {Result.failure(workDataOf("error" to when(e) {
            is java.net.SocketTimeoutException->"网络超时，临时文件已清理；可重新下载或本地导入"
            is java.io.IOException->"下载连接中断，临时文件已清理；可重新下载或本地导入"
            is IllegalArgumentException->e.message?.take(160).orEmpty()
            else->"模型下载或初始化失败，未启用该文件；可重试或本地导入"
        }))}
        finally {directory.listFiles().orEmpty().filter {it.isFile}.forEach {it.delete()};directory.delete()}
    }
}
