package io.readx.app.reader

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import io.readx.app.data.LibraryRepository
import io.readx.app.ui.ReaderSession
import io.readx.app.ui.ReaderSettings
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.File

/** Local-only WebView. No JavaScript, no network, no file/content access and no native JS bridge. */
class ReaderController {
    internal var view: LocalWebReader? = null
    fun turn(direction: Int) { view?.turn(direction) }
}

class LocalWebReader(context: Context) : WebView(context) {
    var reportPosition: ((Float, Int, Int) -> Unit)? = null
    var boundary: ((Int) -> Unit)? = null
    var resized: ((Float) -> Unit)? = null
    var paged = true
    var restoring = true
    @Volatile var viewportWidthCss = 360f
    @Volatile var viewportHeightCss = 640f
    private var lastFraction = 0f
    private var released = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var consumedSwipe = false
    fun horizontalRange(): Int = computeHorizontalScrollRange()
    fun pageInfo(): Pair<Int, Int> {
        if (!paged || width <= 0) return 1 to 1
        val total = (kotlin.math.ceil((horizontalRange() - width).coerceAtLeast(0).toDouble() / width - 0.01).toInt() + 1).coerceAtLeast(1)
        return (kotlin.math.round(scrollX.toFloat() / width).toInt().coerceIn(0, total - 1) + 1) to total
    }
    fun fraction(): Float {
        if (paged) { val (current, total) = pageInfo(); return if (total <= 1) 0f else (current - 1f) / (total - 1f) }
        @Suppress("DEPRECATION") val range = (contentHeight * scale - height).coerceAtLeast(0f)
        return if (range == 0f) 0f else (scrollY / range).coerceIn(0f, 1f)
    }
    fun restore(fraction: Float) {
        if (paged) {
            val total = pageInfo().second
            scrollTo((kotlin.math.round(fraction.coerceIn(0f, 1f) * (total - 1)).toInt() * width), 0)
        } else {
            @Suppress("DEPRECATION") val range = (contentHeight * scale - height).coerceAtLeast(0f)
            scrollTo(0, (range * fraction).toInt())
        }
        report()
    }
    fun snap() { if (paged) scrollTo((pageInfo().first - 1) * width, 0); report() }
    fun turn(direction: Int) {
        if (restoring || released) return
        if (!paged) { scrollBy(0, direction * (height * .9f).toInt()); return }
        val (current, total) = pageInfo()
        val next = current + direction
        if (next in 1..total) { scrollTo((next - 1) * width, 0); report(); postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() { override fun onComplete(requestId: Long) { if (!released) { report(); invalidate() } } }) }
        else boundary?.invoke(direction)
    }
    fun report() { val (current, total) = pageInfo(); lastFraction = fraction(); reportPosition?.invoke(lastFraction, current, total) }
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!restoring && !released) report()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val oldFraction = if (oldw > 0) lastFraction else null
        viewportWidthCss = (w / resources.displayMetrics.density).coerceAtLeast(1f)
        viewportHeightCss = (h / resources.displayMetrics.density).coerceAtLeast(1f)
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldFraction != null && !restoring && !released && (w != oldw || h != oldh)) resized?.invoke(oldFraction)
    }
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!paged) return super.onTouchEvent(event)
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; downTime = event.eventTime; consumedSwipe = false }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - downX) > android.view.ViewConfiguration.get(context).scaledTouchSlop && kotlin.math.abs(event.x - downX) > kotlin.math.abs(event.y - downY) && event.eventTime - downTime < 700) {
                    if (!consumedSwipe) {
                        val cancel = android.view.MotionEvent.obtain(event)
                        cancel.action = android.view.MotionEvent.ACTION_CANCEL
                        super.onTouchEvent(cancel); cancel.recycle(); consumedSwipe = true
                    }
                    return true
                }
                if (consumedSwipe) return true
            }
            android.view.MotionEvent.ACTION_UP -> if (consumedSwipe) { turn(if (event.x < downX) 1 else -1); return true }
        }
        return super.onTouchEvent(event)
    }
    fun release() {
        if (released) return
        released = true
        reportPosition = null; boundary = null; resized = null
        stopLoading(); webViewClient = WebViewClient(); destroy()
    }
}

object LocalHtml {
    fun prepare(source: String, settings: ReaderSettings, foreground: String, background: String, viewportWidth: Float = 360f, viewportHeight: Float = 640f): String {
        val document = Jsoup.parse(source)
        document.select("script,iframe,object,embed,form,input,button,base,meta[http-equiv],link[rel=preload],svg foreignObject").remove()
        document.allElements.forEach { element ->
            element.attributes().asList().forEach { attr ->
                val name = attr.key.lowercase()
                val value = attr.value.trim().lowercase()
                if (name.startsWith("on") || name == "srcdoc" || ((name == "href" || name == "src" || name == "xlink:href") && (value.startsWith("javascript:") || value.startsWith("file:") || value.startsWith("content:")))) element.removeAttr(attr.key)
            }
        }
        document.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", "default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self' data:; script-src 'none'; connect-src 'none'; frame-src 'none'; base-uri 'self'; form-action 'none'")
        document.select("meta[name=viewport]").remove()
        document.head().appendElement("meta").attr("name", "viewport").attr("content", "width=$viewportWidth, height=$viewportHeight, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no")
        document.head().appendElement("style").text("""
            html { background: $background !important; color: $foreground !important; }
            body { margin: 0 !important; padding: 24px ${settings.margin}px 64px !important;
              font-family: ${if(settings.serif) "serif" else "sans-serif"} !important;
              font-size: ${settings.fontSize}px !important; line-height: ${settings.lineHeight} !important;
              overflow-wrap: anywhere; }
            body, p, div, span, li { color: $foreground !important; background-color: transparent !important; }
            p { margin-block: 0 1em; line-height: inherit !important; font-size: inherit !important; }
            h1 { font-size: 1.55em !important; line-height: 1.35; margin-bottom: 1.2em; }
            h2 { font-size: 1.3em !important; } img, svg { max-width: 100% !important; height: auto; }
            a { color: #608874; } ::selection { background: #cbdca0; }
            ${if (settings.layout == io.readx.app.ui.ReadingLayout.PAGED) "html { height: ${viewportHeight}px; overflow-y: hidden; } body { width: ${viewportWidth}px !important; height: ${viewportHeight}px !important; box-sizing: border-box !important; padding: 20px ${settings.margin}px !important; column-width: ${(viewportWidth - settings.margin * 2).coerceAtLeast(40f)}px !important; column-gap: ${settings.margin * 2}px !important; column-fill: auto; overflow: visible; } img, svg { max-height: ${(viewportHeight - 48).coerceAtLeast(1f)}px; object-fit: contain; break-inside: avoid; } p { orphans: 2; widows: 2; }" else "body { height: auto !important; column-width: auto !important; column-count: auto !important; overflow-x: hidden; }"}
        """.trimIndent())
        return document.outerHtml()
    }
}

@Composable
fun WebReader(
    session: ReaderSession, settings: ReaderSettings, repository: LibraryRepository,
    foreground: String, background: String, modifier: Modifier,
    navigate: (Int, String?) -> Unit, onPosition: (Float, Boolean, Int, Int) -> Unit,
    notify: (String) -> Unit, controller: ReaderController,
) {
    val currentPosition by rememberUpdatedState(onPosition)
    val currentNavigate by rememberUpdatedState(navigate)
    val currentNotify by rememberUpdatedState(notify)
    val chapter = session.chapters[session.chapter]
    key(session.book.id, session.chapter, session.target, session.find, session.occurrence) {
        AndroidView(modifier = modifier,
            factory = { context ->
                LocalWebReader(context).apply {
                    this.settings.textZoom = (resources.configuration.fontScale * 100).toInt().coerceAtLeast(1)
                    this.settings.javaScriptEnabled = false
                    this.settings.allowFileAccess = false
                    this.settings.allowContentAccess = false
                    this.settings.blockNetworkLoads = true
                    this.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    this.settings.useWideViewPort = true
                    this.settings.loadWithOverviewMode = false
                    this.settings.builtInZoomControls = false
                    this.settings.displayZoomControls = false
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                }
            },
            update = { view ->
                val signature = listOf(settings, foreground, background, chapter.href, session.target, session.find).hashCode()
                if (view.tag != signature) {
                    val restore = if (view.tag == null) session.fraction else view.fraction()
                    view.tag = signature
                    view.paged = settings.layout == io.readx.app.ui.ReadingLayout.PAGED
                    view.restoring = true
                    view.setBackgroundColor(Color.parseColor(background))
                    controller.view = view
                    view.reportPosition = { fraction, current, total -> currentPosition(fraction, false, current, total) }
                    view.boundary = { direction ->
                        val next = session.chapter + direction
                        if (next in session.chapters.indices) currentNavigate(next, if (direction < 0) "__readx_end__" else null)
                    }
                    view.resized = { fraction -> view.restoring = true; view.reload(); view.postDelayed({ view.restore(fraction); view.restoring = false }, 350) }
                    val content = repository.content(session.book.id)
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse {
                            val uri = request.url
                            if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net" || !uri.path.orEmpty().startsWith("/content/")) return blocked()
                            return try {
                                val relative = uri.path!!.removePrefix("/content/")
                                val file = BookParser.safeFile(content, relative)
                                if (!file.isFile) return blocked()
                                val ext = file.extension.lowercase()
                                val mime = when(ext) { "html", "htm", "xhtml" -> "text/html"; "css" -> "text/css"; "svg" -> "image/svg+xml"; else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream" }
                                val stream = if (mime == "text/html") ByteArrayInputStream(LocalHtml.prepare(file.readText(), settings, foreground, background, view.viewportWidthCss, view.viewportHeightCss).toByteArray()) else file.inputStream()
                                WebResourceResponse(mime, if (mime.startsWith("text/")) "UTF-8" else null, stream)
                            } catch (_: Exception) { blocked() }
                        }
                        override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net") { currentNotify("外部链接未打开：本版本仅访问本地内容"); return true }
                            val href = uri.path.orEmpty().removePrefix("/content/")
                            val index = session.chapters.indexOfFirst { it.href == href }
                            if (index >= 0 && (index != session.chapter || uri.fragment != null)) { currentNavigate(index, uri.fragment); return true }
                            if (index < 0) { currentNotify("该链接不在当前可阅读章节中"); return true }
                            return false
                        }
                        override fun onPageFinished(webView: WebView, url: String) {
                            var attempts = 0
                            fun restorePosition() {
                                if (view.tag != signature) return
                                if (view.contentHeight == 0 && ++attempts < 20) { view.postDelayed({ restorePosition() }, 100); return }
                                if (session.find == null && (session.target == null || session.target == "__readx_end__")) view.restore(if (session.target == "__readx_end__") 1f else restore)
                                else if (session.target != null) view.snap()
                                if (session.find != null) {
                                    var handled = false
                                    view.setFindListener { _, count, done ->
                                        if (done && !handled && count > 0) {
                                            handled = true
                                            repeat(session.occurrence.coerceAtMost(count - 1)) { view.findNext(true) }
                                            view.postDelayed({ view.snap() }, 120)
                                        }
                                    }
                                    view.findAllAsync(session.find)
                                }
                                view.restoring = false
                                view.report()
                            }
                            // Do not expose provisional column counts before Chromium has laid out the document.
                            var lastRange = -1
                            var stableFrames = 0
                            var checks = 0
                            fun waitForLayout() {
                                if (view.tag != signature) return
                                val range = view.horizontalRange()
                                stableFrames = if (range == lastRange) stableFrames + 1 else 0
                                lastRange = range; checks++
                                if (checks >= 6 && stableFrames >= 3 || checks >= 20) {
                                    view.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
                                        override fun onComplete(requestId: Long) { if (view.tag == signature) restorePosition() }
                                    })
                                } else view.postDelayed({ waitForLayout() }, 60)
                            }
                            view.postDelayed({ waitForLayout() }, 60)
                        }
                    }
                    val href = chapter.href.split('/').joinToString("/") { Uri.encode(it) }
                    view.isHorizontalScrollBarEnabled = false
                    view.isVerticalScrollBarEnabled = false
                    val url = "https://appassets.androidplatform.net/content/" + href + (session.target?.takeUnless { it == "__readx_end__" }?.let { "#" + Uri.encode(it) } ?: "")
                    // AndroidView.update can run before native measurement. Loading then would bake
                    // provisional column widths into HTML and cause half-pages until a later resize.
                    view.doOnLayout {
                        if (view.tag == signature) {
                            view.viewportWidthCss = view.width / view.resources.displayMetrics.density
                            view.viewportHeightCss = view.height / view.resources.displayMetrics.density
                            view.loadUrl(url)
                        }
                    }
                }
            },
            onRelease = { view -> val info = view.pageInfo(); currentPosition(view.fraction(), true, info.first, info.second); if (controller.view == view) controller.view = null; view.release() },
        )
    }
}
private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

