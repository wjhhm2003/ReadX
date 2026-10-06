package io.readx.app.reader

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.parser.Parser
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

/** TXT-only derived index of the existing generated HTML. NOT database text offsets.
 * Canonical coordinates include every prepared body text node (including indentation),
 * display coordinates replace block gaps with two newlines. Disk reads stay bounded. */
internal class NativeTextSource private constructor(val directory: File, val length: Int, val canonicalLength: Int, private val runs: List<Run>) {
    data class Run(val display: Int, val canonical: Int, val length: Int)
    fun read(start: Int, count: Int=8192): String = readFile("display",start.coerceIn(0,length),count.coerceAtMost(length-start.coerceIn(0,length)))
    private fun canonical(start: Int,count: Int)=readFile("canonical",start,count.coerceAtMost(canonicalLength-start))
    private fun readFile(name: String,start: Int,count: Int): String {
        if(count<=0) return ""
        return RandomAccessFile(File(directory,name),"r").use {file->file.seek(start*2L); val bytes=ByteArray(count*2);file.readFully(bytes);String(CharArray(count) {index->(((bytes[index*2].toInt() and 255) shl 8) or (bytes[index*2+1].toInt() and 255)).toChar()})}
    }
    private fun runAt(offset:Int,canonical:Boolean):Run? {
        var lo=0;var hi=runs.size
        while(lo<hi) {val mid=(lo+hi)/2;val start=if(canonical) runs[mid].canonical else runs[mid].display;if(start<=offset)lo=mid+1 else hi=mid}
        return runs.getOrNull(lo-1)
    }
    fun toCanonical(offset: Int, end: Boolean=false): Int {
        val run=runAt(offset,false) ?: return 0
        return (run.canonical+(offset-run.display).coerceIn(0,run.length)).coerceIn(0,canonicalLength)
    }
    fun toDisplay(offset: Int): Int {
        val run=runAt(offset,true) ?: return 0
        return (run.display+(offset-run.canonical).coerceIn(0,run.length)).coerceIn(0,length)
    }
    fun anchor(start: Int,end: Int): TextAnchor? {
        var a=toCanonical(start);var b=toCanonical(end,true)
        if(a>0 && a<canonicalLength && canonical(a,1)[0].isLowSurrogate()) a--
        if(b>0 && b<canonicalLength && canonical(b-1,1)[0].isHighSurrogate()) b++
        if(b<=a || b-a>16384) return null
        var p=(a-40).coerceAtLeast(0);var q=(b+40).coerceAtMost(canonicalLength)
        if(p<a && canonical(p,1)[0].isLowSurrogate()) p++
        if(q>b && canonical(q-1,1)[0].isHighSurrogate()) q--
        return TextAnchor(a,b,canonical(a,b-a),canonical(p,a-p),canonical(b,q-b))
    }
    suspend fun resolve(anchor: TextAnchor): Pair<Int,Int>? {
        fun matches(at:Int):Boolean {
            val end=at+anchor.quote.length
            return at>=0 && end<=canonicalLength && canonical(at,anchor.quote.length)==anchor.quote &&
                (anchor.prefix.isEmpty() || (at>=anchor.prefix.length && canonical(at-anchor.prefix.length,anchor.prefix.length)==anchor.prefix)) &&
                (anchor.suffix.isEmpty() || canonical(end,anchor.suffix.length)==anchor.suffix)
        }
        if(matches(anchor.start)) return toDisplay(anchor.start) to toDisplay(anchor.end)
        var match:Int?=null;var from=0
        while(from<canonicalLength) {
            coroutineContext.ensureActive()
            val block=canonical(from,16384+anchor.quote.length+80);var at=0
            while(true) {
                at=block.indexOf(anchor.quote,at);if(at<0 || at>=16384) break
                val absolute=from+at
                if(matches(absolute)) {if(match!=null) return null;match=absolute};at++
            }
            from+=16384
        }
        return match?.let {toDisplay(it) to toDisplay(it+anchor.quote.length)}
    }
    suspend fun find(term:String,occurrence:Int):Pair<Int,Int>? {
        var from=0;var found=0
        while(from<length) {
            coroutineContext.ensureActive();val block=read(from,16384+term.length);var at=0
            while(true) {at=block.indexOf(term,at,true);if(at<0 || at>=16384) break;if(found++==occurrence) return from+at to from+at+term.length;at+=term.length}
            from+=16384
        };return null
    }
    companion object {
        private val lock=Mutex()
        suspend fun open(root:File,book:String,chapter:Int,html:File):NativeTextSource=withContext(Dispatchers.IO) {lock.withLock {
            require(book.matches(Regex("[0-9a-fA-F-]{36}")))
            val parent=File(root,"native-text-v1/$book").apply {mkdirs()};val dir=File(parent,"$chapter")
            fun load():NativeTextSource?=runCatching {DataInputStream(File(dir,"index").inputStream().buffered()).use {i->
                require(i.readInt()==0x52585431);require(i.readLong()==html.length() && i.readLong()==html.lastModified())
                val n=i.readInt();val c=i.readInt();val count=i.readInt();require(n in 1..64000000 && c in 1..64000000 && count in 1..1000000)
                require(File(dir,"display").length()==n*2L && File(dir,"canonical").length()==c*2L)
                val runs=List(count) {Run(i.readInt(),i.readInt(),i.readInt())};NativeTextSource(dir,n,c,runs)
            }}.getOrNull()
            load()?.let {return@withLock it}
            val temp=Files.createTempDirectory(parent.toPath(),"index-").toFile()
            try {
                var canonical=0;var display=0;var inBody=false;var keep=false
                val runs=ArrayList<Run>()
                OutputStreamWriter(File(temp,"canonical").outputStream().buffered(),Charsets.UTF_16BE).use {c->
                    OutputStreamWriter(File(temp,"display").outputStream().buffered(),Charsets.UTF_16BE).use {d->
                        html.bufferedReader().use {reader->
                            val token=StringBuilder();var tag=false;var processed=0
                            fun emit() {if(token.isEmpty())return;val value=Parser.unescapeEntities(token.toString(),false);token.clear();if(!inBody)return
                                c.write(value);if(keep) {runs.add(Run(display,canonical,value.length));d.write(value);display+=value.length};canonical+=value.length
                            }
                            while(true) {
                                val n=reader.read();if(n<0)break;val ch=n.toChar()
                                if(ch=='<' && !tag) {emit();tag=true}
                                else if(ch=='>' && tag) {
                                    val name=token.toString().trim().lowercase();token.clear();tag=false
                                    when {name=="body" || name.startsWith("body ")->inBody=true;name=="/body"->inBody=false
                                        name=="h1" || name.startsWith("h1 ") || name=="p" || name.startsWith("p ")->keep=true
                                        name=="/p" || name=="/h1"->{keep=false;d.write("\n\n");display+=2}}
                                } else {token.append(ch);if(!tag && token.length>=4096 && ch==';')emit();else if(!tag && token.length>=8192 && token.lastIndexOf("&")<token.length-16)emit()}
                                if(++processed%8192==0) coroutineContext.ensureActive()
                            };emit()
                        }
                    }
                }
                require(display>0 && runs.isNotEmpty()) {"TXT 没有正文"}
                DataOutputStream(File(temp,"index").outputStream().buffered()).use {i->i.writeInt(0x52585431);i.writeLong(html.length());i.writeLong(html.lastModified());i.writeInt(display);i.writeInt(canonical);i.writeInt(runs.size);runs.forEach {r->i.writeInt(r.display);i.writeInt(r.canonical);i.writeInt(r.length)}}
                coroutineContext.ensureActive()
                // Only this UUID/chapter derived cache. Never source content or the library.
                if(dir.exists()) dir.deleteRecursively()
                Files.move(temp.toPath(),dir.toPath(),StandardCopyOption.ATOMIC_MOVE)
                NativeTextSource(dir,display,canonical,runs)
            } finally {if(temp.exists()) temp.deleteRecursively()}
        }}
    }
}
