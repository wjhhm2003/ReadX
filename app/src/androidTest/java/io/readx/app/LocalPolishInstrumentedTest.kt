package io.readx.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.readx.app.reader.*
import io.readx.app.ui.*
import io.readx.app.conversion.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalPolishInstrumentedTest {
    @Test fun customCoverAndFontArePrivateAndOriginalBookIsUnchanged()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>();val file=File(app.cacheDir,"local-polish-${System.nanoTime()}.txt").apply {writeText("自生成样书内容，原文不改。")} 
        val book=app.repository.import(Uri.fromFile(file));val bytes=app.repository.source(book).readBytes()
        val picture=File(app.cacheDir,"polish-cover.png")
        val bitmap=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.MAGENTA);picture.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val store=LocalFontStore(app);val before=store.list().map {it.id}.toSet()
        var font:LocalFont?=null
        try {
            app.repository.changeCover(book.id,Uri.fromFile(picture))
            val updated=app.repository.dao.book(book.id)!!;assertTrue(updated.coverPath!!.startsWith("custom-cover-"));assertArrayEquals(bytes,app.repository.source(updated).readBytes())
            val cover=app.repository.cover(updated)!!;val pixel=cover.getPixel(5,5);assertTrue(android.graphics.Color.red(pixel)>=250 && android.graphics.Color.blue(pixel)>=250 && android.graphics.Color.green(pixel)<=4);cover.recycle()
            font=store.import(Uri.fromFile(File("/system/fonts/Roboto-Regular.ttf")))
            assertNotNull(LocalFontStore.file(app,font!!.id));LocalFontStore.prepare(app,font!!.id);assertNotNull(LocalFontStore.typeface(font!!.id))
            val html=LocalHtml.prepare("<body><p>测试</p></body>",ReaderSettings(fontId=font!!.id),"#000000","#ffffff")
            assertTrue(html.contains("/reader-fonts/${font!!.id}.font"));assertTrue(html.contains("script-src 'none'"))
            assertNull(LocalFontStore.file(app,"../../private"))
        } finally {app.repository.delete(book);file.delete();picture.delete();font?.takeIf {it.id !in before}?.let {LocalFontStore.file(app,it.id)?.delete();app.getSharedPreferences("font-names",android.content.Context.MODE_PRIVATE).edit().remove(it.id).apply()}}
    }
    @Test fun modelDownloadOffAndGenerationRejectOldTasks() {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>();val prefs=ReaderPreferences(app);val old=prefs.settings.value
        try {
            prefs.update(old.copy(onlineModels=false));val generation=OcrDownloadPolicy.generation(app)
            assertFalse(OcrDownloadPolicy.allowed(app,generation))
            assertThrows(IllegalStateException::class.java) {OcrDownloadPolicy.enqueue(app,"eng")}
            prefs.update(old.copy(onlineModels=true));assertTrue(OcrDownloadPolicy.allowed(app,generation))
            prefs.update(old.copy(onlineModels=false));assertFalse(OcrDownloadPolicy.allowed(app,generation))
            assertTrue(OcrDownloadPolicy.url("eng").toString().contains(OcrDownloadPolicy.COMMIT))
            assertThrows(IllegalArgumentException::class.java) {OcrDownloadPolicy.url("../book")}
        } finally {prefs.update(old)}
    }
    @Test fun conversionRetainsCompletedPageCheckpointsForRetry() {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>();val root=File(app.cacheDir,"polish-checkpoints-${System.nanoTime()}")
        val files=ConversionFiles(root,"c".repeat(64))
        try {files.save(ConversionFiles.Page(0,"<p>已完成</p>",true,false));files.save(ConversionFiles.Page(1,"<p>第二页</p>",true,false))
            File(files.directory,"page-2.json").writeText("broken")
            assertNotNull(files.load(0));assertNotNull(files.load(1));assertNull(files.load(2));assertEquals("<p>已完成</p>",files.load(0)!!.html)
        } finally {files.cleanup();root.delete()}
    }
}
