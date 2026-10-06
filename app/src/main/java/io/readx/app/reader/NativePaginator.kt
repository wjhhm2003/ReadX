package io.readx.app.reader

import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.readx.app.ui.ReaderSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One bounded layout at a time. Background work backs off after every user interaction. */
internal object NativeWork {
    val dispatcher=Dispatchers.Default.limitedParallelism(1)
    @Volatile var activeUntil=0L
    fun foreground() {activeUntil=SystemClock.uptimeMillis()+350}
    suspend fun backgroundYield() {while(SystemClock.uptimeMillis()<activeUntil) delay(40);yield()}
}
internal data class NativePage(val start:Int,val end:Int,val layout:StaticLayout)
internal class NativePaginator(val source:NativeTextSource,settings:ReaderSettings,width:Int,height:Int,density:Float,fontScale:Float,root:File,key:String) {
    val margin=settings.margin*density
    val top=20*density
    val paint=TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {textSize=settings.fontSize*density*fontScale;typeface=settings.fontId?.let(LocalFontStore::typeface) ?: if(settings.serif) Typeface.SERIF else Typeface.SANS_SERIF}
    private val textWidth=(width-margin*2).toInt().coerceAtLeast(1)
    private val textHeight=(height-top*2).toInt().coerceAtLeast(1)
    private val lineHeight=settings.lineHeight
    private val cache=File(source.directory.parentFile,"pages/$key.bin")
    private val boundaryLock=Any()
    val boundaries=ArrayList<Int>().apply {add(0)}
    companion object {private val saveLock=Mutex()}
    val complete get()=boundaries.last()==source.length
    val count:Int? get()=if(complete) boundaries.size-1 else null
    init {
        runCatching {DataInputStream(cache.inputStream().buffered()).use {i->
            require(i.readInt()==0x52584E31 && i.readInt()==source.length);val n=i.readInt();require(n in 1..1000000 && cache.length()==12L+n*4L)
            val b=List(n) {i.readInt()};require(b.first()==0 && b.last()<=source.length && b.zipWithNext().all {it.first<it.second});boundaries.clear();boundaries.addAll(b)
        }}
    }
    fun adopt(values:List<Int>) { synchronized(boundaryLock) {boundaries.clear();boundaries.addAll(values)} }
    fun pageNumber(offset:Int):Int?=if(offset<=boundaries.last()) (boundaries.binarySearch(offset).let {if(it>=0) it else -it-2}+1).coerceAtMost(count ?: Int.MAX_VALUE) else null
    fun startFor(offset:Int):Int=if(offset<=boundaries.last()) boundaries[(boundaries.binarySearch(offset).let {if(it>=0) it else -it-2}).coerceIn(0,boundaries.lastIndex-(if(complete) 1 else 0))] else offset
    private val windowSize = ((textHeight/(paint.fontSpacing*lineHeight)).coerceAtLeast(1f) * textWidth/(paint.textSize*.55f) * 2.5f).toInt().coerceIn(1024,8192)
    private fun layout(text:String)=StaticLayout.Builder.obtain(text,0,text.length,TextPaint(paint),textWidth).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(0f,lineHeight).setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
    suspend fun page(offset:Int,until:Int=source.length):NativePage=withContext(NativeWork.dispatcher) {
        ensureActive();val start=offset.coerceIn(0,(source.length-1).coerceAtLeast(0));var text=source.read(start,minOf(windowSize,until-start))
        if(text.isNotEmpty() && text.last().isHighSurrogate()) text=text.dropLast(1)
        val measured=layout(text);var lines=0
        while(lines<measured.lineCount && measured.getLineBottom(lines)<=textHeight) lines++
        val size=measured.getLineEnd((lines-1).coerceAtLeast(0)).coerceAtLeast(1)
        val end=(start+size).coerceAtMost(until)
        val page=NativePage(start,end,if(size<text.length) layout(text.take(size)) else measured)
        ensureActive();synchronized(boundaryLock) {if(start==boundaries.last() && end>start) boundaries.add(end)}
        page
    }
    suspend fun previous(offset:Int):NativePage {
        if(offset==source.length && complete) return page(boundaries[boundaries.lastIndex-1],source.length)
        val known=pageNumber(offset)
        if(known!=null && known>1) return page(boundaries[known-2],offset)
        var at=(offset-8192).coerceAtLeast(0);var last:NativePage?=null
        while(at<offset) {val p=page(at,offset);last=p;at=p.end;currentCoroutineContext().ensureActive()}
        return last ?: page(0)
    }
    suspend fun save()=withContext(Dispatchers.IO) {saveLock.withLock {
        val snapshot=synchronized(boundaryLock) {boundaries.toList()};
        val existing=runCatching {DataInputStream(cache.inputStream().buffered()).use {i->if(i.readInt()==0x52584E31 && i.readInt()==source.length)i.readInt() else 0}}.getOrDefault(0)
        if(existing>snapshot.size)return@withLock
        cache.parentFile!!.mkdirs();val temp=File.createTempFile("pages-",".tmp",cache.parentFile)
        try {DataOutputStream(temp.outputStream().buffered()).use {o->o.writeInt(0x52584E31);o.writeInt(source.length);o.writeInt(snapshot.size);snapshot.forEach(o::writeInt)};ensureActive();Files.move(temp.toPath(),cache.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)} finally {temp.delete()}
        // Derived layout caches only, bounded globally; source text indices are not deleted here.
        val files=cache.parentFile!!.listFiles {f->f.extension=="bin"}.orEmpty().sortedBy {it.lastModified()}
        var bytes=files.sumOf {it.length()};for(file in files) {if(bytes<=32L*1024*1024)break;bytes-=file.length();file.delete()}
    }}
}
