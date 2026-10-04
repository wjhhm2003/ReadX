package io.readx.app.reader

import io.readx.app.conversion.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class PdfConversionTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun cjkAndLatinSplitWordsKeepTheirMeaning() {
        assertEquals("这是中文段落",PdfTextFlow.join("这是中文","段落"))
        assertEquals("reading together",PdfTextFlow.join("reading","together"))
        assertEquals("conversion",PdfTextFlow.join("conver-","sion"))
        assertEquals("same",PdfTextFlow.join("","same"))
    }
    @Test fun compatibilityRadicalsDoNotBreakChineseSearchOrChangeMathSuperscripts() {
        assertEquals("正文中文测试 x²",PdfTextFlow.normalize("正⽂中⽂测试 x²"))
    }
    @Test fun doubleColumnOrderIsLeftThenRightNotAlternatingRows() {
        val lines=(0..3).flatMap {n->listOf(TextLine("左${n}。",10f,30f*n,40f,10f),TextLine("右${n}。",60f,30f*n,90f,10f))}
        val flow=PdfTextFlow.layout(lines,100f)
        assertFalse(flow.unreliable)
        assertTrue(flow.paragraphs.joinToString("").indexOf("左3")<flow.paragraphs.joinToString("").indexOf("右0"))
    }
    @Test fun ambiguousSpanningBodyIsNotGuessed() {
        val columns=(0..3).flatMap {n->listOf(TextLine("左。",10f,30f*n,40f,10f),TextLine("右。",60f,30f*n,90f,10f))}
        assertTrue(PdfTextFlow.layout(columns+TextLine("复杂图表",10f,45f,90f,12f),100f).unreliable)
    }
    @Test fun epubIsStandaloneMimetypeFirstAndSecurelyEscapesMetadata() {
        val html=temp.newFile("chapter.xhtml").apply {writeText("<html><body><section data-source-page='3'><h1>第四页</h1><p>连续阅读的正文内容。</p></section></body></html>")}
        val output=temp.newFile("result.epub")
        EpubOutput.write(output,"test-id","标题 <安全>","作者",listOf(EpubOutput.Chapter("第四页\u0002",html)),emptyList())
        EpubOutput.validate(output)
        ZipFile(output).use {zip->assertEquals("mimetype",zip.entries().nextElement().name);assertEquals(0,zip.getEntry("mimetype").method);assertTrue(zip.getInputStream(zip.getEntry("OEBPS/content.opf")).reader().readText().contains("&lt;安全&gt;"))}
        val parsed=BookParser.epub(output,temp.newFolder("content"))
        assertEquals("标题 <安全>",parsed.title);assertEquals(1,parsed.chapters.size);assertTrue(parsed.chapters.single().text.contains("连续阅读"))
        assertEquals(3,EpubOutput.sourcePage(html.readText()))
    }
    @Test(expected=IllegalArgumentException::class) fun emptyBookDoesNotPretendToBeAConversion() {
        EpubOutput.write(temp.newFile(),"id","title","",emptyList(),emptyList())
    }
}
