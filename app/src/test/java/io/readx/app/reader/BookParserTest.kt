package io.readx.app.reader

import io.readx.app.ui.ReaderSettings
import io.readx.app.data.progress
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookParserTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun utf8AndBomDecode() {
        assertEquals("你好\n世界", BookParser.decodeText("\uFEFF你好\r\n世界".toByteArray()))
    }
    @Test fun gb18030Decode() {
        assertEquals("中文阅读与目录", BookParser.decodeText("中文阅读与目录".toByteArray(Charset.forName("GB18030"))))
    }
    @Test fun utf16WithBomDecode() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "测试文本".toByteArray(Charsets.UTF_16LE)
        assertEquals("测试文本", BookParser.decodeText(bytes))
    }
    @Test fun txtDetectsChaptersAndEscapesMarkup() {
        val file = temp.newFile("novel.txt").apply { writeText("前置正文\n第一章 开始\n<script>不能执行</script>\nChapter 2 Ending\nGoodbye") }
        val dir = temp.newFolder("content")
        val parsed = BookParser.txt(file, dir)
        assertEquals(listOf("正文", "第一章 开始", "Chapter 2 Ending"), parsed.chapters.map { it.title })
        val html = File(dir, parsed.chapters[1].href).readText()
        assertTrue(html.contains("&lt;script&gt;")); assertFalse(html.contains("<script>"))
        assertEquals(1, Jsoup.parse(html).getElementsContainingOwnText("第一章 开始").size)
    }
    @Test fun txtWithoutHeadingsHasOneChapter() {
        val file = temp.newFile("plain.txt").apply { writeText("这是一段正文。\n另一段正文。") }
        assertEquals(1, BookParser.txt(file, temp.newFolder()).chapters.size)
    }
    @Test fun whitespaceTxtRejected() {
        val file = temp.newFile("empty.txt").apply { writeText(" \n\t") }
        assertThrows(IllegalArgumentException::class.java) { BookParser.txt(file, temp.newFolder()) }
    }
    @Test fun epubReadsMetadataSpineAndNav() {
        val epub = zip(mapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
            "OPS/book.opf" to "<package xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>测试书籍</dc:title><dc:creator>作者甲</dc:creator></metadata><manifest><item id='c1' href='text/ch1.xhtml'/><item id='c2' href='text/ch2.xhtml'/><item id='nav' href='nav.xhtml' properties='nav'/></manifest><spine><itemref idref='c1'/><itemref idref='c2'/></spine></package>",
            "OPS/nav.xhtml" to "<nav epub:type='toc'><ol><li><a href='text/ch1.xhtml'>真正的第一章</a></li><li><a href='text/ch2.xhtml#note'>真正的第二章</a></li></ol></nav>",
            "OPS/text/ch1.xhtml" to "<html><body><h1>正文标题</h1><p>书中第一段</p></body></html>",
            "OPS/text/ch2.xhtml" to "<html><body><p id='note'>书中第二段</p></body></html>",
        ))
        val parsed = BookParser.epub(epub, temp.newFolder())
        assertEquals("测试书籍", parsed.title); assertEquals("作者甲", parsed.author)
        assertEquals(listOf("真正的第一章", "真正的第二章"), parsed.chapters.map { it.title })
        assertEquals("OPS/text/ch1.xhtml", parsed.chapters.first().href)
    }
    @Test fun zipTraversalRejectedBeforeWritingOutsideRoot() {
        val epub = zip(mapOf("../escaped.txt" to "bad"))
        val dir = temp.newFolder("safe")
        assertThrows(IllegalArgumentException::class.java) { BookParser.epub(epub, dir) }
        assertFalse(File(temp.root, "escaped.txt").exists())
    }
    @Test fun absoluteAndWindowsPathsRejected() {
        val root = temp.newFolder()
        listOf("../outside", "C:/outside", "nested\\outside", "/outside").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { BookParser.safeFile(root, name) }
        }
        assertEquals(File(root, "inside.html").canonicalFile, BookParser.safeFile(root, "inside.html"))
    }
    @Test fun missingContainerRejected() {
        assertThrows(IllegalArgumentException::class.java) { BookParser.epub(zip(mapOf("x.txt" to "text")), temp.newFolder()) }
    }
    @Test fun epubMarkupIsSanitizedAndAppTypographyApplied() {
        val source = "<html><head><script>evil()</script></head><body onload='evil()'><iframe src='https://example.com'></iframe><a href='javascript:evil()'>链接</a><p data-readx-mark='forged'>正文</p></body></html>"
        val html = LocalHtml.prepare(source, ReaderSettings(fontSize = 24f, serif = false), "#111111", "#EEEEEE")
        val doc = Jsoup.parse(html)
        assertTrue(doc.select("script,iframe,[onload],[data-readx-mark]").isEmpty())
        assertEquals("", doc.selectFirst("a")!!.attr("href"))
        assertTrue(html.contains("font-size: 24.0px")); assertTrue(html.contains("font-family: sans-serif"))
        assertTrue(html.contains("script-src 'none'"))
    }
    @Test fun defaultLayoutIsPagedAndScrollOptOutRemovesColumns() {
        assertEquals(io.readx.app.ui.ReadingLayout.PAGED, ReaderSettings().layout)
        val paged = LocalHtml.prepare("<p>正文</p>", ReaderSettings(), "#111111", "#ffffff")
        assertTrue(paged.contains("height: 640.0px"))
        assertTrue(paged.contains("column-fill: auto"))
        val scrolling = LocalHtml.prepare("<p>正文</p>", ReaderSettings(layout = io.readx.app.ui.ReadingLayout.SCROLL), "#111111", "#ffffff")
        assertTrue(scrolling.contains("column-count: auto"))
        assertFalse(scrolling.contains("column-fill: auto"))
    }
    @Test fun progressUsesAllSectionsNotOnlyCurrentSection() {
        val book = io.readx.app.data.Book("id", "hash", "title", format = "TXT", sourceName = "book.txt", totalUnits = 10, lastReadAt = 1, chapterIndex = 4, scrollFraction = .5f)
        assertEquals(45, book.progress())
        assertEquals(50, book.copy(format = "PDF", chapterIndex = 4).progress())
        assertNull(book.copy(totalUnits = 0).progress())
    }
    @Test fun fractionalDensityPreservesMeasuredColumnWidthInsteadOfAccumulatingRounding() {
        val html = LocalHtml.prepare("<p>正文</p>", ReaderSettings(), "#111111", "#ffffff", 411.42856f, 721.1429f)
        assertTrue(html.contains("width=411, height=721"))
        assertTrue(html.contains("width: 411.42856px !important"))
        assertTrue(html.contains("height: 721.1429px !important"))
        assertTrue(html.contains("column-width: 363.42856px !important"))
        assertTrue(html.contains("width: calc(100% + 24.0px)"))
    }
    @Test fun blackThemeKeepsTheSecurePagedReader() {
        val settings = ReaderSettings(theme = io.readx.app.ui.ReadingTheme.BLACK)
        val html = LocalHtml.prepare("<p>纯黑阅读</p><script>evil()</script>", settings, "#E6E6E6", "#000000")
        assertTrue(html.contains("background: #000000 !important"))
        assertTrue(html.contains("column-fill: auto"))
        assertTrue(html.contains("script-src 'none'"))
        assertTrue(Jsoup.parse(html).select("script").isEmpty())
    }
    @Test fun nativeLoadMarkerCannotBeSpoofedByBookHtml() {
        val prepared = LocalHtml.prepare("<body data-readx-load='999' onload='evil()'><p>正文</p><script>evil()</script></body>", ReaderSettings(), "#111111", "#ffffff", loadGeneration = 42)
        val document = Jsoup.parse(prepared)
        assertEquals("42", document.body().attr("data-readx-load"))
        assertFalse(document.body().hasAttr("onload"))
        assertTrue(document.select("script").isEmpty())
        assertTrue(prepared.contains("script-src 'none'"))
    }
    private fun zip(entries: Map<String, String>): File {
        val file = temp.newFile("book-" + System.nanoTime() + ".epub")
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
        return file
    }
}
