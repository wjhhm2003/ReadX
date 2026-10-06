package io.readx.app.reader

import java.text.BreakIterator
import java.util.Locale

enum class SelectionUnit { WORD, SENTENCE }
/** Unicode-aware local boundary heuristic, not semantic/AI sentence understanding. */
object SelectionSnap {
    fun range(text:String,start:Int,end:Int,unit:SelectionUnit):Pair<Int,Int> {
        val lo=minOf(start,end).coerceIn(0,text.length);val hi=maxOf(start,end).coerceIn(lo,text.length)
        if(lo==hi)return lo to hi
        val iterator=if(unit==SelectionUnit.SENTENCE)BreakIterator.getSentenceInstance(Locale.CHINA) else BreakIterator.getWordInstance(Locale.CHINA)
        iterator.setText(text)
        var a=if(iterator.isBoundary(lo))lo else iterator.preceding(lo).takeIf {it!=BreakIterator.DONE} ?: 0
        var b=if(iterator.isBoundary(hi))hi else iterator.following(hi).takeIf {it!=BreakIterator.DONE} ?: text.length
        while(a<b && text[a].isWhitespace())a++
        while(b>a && text[b-1].isWhitespace())b--
        if(a==b) {
            var previous=(lo-1).coerceAtMost(text.lastIndex);var following=hi
            while(previous>=0 && text[previous].isWhitespace())previous--
            while(following<text.length && text[following].isWhitespace())following++
            val at=when {previous<0 && following>=text.length->return lo to hi
                previous<0->following;following>=text.length->previous
                following-hi<=lo-previous->following;else->previous}
            a=iterator.preceding(at+1).takeIf {it!=BreakIterator.DONE} ?: 0
            b=iterator.following(at).takeIf {it!=BreakIterator.DONE} ?: text.length
            while(a<b && text[a].isWhitespace())a++
            while(b>a && text[b-1].isWhitespace())b--
        }
        if(a>0 && a<text.length && text[a].isLowSurrogate())a--
        if(b>0 && b<text.length && text[b-1].isHighSurrogate())b++
        return a to b
    }
}
