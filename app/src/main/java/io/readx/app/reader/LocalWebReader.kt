package io.readx.app.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
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

/** Local-only WebView. No JavaScript, no network, no file/content access and no native JS bridge. */
class ReaderController {
    internal var view: LocalWebReader? = null
    fun turn(direction: Int) { view?.turn(direction) }
    fun jumpToPage(page: Int) { view?.jumpToPage(page) }
}

class LocalWebReader(context: Context) : WebView(context) {
    var reportPosition: ((Float, Int, Int) -> Unit)? = null
    var boundary: ((Int) -> Unit)? = null
    var resized: ((Float) -> Unit)? = null
    var paged = true
    var restoring = true
    var loadGeneration = 0L
    @Volatile var viewportWidthCss = 360f
    @Volatile var viewportHeightCss = 640f
    private var lastFraction = 0f
    private var released = false
    var linkOriginFraction = 0f
        private set
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var dragStartX = 0
    private var consumedSwipe = false
    private var pageAnimator: ValueAnimator? = null
    private var destinationX = 0
    fun isCurrentLoad(generation: Long) = !released && loadGeneration == generation
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
    fun finishTransition() {
        pageAnimator?.let { animator ->
            animator.removeAllListeners(); animator.cancel(); pageAnimator = null
            scrollTo(destinationX, 0)
        }
        animate().cancel()
        alpha = 1f
    }
    fun restore(fraction: Float) {
        finishTransition()
        if (paged) {
            val total = pageInfo().second
            scrollTo((kotlin.math.round(fraction.coerceIn(0f, 1f) * (total - 1)).toInt() * width), 0)
        } else {
            @Suppress("DEPRECATION") val range = (contentHeight * scale - height).coerceAtLeast(0f)
            scrollTo(0, (range * fraction.coerceIn(0f, 1f)).toInt())
        }
        report()
    }
    fun snap() { if (paged) scrollTo((pageInfo().first - 1) * width, 0); report() }
    fun jumpToPage(page: Int) {
        if (restoring || released || !paged) return
        finishTransition()
        // One native scroll on slider release; no HTML reload or intermediate page rendering.
        scrollTo((page.coerceIn(1, pageInfo().second) - 1) * width, 0)
        report()
        invalidate()
    }
    private fun animateTo(x: Int) {
        destinationX = x
        if (!ValueAnimator.areAnimatorsEnabled() || x == scrollX) {
            scrollTo(x, 0); report(); return
        }
        pageAnimator = ValueAnimator.ofInt(scrollX, x).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { if (!released) scrollTo(it.animatedValue as Int, 0) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    pageAnimator = null
                    if (!released) { scrollTo(x, 0); report(); invalidate() }
                }
            })
            start()
        }
    }
    private fun crossBoundary(direction: Int) {
        // Suppress outgoing callbacks before replacing the chapter WebView.
        restoring = true
        if (ValueAnimator.areAnimatorsEnabled()) {
            animate().alpha(0f).setDuration(100).withEndAction { if (!released) boundary?.invoke(direction) }.start()
        } else boundary?.invoke(direction)
    }
    fun turn(direction: Int) {
        if (restoring || released) return
        finishTransition()
        if (!paged) { scrollBy(0, direction * (height * .9f).toInt()); return }
        val (current, total) = pageInfo()
        val next = current + direction
        if (next in 1..total) animateTo((next - 1) * width)
        else crossBoundary(direction)
    }
    fun report() {
        if (restoring || released || pageAnimator != null || consumedSwipe) return
        val (current, total) = pageInfo(); lastFraction = fraction()
        reportPosition?.invoke(lastFraction, current, total)
    }
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        report()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val oldFraction = if (oldw > 0) lastFraction else null
        viewportWidthCss = (w / resources.displayMetrics.density).coerceAtLeast(1f)
        viewportHeightCss = (h / resources.displayMetrics.density).coerceAtLeast(1f)
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldFraction != null && !restoring && !released && (w != oldw || h != oldh)) resized?.invoke(oldFraction)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (restoring) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { finishTransition(); linkOriginFraction = fraction() }
        if (!paged || event.pointerCount > 1) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downTime = event.eventTime
                dragStartX = (pageInfo().first - 1) * width; consumedSwipe = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (!consumedSwipe && kotlin.math.abs(dx) > ViewConfiguration.get(context).scaledTouchSlop &&
                    kotlin.math.abs(dx) > kotlin.math.abs(event.y - downY) && event.eventTime - downTime < 700) {
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.onTouchEvent(cancel); cancel.recycle(); consumedSwipe = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (consumedSwipe) {
                    val maxX = (pageInfo().second - 1) * width
                    scrollTo((dragStartX - dx.toInt()).coerceIn(
                        (dragStartX - width).coerceAtLeast(0), (dragStartX + width).coerceAtMost(maxX)), 0)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> if (consumedSwipe) {
                consumedSwipe = false
                parent?.requestDisallowInterceptTouchEvent(false)
                val dx = event.x - downX
                val direction = if (dx < 0) 1 else -1
                val next = dragStartX / width + 1 + direction
                if (kotlin.math.abs(dx) < width * .12f) animateTo(dragStartX)
                else if (next in 1..pageInfo().second) animateTo((next - 1) * width)
                else { scrollTo(dragStartX, 0); crossBoundary(direction) }
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (consumedSwipe) {
                consumedSwipe = false; parent?.requestDisallowInterceptTouchEvent(false)
                animateTo(dragStartX); return true
            }
        }
        return super.onTouchEvent(event)
    }
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_ENTER || keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER) linkOriginFraction = fraction()
        return super.onKeyDown(keyCode, event)
    }
    fun release() {
        if (released) return
        finishTransition()
        released = true; loadGeneration++
        reportPosition = null; boundary = null; resized = null
        stopLoading(); webViewClient = WebViewClient(); destroy()
    }
}

object LocalHtml {
    fun prepare(source: String, settings: ReaderSettings, foreground: String, background: String, viewportWidth: Float = 360f, viewportHeight: Float = 640f): String {
        // Chromium's viewport meta width is integral. Use that same width in columns and scale
        // height proportionally; fractional density conversions otherwise accumulate page drift.
        val pageWidth = kotlin.math.round(viewportWidth).coerceAtLeast(1f)
        val pageHeight = kotlin.math.floor(viewportHeight * pageWidth / viewportWidth.coerceAtLeast(1f)).coerceAtLeast(1f)
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
        document.head().appendElement("meta").attr("name", "viewport").attr("content", "width=${pageWidth.toInt()}, height=${pageHeight.toInt()}, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no")
        // The net-zero-height paged tail includes the final right margin in Chromium's scroll range.
        // Without it, scrollTo(lastPage * width) is clamped short and the last column is misaligned.
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
            ${if (settings.layout == io.readx.app.ui.ReadingLayout.PAGED) "html { height: ${pageHeight}px; overflow-y: hidden; } body { width: ${pageWidth}px !important; height: ${pageHeight}px !important; box-sizing: border-box !important; padding: 20px ${settings.margin}px !important; column-width: ${(pageWidth - settings.margin * 2).coerceAtLeast(40f)}px !important; column-gap: ${settings.margin * 2}px !important; column-fill: auto; overflow: visible; } img, svg { max-height: ${(pageHeight - 48).coerceAtLeast(1f)}px; object-fit: contain; break-inside: avoid; } p { orphans: 2; widows: 2; } body::after { content: ''; display: block; width: calc(100% + ${settings.margin}px); height: 1px; margin-top: -1px; }" else "body { height: auto !important; column-width: auto !important; column-count: auto !important; overflow-x: hidden; }"}
        """.trimIndent())
        return document.outerHtml()
    }
}

@Composable
fun WebReader(
    session: ReaderSession, settings: ReaderSettings, repository: LibraryRepository,
    foreground: String, background: String, modifier: Modifier,
    navigate: (Int, String?) -> Unit, followLink: (Int, String?, Float) -> Unit,
    onPosition: (Float, Boolean, Int, Int) -> Unit,
    notify: (String) -> Unit, controller: ReaderController,
) {
    val chapter = session.chapters[session.chapter]
    key(session.book.id, session.navigationId, session.chapter, session.target, session.find, session.occurrence) {
        // These bindings belong to this navigation, not to the incoming chapter during onRelease.
        val currentPosition by rememberUpdatedState(onPosition)
        val currentNavigate by rememberUpdatedState(navigate)
        val currentLink by rememberUpdatedState(followLink)
        val currentNotify by rememberUpdatedState(notify)
        AndroidView(modifier = modifier,
            factory = { context ->
                LocalWebReader(context).apply {
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
                    isHorizontalScrollBarEnabled = false; isVerticalScrollBarEnabled = false
                    alpha = 0f
                }
            },
            update = { view ->
                val fontScale = view.resources.configuration.fontScale
                val signature = listOf(settings, foreground, background, chapter.href, session.navigationId, fontScale)
                if (view.tag != signature) {
                    view.finishTransition()
                    var restore = if (view.tag == null) session.fraction else view.fraction()
                    var target = if (view.tag == null) session.target else null
                    var handled = false
                    var loadedUrl = ""
                    view.tag = signature
                    view.paged = settings.layout == io.readx.app.ui.ReadingLayout.PAGED
                    view.restoring = true
                    view.settings.textZoom = (fontScale * 100).toInt().coerceAtLeast(1)
                    view.setBackgroundColor(Color.parseColor(background))
                    controller.view = view
                    view.reportPosition = { fraction, current, total -> currentPosition(fraction, false, current, total) }
                    view.boundary = { direction ->
                        val next = session.chapter + direction
                        if (next in session.chapters.indices) currentNavigate(next, if (direction < 0) "__readx_end__" else null)
                        else { view.restoring = false; view.alpha = 1f; view.report() }
                    }
                    val href = chapter.href.split('/').joinToString("/") { Uri.encode(it) }
                    val baseUrl = "https://appassets.androidplatform.net/content/" + href
                    view.resized = { fraction ->
                        view.finishTransition(); restore = fraction; target = null; handled = false
                        view.restoring = true; view.loadGeneration++; loadedUrl = baseUrl
                        view.loadUrl(baseUrl)
                    }
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
                            val linkedHref = uri.path.orEmpty().removePrefix("/content/")
                            val index = session.chapters.indexOfFirst { it.href == linkedHref }
                            if (index < 0) { currentNotify("该链接不在当前可阅读章节中"); return true }
                            view.finishTransition()
                            val origin = view.fraction()
                            view.restoring = true
                            currentLink(index, uri.fragment, origin)
                            return true
                        }
                        override fun doUpdateVisitedHistory(webView: WebView, url: String, isReload: Boolean) {
                            // Chromium does not call shouldOverrideUrlLoading for same-document #fragment links.
                            // Capture the source before native anchor scrolling, then enter the same managed return flow.
                            val uri = Uri.parse(url)
                            if (!view.restoring && url != loadedUrl && uri.scheme == "https" &&
                                uri.host == "appassets.androidplatform.net" &&
                                uri.path.orEmpty().removePrefix("/content/") == chapter.href && uri.fragment != null) {
                                val origin = view.linkOriginFraction
                                view.restoring = true
                                currentLink(session.chapter, uri.fragment, origin)
                            }
                        }
                        override fun onPageFinished(webView: WebView, url: String) {
                            if (view.tag != signature || handled) return
                            handled = true
                            val generation = view.loadGeneration
                            fun active() = view.tag == signature && view.isCurrentLoad(generation)
                            fun restorePosition() {
                                if (!active()) return
                                // Fragment navigation must remain at its target. Never restore the source fraction afterwards.
                                if (target == "__readx_end__") view.restore(1f)
                                else if (target != null) view.snap()
                                else view.restore(restore)
                                view.restoring = false
                                view.report()
                                view.alpha = if (ValueAnimator.areAnimatorsEnabled()) 0f else 1f
                                view.animate().alpha(1f).setDuration(140).start()
                                if (session.find != null) {
                                    var found = false
                                    view.setFindListener { _, count, done ->
                                        if (done && !found && active()) {
                                            found = true
                                            if (count > 0) {
                                                repeat(session.occurrence.coerceAtMost(count - 1)) { view.findNext(true) }
                                                view.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
                                                    override fun onComplete(requestId: Long) { if (active()) view.snap() }
                                                })
                                            }
                                        }
                                    }
                                    view.findAllAsync(session.find)
                                }
                            }
                            var lastRange = -1
                            var stableFrames = 0
                            var checks = 0
                            fun waitForLayout() {
                                if (!active()) return
                                val range = view.horizontalRange()
                                stableFrames = if (range == lastRange) stableFrames + 1 else 0
                                lastRange = range; checks++
                                if ((checks >= 6 && stableFrames >= 3 && view.contentHeight > 0) || checks >= 20) {
                                    view.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
                                        override fun onComplete(requestId: Long) { if (active()) restorePosition() }
                                    })
                                } else view.postDelayed({ waitForLayout() }, 60)
                            }
                            view.postDelayed({ waitForLayout() }, 60)
                        }
                    }
                    // HTML pagination is derived only from the measured reading viewport.
                    view.doOnLayout {
                        if (view.tag == signature) {
                            view.stopLoading(); view.loadGeneration++
                            view.viewportWidthCss = view.width / view.resources.displayMetrics.density
                            view.viewportHeightCss = view.height / view.resources.displayMetrics.density
                            val url = baseUrl + (target?.takeUnless { it == "__readx_end__" }?.let { "#" + Uri.encode(it) } ?: "")
                            loadedUrl = url
                            view.loadUrl(url)
                        }
                    }
                }
            },
            onRelease = { view ->
                view.finishTransition()
                if (!view.restoring) {
                    val info = view.pageInfo()
                    currentPosition(view.fraction(), true, info.first, info.second)
                }
                if (controller.view == view) controller.view = null
                view.release()
            },
        )
    }
}
private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
