package io.readx.app.conversion

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

class OcrModelManager(private val context: Context) {
    private val preferences = context.getSharedPreferences("ocr-models", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "ocr-models")
    private val lock = Mutex()
    private val mutableModels = MutableStateFlow(read())
    val models = mutableModels.asStateFlow()
    private fun read() = SUPPORTED.mapNotNull { name -> preferences.getString(name, null)?.let { name to it } }.toMap()
    internal fun reload() { mutableModels.value=read() }
    fun snapshot(languages: String): PdfConversionOptions = PdfConversionOptions(languages, models.value.filterKeys { it in languages.split('+') })
    fun model(name: String, hash: String): File {
        require(name in SUPPORTED && hash.matches(Regex("[a-f0-9]{64}")))
        return File(root, "$name/$hash/tessdata/$name.traineddata")
    }
    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) { lock.withLock {
        val displayName = if(uri.scheme == "content") context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null } else uri.lastPathSegment
        val name = displayName.orEmpty().removeSuffix(".traineddata")
        require(name in SUPPORTED && displayName == "$name.traineddata") { "请选择 chi_sim、eng 或 chi_tra.traineddata 模型" }
        root.mkdirs()
        val temporary = File.createTempFile("model-", ".tmp", root)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(65536); var bytes = 0L
                while(true) { ensureActive(); val n=input.read(buffer); if(n<0) break; bytes+=n; require(bytes<=64L*1024*1024) { "模型超过 64 MiB" }; digest.update(buffer,0,n);output.write(buffer,0,n) }
                require(bytes>0) { "模型为空" }
            } } ?: error("模型无法读取")
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = model(name,hash)
            destination.parentFile!!.mkdirs()
            if(!destination.exists()) require(temporary.renameTo(destination)) { "模型保存失败" }
            val api = TessBaseAPI()
            try { require(api.init(destination.parentFile!!.parentFile!!.absolutePath, name, TessBaseAPI.OEM_LSTM_ONLY)) { "模型不兼容或已损坏" } }
            catch(e: Exception) { if(models.value[name]!=hash) destination.delete();throw e }
            finally { api.recycle() }
            ensureActive()
            preferences.edit().putString(name,hash).commit()
            mutableModels.value=read()
        } finally { temporary.delete() }
    } }
    internal suspend fun pruneUnusedVersions() = withContext(Dispatchers.IO) { lock.withLock {
        val tasks=(context.applicationContext as io.readx.app.ReadXApplication).database.conversions().observeAll().first()
        val retained=mutableSetOf<String>().apply {addAll(models.value.values)}
        tasks.filter {it.active}.forEach {task->runCatching {retained.addAll(PdfConversionOptions.parse(task.optionsJson).models.values)}}
        for(name in SUPPORTED) File(root,name).listFiles()?.filter {it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}")) && it.name !in retained}?.forEach {version->
            val data=File(version,"tessdata")
            data.listFiles()?.filter {it.isFile && it.extension=="traineddata"}?.forEach {it.delete()}
            data.delete();version.delete()
        }
    } }
    companion object { val SUPPORTED = listOf("chi_sim", "eng", "chi_tra") }
}
