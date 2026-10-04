package io.readx.app.reader

import android.net.Uri
import android.os.Build
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import io.readx.app.data.Book
import io.readx.app.data.Chapter
import io.readx.app.data.LibraryRepository
import io.readx.app.ui.ReaderSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.io.ByteArrayInputStream

/** Real counts for one layout; unknown chapters are null, never estimated as one page. */
data class BookPageIndex(val counts: List<Int?>) {
    init {
        require(counts.all { it == null || it in 1..1_000_000 })
        require(counts.sumOf { (it ?: 0).toLong() } <= Int.MAX_VALUE)
    }
    val measured = counts.count { it != null }
    val complete = counts.isNotEmpty() && measured == counts.size
    private val prefix = IntArray(counts.size + 1).also { values -> counts.forEachIndexed { i, count -> values[i + 1] = values[i] + (count ?: 0) } }
    val total: Int? = if (complete) prefix.last() else null
    fun withCount(chapter: Int, count: Int) = copy(counts = counts.toMutableList().also { it[chapter] = count })
    fun globalPage(chapter: Int, page: Int): Int? = if (complete && chapter in counts.indices) prefix[chapter] + page.coerceIn(1, counts[chapter]!!) else null
    fun locate(page: Int): Pair<Int, Int>? {
        val total = total ?: return null
        val target = page.coerceIn(1, total)
        var lo = 0; var hi = counts.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (prefix[mid + 1] < target) lo = mid + 1 else hi = mid }
        return lo to (target - prefix[lo])
    }
}

/** Mounted only after the foreground chapter's restored viewport has been visually committed. */
@Composable
fun BookPageCounter(book: Book, chapters: List<Chapter>, settings: ReaderSettings, repository: LibraryRepository,
    viewport: Pair<Int, Int>, modifier: Modifier, retry: Int, currentChapter: Int, foregroundPages: Int,
    onIndex: (BookPageIndex?, String?) -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val fontScale = LocalDensity.current.fontScale
    val webVersion = remember { WebView.getCurrentWebViewPackage()?.let { "${it.packageName}:${it.versionName}" }.orEmpty() }
    val locales = LocalConfiguration.current.locales.toLanguageTags()
    val config = remember(book.fingerprint, chapters, viewport, density, fontScale, settings.fontSize,
        settings.lineHeight, settings.margin, settings.serif, webVersion, locales) {
        LayoutConfig(book.fingerprint, chapters.map { it.href }, viewport.first, viewport.second, density,
            fontScale, settings.fontSize, settings.lineHeight, settings.margin, settings.serif,
            webVersion, Build.FINGERPRINT, locales)
    }
    val layoutKey = remember(config) { config.generateKey() }
    val cache = remember { PageIndexCache(context.applicationContext.cacheDir) }
    val coordinator = remember(book.id, layoutKey) { PaginationCoordinator(cache, book.id, layoutKey, chapters.size) }
    val state by coordinator.state.collectAsState()
    val notify by rememberUpdatedState(onIndex)
    LaunchedEffect(state) { notify(state.index, state.error) }
    var needsCounter by remember(layoutKey) { mutableStateOf(false) }
    var counter by remember(layoutKey) { mutableStateOf<LocalWebReader?>(null) }
    if (needsCounter) key(layoutKey) {
        AndroidView(modifier = modifier.alpha(0f), factory = { factoryContext ->
            LocalWebReader(factoryContext).apply {
                // Only bundled native-evaluated readiness code runs. Book scripts remain blocked by CSP.
                this.settings.javaScriptEnabled = true
                this.settings.allowFileAccess = false; this.settings.allowContentAccess = false
                this.settings.blockNetworkLoads = true; this.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                this.settings.useWideViewPort = true; this.settings.loadWithOverviewMode = false
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                isFocusable = false; isClickable = false; isEnabled = false
                doOnLayout { counter = this }
            }
        }, onRelease = { it.release(); if (counter === it) counter = null })
    }
    LaunchedEffect(coordinator, currentChapter, foregroundPages, retry) { withContext(Dispatchers.Main.immediate) {
        var usedView: LocalWebReader? = null
        var generation = -1L
        try {
            coordinator.calculate(currentChapter, foregroundPages) { ordinal ->
                // Cache hits never create a second WebView. Allow the foreground to draw before missing work.
                if (!needsCounter) { withFrameNanos { }; needsCounter = true }
                val view = snapshotFlow { counter?.takeIf { it.width == viewport.first && it.height == viewport.second } }.filterNotNull().first()
                usedView = view
                view.paged = true; view.settings.textZoom = (fontScale * 100).toInt().coerceAtLeast(1)
                view.viewportWidthCss = viewport.first / density; view.viewportHeightCss = viewport.second / density
                generation = ++view.loadGeneration
                val load = generation
                val done = CompletableDeferred<Int>()
                val content = repository.content(book.id)
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest) = true
                    override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse {
                        val uri = request.url
                        if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net" || !uri.path.orEmpty().startsWith("/content/")) return denied()
                        return try {
                            val file = BookParser.safeFile(content, uri.path!!.removePrefix("/content/"))
                            if (!file.isFile) return denied()
                            val mime = when (file.extension.lowercase()) { "html", "xhtml", "htm" -> "text/html"; "css" -> "text/css"; "svg" -> "image/svg+xml"; else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "application/octet-stream" }
                            val stream = if (mime == "text/html") ByteArrayInputStream(LocalHtml.prepare(file.readText(), settings, "#111111", "#ffffff", viewport.first / density, viewport.second / density, load).toByteArray()) else file.inputStream()
                            WebResourceResponse(mime, if (mime.startsWith("text/")) "UTF-8" else null, stream)
                        } catch (_: Exception) { denied() }
                    }
                    override fun onReceivedError(webView: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame && view.isCurrentLoad(load)) done.completeExceptionally(IllegalStateException("章节加载失败"))
                    }
                    override fun onReceivedHttpError(webView: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame && view.isCurrentLoad(load)) done.completeExceptionally(IllegalStateException("章节资源读取失败"))
                    }
                    override fun onPageFinished(webView: WebView, url: String) {
                        if (!view.isCurrentLoad(load) || done.isCompleted) return
                        view.whenLayoutStable(load, { done.complete(view.pageInfo().second) }, { done.completeExceptionally(IllegalStateException(it)) })
                    }
                }
                val href = chapters[ordinal].href.split('/').joinToString("/") { Uri.encode(it) }
                view.loadUrl("https://appassets.androidplatform.net/content/$href")
                try { withTimeout(10_000) { done.await() } }
                catch (timeout: TimeoutCancellationException) {
                    ensureActive()
                    throw IllegalStateException("章节分页统计超时，可重试", timeout)
                }
                finally { view.cancelLayoutCheck() }
            }
            needsCounter = false // Exact totals no longer retain a hidden renderer.
        } finally {
            usedView?.takeIf { it.isCurrentLoad(generation) }?.let { it.cancelLayoutCheck(); it.loadGeneration++; it.stopLoading() }
        }
    } }
}

private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
