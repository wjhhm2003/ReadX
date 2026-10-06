package io.readx.app.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import io.readx.app.data.Annotation
import io.readx.app.data.LibraryRepository
import io.readx.app.data.MarkColor
import io.readx.app.ui.ReaderSession
import io.readx.app.ui.ReaderSettings
import io.readx.app.ui.ReadingLayout
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import java.io.File

/** Draws only the current bounded StaticLayout and (in scroll mode) one neighbour. */
class NativeReaderView(context:Context):View(context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var operation:Job?=null
    private var turning=false
    internal var pendingJumpPage:Int?=null
    private var markJob:Job?=null
    internal var engine:NativePaginator?=null
    private var current:NativePage?=null
    internal val currentPageEnd:Int get()=current?.end ?: 0
    private var neighbour:NativePage?=null
    private var history=ArrayList<Int>()
    internal var navigationId=Long.MIN_VALUE
    internal var chapterOrdinal=-1
    internal var layoutKey=""
    var ready by mutableStateOf(false);private set
    @Volatile var drawnOffset=0;private set
    private var drawCommitPending=false
    internal var animateEntry=false
    var viewportAnchor:TextAnchor?=null;private set
    var onPosition:((Float,Boolean,Int,Int)->Unit)?=null
    var onSelection:((ReaderSelection?)->Unit)?=null
    var onBoundary:((Int)->Unit)?=null
    var onTap:((Int)->Unit)?=null
    var onFailure:((String)->Unit)?=null
    var paged=true
    var ink=android.graphics.Color.BLACK
    var paper=android.graphics.Color.WHITE
    private var scroll=0f
    private var savedOffset=0
    private var downX=0f;private var downY=0f;private var lastY=0f
    private val scroller = android.widget.OverScroller(context)
    private var velocityTracker: android.view.VelocityTracker? = null
    private val minFlingVelocity = android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = android.view.ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var lastFlingY = 0

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                val currY = scroller.currY
                val deltaY = (currY - lastFlingY).toFloat()
                lastFlingY = currY
                val canContinue = applyScrollDelta(deltaY)
                if (canContinue && !scroller.isFinished) {
                    postOnAnimation(this)
                } else {
                    scroller.forceFinished(true)
                    report()
                }
            } else {
                report()
            }
        }
    }

    private fun applyScrollDelta(deltaY: Float): Boolean {
        scroll += deltaY
        val p = current ?: return false
        val e = engine ?: return false
        if (scroll < -p.layout.height && neighbour != null) {
            current = neighbour
            neighbour = null
            scroll += p.layout.height
            report()
            prefetch()
            updateMarks(annotationRows)
        } else if (scroll > 0 && p.start > 0 && operation?.isActive != true) {
            val y = scroll
            operation = scope.launch {
                val prev = e.previous(p.start)
                current = prev
                neighbour = p
                scroll = y - prev.layout.height
                report()
                invalidate()
                updateMarks(annotationRows)
            }
        } else if (p.start == 0 && scroll > 0) {
            scroll = 0f
            invalidate()
            return false
        }
        if (p.end == e.source.length) {
            val minScroll = minOf(0f, height - e.top * 2 - p.layout.height)
            if (scroll <= minScroll) {
                scroll = minScroll
                invalidate()
                return false
            }
        }
        invalidate()
        return true
    }
    private var flashRange:Pair<Int,Int>?=null
    private var flashJob:Job?=null
    private var selectionUnit=SelectionUnit.WORD
    private var startSelection:Int?=null;private var endSelection:Int?=null
    private var draggingEnd=true
    private var selecting=false
    private var edgeTurn=0L
    private var annotationRows:List<Annotation> = emptyList()
    private var records:List<Triple<Annotation,Int,Int>> = emptyList()
    private val highlightPaint=Paint().apply {color=0x55246BFC}
    private val detector=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e:MotionEvent)=true
        override fun onLongPress(e:MotionEvent) {
            if(!ready)return;val offset=offsetAt(e.x,e.y) ?: return
            startSelection=offset;endSelection=(offset+2).coerceAtMost(engine!!.source.length);selecting=true;draggingEnd=true
            parent.requestDisallowInterceptTouchEvent(true);publishSelection();invalidate()
        }
        override fun onSingleTapUp(e:MotionEvent):Boolean {
            performClick()
            if(!ready)return true
            val offset=offsetAt(e.x,e.y)
            val rows=records.filter {offset!=null && offset in it.second until it.third}
            if(rows.isNotEmpty()) {val row=rows.maxBy {maxOf(it.first.updatedAt,it.first.createdAt)};startSelection=row.second;endSelection=row.third;publishSelection(rows.map {it.first.id},true);invalidate();return true}
            if(startSelection!=null) {clearSelection();return true}
            onTap?.invoke(if(!paged) 0 else if(e.x<width/3f) -1 else if(e.x>width*2/3f) 1 else 0);return true
        }
    })
    init {detector.setOnDoubleTapListener(null)}
    internal fun beginNavigation() {
        ready=false;drawCommitPending=false;operation?.cancel();operation=null;turning=false;pendingJumpPage=null;markJob?.cancel()
        current=null;neighbour=null;records=emptyList();annotationRows=emptyList();clearSelection();flashJob?.cancel();flashRange=null;invalidate()
    }
    internal suspend fun install(paginator:NativePaginator,offset:Int) {
        ready=false;engine=paginator;history.clear();scroll=0f
        current=paginator.page(paginator.startFor(offset));viewportAnchor=withContext(Dispatchers.IO) {paginator.source.anchor(current!!.start,(current!!.start+32).coerceAtMost(current!!.end))}
        ReaderPerformance.mark("layout");ReaderPerformance.mark("restore");report();invalidate();prefetch()
    }
    private fun prefetch() {val p=current ?: return;val engine=engine ?: return
        scope.launch {if(p.end<engine.source.length) {val next=engine.page(p.end);if(current===p) {neighbour=next;invalidate();if(!paged)updateMarks(annotationRows)}}}
    }
    fun currentOffset()=current?.start ?: 0
    fun pageInfo():Pair<Int,Int> = (engine?.let {if(it.boundaries.binarySearch(currentOffset())>=0)it.pageNumber(currentOffset()) ?: 0 else 0} ?: 0) to (engine?.count ?: 0)
    fun report(final:Boolean=false) {
        val e=engine ?: return;val p=current ?: return
        val local=if(!paged && scroll<0) p.layout.getLineStart(p.layout.getLineForVertical((-scroll).toInt())) else 0
        val offset=p.start+local
        savedOffset=offset
        scope.launch {val anchor=withContext(Dispatchers.IO) {e.source.anchor(offset,(offset+32).coerceAtMost(p.end))};if(current===p) {viewportAnchor=anchor;onPosition?.invoke(offset.toFloat()/e.source.length,final,pageInfo().first,pageInfo().second)}}
    }
    fun turn(direction:Int) {
        pendingJumpPage=null
        NativeWork.foreground();val e=engine ?: return;val p=current ?: return
        if(!ready || turning)return
        if(direction>0 && p.end>=e.source.length || direction<0 && p.start==0) {if(startSelection!=null) onFailure?.invoke("本轮不支持跨章节选段，请先结束选区") else onBoundary?.invoke(direction);return}
        turning=true
        operation=scope.launch {
            try {
                val next=if(direction>0) {history.add(p.start);neighbour?.takeIf {it.start==p.end} ?: e.page(p.end)}
                    else if(history.isNotEmpty()) e.page(history.removeAt(history.lastIndex),p.start) else e.previous(p.start)
                val anchor=withContext(Dispatchers.IO) {e.source.anchor(next.start,(next.start+32).coerceAtMost(next.end))}
                current=next;neighbour=null;scroll=0f;viewportAnchor=anchor;turning=false
                if(startSelection!=null && endSelection!=null && !selecting) {
                    if(direction>0) endSelection=(next.start+minOf(16,next.layout.text.length)).coerceAtMost(next.end)
                    else startSelection=(next.end-minOf(16,next.layout.text.length)).coerceAtLeast(next.start)
                }
                report();invalidate();prefetch();updateMarks(annotationRows);if(startSelection!=null) snapSelection(selectionUnit)
                e.save()
            } catch(cancelled:CancellationException) {throw cancelled} catch(error:Exception) {turning=false;onFailure?.invoke(error.message ?: "原生翻页失败")}
        }
    }
    fun jumpToPage(page:Int) {val e=engine ?: return;val target=e.boundaries.getOrNull(page-1) ?: run {pendingJumpPage=page;onFailure?.invoke("分页边界缓存需要重建，可先阅读；统计完成后跳到指定页");return}
        operation?.cancel();operation=scope.launch {current=e.page(target);scroll=0f;viewportAnchor=withContext(Dispatchers.IO) {e.source.anchor(target,(target+32).coerceAtMost(current!!.end))};report();invalidate();prefetch()}}
    fun captureAnchor(callback:(TextAnchor?)->Unit) {
        val e=engine ?: run {callback(null);return};val p=current ?: run {callback(null);return}
        val local=if(!paged && scroll<0) p.layout.getLineStart(p.layout.getLineForVertical((-scroll).toInt())) else 0
        val offset=p.start+local
        scope.launch {val anchor=withContext(Dispatchers.IO) {e.source.anchor(offset,(offset+32).coerceAtMost(p.end))};if(current===p) {viewportAnchor=anchor;callback(anchor)}else callback(viewportAnchor)}
    }
    fun flash(range:Pair<Int,Int>) {
        flashJob?.cancel();flashJob=scope.launch {
            repeat(3) {flashRange=range;invalidate();delay(220);flashRange=null;invalidate();delay(140)}
        }
    }
    fun snapSelection(unit:SelectionUnit) {
        selectionUnit=unit
        val a=startSelection ?: return;val b=endSelection ?: return;val source=engine?.source ?: return
        scope.launch {
            val result=withContext(Dispatchers.IO) {
                val left=(minOf(a,b)-256).coerceAtLeast(0);val right=(maxOf(a,b)+256).coerceAtMost(source.length)
                val text=source.read(left,right-left);val snapped=SelectionSnap.range(text,a-left,b-left,unit)
                (left+snapped.first) to (left+snapped.second)
            }
            if(startSelection!=a || endSelection!=b)return@launch
            if(result.second-result.first>16384) {onFailure?.invoke("吸附后的选段过长，保留当前端点");return@launch}
            startSelection=result.first;endSelection=result.second;publishSelection();invalidate()
        }
    }
    fun clearSelection() {startSelection=null;endSelection=null;selecting=false;onSelection?.invoke(null);invalidate()}
    internal fun updateMarks(rows:List<Annotation>) {
        annotationRows=rows
        val e=engine ?: return;val page=current ?: return;markJob?.cancel()
        markJob=scope.launch {
            records=withContext(Dispatchers.IO) {rows.filter {it.kind!="BOOKMARK"}.mapNotNull {a->TextAnchor.parse(a.locator)?.let {anchor->
                val lo=e.source.toCanonical(page.start);val hi=e.source.toCanonical(neighbour?.end ?: page.end)
                if(anchor.end<=lo || anchor.start>=hi) null else e.source.resolve(anchor)?.let {Triple(a,it.first,it.second)}
            }}}
            ReaderPerformance.mark("annotations");invalidate()
        }
    }
    private fun offsetAt(x:Float,y:Float):Int? {val p=current ?: return null;val e=engine ?: return null
        val line=p.layout.getLineForVertical((y-e.top-scroll).toInt().coerceAtLeast(0));val raw=p.start+p.layout.getOffsetForHorizontal(line,x-e.margin)
        // Never place an endpoint inside a UTF-16 surrogate pair.
        val text=p.layout.text;val local=(raw-p.start).coerceIn(0,text.length)
        return if(local in 1 until text.length && text[local].isLowSurrogate()) raw-1 else raw
    }
    private fun point(offset:Int):Pair<Float,Float>? {val p=current ?: return null;val e=engine ?: return null;if(offset !in p.start..p.end)return null
        val local=(offset-p.start).coerceIn(0,p.layout.text.length);val line=p.layout.getLineForOffset(local)
        return e.margin+p.layout.getPrimaryHorizontal(local) to e.top+scroll+p.layout.getLineBottom(line)}
    private fun publishSelection(ids:List<String> = emptyList(),fromMark:Boolean=false) {
        val e=engine ?: return;val a=startSelection ?: return;val b=endSelection ?: return
        scope.launch {
            val (anchor,displayQuote)=withContext(Dispatchers.IO) {
                val value=e.source.anchor(minOf(a,b),maxOf(a,b))
                value to if(value!=null)e.source.read(minOf(a,b),kotlin.math.abs(b-a)) else ""
            }
            if(anchor==null) {onFailure?.invoke("选段最多 16384 个 UTF-16 单位，且不能跨章节");return@launch}
            if(startSelection!=a || endSelection!=b)return@launch
            val p=point(minOf(a,b));val q=point(maxOf(a,b));val d=resources.displayMetrics.density
            val rect=Rect((p?.first ?: e.margin)/d,((p?.second ?: e.top)-e.paint.textSize)/d,(q?.first ?: width-e.margin).coerceAtLeast(p?.first ?: e.margin)/d,(q?.second ?: height-e.top)/d)
            onSelection?.invoke(ReaderSelection(anchor,rect,ids,fromMark,ids.firstOrNull(),displayQuote))
        }
    }
    override fun onDraw(canvas:Canvas) {
        canvas.drawColor(paper);val p=current ?: return;val e=engine ?: return
        fun draw(page:NativePage,y:Float) {
            canvas.save();canvas.translate(e.margin,y);canvas.clipRect(0f,0f,width-e.margin*2,page.layout.height.toFloat())
            // Duplicate marks share one translucent layer, so historical duplicates don't darken text.
            val layer=canvas.saveLayerAlpha(0f,0f,width.toFloat(),page.layout.height.toFloat(),82)
            records.sortedBy {maxOf(it.first.updatedAt,it.first.createdAt)}.filter {it.first.kind=="HIGHLIGHT"}.forEach {(a,s,t)->
                val lo=(s-page.start).coerceIn(0,page.layout.text.length);val hi=(t-page.start).coerceIn(0,page.layout.text.length)
                if(hi>lo) {val path=Path();page.layout.getSelectionPath(lo,hi,path);highlightPaint.color=android.graphics.Color.parseColor(MarkColor.normalize(a.color));canvas.drawPath(path,highlightPaint)}
            };canvas.restoreToCount(layer)
            page.layout.paint.color=ink;page.layout.draw(canvas)
            records.filter {it.first.kind in listOf("UNDERLINE","NOTE")}.forEach {(a,s,t)->
                val lo=(s-page.start).coerceIn(0,page.layout.text.length);val hi=(t-page.start).coerceIn(0,page.layout.text.length)
                if(hi>lo) {highlightPaint.color=android.graphics.Color.parseColor(MarkColor.normalize(a.color));highlightPaint.strokeWidth=2*resources.displayMetrics.density
                    for(line in page.layout.getLineForOffset(lo)..page.layout.getLineForOffset(hi)) {val begin=maxOf(lo,page.layout.getLineStart(line));val end=minOf(hi,page.layout.getLineEnd(line));if(end>begin)canvas.drawLine(page.layout.getPrimaryHorizontal(begin),page.layout.getLineBottom(line).toFloat()-2,page.layout.getPrimaryHorizontal(end),page.layout.getLineBottom(line).toFloat()-2,highlightPaint)}
                }
            }
            flashRange?.let {(start,end)->
                val lo=(start-page.start).coerceIn(0,page.layout.text.length);val hi=(end-page.start).coerceIn(0,page.layout.text.length)
                if(hi>lo) {val path=Path();page.layout.getSelectionPath(lo,hi,path);highlightPaint.color=0x55246BFC;canvas.drawPath(path,highlightPaint)}
            }
            val a=startSelection;val b=endSelection
            if(a!=null && b!=null) {val lo=(minOf(a,b)-page.start).coerceIn(0,page.layout.text.length);val hi=(maxOf(a,b)-page.start).coerceIn(0,page.layout.text.length);if(hi>lo) {val path=Path();page.layout.getSelectionPath(lo,hi,path);highlightPaint.color=0x55246BFC;canvas.drawPath(path,highlightPaint)}}
            canvas.restore()
        }
        draw(p,e.top+scroll);if(!paged)neighbour?.let {draw(it,e.top+scroll+p.layout.height)}
        drawnOffset=p.start
        if(!ready && !drawCommitPending) {
            drawCommitPending=true
            val measuredWidth=width;val measuredHeight=height
            postOnAnimation {
                drawCommitPending=false
                if(current===p && width==measuredWidth && height==measuredHeight) {ready=true;ReaderPerformance.mark("ready");if(animateEntry) {alpha=.15f;animate().alpha(1f).setDuration(160).start();animateEntry=false}}
            }
        }
        highlightPaint.color=0xFF246BFC.toInt();listOfNotNull(startSelection,endSelection).forEach {point(it)?.let {(x,y)->canvas.drawCircle(x,y,8*resources.displayMetrics.density,highlightPaint)}}
    }
    override fun performClick():Boolean {super.performClick();return true}
    override fun onTouchEvent(event:MotionEvent):Boolean {
        NativeWork.foreground()
        if (!paged) {
            if (velocityTracker == null) velocityTracker = android.view.VelocityTracker.obtain()
            velocityTracker?.addMovement(event)
        }
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            pendingJumpPage=null;downX=event.x;downY=event.y;lastY=event.y
            if (!paged) {
                scroller.forceFinished(true)
                removeCallbacks(flingRunnable)
            }
            val a=startSelection?.let(::point);val b=endSelection?.let(::point)
            fun near(p:Pair<Float,Float>?)=p!=null && kotlin.math.abs(event.x-p.first)<40*resources.displayMetrics.density && kotlin.math.abs(event.y-p.second)<48*resources.displayMetrics.density
            selecting=near(a)||near(b);draggingEnd=near(b);if(selecting)parent.requestDisallowInterceptTouchEvent(true)
        }
        if(selecting) {
            if(event.actionMasked==MotionEvent.ACTION_MOVE) {
                offsetAt(event.x,event.y)?.let {if(draggingEnd)endSelection=it else startSelection=it};invalidate()
                if(paged && SystemClock.uptimeMillis()-edgeTurn>650 && (event.x<24 || event.x>width-24)) {edgeTurn=SystemClock.uptimeMillis();turn(if(event.x<24) -1 else 1)}
            }
            if(event.actionMasked==MotionEvent.ACTION_UP) {selecting=false;snapSelection(selectionUnit)}
            return true
        }
        detector.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_MOVE && !paged && kotlin.math.abs(event.y-downY)>8) {
            applyScrollDelta(event.y-lastY)
            lastY=event.y
            return true
        }
        if(event.actionMasked==MotionEvent.ACTION_UP && !paged) {
            velocityTracker?.let { tracker ->
                tracker.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                val vy = tracker.yVelocity
                if (kotlin.math.abs(vy) >= minFlingVelocity) {
                    scroller.forceFinished(true)
                    removeCallbacks(flingRunnable)
                    lastFlingY = 0
                    scroller.fling(0, 0, 0, vy.toInt(), 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
                    postOnAnimation(flingRunnable)
                } else {
                    report()
                }
                tracker.recycle()
                velocityTracker = null
            } ?: report()
        }
        if(event.actionMasked==MotionEvent.ACTION_CANCEL && !paged) {
            velocityTracker?.recycle()
            velocityTracker = null
            report()
        }
        if(event.actionMasked==MotionEvent.ACTION_UP && paged && kotlin.math.abs(event.x-downX)>64*resources.displayMetrics.density && kotlin.math.abs(event.x-downX)>kotlin.math.abs(event.y-downY)*1.3f)turn(if(event.x<downX) 1 else -1)
        lastY=event.y;return true
    }
    fun release() {
        removeCallbacks(flingRunnable)
        scroller.forceFinished(true)
        velocityTracker?.recycle()
        velocityTracker = null
        engine?.let {e->onPosition?.invoke(savedOffset.toFloat()/e.source.length,true,pageInfo().first,pageInfo().second)}
        scope.cancel()
        engine=null
    }
}

internal fun nativeLayoutKey(session:ReaderSession,settings:ReaderSettings,w:Int,h:Int,d:Float,f:Float)=LayoutConfig(session.book.fingerprint,session.chapters.map {it.href},w,h,d,f,settings.fontSize,settings.lineHeight,settings.margin,settings.serif,"none",android.os.Build.FINGERPRINT,android.os.LocaleList.getDefault().toLanguageTags(),"staticlayout-v3",fontId=settings.fontId,textScript=settings.textScript.name+"@"+ChineseText.VERSION).generateKey()

@Composable
fun NativeTxtReader(session:ReaderSession,settings:ReaderSettings,repository:LibraryRepository,foreground:String,background:String,modifier:Modifier,annotations:List<Annotation>,controller:ReaderController,onViewport:(Int,Int)->Unit,onTap:(Int)->Unit,onSelection:(ReaderSelection?)->Unit,onPosition:(Float,Boolean,Int,Int)->Unit,onReady:(Int)->Unit,onBoundary:(Int)->Unit,notify:(String)->Unit,onIndex:(BookPageIndex?,String?)->Unit) {
    val d=LocalDensity.current
    var view by remember {mutableStateOf<NativeReaderView?>(null)}
    var viewport by remember {mutableStateOf(0 to 0)}
    var foregroundReady by remember(session.navigationId,settings.layout,settings.fontSize,settings.lineHeight,settings.margin,settings.serif,settings.fontId,settings.textScript) {mutableStateOf(false)}
    val latestPosition by rememberUpdatedState(onPosition);val latestSelection by rememberUpdatedState(onSelection);val latestTap by rememberUpdatedState(onTap);val latestBoundary by rememberUpdatedState(onBoundary);val latestNotify by rememberUpdatedState(notify)
    AndroidView(modifier=modifier,factory={context->NativeReaderView(context).also {v->view=v;controller.native=v
        v.onPosition={a,b,c,e->latestPosition(a,b,c,e)};v.onSelection={latestSelection(it)};v.onTap={latestTap(it)};v.onBoundary={latestBoundary(it)};v.onFailure={latestNotify(it)}
        v.addOnLayoutChangeListener {_,l,t,r,b,_,_,_,_->if(r-l>0 && b-t>0){viewport=(r-l) to (b-t);onViewport(r-l,b-t)}}
    }},update={v->v.ink=android.graphics.Color.parseColor(foreground);v.paper=android.graphics.Color.parseColor(background);v.paged=settings.layout==ReadingLayout.PAGED;v.invalidate()},onRelease={v->if(controller.native===v)controller.native=null;v.release()})
    LaunchedEffect(view,viewport,session.navigationId,settings.layout,settings.fontSize,settings.lineHeight,settings.margin,settings.serif,settings.fontId,settings.textScript,d) {
        val v=view ?: return@LaunchedEffect;if(viewport.first<=0 || viewport.second<=0)return@LaunchedEffect
        val newNavigation=v.navigationId!=session.navigationId
        val old=v.viewportAnchor.takeIf {v.navigationId==session.navigationId}
        v.beginNavigation()
        try {
            LocalFontStore.prepare(v.context,settings.fontId)
            NativeWork.foreground()
            val source=NativeTextSource.open(v.context.cacheDir,session.book.id,session.chapter,BookParser.safeFile(repository.content(session.book.id),session.chapters[session.chapter].href),settings.textScript,v.context.assets)
            ReaderPerformance.mark("text_index")
            val key=nativeLayoutKey(session,settings,viewport.first,viewport.second,d.density,d.fontScale)
            val engine=withContext(Dispatchers.IO) {NativePaginator(source,settings,viewport.first,viewport.second,d.density,d.fontScale,v.context.cacheDir,layoutDigest(key+session.chapter))}
            val anchor=old ?: session.anchor
            val resolved=withContext(Dispatchers.IO) {anchor?.let {source.resolve(it)}}
            if(anchor!=null && resolved==null)notify("文字锚点未能唯一定位，保留原记录并使用兼容章节位置")
            val search=withContext(Dispatchers.IO) {session.find?.let {source.find(it,session.occurrence)}}
            val pageRequest=session.requestedPage.takeIf {v.navigationId!=session.navigationId}
            val requestedOffset=pageRequest?.let {engine.boundaries.getOrNull(it-1)}
            val offset=requestedOffset ?: if(session.target=="__readx_end__") engine.previous(source.length).start else search?.first ?: resolved?.first ?: (session.fraction*source.length).toInt().coerceIn(0,(source.length-1).coerceAtLeast(0))
            v.navigationId=session.navigationId;v.chapterOrdinal=session.chapter;v.layoutKey=key
            v.animateEntry=newNavigation && session.flashAnchor
            v.install(engine,offset)
            if(pageRequest!=null && requestedOffset==null) {v.pendingJumpPage=pageRequest;notify("正在重建指定页边界，当前先显示兼容位置")}
            v.updateMarks(annotations)
            snapshotFlow {v.ready}.filter {it}.first()
            foregroundReady=true;onReady(engine.count ?: 0)
            if(session.flashAnchor && newNavigation && resolved!=null)v.flash(resolved)
        } catch(e:CancellationException) {throw e} catch(e:Exception) {notify(e.message ?: "TXT 原生排版失败，可切换 WebView")}
    }
    LaunchedEffect(view,annotations,foregroundReady) {if(foregroundReady)view?.updateMarks(annotations)}
    // One cancellable task for the entire book, separate from foreground page rendering.
    LaunchedEffect(session.book.id,session.chapter,viewport,settings.fontSize,settings.lineHeight,settings.margin,settings.serif,settings.fontId,settings.textScript,d) {
        val v=snapshotFlow {view}.filter {it!=null}.first()!!
        if(viewport.first<=0 || viewport.second<=0)return@LaunchedEffect
        snapshotFlow {foregroundReady}.filter {it}.first()
        val key=nativeLayoutKey(session,settings,viewport.first,viewport.second,d.density,d.fontScale)
        val cache=PageIndexCache(v.context.cacheDir)
        var index=cache.load(session.book.id,key,session.chapters.size) ?: BookPageIndex(List(session.chapters.size) {null});onIndex(index,null)
        try {
            for(ordinal in chapterPriority(session.chapters.size,session.chapter)) {
                if(index.counts[ordinal]!=null && !(ordinal==v.chapterOrdinal && v.engine?.count==null))continue
                NativeWork.backgroundYield()
                val source=NativeTextSource.open(v.context.cacheDir,session.book.id,ordinal,BookParser.safeFile(repository.content(session.book.id),session.chapters[ordinal].href),settings.textScript,v.context.assets)
                val engine=withContext(Dispatchers.IO) {NativePaginator(source,settings,viewport.first,viewport.second,d.density,d.fontScale,v.context.cacheDir,layoutDigest(key+ordinal))}
                try {
                    var batch=0
                    while(!engine.complete) {NativeWork.backgroundYield();engine.page(engine.boundaries.last());if(++batch%32==0)engine.save()}
                } finally {withContext(NonCancellable) {engine.save()}}
                if(v.chapterOrdinal==ordinal && v.layoutKey==key) {v.engine?.adopt(engine.boundaries);val pending=v.pendingJumpPage;v.pendingJumpPage=null;if(pending!=null)v.jumpToPage(pending) else v.report()}
                index=index.withCount(ordinal,engine.count!!);onIndex(index,null);cache.save(session.book.id,key,index)
            };ReaderPerformance.mark("statistics")
        } catch(e:CancellationException) {throw e} catch(e:Exception) {onIndex(index,e.message ?: "全书页数统计失败，可继续阅读")}
    }
}
