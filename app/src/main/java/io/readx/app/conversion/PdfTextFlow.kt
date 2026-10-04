package io.readx.app.conversion

import kotlin.math.abs

/** Android adaptation of pdf-craft Jointer's Latin split-word/CJK flow rules (MIT).
 * Upstream: 7d72c86bf4b77705767e90b8ae2d4881b37e79fa, chapter/jointer.py.
 * ReadX additions: PDF/OCR geometry adapter and conservative whole-page fallback.
 */
data class TextLine(val text: String, val left: Float, val top: Float, val right: Float, val height: Float)
data class FlowPage(val paragraphs: List<String>, val unreliable: Boolean = false)
object PdfTextFlow {
    fun isHeading(text:String):Boolean = text.trim().length<150 && text.trim().matches(Regex("^(第.{1,12}[章卷节].*|(?:Chapter|CHAPTER)\\s+[0-9]+.*|序章|序言|前言|后记|尾声)$"))
    private fun latin(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    fun normalize(text: String): String = buildString {
        text.codePoints().forEach { cp ->
            val value=String(Character.toChars(cp))
            append(if(cp in 0x2F00..0x2FDF || cp in 0xF900..0xFAFF) java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC) else value)
        }
    }
    fun join(first: String, second: String): String {
        if(first.isEmpty()) return second
        if(second.isEmpty()) return first
        val split = first.length>=2 && first.last() in "-‐‑" && latin(first[first.lastIndex-1]) && latin(second.first())
        if(split) return first.dropLast(1)+second
        val space = latin(first.last()) && latin(second.first()) || first.last().isDigit() && latin(second.first())
        return first + (if(space) " " else "") + second
    }
    fun layout(lines: List<TextLine>, width: Float): FlowPage {
        if(lines.isEmpty()) return FlowPage(emptyList())
        val clean=lines.filter {it.text.isNotBlank()}.sortedBy {it.top}
        val left=clean.filter {it.right<width*.49f}; val right=clean.filter {it.left>width*.51f}
        val columns=left.size>=3 && right.size>=3
        val ordered=if(columns) {
            val start=minOf(left.minOf {it.top},right.minOf {it.top})
            val end=maxOf(left.maxOf {it.top+it.height},right.maxOf {it.top+it.height})
            val spanning=clean.filter {it !in left && it !in right}
            if(spanning.any {it.top>start+2 && it.top<end-2}) return FlowPage(clean.map {it.text},true)
            spanning.filter {it.top<=start} + left.sortedBy {it.top} + right.sortedBy {it.top} + spanning.filter {it.top>=end}
        } else clean
        val deltas=ordered.zipWithNext().map { (a,b) -> b.top-a.top }.filter {it>1f && it<100f}.sorted()
        val step=if(deltas.isEmpty()) 18f else deltas[deltas.size/2]
        val paragraphs=mutableListOf<String>(); var text=""; var previous:TextLine?=null
        for(line in ordered) {
            val p=previous
            val newParagraph=p!=null && (isHeading(normalize(line.text)) || isHeading(normalize(p.text)) || line.top-p.top>maxOf(step*1.45f,p.height*1.8f) || line.left-p.left>p.height*2 || line.top<p.top || abs(line.left-p.left)>width*.3f)
            if(newParagraph && text.isNotBlank()) {paragraphs+=text;text=""}
            text=join(text,normalize(line.text.trim()));previous=line
        }
        if(text.isNotBlank()) paragraphs+=text
        val bad=ordered.any {l->l.text.count {it=='\uFFFD' || it.isISOControl() && !it.isWhitespace()}>l.text.length/20}
        return FlowPage(paragraphs,bad)
    }
}
