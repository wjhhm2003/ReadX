package io.readx.app.pdf

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** Selection comes exclusively from the PDF text layer, never estimated rows or region rectangles. */
internal class PdfTextSelectionState(
    private val scope:CoroutineScope,
    private val page:Int,
    private val width:Float,
    private val height:Float,
    private val load:suspend (PointF,PointF)->PdfSelection?,
    private val publish:(PdfSelection)->Unit,
    private val notify:(String)->Unit
) {
    var picked by mutableStateOf<PdfSelection?>(null)
        private set
    private var start=PointF()
    private var end=PointF()
    private var generation=0
    private var query:Job?=null
    private var editing=false
    fun sync(value:PdfSelection?) {
        if(editing)return
        picked=value?.takeIf {it.quote.isNotBlank() && it.boxes.any {b->b.page==page}}
        picked?.let {anchor(it)}
    }
    private fun anchor(value:PdfSelection) {
        val boxes=value.boxes.filter {it.page==page}
        val first=boxes.first();val last=boxes.last()
        start=PointF(first.left*width+0.5f,(first.top+first.bottom)*height/2)
        end=PointF(last.right*width-0.5f,(last.top+last.bottom)*height/2)
    }
    fun begin(at:PointF) {editing=true;start=at;end=at;request(true)}
    fun extend(at:PointF) {end=at;request(false)}
    fun finish() {request(true)}
    fun cancel() {editing=false;generation++;query?.cancel();picked=null}
    fun moveHandle(index:Int,at:PointF,finished:Boolean=false) {
        editing=true
        if(index==0)start=at else end=at
        request(finished)
    }
    private fun request(commit:Boolean) {
        val version=++generation
        query?.cancel()
        val a=PointF(start.x,start.y);val b=PointF(end.x,end.y)
        query=scope.launch {
            try {
                // Small debounce collapses move events; committing and initial long press are immediate.
                if(!commit)delay(24)
                val value=load(a,b)?.takeIf {it.quote.isNotBlank() && it.boxes.isNotEmpty()}
                ensureActive()
                if(version!=generation)return@launch
                if(value!=null) {
                    picked=value
                    if(commit) {anchor(value);editing=false;publish(value)}
                } else if(commit) {
                    picked=null;editing=false
                    notify("此处没有可选文字；扫描页需先 OCR，不会创建框选标记")
                }
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {if(commit) {editing=false;notify("PDF 文字选取失败，未保存标记")}}
        }
    }
}

@Composable
internal fun rememberPdfTextSelection(page:Int,width:Float,height:Float,load:suspend (PointF,PointF)->PdfSelection?,publish:(PdfSelection)->Unit,notify:(String)->Unit):PdfTextSelectionState {
    val scope=rememberCoroutineScope()
    val currentLoad by rememberUpdatedState(load)
    val currentPublish by rememberUpdatedState(publish)
    val currentNotify by rememberUpdatedState(notify)
    return remember(page,width,height) {PdfTextSelectionState(scope,page,width,height,{a,b->currentLoad(a,b)},{currentPublish(it)},{currentNotify(it)})}
}

/** Draw the actual selected glyph bounds, with two independent 48dp touch targets. */
@Composable
internal fun PdfTextSelectionHandles(state:PdfTextSelectionState,page:Int,toView:(Float,Float)->Offset,toPage:(Offset)->PointF) {
    val currentToView by rememberUpdatedState(toView)
    val currentToPage by rememberUpdatedState(toPage)
    val boxes=state.picked?.boxes?.filter {it.page==page}.orEmpty()
    if(boxes.isEmpty())return
    Canvas(Modifier.fillMaxSize()) {
        boxes.forEach {box->
            val a=currentToView(box.left,box.top);val b=currentToView(box.right,box.bottom)
            drawRect(Color(0x55246BFC),a,Size(b.x-a.x,b.y-a.y))
        }
    }
    val first=boxes.first();val last=boxes.last()
    listOf(first.left to first.bottom,last.right to last.bottom).forEachIndexed {index,(x,y)->
        val where=currentToView(x,y)
        Box(Modifier.offset {IntOffset(where.x.roundToInt()-24.dp.roundToPx(),where.y.roundToInt())}
            .size(48.dp).testTag("pdf-text-handle-$index")
            .semantics {contentDescription=if(index==0) "文字选区起点" else "文字选区终点";stateDescription=state.picked?.quote.orEmpty().take(128)}
            .pointerInput(state,index) {
                var at=Offset.Zero
                detectDragGestures(
                    onDragStart={
                        val p=state.picked?.boxes?.filter {it.page==page}.orEmpty()
                        if(p.isNotEmpty()) {val b=if(index==0)p.first() else p.last();at=currentToView(if(index==0)b.left else b.right,(b.top+b.bottom)/2)}
                    },
                    onDragEnd={state.moveHandle(index,currentToPage(at),true)},
                    onDragCancel={state.finish()}
                ) {change,delta->change.consume();at+=delta;state.moveHandle(index,currentToPage(at))}
            },contentAlignment=Alignment.TopCenter) {
            Box(Modifier.size(16.dp).background(Color(0xFF246BFC),CircleShape))
        }
    }
}
