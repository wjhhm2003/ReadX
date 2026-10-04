package io.readx.app.conversion

import org.jsoup.Jsoup
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** MIT epub-generator 0.1.7 adaptation: OEBPS manifest/spine/nav/container and mimetype-first ZIP.
 * Templates/protocol are adapted from commit 8223cc33142f5682d581ee907f85de45b4c94970.
 * ReadX adds bounded streaming, source-page anchors and no Python/LaTeX/remote runtime.
 */
object EpubOutput {
    data class Chapter(val title: String, val file: File)
    private fun xmlText(text:String):String = buildString {
        text.codePoints().forEach {cp->if(cp==9 || cp==10 || cp==13 || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF) append(String(Character.toChars(cp)))}
    }
    fun escape(text: String): String = xmlText(text).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
    fun write(output: File, id: String, title: String, author: String, chapters: List<Chapter>, images: List<File>, modifiedAt: Long = 0L, checkCancelled: ()->Unit = {}) {
        require(chapters.isNotEmpty() && chapters.size+images.size+6 <= 10000)
        require((chapters.map {it.file.length()}+images.map {it.length()}).sum()<=160L*1024*1024) { "转换内容超过 EPUB 资源上限" }
        ZipOutputStream(output.outputStream().buffered()).use {zip->
            fun entry(name: String, bytes: ByteArray, stored: Boolean=false) {
                checkCancelled(); require(bytes.size<=24*1024*1024)
                val e=ZipEntry(name).apply {time=0;if(stored) {method=ZipEntry.STORED;size=bytes.size.toLong();compressedSize=size;crc=CRC32().apply {update(bytes)}.value}}
                zip.putNextEntry(e);zip.write(bytes);zip.closeEntry()
            }
            entry("mimetype","application/epub+zip".toByteArray(),true)
            entry("META-INF/container.xml","""<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
            val manifest=chapters.indices.joinToString("") {"<item id='c$it' href='chapter-$it.xhtml' media-type='application/xhtml+xml'/>"}+images.joinToString("") {"<item id='${it.nameWithoutExtension}' href='images/${it.name}' media-type='image/jpeg'${if(it.name=="p0.jpg") " properties='cover-image'" else ""}/>"}
            val spine=chapters.indices.joinToString("") {"<itemref idref='c$it'/>"}
            entry("OEBPS/content.opf","""<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0" unique-identifier="uid"><metadata><dc:identifier id="uid">urn:readx:$id</dc:identifier><dc:title>${escape(title)}</dc:title><dc:creator>${escape(author)}</dc:creator><dc:language>zh</dc:language><meta property="dcterms:modified">${java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.ofEpochMilli(modifiedAt))}</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest><spine>$spine</spine></package>""".toByteArray())
            val toc=chapters.mapIndexed {i,c->"<li><a href='chapter-$i.xhtml'>${escape(c.title)}</a></li>"}.joinToString("")
            entry("OEBPS/nav.xhtml","""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><h1>目录</h1><ol>$toc</ol></nav></body></html>""".toByteArray())
            chapters.forEachIndexed {i,c->require(c.file.length()<=8L*1024*1024);entry("OEBPS/chapter-$i.xhtml",c.file.readBytes())}
            images.forEach {entry("OEBPS/images/${it.name}",it.readBytes())}
        }
    }
    fun validate(file:File) {
        java.util.zip.ZipFile(file).use {zip->
            require(zip.entries().nextElement().name=="mimetype" && zip.getEntry("mimetype").method==ZipEntry.STORED)
            val builder=javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true}.newDocumentBuilder()
            builder.setEntityResolver {_,_->org.xml.sax.InputSource(java.io.StringReader(""))}
            for(entry in zip.entries().asSequence().filter {it.name.endsWith(".xml") || it.name.endsWith(".opf") || it.name.endsWith(".xhtml")}) {
                zip.getInputStream(entry).use {builder.parse(it)}
            }
        }
    }
    fun chapterHtml(title: String, sections: String): String {
        val document=Jsoup.parse("<html><head><meta charset='UTF-8'/></head><body></body></html>")
        document.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
        document.documentType()?.remove()
        document.selectFirst("html")!!.attr("xmlns","http://www.w3.org/1999/xhtml")
        document.head().appendElement("title").text(xmlText(title))
        document.body().appendElement("h1").text(xmlText(title))
        document.body().append(sections)
        var previous:org.jsoup.nodes.Element?=null
        for(section in document.select("section[data-source-page]")) {
            val first=section.selectFirst("p")
            val last=previous
            if(first!=null && last!=null && section.select("img").isEmpty() && last.text().isNotBlank() &&
                last.text().last() !in "。！？.!?:：；;" && !last.text().trim().all {it.isDigit()} &&
                !first.text().matches(Regex("^(第.{1,12}[章卷节].*|Chapter .*)$"))) {
                val before=last.text()
                val following=first.text()
                val joined=PdfTextFlow.join(before,following)
                if(joined.startsWith(before)) {
                    last.appendElement("span").attr("data-source-page",section.attr("data-source-page")).text(joined.substring(before.length))
                } else { last.text(joined) }
                first.remove()
            }
            previous=if(section.select("img").isNotEmpty()) null else section.select("p").lastOrNull() ?: previous
        }
        return document.outerHtml()
    }
    fun sourcePage(chapterHtml: String): Int? = Jsoup.parse(chapterHtml).selectFirst("[data-source-page]")?.attr("data-source-page")?.toIntOrNull()
}
