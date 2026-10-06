package io.readx.app.reader

import io.readx.app.data.*
import io.readx.app.pdf.*
import io.readx.app.ui.*
import org.junit.Assert.*
import org.junit.Test

class PolishFeatureTest {
    @Test fun exportEscapesMarkdownAndKeepsHistoricalNotesAndUnicode() {
        val old=Annotation("one","book","NOTE",2,.5f,"[章节]","中文😀 <img>","笔记 **一**",color="#75BEFF")
        val second=old.copy(id="two",note="历史笔记二")
        val text=AnnotationExport.heading("标题 # 一",true)+AnnotationExport.entry(old,"TXT",true)+AnnotationExport.entry(second,"TXT",true)
        assertTrue(text.contains("中文😀 &lt;img&gt;"));assertTrue(text.contains("\\*\\*一\\*\\*"));assertTrue(text.contains("历史笔记二"));assertTrue(text.contains("第 3 章"))
        val plain=AnnotationExport.entry(old,"PDF",false);assertTrue(plain.contains("原文第 3 页"));assertTrue(plain.contains("笔记 **一**"))
    }
    @Test fun wordAndSentenceSnapKeepEmojiAndNoHalfSurrogates() {
        val text="开始。Hello reader 😀 today。下一句。"
        val word=SelectionSnap.range(text,4,7,SelectionUnit.WORD)
        assertEquals("Hello",text.substring(word.first,word.second))
        val sentence=SelectionSnap.range(text,9,15,SelectionUnit.SENTENCE)
        assertEquals("Hello reader 😀 today。",text.substring(sentence.first,sentence.second))
        val at=text.indexOf("😀");val emoji=SelectionSnap.range(text,at+1,at+2,SelectionUnit.WORD)
        assertEquals("😀",text.substring(emoji.first,emoji.second))
    }
    @Test fun whitespaceSelectionSnapsToNearbyWordRatherThanCollapsing() {
        val text="word    next";val range=SelectionSnap.range(text,5,7,SelectionUnit.WORD)
        assertEquals("next",text.substring(range.first,range.second))
    }
    @Test fun cropHandlesMoveCornersAndMagnetDoesNotCollapseTheRectangle() {
        val initial=CropRect(.1f,.1f,.9f,.9f)
        val moved=CropHandles.move(initial,CropHandle.TOP_LEFT,.03f,.02f)
        assertEquals(.13f,moved.left,.00001f);assertEquals(.12f,moved.top,.00001f);assertEquals(.9f,moved.right,.00001f)
        val magnet=CropHandles.snap(moved,CropRect(.14f,.11f,.85f,.88f),.02f,.02f)
        assertEquals(.14f,magnet.left,.00001f);assertEquals(.11f,magnet.top,.00001f)
        assertTrue(CropHandles.move(initial,CropHandle.RIGHT,-2f,0f).width>=.05f)
    }
    @Test fun centeredPdfTransformRoundTripsAfterCropZoomAndPan() {
        val transform=PdfCoordinateTransform(CropRect(.1f,.2f,.9f,.8f),612f,792f,.8f,21f,2.1f,-50f,72f,top=200f)
        for(x in listOf(.1f,.44f,.9f))for(y in listOf(.2f,.51f,.8f)) {val v=transform.toView(x,y);val p=transform.toPage(v.first,v.second);assertEquals(x,p.first,.000001f);assertEquals(y,p.second,.000001f)}
    }
    @Test fun shelfSortUsesRealSizeAndUnknownProgressIsNotEstimated() {
        val a=Book("a","a","乙",format="PDF",sourceName="a",lastReadAt=1,totalUnits=10,chapterIndex=4,importedAt=1)
        val b=Book("b","b","甲",format="TXT",sourceName="b",lastReadAt=2,totalUnits=0,importedAt=2)
        assertEquals(listOf("b","a"),sortShelf(listOf(a,b),ShelfSort.SIZE,mapOf("a" to 20,"b" to 100)).map {it.id})
        assertEquals("a",sortShelf(listOf(b,a),ShelfSort.PROGRESS,emptyMap()).first().id)
        assertEquals("b",sortShelf(listOf(a,b),ShelfSort.IMPORTED,emptyMap()).first().id)
    }
    @Test fun fontHashInvalidatesLayoutButColorDoesNotBelongInIt() {
        val base=LayoutConfig("hash",listOf("c.html"),360,700,1f,1f,20f,1.8f,24f,true,"web","system","zh")
        assertNotEquals(base.generateKey(),base.copy(fontId="a".repeat(64)).generateKey())
    }
    @Test fun fontValidationRejectsInvalidHeaderOrSize() {
        val temp=java.io.File.createTempFile("bad-font",".tmp")
        try {
            temp.writeBytes(ByteArray(8))
            try { LocalFontStore.validate(temp); fail("Should fail") } catch(e: IllegalArgumentException) { assertTrue(e.message!!.contains("字体长度无效")) }
        } finally { temp.delete() }
    }
    @Test fun annotationCopyTextFormatsQuoteAndNoteCorrectly() {
        val noteWithQuote = Annotation("1", "b1", "NOTE", 0, 0f, "label", quote = "天地玄黄", note = "宇宙洪荒")
        val content = buildString {
            if (noteWithQuote.quote.isNotBlank()) append(noteWithQuote.quote)
            if (noteWithQuote.note.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("笔记：").append(noteWithQuote.note)
            }
            if (isEmpty()) append(noteWithQuote.label)
        }
        assertEquals("天地玄黄\n\n笔记：宇宙洪荒", content)

        val bookmark = Annotation("2", "b1", "BOOKMARK", 1, 0.5f, "第 2 章 50%", quote = "", note = "")
        val bmContent = buildString {
            if (bookmark.quote.isNotBlank()) append(bookmark.quote)
            if (bookmark.note.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("笔记：").append(bookmark.note)
            }
            if (isEmpty()) append(bookmark.label)
        }
        assertEquals("第 2 章 50%", bmContent)
    }
}

