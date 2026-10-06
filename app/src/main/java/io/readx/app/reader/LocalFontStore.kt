package io.readx.app.reader

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Private, content-addressed local fonts. No arbitrary font paths or book-controlled CSS URL. */
data class LocalFont(val id:String,val name:String)
class LocalFontStore(private val context:Context) {
    private val directory=File(context.filesDir,"reader-fonts")
    private val prefs=context.getSharedPreferences("font-names",Context.MODE_PRIVATE)
    suspend fun list():List<LocalFont> = withContext(Dispatchers.IO) {directory.listFiles().orEmpty().filter {it.name.matches(Regex("[0-9a-f]{64}\\.font"))}.map {LocalFont(it.nameWithoutExtension,prefs.getString(it.nameWithoutExtension,"本地字体").orEmpty())}.sortedBy {it.name}}
    suspend fun import(uri:Uri):LocalFont = withContext(Dispatchers.IO) {
        val name=(if(uri.scheme=="content") context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())it.getString(0) else null} else uri.lastPathSegment)?.take(120) ?: "本地字体"
        directory.mkdirs();val temp=File.createTempFile("font-",".tmp",directory)
        try {
            val hash=MessageDigest.getInstance("SHA-256");var bytes=0L
            context.contentResolver.openInputStream(uri)?.use {input->temp.outputStream().buffered().use {out->
                val buffer=ByteArray(32768);while(true) {ensureActive();val n=input.read(buffer);if(n<0)break;bytes+=n;require(bytes<=16*1024*1024) {"字体文件最多 16 MiB"};hash.update(buffer,0,n);out.write(buffer,0,n)}
            }} ?: error("无法读取字体")
            validate(temp)
            val id=hash.digest().joinToString("") {"%02x".format(it)}
            val face=Typeface.createFromFile(temp);require(face!=Typeface.DEFAULT) {"字体文件无效或当前系统不支持"}
            val target=File(directory,"$id.font")
            require(target.exists() || list().size<12) {"最多保留12个本地字体"}
            ensureActive()
            if(!target.exists()) Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE)
            val label=prefs.getString(id,null) ?: name
            prefs.edit().putString(id,label).apply();faces[id]=face;LocalFont(id,label)
        } finally {temp.delete()}
    }
    suspend fun delete(id:String):Boolean = withContext(Dispatchers.IO) {
        if(!id.matches(Regex("[0-9a-f]{64}"))) return@withContext false
        val target=File(directory,"$id.font")
        val deleted=target.delete()
        prefs.edit().remove(id).apply()
        faces.remove(id)
        deleted
    }
    companion object {
        private val faces=ConcurrentHashMap<String,Typeface>()
        fun file(context:Context,id:String):File?=id.takeIf {it.matches(Regex("[0-9a-f]{64}"))}?.let {File(context.filesDir,"reader-fonts/$it.font").takeIf(File::isFile)}
        fun typeface(id:String):Typeface?=faces[id]
        fun response(context:Context,path:String):android.webkit.WebResourceResponse? {
            if(!path.startsWith("/reader-fonts/"))return null
            val id=path.removePrefix("/reader-fonts/").removeSuffix(".font")
            val font=if(path=="/reader-fonts/$id.font")file(context,id) else null
            return if(font!=null)android.webkit.WebResourceResponse("font/otf",null,font.inputStream()) else android.webkit.WebResourceResponse("text/plain","UTF-8",404,"Not Found",emptyMap(),java.io.ByteArrayInputStream(byteArrayOf()))
        }
        suspend fun prepare(context:Context,id:String?):Boolean {
            if(id==null || faces.containsKey(id))return true
            return withContext(Dispatchers.IO) {
                val source=file(context,id) ?: return@withContext false
                try {validate(source);faces[id]=Typeface.createFromFile(source);true}
                catch(e:CancellationException) {throw e} catch(_:Exception) {false}
            }
        }
        fun validate(file:File) {
            RandomAccessFile(file,"r").use {f->
                require(f.length() in 12..16L*1024*1024) {"字体长度无效"}
                require(f.readInt() in listOf(0x00010000,0x4F54544F)) {"仅支持独立 TTF / OTF 字体（不支持 TTC）"}
                val count=f.readUnsignedShort();require(count in 1..4096 && 12L+count*16L<=f.length()) {"字体表损坏"};f.seek(12)
                repeat(count) {f.readInt();f.readInt();val start=f.readInt().toLong() and 0xffffffffL;val size=f.readInt().toLong() and 0xffffffffL;require(start<=f.length() && size<=f.length()-start) {"字体表越界"}}
            }
        }
    }
}
