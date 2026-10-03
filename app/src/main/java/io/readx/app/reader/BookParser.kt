package io.readx.app.reader

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.zip.ZipFile

/** Engine-neutral parsed chapters. No Android dependencies: parser/security tests run on the JVM. */
data class ParsedChapter(val title: String, val href: String, val text: String)
data class ParsedBook(val title: String?, val author: String, val chapters: List<ParsedChapter>, val cover: String? = null)

object BookParser {
    private const val MAX_TEXT_BYTES = 32 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 160L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 24 * 1024 * 1024
    private val heading = Regex("^(第[零〇一二三四五六七八九十百千万两0-9]+[章回卷部节篇].{0,50}|(?:Chapter|CHAPTER)\\s+\\d+.{0,50}|序章|序言|前言|后记|尾声|引子)$")

    fun decodeText(bytes: ByteArray): String {
        require(bytes.size <= MAX_TEXT_BYTES) { "TXT 超过 32 MB，当前版本暂不支持" }
        val encoding = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> "UTF-8"
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> "UTF-16LE"
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> "UTF-16BE"
            else -> null
        }
        val decoded = if (encoding != null) String(bytes, Charset.forName(encoding)) else {
            runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            }.getOrElse { String(bytes, Charset.forName("GB18030")) }
        }
        return decoded.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")
    }

    fun txt(file: File, contentDir: File): ParsedBook {
        require(file.length() <= MAX_TEXT_BYTES) { "TXT 超过 32 MB，当前版本暂不支持" }
        val text = decodeText(file.readBytes())
        require(text.isNotBlank()) { "TXT 没有可阅读的正文" }
        val groups = mutableListOf<Pair<String, String>>()
        var title = "正文"
        var hasHeading = false
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotBlank() || hasHeading) groups += title to buffer.toString()
            buffer.clear(); hasHeading = false
        }
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (heading.matches(trimmed)) { flush(); title = trimmed; hasHeading = true }
            else buffer.appendLine(line)
        }
        flush()
        contentDir.mkdirs()
        val chapters = groups.mapIndexed { index, (name, body) ->
            val html = Jsoup.parse("<html><head><meta charset='utf-8'></head><body></body></html>")
            html.body().appendElement("h1").text(name)
            body.lineSequence().filter { it.isNotBlank() }.forEachIndexed { p, line ->
                html.body().appendElement("p").attr("id", "rx-p-$p").text(line)
            }
            val href = "chapter-$index.html"
            File(contentDir, href).writeText(html.outerHtml())
            ParsedChapter(name, href, name + "\n" + body)
        }
        return ParsedBook(null, "", chapters)
    }

    fun safeFile(root: File, relative: String): File {
        require(!relative.contains('\\') && !relative.startsWith('/') && !relative.contains(':')) { "EPUB 包含非法资源路径" }
        val target = File(root, relative).canonicalFile
        val canonicalRoot = root.canonicalFile
        require(target.toPath().startsWith(canonicalRoot.toPath()) && target != canonicalRoot) { "EPUB 包含越界资源路径" }
        return target
    }

    private fun resolve(base: String, href: String): String {
        val uri = java.net.URI(base).resolve(href).normalize()
        require(uri.scheme == null && uri.host == null && !uri.path.startsWith('/') && !uri.path.startsWith("../")) { "EPUB 包含外部或越界资源引用" }
        return uri.path
    }

    fun epub(file: File, contentDir: File): ParsedBook {
        contentDir.mkdirs()
        ZipFile(file).use { zip ->
            var expanded = 0L
            var count = 0
            zip.entries().asSequence().forEach { entry ->
                require(++count <= 10000) { "EPUB 资源数量超过安全上限" }
                val target = safeFile(contentDir, entry.name.trimEnd('/'))
                if (entry.isDirectory) { target.mkdirs(); return@forEach }
                require(entry.size <= MAX_ENTRY_BYTES) { "EPUB 单个资源过大" }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var entryBytes = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        expanded += n; entryBytes += n
                        require(expanded <= MAX_ARCHIVE_BYTES && entryBytes <= MAX_ENTRY_BYTES) { "EPUB 解压内容超过安全上限" }
                        output.write(buffer, 0, n)
                    }
                } }
            }
        }
        val containerFile = safeFile(contentDir, "META-INF/container.xml")
        require(containerFile.isFile) { "EPUB 缺少 container.xml" }
        val container = Jsoup.parse(containerFile.readText(), "", Parser.xmlParser())
        val opfPath = container.getElementsByTag("rootfile").firstOrNull()?.attr("full-path") ?: error("EPUB 缺少内容清单")
        val opf = Jsoup.parse(safeFile(contentDir, opfPath).readText(), "", Parser.xmlParser())
        require(opf.getElementsByTag("encryption").isEmpty() && !File(contentDir, "META-INF/encryption.xml").exists()) { "暂不支持加密或字体混淆 EPUB" }
        val metadata = opf.getElementsByTag("metadata").firstOrNull()
        fun meta(name: String) = metadata?.getAllElements()?.firstOrNull { it.tagName().substringAfter(':') == name }?.text()
        val manifest = opf.getElementsByTag("item").associateBy { it.attr("id") }
        val tocTitles = mutableMapOf<String, String>()
        manifest.values.firstOrNull { "nav" in it.attr("properties").split(' ') }?.let { item ->
            val navPath = resolve(opfPath, item.attr("href"))
            val nav = Jsoup.parse(safeFile(contentDir, navPath).readText())
            val toc = nav.select("nav").firstOrNull { it.attr("epub:type").split(' ').contains("toc") || it.attr("role") == "doc-toc" }
                ?: nav.selectFirst("nav")
            toc?.select("a[href]")?.forEach { link ->
                runCatching { tocTitles.putIfAbsent(resolve(navPath, link.attr("href")), link.text()) }
            }
        }
        val ncxId = opf.getElementsByTag("spine").firstOrNull()?.attr("toc")
        manifest[ncxId]?.let { item ->
            val ncxPath = resolve(opfPath, item.attr("href"))
            val ncx = Jsoup.parse(safeFile(contentDir, ncxPath).readText(), "", Parser.xmlParser())
            ncx.getElementsByTag("navPoint").forEach { point ->
                val src = point.getElementsByTag("content").firstOrNull()?.attr("src")
                val label = point.getElementsByTag("navLabel").firstOrNull()?.text()
                if (src != null && label != null) runCatching { tocTitles.putIfAbsent(resolve(ncxPath, src), label) }
            }
        }
        val chapters = opf.getElementsByTag("itemref").filter { it.attr("linear") != "no" }.mapIndexed { index, ref ->
            val item = manifest[ref.attr("idref")] ?: error("EPUB 章节清单不完整")
            val href = resolve(opfPath, item.attr("href"))
            val resource = safeFile(contentDir, href)
            require(resource.length() <= 8 * 1024 * 1024) { "EPUB 单章过大" }
            val document = Jsoup.parse(resource.readText())
            val title = tocTitles[href] ?: document.selectFirst("h1,h2,title")?.text()?.takeIf { it.isNotBlank() } ?: "第 $index 节"
            ParsedChapter(title, href, document.body().text())
        }
        require(chapters.isNotEmpty()) { "EPUB 没有可阅读的章节" }
        val coverId = metadata?.select("meta[name=cover]")?.firstOrNull()?.attr("content")
        val coverItem = manifest[coverId] ?: manifest.values.firstOrNull { it.attr("properties").split(' ').contains("cover-image") }
        val cover = coverItem?.let { runCatching { resolve(opfPath, it.attr("href")) }.getOrNull() }
        return ParsedBook(meta("title"), meta("creator").orEmpty(), chapters, cover)
    }
}
