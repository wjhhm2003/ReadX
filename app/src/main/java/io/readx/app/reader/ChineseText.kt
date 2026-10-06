package io.readx.app.reader

import android.content.res.AssetManager
import androidx.annotation.WorkerThread
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.jsoup.nodes.Node
import java.util.concurrent.ConcurrentHashMap

/** Display only: canonical DOM UTF-16 coordinates and original files stay unchanged. */
enum class ChineseScript(val label:String) { ORIGINAL("原文"), SIMPLIFIED("简体"), TRADITIONAL("繁体") }

internal object ChineseText {
    const val VERSION="opencc-119-length-v1"
    private val dictionaries=ConcurrentHashMap<ChineseScript,ChineseDictionary>()
    private val lock=Any()

    /** Call from HTML interception or native indexing workers, never from Compose rendering. */
    @WorkerThread
    fun dictionary(assets:AssetManager,script:ChineseScript,checkActive:()->Unit={}):ChineseDictionary? {
        if(script==ChineseScript.ORIGINAL)return null
        dictionaries[script]?.let {return it}
        return synchronized(lock) {
            dictionaries[script] ?: ChineseDictionary().also {dictionary->
                val prefix=if(script==ChineseScript.SIMPLIFIED) "TS" else "ST"
                var linesRead=0
                for(suffix in listOf("Characters","Phrases")) {
                    assets.open("chinese/$prefix$suffix.txt").bufferedReader(Charsets.UTF_8).useLines {lines->
                        lines.forEach {line->
                            if(++linesRead%256==0)checkActive()
                            if(line.isNotEmpty() && !line.startsWith('#')) {
                                val tab=line.indexOf('\t')
                                if(tab>0) {
                                    val source=line.substring(0,tab)
                                    val target=line.substring(tab+1).substringBefore(' ')
                                    // Supplementary-character replacements of different UTF-16 length
                                    // cannot reuse old DOM offsets safely. Preserve those rare source forms.
                                    if(source.length==target.length && source.length<=128)dictionary.add(source,target)
                                }
                            }
                        }
                    }
                }
                checkActive();dictionary.freeze();dictionaries[script]=dictionary
            }
        }
    }

    /** Only text nodes, after sanitization; never CSS, URLs, attributes or book scripts. */
    fun prepare(document:Document,dictionary:ChineseDictionary?) {
        if(dictionary==null)return
        val text=ArrayList<TextNode>()
        NodeTraversor.traverse(object:NodeVisitor {
            override fun head(node:Node,depth:Int) {
                if(node is TextNode && node.parent()?.nodeName() !in listOf("script","style","noscript"))text+=node
            }
            override fun tail(node:Node,depth:Int) {}
        },document.body())
        for(node in text) {
            val original=node.wholeText
            val converted=dictionary.convert(original)
            if(converted==original)continue
            // Jsoup encodes book text as attribute/text DATA, not executable script.
            // The trusted reader recovers original slices even after annotation splitText().
            val span=Element("span").attr("data-readx-source",original)
            node.replaceWith(span);span.appendChild(TextNode(converted))
        }
    }
}

/** Compact flat UTF-16 trie: phrase-longest match, first upstream candidate, then characters. */
class ChineseDictionary internal constructor() {
    private val roots=IntArray(65536)
    private var letters=CharArray(4096)
    private var children=IntArray(4096)
    private var siblings=IntArray(4096)
    private var outputs=arrayOfNulls<String>(4096)
    private var used=1
    var maxLength=1
        private set
    private var frozen=false
    private fun allocate(letter:Char):Int {
        if(used==letters.size) {
            val n=used*2;letters=letters.copyOf(n);children=children.copyOf(n);siblings=siblings.copyOf(n);outputs=outputs.copyOf(n)
        }
        val index=used++;letters[index]=letter;return index
    }
    fun add(source:String,target:String) {
        check(!frozen)
        var index=roots[source[0].code]
        if(index==0) {index=allocate(source[0]);roots[source[0].code]=index}
        for(at in 1 until source.length) {
            var child=children[index]
            while(child!=0 && letters[child]!=source[at])child=siblings[child]
            if(child==0) {child=allocate(source[at]);siblings[child]=children[index];children[index]=child}
            index=child
        }
        outputs[index]=target;maxLength=maxOf(maxLength,source.length)
    }
    fun freeze() {letters=letters.copyOf(used);children=children.copyOf(used);siblings=siblings.copyOf(used);outputs=outputs.copyOf(used);frozen=true}
    fun convert(source:String,limit:Int=source.length):String {
        check(frozen)
        if(source.isEmpty())return source
        val result=StringBuilder(source.length)
        var at=0
        while(at<limit.coerceIn(0,source.length)) {
            var index=roots[source[at].code]
            var best=if(index==0)null else outputs[index]
            var end=at+1;var next=end
            while(index!=0 && next<source.length && next-at<maxLength) {
                var child=children[index]
                while(child!=0 && letters[child]!=source[next])child=siblings[child]
                index=child;next++
                if(index!=0 && outputs[index]!=null) {best=outputs[index];end=next}
            }
            if(best==null) {result.append(source[at]);at++} else {result.append(best);at=end}
        }
        val converted=result.toString()
        return if(converted==source)source else converted
    }
}
