package io.readx.app.conversion

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class ConversionFiles(root: File, id: String) {
    val directory=File(root,id)
    init {require(id.matches(Regex("[a-f0-9]{64}")));directory.mkdirs()}
    data class Page(val index: Int, val html: String, val text: Boolean, val image: Boolean, val ocr: Boolean = false, val title: String = "") {
        fun json(): String = JSONObject().put("page",index).put("html",html).put("text",text).put("image",image).put("ocr",ocr).put("title",title).toString()
    }
    fun load(index: Int): Page? = try {
        val file=File(directory,"page-$index.json")
        if(!file.isFile || file.length()>8L*1024*1024) null else {
            val o=JSONObject(file.readText());require(o.getInt("page")==index)
            if(o.getBoolean("image") && !image(index).isFile) null else Page(index,o.getString("html"),o.getBoolean("text"),o.getBoolean("image"),o.optBoolean("ocr"),o.optString("title"))
        }
    } catch(_: Exception) {null}
    fun save(page: Page) {atomic(File(directory,"page-${page.index}.json"),page.json().toByteArray())}
    fun cleanupTemporary() {directory.listFiles()?.filter {it.isFile && it.name.startsWith("write-") && it.extension=="tmp"}?.forEach {it.delete()}}
    fun image(index: Int)=File(directory,"p$index.jpg")
    fun atomic(file: File, bytes: ByteArray) {
        require(bytes.size<=8*1024*1024) { "章节超过转换限额" }
        val tmp=File.createTempFile("write-",".tmp",directory)
        try {tmp.writeBytes(bytes);Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)} finally {tmp.delete()}
    }
    fun cleanup() { directory.listFiles()?.filter {it.isFile}?.forEach {it.delete()};directory.delete() }
}
