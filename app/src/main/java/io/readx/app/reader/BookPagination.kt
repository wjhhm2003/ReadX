package io.readx.app.reader

import android.net.Uri
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.viewinterop.AndroidView
import io.readx.app.data.Book
import io.readx.app.data.Chapter
import io.readx.app.data.LibraryRepository
import io.readx.app.ui.ReaderSettings
import io.readx.app.ui.ReadingLayout
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/** Real counts for one layout; unknown chapters are null, never estimated as one page. */
data class BookPageIndex(val counts: List<Int?>) {
    val measured = counts.count { it != null }
    val complete = counts.isNotEmpty() && measured == counts.size
    private val prefix = IntArray(counts.size+1).also { values->counts.forEachIndexed { i,count->values[i+1]=values[i]+(count ?: 0) } }
    val total: Int? = if (complete) prefix.last() else null
    fun globalPage(chapter: Int, page: Int): Int? = if (complete) prefix[chapter] + page.coerceIn(1,counts[chapter]!!) else null
    fun locate(page: Int): Pair<Int,Int>? {
        val total = total ?: return null
        val target=page.coerceIn(1,total)
        var lo=0;var hi=counts.size
        while(lo<hi) {val mid=(lo+hi)/2;if(prefix[mid+1]<target) lo=mid+1 else hi=mid}
        return lo to (target-prefix[lo])
    }
}

/** Bounded, disposable layout cache. No Room book progress semantics are changed by pagination. */
private class PageIndexCache(root: File) {
    private val directory = File(root,"page-indices").apply { mkdirs() }
    suspend fun load(key: String, chapters: Int): BookPageIndex? = withContext(Dispatchers.IO) {
        runCatching {
            val file=File(directory,"$key.json"); if (!file.isFile || file.length()>200_000) return@runCatching null
            val values=JSONArray(file.readText()); if(values.length()!=chapters) return@runCatching null
            BookPageIndex((0 until chapters).map { if(values.isNull(it)) null else values.getInt(it).takeIf { count->count in 1..1_000_000 } })
        }.getOrNull()
    }
    suspend fun save(key: String, index: BookPageIndex) = withContext(Dispatchers.IO) {
        val json=JSONArray(); index.counts.forEach { json.put(it ?: org.json.JSONObject.NULL) }
        val temporary=File(directory,"$key.tmp"); temporary.writeText(json.toString())
        val destination=File(directory,"$key.json")
        if (!temporary.renameTo(destination)) { destination.writeText(json.toString()); temporary.delete() }
        directory.listFiles { file -> file.extension=="json" }?.sortedByDescending { it.lastModified() }?.drop(24)?.forEach { it.delete() }
    }
}

@Composable
fun BookPageCounter(book: Book, chapters: List<Chapter>, settings: ReaderSettings, repository: LibraryRepository,
    viewport: Pair<Int,Int>, modifier: Modifier, retry: Int, onIndex: (BookPageIndex?, String?) -> Unit) {
    var counter by remember { mutableStateOf<LocalWebReader?>(null) }
    val notify by rememberUpdatedState(onIndex)
    AndroidView(modifier=modifier.alpha(0f), factory={ context ->
        LocalWebReader(context).apply {
            this.settings.javaScriptEnabled=false; this.settings.allowFileAccess=false; this.settings.allowContentAccess=false
            this.settings.blockNetworkLoads=true; this.settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            this.settings.useWideViewPort=true; this.settings.loadWithOverviewMode=false
            importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable=false; isClickable=false; isEnabled=false
            counter=this
        }
    }, onRelease={ it.release(); if(counter===it) counter=null })
    val fontScale=counter?.resources?.configuration?.fontScale ?: 1f
    val density=counter?.resources?.displayMetrics?.density ?: 1f
    val rawKey=listOf("columns-v7",book.fingerprint,chapters.map { it.href },viewport,density,settings.fontSize,settings.lineHeight,settings.margin,settings.serif,fontScale).joinToString("|")
    val key=MessageDigest.getInstance("SHA-256").digest(rawKey.toByteArray()).joinToString("") { "%02x".format(it) }
    LaunchedEffect(counter,key,retry) { withContext(Dispatchers.Main.immediate) {
        val view=counter ?: return@withContext
        if (viewport.first<=0 || viewport.second<=0 || settings.layout!=ReadingLayout.PAGED) { notify(null,null); return@withContext }
        val cache=PageIndexCache(view.context.cacheDir)
        var index=cache.load(key,chapters.size) ?: BookPageIndex(List(chapters.size) { null })
        notify(index,null)
        if(index.complete) return@withContext
        view.paged=true; view.settings.textZoom=(fontScale*100).toInt()
        val width=viewport.first/density; val height=viewport.second/density
        val content=repository.content(book.id)
        try {
            for (chapter in chapters) {
                ensureActive()
                if(index.counts[chapter.ordinal]!=null) continue
                val done=CompletableDeferred<Int>()
                val generation=++view.loadGeneration
                view.webViewClient=object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest)=true
                    override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse {
                        val uri=request.url
                        if(uri.scheme!="https" || uri.host!="appassets.androidplatform.net" || !uri.path.orEmpty().startsWith("/content/")) return denied()
                        return try {
                            val file=BookParser.safeFile(content,uri.path!!.removePrefix("/content/")); if(!file.isFile) return denied()
                            val mime=when(file.extension.lowercase()) { "html","xhtml","htm"->"text/html"; "css"->"text/css"; "svg"->"image/svg+xml"; else->MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension)?:"application/octet-stream" }
                            val stream=if(mime=="text/html") ByteArrayInputStream(LocalHtml.prepare(file.readText(),settings,"#111111","#ffffff",width,height).toByteArray()) else file.inputStream()
                            WebResourceResponse(mime,if(mime.startsWith("text/")) "UTF-8" else null,stream)
                        } catch (_: Exception) { denied() }
                    }
                    override fun onPageFinished(webView: WebView,url: String) {
                        var last=-1; var stable=0; var checks=0
                        fun check() {
                            if(!view.isCurrentLoad(generation) || done.isCompleted) return
                            val range=view.horizontalRange(); stable=if(range==last) stable+1 else 0; last=range; checks++
                            if(checks>=6 && stable>=3 && view.contentHeight>0) {
                                view.postVisualStateCallback(generation,object : WebView.VisualStateCallback() {
                                    override fun onComplete(requestId: Long) { if(view.isCurrentLoad(generation)) done.complete(view.pageInfo().second) }
                                })
                            } else if(checks>=60) done.completeExceptionally(IllegalStateException("章节排版未能稳定"))
                            else view.postDelayed({check()},50)
                        }
                        view.postDelayed({check()},50)
                    }
                }
                val href=chapter.href.split('/').joinToString("/") { Uri.encode(it) }
                view.loadUrl("https://appassets.androidplatform.net/content/$href")
                val count=withTimeout(8000) { done.await() }
                index=index.copy(counts=index.counts.toMutableList().also { it[chapter.ordinal]=count })
                notify(index,null)
                if(index.measured%4==0 || index.complete) cache.save(key,index)
                // One measured document at a time; yield to foreground input between chapters.
                yield()
            }
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { notify(index,e.message?:"全书分页统计失败，可重试") }
        finally { if(view.isCurrentLoad(view.loadGeneration)) { view.loadGeneration++; view.stopLoading() } }
    } }
}
private fun denied()=WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),ByteArrayInputStream(byteArrayOf()))
