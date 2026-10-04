package io.readx.app.conversion

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.jsoup.Jsoup
import kotlin.math.abs

internal class PdfPageExtractor : PDFTextStripper() {
    private val glyphs=mutableListOf<TextPosition>()
    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        require(glyphs.size+textPositions.size<=200000) { "页面文字过多" };glyphs.addAll(textPositions)
    }
    fun extract(document: PDDocument, index: Int): FlowPage {
        glyphs.clear();startPage=index+1;endPage=index+1;sortByPosition=true;getText(document)
        val page=document.getPage(index); val width=page.cropBox.width
        val rows=mutableListOf<MutableList<TextPosition>>()
        for(g in glyphs.sortedWith(compareBy<TextPosition> {it.yDirAdj}.thenBy {it.xDirAdj})) {
            val row=rows.lastOrNull()?.takeIf {abs(it[0].yDirAdj-g.yDirAdj)<maxOf(2f,g.heightDir*.4f)}
            if(row==null) rows+= mutableListOf(g) else row+=g
        }
        // Split lines at a real column gutter, not at normal word spacing.
        val lines=rows.flatMap {row->
            val groups=mutableListOf<MutableList<TextPosition>>();var last:TextPosition?=null
            for(g in row.sortedBy {it.xDirAdj}) {
                if(last==null || g.xDirAdj-last!!.xDirAdj-last!!.widthDirAdj>maxOf(width*.04f,g.heightDir*2)) groups.add(mutableListOf<TextPosition>())
                groups.last()+=g;last=g
            }
            groups.map {group->
                val text=StringBuilder();var previous:TextPosition?=null
                group.forEach {g->val p=previous;if(p!=null && g.xDirAdj-p.xDirAdj-p.widthDirAdj>maxOf(1f,g.heightDir*.2f) && p.unicode.lastOrNull()?.isLetterOrDigit()==true && g.unicode.firstOrNull()?.code?.let {it<128}==true) text.append(' ');text.append(g.unicode);previous=g}
                TextLine(text.toString(),group.minOf {it.xDirAdj},group.minOf {it.yDirAdj},group.maxOf {it.xDirAdj+it.widthDirAdj},group.maxOf {it.heightDir})
            }
        }
        val parser=com.tom_roush.pdfbox.pdfparser.PDFStreamParser(page)
        parser.parse()
        val operators=parser.tokens.filterIsInstance<com.tom_roush.pdfbox.contentstream.operator.Operator>()
        val vectors=operators.count {it.name in listOf("l","re","c","v","y")}
        val flow=PdfTextFlow.layout(lines,width)
        val resources=page.resources
        val images=mutableListOf<PDImageXObject>()
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PDResources,Boolean>())
        fun imageResources(current:PDResources?, depth:Int=0) {
            if(current==null || depth>8 || !seen.add(current)) return
            for(name in current.xObjectNames) when(val objectValue=current.getXObject(name)) {
                is PDImageXObject -> images.add(objectValue)
                is PDFormXObject -> imageResources(objectValue.resources,depth+1)
            }
        }
        imageResources(resources)
        val insetImages=images.any {image-> if(image.cosObject.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.FILTER)?.toString()?.contains("JPXDecode")==true) return@any true; val ratio=image.width.toFloat()/image.height;abs(ratio-width/page.cropBox.height)>.15f || image.width<width*.8f || image.height<page.cropBox.height*.8f }
        val formulas=flow.paragraphs.joinToString("").count {it in "∫∑√≠≤≥∞∂"}>3
        val nonHorizontal=glyphs.any {it.dir!=0f}
        val table=lines.size>10 && lines.count {it.text.trim().length<5}>lines.size/2
        return flow.copy(unreliable=flow.unreliable || insetImages || formulas || nonHorizontal || table || vectors>=10)
    }
    companion object {
        fun ocr(hocr: String, width: Float): FlowPage {
            val document=Jsoup.parse(hocr)
            val lines=document.select(".ocr_line,.ocr_header").mapNotNull {element->
                val bbox=Regex("bbox (\\d+) (\\d+) (\\d+) (\\d+)").find(element.attr("title")) ?: return@mapNotNull null
                val v=bbox.groupValues.drop(1).map {it.toFloat()}
                TextLine(element.text().replace(Regex("(?<=[\\p{IsHan}])\\s+(?=[\\p{IsHan}])"),""),v[0],v[1],v[2],v[3]-v[1])
            }
            val flow=PdfTextFlow.layout(lines,width)
            val symbolic=lines.joinToString("") {it.text}.count {it in "∫∑√≠≤≥∞∂"}>3
            val smallCells=lines.size>10 && lines.count {it.text.trim().length<6}>lines.size/2
            return flow.copy(unreliable=flow.unreliable || symbolic || smallCells)
        }
    }
}
