package io.readx.app.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
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
import io.readx.app.data.Annotation
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import io.readx.app.ui.ReaderSession
import io.readx.app.ui.ReaderSettings
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream

/** Local-only WebView. Book scripts blocked by CSP; only bundled native-evaluated code, no JS bridge or network. */
class ReaderController {
    internal var view: LocalWebReader? = null
    internal var native: NativeReaderView? = null
    fun captureAnchor(callback: (TextAnchor?) -> Unit) { native?.captureAnchor(callback) ?: view?.captureViewportAnchor(callback) ?: callback(null) }
    fun clearSelection() { native?.clearSelection(); view?.clearReaderSelection() }
    fun selectionPage(direction:Int) {
        native?.turn(direction)
        view?.let {v->v.trusted("window.ReadX.preserveSelection(${v.paged},$direction)") {v.report();v.showSelectionPopup()}}
    }
    fun snapSelection(unit:SelectionUnit) {native?.snapSelection(unit);view?.showSelectionPopup("window.ReadX.snapSelection(${JSONObject.quote(unit.name)})")}
    fun extendSelection(direction:Int) {view?.showSelectionPopup("window.ReadX.extendSelection($direction)")}
    fun report() { native?.report(); view?.finishTransition(); view?.report() }
    fun turn(direction: Int) { native?.turn(direction) ?: view?.turn(direction) }
    fun jumpToPage(page: Int) { native?.jumpToPage(page) ?: view?.jumpToPage(page) }
    fun selection(callback: (TextAnchor?) -> Unit) { view?.readAnchor("window.ReadX.selection()", callback) ?: callback(null) }
    fun restoreAnchor(anchor: TextAnchor) { view?.navigateAnchor(anchor) }

}

class LocalWebReader(context: Context) : WebView(context) {
    var reportPosition: ((Float, Int, Int) -> Unit)? = null
    var boundary: ((Int) -> Unit)? = null
    var resized: ((Float) -> Unit)? = null
    var tapZone: ((Int) -> Unit)? = null
    var selectionPopup: ((ReaderSelection?) -> Unit)? = null
    private var lastSelectionRead=0L
    var annotationSelected: ((String, TextAnchor) -> Unit)? = null
    var sourceAnchor: TextAnchor? = null
        private set
    private var selectionActive = false
    private var windowActionMode: ActionMode? = null
    private var annotationGeneration = -1L
    private var displayedAnnotations: List<Annotation>? = null
    private val trustedScript by lazy { context.assets.open("reader.js").bufferedReader().use { it.readText().removePrefix("\uFEFF") } }
    fun trusted(expression: String, callback: (String) -> Unit = {}) {
        if(released) return
        val generation = loadGeneration
        val uri = Uri.parse(url ?: return)
        if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net") return
        evaluateJavascript(expression) { if (isCurrentLoad(generation)) callback(it) }
    }
    fun showSelectionPopup(expression: String="window.ReadX.selectionInfo()") {
        trusted("JSON.stringify($expression)") {result->
            val raw=runCatching {JSONTokener(result).nextValue() as? String}.getOrNull()
            selectionPopup?.invoke(raw?.let(ReaderSelection::parse))
        }
    }
    fun clearReaderSelection() {
        windowActionMode?.finish();windowActionMode=null;selectionActive=false
        trusted("window.ReadX.clearSelection()")
        selectionPopup?.invoke(null)
    }
    fun initializeTrusted(callback: () -> Unit) = trusted(trustedScript) { callback() }
    fun readAnchor(expression: String, callback: (TextAnchor?) -> Unit) {
        trusted("JSON.stringify($expression)") { result ->
            val value = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
            callback(value?.let(TextAnchor::parse))
        }
    }
    fun captureViewportAnchor(callback: (TextAnchor?) -> Unit) {
        val generation = loadGeneration
        // Native scrollTo may precede Chromium's DOM viewport update; never capture the previous page.
        postVisualStateCallback(generation, object : VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (isCurrentLoad(generation)) readAnchor("window.ReadX.viewportAnchor()", callback)
            }
        })
    }
    fun navigateAnchor(anchor: TextAnchor, restoringPosition: Boolean = false, finished: (Boolean) -> Unit = {}) {
        trusted("JSON.stringify(window.ReadX.navigate(${anchor.json()},$paged))") { result ->
            val value = runCatching { JSONObject(JSONTokener(result).nextValue() as String) }.getOrNull()
            val found = value?.optBoolean("found") == true
            if (found && paged) {
                val page = value!!.optInt("page", 1)
                if (restoringPosition) { finishTransition(); scrollTo((page.coerceIn(1, pageInfo().second) - 1) * width, 0) }
                else jumpToPage(page)
            }
            if (found) report()
            finished(found)
        }
    }
    fun applyAnnotations(annotations: List<Annotation>, finished: () -> Unit = {}) {
        if (annotationGeneration == loadGeneration && displayedAnnotations == annotations) { finished(); return }
        val hadMarks = displayedAnnotations?.any { it.kind != "BOOKMARK" } == true
        fun structure(rows: List<Annotation>)=rows.filter {it.kind!="BOOKMARK"}.map {Triple(it.id,it.kind,it.locator)}
        val restyle=annotationGeneration==loadGeneration && displayedAnnotations?.let {structure(it)==structure(annotations)}==true
        annotationGeneration = loadGeneration; displayedAnnotations = annotations.toList()
        if (annotations.none { it.kind != "BOOKMARK" } && !hadMarks) { finished(); return }
        val items = JSONArray()
        annotations.filter { it.kind != "BOOKMARK" }.forEach { annotation ->
            TextAnchor.parse(annotation.locator)?.let { anchor -> items.put(JSONObject().put("id", annotation.id).put("kind", annotation.kind).put("anchor", anchor.json()).put("color",annotation.color).put("updatedAt",maxOf(annotation.updatedAt,annotation.createdAt))) }
        }
        trusted("window.ReadX.${if(restyle) "restyle" else "marks"}($items)") { finished() }
    }
    fun bindWindowActionMode(mode: ActionMode) {
        windowActionMode=mode;selectionActive=true
        mode.menu.clear()
        post {if(windowActionMode===mode) {mode.menu.clear();showSelectionPopup()}}
    }
    fun unbindWindowActionMode(mode: ActionMode) {
        if(windowActionMode===mode) {windowActionMode=null;selectionActive=false;selectionPopup?.invoke(null);report()}
    }
    override fun startActionMode(callback: ActionMode.Callback): ActionMode?=startActionMode(callback,ActionMode.TYPE_FLOATING)
    override fun startActionMode(callback: ActionMode.Callback,type: Int): ActionMode? {
        val wrapper=object: ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode,menu: Menu): Boolean {
                val created=callback.onCreateActionMode(mode,menu);menu.clear()
                if(created) {selectionActive=true;windowActionMode=mode;post {showSelectionPopup()}}
                return created
            }
            override fun onPrepareActionMode(mode: ActionMode,menu: Menu): Boolean {callback.onPrepareActionMode(mode,menu);menu.clear();return true}
            override fun onActionItemClicked(mode: ActionMode,item: MenuItem)=false
            override fun onDestroyActionMode(mode: ActionMode) {unbindWindowActionMode(mode);callback.onDestroyActionMode(mode)}
            override fun onGetContentRect(mode: ActionMode,view: android.view.View,out: android.graphics.Rect) {
                if(callback is ActionMode.Callback2) callback.onGetContentRect(mode,view,out) else super.onGetContentRect(mode,view,out)
            }
        }
        return super.startActionMode(wrapper,type)
    }
    var paged = true
    var restoring = true
    internal var pendingReflowAnchor: TextAnchor? = null
    internal var pendingRestoreFraction: Float? = null
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
    val isTurning get() = pageAnimator != null
    private var destinationX = 0
    fun isCurrentLoad(generation: Long) = !released && loadGeneration == generation
    private var cancelPendingLayout: (() -> Unit)? = null
    fun cancelLayoutCheck() { cancelPendingLayout?.invoke(); cancelPendingLayout = null }

    /** No minimum wall-clock sleep: resources + consecutive frames + Chromium visual commit. */
    fun whenLayoutStable(generation: Long, ready: () -> Unit, failed: (String) -> Unit) {
        cancelLayoutCheck()
        var cancelled = false
        var lastRange = -1
        var lastHeight = -1
        var stableFrames = 0
        lateinit var check: Runnable
        lateinit var timeout: Runnable
        fun active() = !cancelled && isCurrentLoad(generation)
        fun cancel() { cancelled = true; removeCallbacks(check); removeCallbacks(timeout) }
        timeout = Runnable { if (active()) { cancel(); failed("章节排版未能在限时内稳定") } }
        check = Runnable {
            if (!active()) return@Runnable
            trusted("document.body && document.body.getAttribute('data-readx-load') === '$generation' && document.readyState === 'complete' && (!document.fonts || document.fonts.status === 'loaded') && Array.from(document.images).every(i => i.complete)") { value ->
                if (!active()) return@trusted
                val range = horizontalRange()
                val height = contentHeight
                stableFrames = if (value == "true" && range == lastRange && height == lastHeight && height > 0) stableFrames + 1 else 0
                lastRange = range; lastHeight = height
                if (stableFrames >= 2) {
                    postVisualStateCallback(generation, object : VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (!active()) return
                            if (horizontalRange() == range && contentHeight == height) { cancel(); ready() }
                            else { stableFrames = 0; postOnAnimation(check) }
                        }
                    })
                } else postOnAnimation(check)
            }
        }
        cancelPendingLayout = ::cancel
        postDelayed(timeout, 8_000)
        postOnAnimation(check)
    }
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
                    if (!released) {
                        scrollTo(x,0)
                        postVisualStateCallback(loadGeneration,object: WebView.VisualStateCallback() {override fun onComplete(requestId: Long) {if(!released) {report();invalidate()}}})
                    }
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
    private var lastAnchorPosition: Pair<Int,Int>? = null
    fun report() {
        if (restoring || released || pageAnimator != null || consumedSwipe) return
        val (current, total) = pageInfo(); lastFraction = fraction()
        val position=scrollX to scrollY
        if(position!=lastAnchorPosition) {
            lastAnchorPosition=position
            captureViewportAnchor {anchor->if((scrollX to scrollY)==position) {sourceAnchor=anchor;reportPosition?.invoke(lastFraction,current,total)}}
        }
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
    private fun handleShortTap(event: MotionEvent): Boolean {
        if(event.actionMasked!=MotionEvent.ACTION_UP) return false
        if (!selectionActive && event.eventTime - downTime < ViewConfiguration.getLongPressTimeout() &&
                kotlin.math.abs(event.x-downX) < ViewConfiguration.get(context).scaledTouchSlop &&
                kotlin.math.abs(event.y-downY) < ViewConfiguration.get(context).scaledTouchSlop) {
                val x=event.x; val y=event.y
                val cancel=MotionEvent.obtain(event); cancel.action=MotionEvent.ACTION_CANCEL; super.onTouchEvent(cancel); cancel.recycle()
                trusted("JSON.stringify(window.ReadX.markAt(${x / resources.displayMetrics.density},${y / resources.displayMetrics.density}))") {result->
                    val raw=runCatching {JSONTokener(result).nextValue() as? String}.getOrNull()
                    val mark=raw?.let(ReaderSelection::parse)
                    if(mark!=null) selectionPopup?.invoke(mark)
                    else trusted("window.ReadX.interactiveAt(${x / resources.displayMetrics.density},${y / resources.displayMetrics.density})") {interactive->
                        if(interactive=="true") trusted("(function(){var e=document.elementFromPoint(${x / resources.displayMetrics.density},${y / resources.displayMetrics.density});var a=e && e.closest('a');if(a)a.click();})()")
                        else {selectionPopup?.invoke(null);tapZone?.invoke(if(!paged) 0 else if(x<width/3f) -1 else if(x>width*2/3f) 1 else 0)}
                    }
                }
                performClick()
                return true
            }
        return false
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (restoring) return true
        if (event.actionMasked in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) WebReadingPriority.input()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { finishTransition(); linkOriginFraction = fraction() }
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {downX=event.x;downY=event.y;downTime=event.eventTime}
        if(selectionActive) {
            val handled=super.onTouchEvent(event)
            if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_MOVE && event.eventTime-lastSelectionRead>100) {
                lastSelectionRead=event.eventTime
                post {if(selectionActive) {windowActionMode?.menu?.clear();showSelectionPopup()}}
            }
            return handled
        }
        if(event.pointerCount>1) return super.onTouchEvent(event)
        if(!paged) {if(handleShortTap(event)) return true;return super.onTouchEvent(event)}
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
            MotionEvent.ACTION_UP -> { if (consumedSwipe) {
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
            if(handleShortTap(event)) return true
            }
            MotionEvent.ACTION_CANCEL -> if (consumedSwipe) {
                consumedSwipe = false; parent?.requestDisallowInterceptTouchEvent(false)
                animateTo(dragStartX); return true
            }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_ENTER || keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER) linkOriginFraction = fraction()
        return super.onKeyDown(keyCode, event)
    }
    fun release() {
        if (released) return
        finishTransition()
        windowActionMode?.finish();windowActionMode=null;selectionActive=false
        cancelLayoutCheck()
        released = true; loadGeneration++
        reportPosition = null; boundary = null; resized = null; tapZone=null; annotationSelected=null;selectionPopup=null
        stopLoading(); webViewClient = WebViewClient(); destroy()
    }
}

object LocalHtml {
    fun prepare(source: String, settings: ReaderSettings, foreground: String, background: String, viewportWidth: Float = 360f, viewportHeight: Float = 640f, loadGeneration: Long? = null): String {
        // Preserve measured fractional CSS pixels: initial-scale=1 follows the native density,
        // while viewport meta dimensions are integral. Rounding column widths accumulates drift in long chapters.
        val pageWidth = viewportWidth.coerceAtLeast(1f)
        val pageHeight = viewportHeight.coerceAtLeast(1f)
        val document = Jsoup.parse(source)
        document.select("script,noscript,iframe,object,embed,form,input,button,base,meta[http-equiv],link[rel=preload],svg foreignObject").remove()
        document.allElements.forEach { element ->
            element.attributes().asList().forEach { attr ->
                val name = attr.key.lowercase()
                val value = attr.value.trim().lowercase()
                if (name.startsWith("on") || name.startsWith("data-readx-") || name == "srcdoc" || ((name == "href" || name == "src" || name == "xlink:href") && (value.startsWith("javascript:") || value.startsWith("file:") || value.startsWith("content:")))) element.removeAttr(attr.key)
            }
        }
        loadGeneration?.let { document.body().attr("data-readx-load", it.toString()) }
        document.select("body style").forEach { it.remove(); document.head().appendChild(it) }
        document.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", "default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self' data:; script-src 'none'; connect-src 'none'; frame-src 'none'; base-uri 'self'; form-action 'none'")
        document.select("meta[name=viewport]").remove()
        document.head().appendElement("meta").attr("name", "viewport").attr("content", "width=${pageWidth.toInt()}, height=${pageHeight.toInt()}, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no")
        // The net-zero-height paged tail includes the final right margin in Chromium's scroll range.
        // Without it, scrollTo(lastPage * width) is clamped short and the last column is misaligned.
        val localFont=settings.fontId?.takeIf {it.matches(Regex("[0-9a-f]{64}"))}
        if(localFont!=null) document.head().appendElement("style").text("@font-face {font-family: ReadXLocal; src: url('https://appassets.androidplatform.net/reader-fonts/$localFont.font'); font-display: block;}")
        document.head().appendElement("style").text("""
            html { background: $background !important; color: $foreground !important; }
            body { margin: 0 !important; padding: 24px ${settings.margin}px 64px !important;
              font-family: ${if(settings.fontId?.matches(Regex("[0-9a-f]{64}"))==true) "ReadXLocal, " else ""}${if(settings.serif) "serif" else "sans-serif"} !important;
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
    navigate: (Int, String?) -> Unit, followLink: (Int, String?, Float, TextAnchor?) -> Unit,
    onViewport: (Int, Int) -> Unit, annotations: List<Annotation>, onTapZone: (Int) -> Unit, onSelection: (ReaderSelection?) -> Unit, onAnnotation: (String, TextAnchor) -> Unit,
    onPosition: (Float, Boolean, Int, Int) -> Unit,
    notify: (String) -> Unit, controller: ReaderController,
    onReady: (Int) -> Unit = {},
) {
    val chapter = session.chapters[session.chapter]
    key(session.book.id, session.navigationId, session.chapter, session.target, session.find, session.occurrence) {
        // These bindings belong to this navigation, not to the incoming chapter during onRelease.
        val currentPosition by rememberUpdatedState(onPosition)
        val currentNavigate by rememberUpdatedState(navigate)
        val currentLink by rememberUpdatedState(followLink)
        val currentNotify by rememberUpdatedState(notify)
        val currentAnnotations by rememberUpdatedState(annotations)
        val currentTap by rememberUpdatedState(onTapZone)
        val currentAnnotation by rememberUpdatedState(onAnnotation)
        val currentSelection by rememberUpdatedState(onSelection)
        val currentViewport by rememberUpdatedState(onViewport)
        val currentReady by rememberUpdatedState(onReady)
        AndroidView(modifier = modifier,
            factory = { context ->
                LocalWebReader(context).apply {
                    this.settings.javaScriptEnabled = true
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
                view.tapZone = { currentTap(it) }
                view.selectionPopup = {currentSelection(it)}
                view.annotationSelected = { kind, anchor -> currentAnnotation(kind, anchor) }
                if (!view.restoring) view.applyAnnotations(annotations)
                val fontScale = view.resources.configuration.fontScale
                val signature = listOf(settings.layout, settings.fontSize, settings.lineHeight, settings.margin, settings.serif,settings.fontId, foreground, background, chapter.href, session.navigationId, fontScale)
                if (view.tag != signature) {
                    view.finishTransition()
                    val reflowing = view.tag != null
                    val wasRestoring = view.restoring
                    var reflowAnchor: TextAnchor? = if (wasRestoring) view.pendingReflowAnchor else null
                    var restore = if (!reflowing) session.fraction else if (wasRestoring) view.pendingRestoreFraction ?: view.fraction() else view.fraction()
                    var target = if (view.tag == null) session.target else null
                    var handled = false
                    val issuedGeneration = java.util.concurrent.atomic.AtomicLong(-1)
                    var loadedUrl = ""
                    view.tag = signature
                    view.restoring = true
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
                        currentViewport(view.width, view.height)
                        view.finishTransition(); restore = fraction; target = null; handled = false
                        view.restoring = true
                        view.captureViewportAnchor { anchor ->
                            if (view.tag == signature) {
                                reflowAnchor = anchor
                                view.pendingReflowAnchor = anchor; view.pendingRestoreFraction = restore
                                view.cancelLayoutCheck(); view.loadGeneration++; issuedGeneration.set(view.loadGeneration); handled = false; loadedUrl = baseUrl
                                view.scrollTo(0, 0)
                                view.loadUrl(baseUrl)
                            }
                        }
                    }
                    val content = repository.content(session.book.id)
                    view.webViewClient = object : WebViewClient() {
                        private fun mainFrameFailure(message: String) {
                            if (view.tag != signature || issuedGeneration.get() != view.loadGeneration) return
                            handled = true
                            view.cancelLayoutCheck()
                            view.alpha = 1f
                            currentNotify(message)
                        }
                        override fun onReceivedError(webView: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) mainFrameFailure("章节加载失败，请返回后重试")
                        }
                        override fun onReceivedHttpError(webView: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame) mainFrameFailure("章节资源读取失败，请返回后重试")
                        }
                        override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse {
                            val uri = request.url
                            if(uri.scheme=="https" && uri.host=="appassets.androidplatform.net") LocalFontStore.response(view.context,uri.path.orEmpty())?.let {return it}
                            if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net" || !uri.path.orEmpty().startsWith("/content/")) return blocked()
                            return try {
                                val relative = uri.path!!.removePrefix("/content/")
                                val file = BookParser.safeFile(content, relative)
                                if (!file.isFile) return blocked()
                                val ext = file.extension.lowercase()
                                val mime = when(ext) { "html", "htm", "xhtml" -> "text/html"; "css" -> "text/css"; "svg" -> "image/svg+xml"; else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream" }
                                val stream = if (mime == "text/html") ByteArrayInputStream(LocalHtml.prepare(file.readText(), settings, foreground, background, view.viewportWidthCss, view.viewportHeightCss, issuedGeneration.get()).toByteArray()) else file.inputStream()
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
                            currentLink(index, uri.fragment, origin, view.sourceAnchor)
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
                                currentLink(session.chapter, uri.fragment, origin, view.sourceAnchor)
                            }
                        }
                        override fun onPageFinished(webView: WebView, url: String) {
                            if (view.tag != signature || handled || issuedGeneration.get() != view.loadGeneration || !view.isCurrentLoad(view.loadGeneration)) return
                            handled = true
                            val generation = view.loadGeneration
                            fun active() = view.tag == signature && view.isCurrentLoad(generation)
                            fun restorePosition() {
                                ReaderPerformance.mark("layout")
                                if (!active()) return
                                // Fragment navigation must remain at its target. Never restore the source fraction afterwards.
                                if (target == "__readx_end__") view.restore(1f)
                                else if (target != null) view.snap()
                                else if (reflowAnchor == null && session.anchor == null) view.restore(restore)
                                view.initializeTrusted {
                                    if (!active()) return@initializeTrusted
                                    view.applyAnnotations(currentAnnotations) {
                                        ReaderPerformance.mark("annotations")
                                        if (!active()) return@applyAnnotations
                                        fun commit() {
                                            view.postVisualStateCallback(generation, object : WebView.VisualStateCallback() {
                                                override fun onComplete(requestId: Long) {
                                                    if (!active()) return
                                                    ReaderPerformance.mark("restore")
                                                    ReaderPerformance.mark("ready")
                                                    view.restoring = false
                                                    view.pendingReflowAnchor = null; view.pendingRestoreFraction = null
                                                    view.report()
                                                    // The visible viewport is committed before any full-book work starts.
                                                    view.alpha = 1f
                                                    currentReady(view.pageInfo().second)
                                                    if(session.flashAnchor && session.anchor!=null && !reflowing) view.trusted("window.ReadX.flash(${session.anchor.json()})") {view.alpha=.15f;view.animate().alpha(1f).setDuration(160).start()}
                                                }
                                            })
                                        }
                                        val anchor = reflowAnchor ?: session.anchor
                                        if (anchor != null) {
                                            // navigateAnchor uses the existing validated UTF-16 locator; position is not a page fraction.
                                            view.navigateAnchor(anchor, restoringPosition = true) { found ->
                                                if (!active()) return@navigateAnchor
                                                if (!found) {
                                                    view.restore(restore)
                                                    if (session.anchor != null) currentNotify("批注文字未能唯一定位，已回到原章节位置")
                                                }
                                                commit()
                                            }
                                        } else commit()
                                    }
                                }
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
                            view.whenLayoutStable(generation, ::restorePosition) { message ->
                                if (active()) {
                                    // Show the content and an explicit error, but never publish an unstable exact count.
                                    view.alpha = 1f
                                    currentNotify(message)
                                }
                            }
                        }
                    }
                    fun loadMeasured() {
                        if (view.tag != signature || !view.isCurrentLoad(view.loadGeneration)) return
                        // HTML pagination is derived only from the measured reading viewport.
                        view.doOnLayout {
                            if (view.tag != signature || !view.isCurrentLoad(view.loadGeneration)) return@doOnLayout
                            currentViewport(view.width, view.height)
                            view.cancelLayoutCheck(); view.stopLoading(); view.loadGeneration++
                            issuedGeneration.set(view.loadGeneration); handled = false
                            view.pendingReflowAnchor = reflowAnchor; view.pendingRestoreFraction = restore
                            view.paged = settings.layout == io.readx.app.ui.ReadingLayout.PAGED
                            view.settings.textZoom = (fontScale * 100).toInt().coerceAtLeast(1)
                            view.viewportWidthCss = view.width / view.resources.displayMetrics.density
                            view.viewportHeightCss = view.height / view.resources.displayMetrics.density
                            val url = baseUrl + (target?.takeUnless { it == "__readx_end__" }?.let { "#" + Uri.encode(it) } ?: "")
                            loadedUrl = url
                            view.scrollTo(0, 0)
                            view.loadUrl(url)
                        }
                    }
                    if (reflowing && !wasRestoring) {
                        // Capture the old DOM before loading a different layout. Old callbacks are generation-guarded.
                        view.captureViewportAnchor { anchor ->
                            if (view.tag == signature) { reflowAnchor = anchor; loadMeasured() }
                        }
                    } else loadMeasured()
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
