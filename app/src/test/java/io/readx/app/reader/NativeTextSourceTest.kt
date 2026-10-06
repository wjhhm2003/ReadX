package io.readx.app.reader

import io.readx.app.ui.ReaderSettings
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.util.UUID

class NativeTextSourceTest {
    @Test fun mapsPreparedDomNotDatabaseBodyAndKeepsEmojiAndWhitespace()=runBlocking {
        val dir=Files.createTempDirectory("readx-native-test").toFile()
        try {
            val input=File(dir,"input.txt").apply {writeText("第一章 映射\n\n前文 😀中文 & <符号>\n重复文本\n\n后文 🧑‍💻结束\n")}
            val parsed=BookParser.txt(input,File(dir,"content"));val html=File(dir,"content/${parsed.chapters[0].href}")
            val prepared=LocalHtml.prepare(html.readText(),ReaderSettings(),"#000000","#ffffff",360f,700f)
            val text=StringBuilder()
            fun walk(node:org.jsoup.nodes.Node) {if(node is TextNode)text.append(node.wholeText);else if(node.nodeName() !in listOf("script","style","noscript"))node.childNodes().forEach(::walk)}
            walk(Jsoup.parse(prepared).body())
            val source=NativeTextSource.open(File(dir,"cache"),UUID.randomUUID().toString(),0,html)
            assertEquals(text.length,source.canonicalLength)
            val start=text.indexOf("😀中文");val old=TextAnchor(start,start+4,"😀中文",text.substring((start-8).coerceAtLeast(0),start),text.substring(start+4,start+8))
            val resolved=source.resolve(old)!!;val reconstructed=source.anchor(resolved.first,resolved.second)!!
            assertEquals(old.start,reconstructed.start);assertEquals(old.quote,reconstructed.quote)
            assertNotEquals(parsed.chapters[0].text.indexOf("😀中文"),old.start)
            val restored=NativeTextSource.open(File(dir,"cache"),source.directory.parentFile!!.name,0,html)
            assertEquals(source.length,restored.length)
        } finally {dir.deleteRecursively()}
    }
    @Test fun duplicateWithoutContextNeverGuesses()=runBlocking {
        val dir=Files.createTempDirectory("readx-ambiguous").toFile()
        try {val html=File(dir,"chapter.html").apply {writeText("<html><body><h1>正文</h1><p>重复😀</p><p>重复😀</p></body></html>")}
            val source=NativeTextSource.open(dir,UUID.randomUUID().toString(),0,html)
            assertNull(source.resolve(TextAnchor(999,1003,"重复😀")))
        } finally {dir.deleteRecursively()}
    }
}
