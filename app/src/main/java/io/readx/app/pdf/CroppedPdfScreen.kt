@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)
package io.readx.app.pdf

import android.graphics.PointF
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.pdf.PdfDocument
import androidx.pdf.content.PdfPageTextContent
import io.readx.app.data.*
import io.readx.app.data.Annotation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import java.io.File
import kotlin.math.*

/** Owns bounded bitmap cache, not the advanced document's lifecycle. No full-document pre-render. */
internal class CroppedPdfSource(val document:PdfDocument?,file:File) {
    private val basic=if(document==null) NativePdfSource(file) else null
    val count=document?.pageCount ?: basic!!.count
    private val bitmaps=object:LruCache<String,RenderedPdfPage>(32*1024*1024) {
        override fun sizeOf(key:String,value:RenderedPdfPage)=value.bitmap.allocationByteCount
    }
    val cacheBytes get()=bitmaps.size()
    suspend fun render(page:Int,preview:Boolean=false):RenderedPdfPage {
        val key="$page:$preview";bitmaps.get(key)?.let {return it}
        val target=if(preview) 384 else 1280
        val value=if(document!=null) {
            val info=document.getPageInfo(page);val source=document.getPageBitmapSource(page)
            try {RenderedPdfPage(source.getBitmap(renderSize(info.width,info.height,target)),info.width,info.height)}
            finally {withContext(NonCancellable+Dispatchers.IO) {source.close()}}
        } else basic!!.render(page,target)
        currentCoroutineContext().ensureActive();bitmaps.put(key,value);return value
    }
    suspend fun select(page:Int,a:PointF,b:PointF):PdfSelection? {
        if(document==null)return null // Basic path is explicitly region-only, regardless of newer renderer APIs.
        val (p1, p2) = PdfFlowSelection.orderPoints(a, b)
        val picked = document.getSelectionBounds(page, p1, p2) ?: document.getSelectionBounds(page, p2, p1)
        val contents = picked?.selectedContents?.filterIsInstance<PdfPageTextContent>().orEmpty()
        val info=document.getPageInfo(page)
        return PdfSelection(contents.joinToString("\n") {it.text}.take(16384),contents.flatMap {it.bounds}.mapNotNull {PdfLocators.normalize(page,it,info.width,info.height)}).takeIf {it.boxes.isNotEmpty()}
    }
    suspend fun close() {bitmaps.evictAll();basic?.close()}
}

@Composable
internal fun CroppedPdfScreen(book:Book,repository:LibraryRepository,document:PdfDocument?,advanced:Boolean,config:PdfCropConfig,vertical:Boolean,annotations:List<Annotation>,requestedPage:Int?,originalFraction:Float,onPage:(Int,Int,Float)->Unit,onSelection:(PdfSelection)->Unit,onTap:()->Unit,searchBoxes:List<PdfBox> = emptyList(),notify:(String)->Unit = {}) {
    var source by remember(book.id,document,advanced) {mutableStateOf<CroppedPdfSource?>(null)}
    var failure by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(book.id,document,advanced) {
        if(advanced && document==null)return@LaunchedEffect
        var opened:CroppedPdfSource?=null
        try {withContext(Dispatchers.IO) {opened=CroppedPdfSource(document,repository.source(book))};source=opened;opened=null}
        catch(e:CancellationException) {throw e} catch(e:Exception) {failure=e.message ?: "PDF 无法打开"}
        finally {opened?.let {withContext(NonCancellable) {it.close()}}}
    }
    DisposableEffect(source) {val s=source;onDispose {if(s!=null)repository.closePdfResource {s.close()}}}
    val s=source
    if(s==null) {Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {if(failure!=null)Text(failure!!) else CircularProgressIndicator()};return}
    val initial=(requestedPage ?: book.chapterIndex).coerceIn(0,s.count-1)
    val scope=rememberCoroutineScope()
    val currentOnPage by rememberUpdatedState(onPage)
    if(vertical) {
        val list=rememberLazyListState(initial)
        val geometry=remember(s) {object:LinkedHashMap<Int,Pair<Int,CropRect>>() {
            override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Int,Pair<Int,CropRect>>?)=size>32
        }}
        val geometryEvents=remember(s) {MutableStateFlow<Map<Int,Pair<Int,CropRect>>>(emptyMap())}
        var restoring by remember {mutableStateOf(true)}
        var reported by remember {mutableStateOf<Pair<Int,Int>?>(null)}
        LaunchedEffect(requestedPage,config) {
            restoring=true
            try {
                val target=(requestedPage ?: initial).coerceIn(0,s.count-1)
                val original=originalFraction
                list.scrollToItem(target, 0)
                currentOnPage(target, s.count, 0f)
                if (original > 0f) {
                    val geo = withTimeoutOrNull(250) {
                        geometryEvents.map { it[target] }.filterNotNull().first()
                    }
                    if (geo != null) {
                        val (height, crop) = geo
                        val offset = ((original - crop.top) / crop.height * height).toInt().coerceIn(0, (height - 1).coerceAtLeast(0))
                        list.scrollToItem(target, offset)
                        currentOnPage(target, s.count, (crop.top + offset.toFloat() / height * crop.height).coerceIn(0f, 1f))
                    }
                }
            } finally {restoring=false;reported=null}
        }
        LazyColumn(Modifier.fillMaxSize().testTag("pdf-cropped-list").pointerInput(s) {detectTapGestures(onTap={onTap()})}) {items(s.count,key={it}) {p->
            CroppedPdfPage(s,p,config,annotations,searchBoxes,false,onSelection,onTap,{target->scope.launch {list.requestScrollToItem(target)}},notify) {height,crop,top->
                val old=geometry[p]
                val visible=top<=0f && top+height>0f
                if(old!=(height to crop)) {
                    geometry[p]=height to crop;geometryEvents.value=geometry.toMap()
                    if(old!=null && old.second!=crop && visible && !restoring) {
                        val original=originalFraction
                        restoring=true
                        scope.launch {try {val offset=((original-crop.top)/crop.height*height).toInt().coerceIn(0,(height-1).coerceAtLeast(0));list.requestScrollToItem(p,offset)} finally {restoring=false;reported=null}}
                    }
                }
                if(!restoring && visible) {
                    val offset=(-top).roundToInt()
                    if(reported!=(p to offset)) {reported=p to offset;currentOnPage(p,s.count,(crop.top+offset.toFloat()/height*crop.height).coerceIn(0f,1f))}
                }
            }
        }}
    } else {
        val pager=rememberPagerState(initialPage=initial,pageCount={s.count})
        LaunchedEffect(requestedPage) {requestedPage?.let {pager.scrollToPage(it.coerceIn(0,s.count-1))}}
        LaunchedEffect(pager.settledPage) {currentOnPage(pager.settledPage,s.count,originalFraction)}
        HorizontalPager(pager,Modifier.fillMaxSize(),beyondViewportPageCount=0) {p->
            CroppedPdfPage(s,p,config,annotations,searchBoxes,true,onSelection,onTap,{target->scope.launch {pager.animateScrollToPage(target.coerceIn(0,s.count-1))}},notify) {_,_,_->}
        }
    }
}

@Composable
private fun CroppedPdfPage(source:CroppedPdfSource,page:Int,config:PdfCropConfig,annotations:List<Annotation>,searchBoxes:List<PdfBox>,horizontal:Boolean,onSelection:(PdfSelection)->Unit,onTap:()->Unit,go:(Int)->Unit,notify:(String)->Unit,measured:(Int,CropRect,Float)->Unit) {
    var image by remember(source,page) {mutableStateOf<RenderedPdfPage?>(null)}
    var detected by remember(source,page) {mutableStateOf(CropRect.FULL)}
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(source,page) {try {image=source.render(page)}catch(e:CancellationException) {throw e}catch(e:Exception) {error=e.message}}
    LaunchedEffect(source,page,config.automatic,config.enabled) {
        if(!config.enabled || !config.automatic)return@LaunchedEffect
        try {val preview=source.render(page,true);detected=withContext(Dispatchers.Default) {val b=preview.bitmap;val pixels=IntArray(b.width*b.height);b.getPixels(pixels,0,b.width,0,0,b.width,b.height);ensureActive();AutoPdfCrop.detect(pixels,b.width,b.height)}}
        catch(e:CancellationException) {throw e}catch(_:Exception) {detected=CropRect.FULL}
    }
    val pageMarks=remember(annotations,page) {
        annotations.sortedBy {maxOf(it.updatedAt,it.createdAt)}.mapNotNull {ann->
            val boxes=PdfLocators.decode(ann.locator).filter {it.page==page}
            if(boxes.isEmpty()) null
            else {
                val color=try {android.graphics.Color.parseColor(MarkColor.normalize(ann.color))} catch(_:Exception) {0xFFFFD240.toInt()}
                Pair(ann to boxes, color)
            }
        }
    }
    val highlightMarks=remember(pageMarks) {pageMarks.filter {it.first.first.kind=="HIGHLIGHT"}}
    val lineMarks=remember(pageMarks) {pageMarks.filter {it.first.first.kind in listOf("UNDERLINE","NOTE")}}
    val drawPaint=remember {android.graphics.Paint().apply {isAntiAlias=true}}
    val crop=config.resolve(page,detected)
    var localTop by remember {mutableFloatStateOf(0f)}
    var origin by remember {mutableStateOf(Offset.Zero)}
    var width by remember {mutableIntStateOf(0)}
    var size by remember {mutableStateOf(IntSize.Zero)}
    var zoom by remember(page,config) {mutableFloatStateOf(1f)}
    var pan by remember(page,config) {mutableStateOf(Offset.Zero)}
    var endpoints by remember(page) {mutableStateOf<Pair<Offset,Offset>?>(null)}
    val scope=rememberCoroutineScope()
    val transform=rememberTransformableState {factor,delta,_->zoom=(zoom*factor).coerceIn(1f,5f);pan+=delta}
    LaunchedEffect(size,crop,image) {if(size.height>0 && image!=null) measured(size.height,crop,localTop)}
    val im=image
    val ratio=if(im!=null) im.height*crop.height/(im.width*crop.width) else 1.4f
    val pageModifier=if(horizontal)Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(1/ratio)
    Box(pageModifier.background(MaterialTheme.colorScheme.background).testTag(if(image!=null) "pdf-cropped-page-$page" else "pdf-cropped-loading-$page").onGloballyPositioned {origin=it.positionInWindow();localTop=it.positionInRoot().y;if(image!=null)measured(it.size.height,crop,localTop)}.onSizeChanged {size=it;width=it.width}) {
        if(im==null) {if(error!=null)Text(error!!,Modifier.padding(20.dp))else CircularProgressIndicator(Modifier.align(Alignment.Center));return@Box}
        val fit=if(horizontal) min(size.width/(im.width*crop.width),size.height/(im.height*crop.height)) else size.width/(im.width*crop.width)
        if(fit<=0)return@Box
        val left=(size.width-im.width*crop.width*fit)/2f
        val top=if(horizontal) ((size.height-im.height*crop.height*fit)/2f).coerceAtLeast(0f) else 0f
        val coordinates=PdfCoordinateTransform(crop,im.width.toFloat(),im.height.toFloat(),fit,left,zoom,pan.x,pan.y,top=top)
        val currentCoordinates by rememberUpdatedState(coordinates)
        val currentMarks by rememberUpdatedState(pageMarks.map {it.first})
        val currentSize by rememberUpdatedState(size)
        fun point(p:Offset):PointF {val c=currentCoordinates;val(x,y)=c.toPage(p.x,p.y);return PointF(x.coerceIn(c.crop.left,c.crop.right)*im.width,y.coerceIn(c.crop.top,c.crop.bottom)*im.height)}
        fun withBounds(value:PdfSelection):PdfSelection {
            val boxes=value.boxes.filter {it.page==page}
            if(boxes.isEmpty())return value
            val c=currentCoordinates
            val a=c.toView(boxes.minOf {it.left},boxes.minOf {it.top});val b=c.toView(boxes.maxOf {it.right},boxes.maxOf {it.bottom})
            return value.copy(windowBounds=androidx.compose.ui.geometry.Rect(origin.x+a.first,origin.y+a.second,origin.x+b.first,origin.y+b.second))
        }
        fun select(a:Offset,b:Offset) {scope.launch {try {
            val pa=point(a);val pb=point(b)
            val text=source.select(page,pa,pb)
            if(text!=null)onSelection(withBounds(text)) else {
                val boxes = PdfFlowSelection.buildFlowBoxes(page, pa, pb, im.width.toFloat(), im.height.toFloat(), crop.left, crop.right)
                if (boxes.isNotEmpty()) onSelection(withBounds(PdfSelection("", boxes)))
            }
        }catch(e:CancellationException) {throw e}catch(_:Exception) {notify("PDF 选区读取失败，未保存标记")}}}
        Canvas(Modifier.fillMaxSize().transformable(transform,canPan={zoom>1f})
            .pointerInput(source,page) {detectTapGestures(onTap={where->
                val p=point(where);val x=p.x/im.width;val y=p.y/im.height
                val found=currentMarks.filter {(_,boxes)->boxes.any {x in it.left..it.right && y in it.top..it.bottom}}.map {it.first}
                if(found.isNotEmpty()) {val a=found.maxBy {maxOf(it.updatedAt,it.createdAt)};onSelection(withBounds(PdfSelection(a.quote,PdfLocators.decode(a.locator),found.map {it.id})))}
                else scope.launch {
                    try {
                    val links=source.document?.getPageLinks(page)
                    val internal=links?.gotoLinks?.firstOrNull {it.bounds.any {r->r.contains(p.x,p.y)}}
                    if(internal!=null)go(internal.destination.pageNumber)
                    else if(links?.externalLinks?.any {it.bounds.any {r->r.contains(p.x,p.y)}}==true)notify("外部链接未打开：本版本仅访问本地内容")
                    else if(horizontal && where.x<currentSize.width/3)go((page-1).coerceAtLeast(0))
                    else if(horizontal && where.x>currentSize.width*2/3)go((page+1).coerceAtMost(source.count-1)) else onTap()
                    } catch(e:CancellationException) {throw e} catch(_:Exception) {notify("页面链接读取失败")}
                }
            })}
            .pointerInput(source,page) {
                var a=Offset.Zero;var b=Offset.Zero
                detectDragGesturesAfterLongPress(onDragStart={a=it;b=it;val na=currentCoordinates.toPage(a.x,a.y);endpoints=Offset(na.first,na.second) to Offset(na.first,na.second)},onDragEnd={select(a,b)},onDragCancel={endpoints=null}) {change,_->change.consume();b=change.position;val na=currentCoordinates.toPage(a.x,a.y);val nb=currentCoordinates.toPage(b.x,b.y);endpoints=Offset(na.first,na.second) to Offset(nb.first,nb.second)}
            }) {
            val canvas=drawContext.canvas.nativeCanvas
            canvas.save();canvas.clipRect(0f,0f,size.width.toFloat(),size.height.toFloat())
            canvas.translate(pan.x,pan.y);canvas.scale(zoom,zoom)
            canvas.clipRect(left,top,left+im.width*crop.width*fit,top+im.height*crop.height*fit)
            val x=left-crop.left*im.width*fit;val y=top-crop.top*im.height*fit
            drawImage(im.bitmap.asImageBitmap(),dstOffset=IntOffset(x.roundToInt(),y.roundToInt()),dstSize=IntSize((im.width*fit).roundToInt(),(im.height*fit).roundToInt()))
            val lineWidth=2.dp.toPx()
            if(highlightMarks.isNotEmpty()) {
                val layer=canvas.saveLayerAlpha(null,82)
                highlightMarks.forEach {item->
                    drawPaint.color=item.second
                    item.first.second.forEach {b->
                        val bl=left+(b.left-crop.left)*im.width*fit;val bt=top+(b.top-crop.top)*im.height*fit
                        val br=left+(b.right-crop.left)*im.width*fit;val bb=top+(b.bottom-crop.top)*im.height*fit
                        canvas.drawRect(bl,bt,br,bb,drawPaint)
                    }
                }
                canvas.restoreToCount(layer)
            }
            if(lineMarks.isNotEmpty()) {
                drawPaint.strokeWidth=lineWidth
                lineMarks.forEach {item->
                    drawPaint.color=item.second
                    item.first.second.forEach {b->
                        val bl=left+(b.left-crop.left)*im.width*fit;val br=left+(b.right-crop.left)*im.width*fit
                        val bb=top+(b.bottom-crop.top)*im.height*fit
                        canvas.drawLine(bl,bb,br,bb,drawPaint)
                    }
                }
            }
            if(searchBoxes.any {it.page==page}) {
                drawPaint.color=0x663B82F6
                searchBoxes.filter {it.page==page}.forEach {b->
                    val bl=left+(b.left-crop.left)*im.width*fit;val bt=top+(b.top-crop.top)*im.height*fit
                    val br=left+(b.right-crop.left)*im.width*fit;val bb=top+(b.bottom-crop.top)*im.height*fit
                    canvas.drawRect(bl,bt,br,bb,drawPaint)
                }
            }
            endpoints?.let { (epA, epB) ->
                val (pa, pb) = if (epA.y < epB.y || (epA.y == epB.y && epA.x <= epB.x)) epA to epB else epB to epA
                drawPaint.color = 0x55246BFC
                val lineHeight = 0.024f * im.height * fit
                val contentLeft = left
                val contentRight = left + im.width * crop.width * fit
                val v1x = left + (pa.x - crop.left) * im.width * fit
                val v1y = top + (pa.y - crop.top) * im.height * fit
                val v2x = left + (pb.x - crop.left) * im.width * fit
                val v2y = top + (pb.y - crop.top) * im.height * fit
                if (kotlin.math.abs(v2y - v1y) < lineHeight * 0.9f) {
                    val l = minOf(v1x, v2x)
                    val r = maxOf(v1x, v2x).coerceAtLeast(l + 16f)
                    val t = minOf(v1y, v2y)
                    canvas.drawRect(l, t, r, t + lineHeight, drawPaint)
                } else {
                    val firstLeft = v1x.coerceIn(contentLeft, contentRight)
                    if (contentRight > firstLeft) {
                        canvas.drawRect(firstLeft, v1y, contentRight, v1y + lineHeight, drawPaint)
                    }
                    var curY = v1y + lineHeight
                    while (curY + lineHeight <= v2y) {
                        canvas.drawRect(contentLeft, curY, contentRight, curY + lineHeight, drawPaint)
                        curY += lineHeight
                    }
                    val lastRight = v2x.coerceIn(contentLeft, contentRight)
                    if (lastRight > contentLeft) {
                        canvas.drawRect(contentLeft, curY, lastRight, curY + lineHeight, drawPaint)
                    }
                }
            }
            canvas.restore()
        }
        endpoints?.let {points->listOf(points.first,points.second).forEachIndexed {index,p->
            val position=coordinates.toView(p.x,p.y)
            Box(Modifier.offset {IntOffset(position.first.roundToInt()-24.dp.roundToPx(),position.second.roundToInt()-24.dp.roundToPx())}.size(48.dp)
                .pointerInput(coordinates,index) {detectDragGestures(onDragEnd={endpoints?.let {(a,b)->val av=coordinates.toView(a.x,a.y);val bv=coordinates.toView(b.x,b.y);select(Offset(av.first,av.second),Offset(bv.first,bv.second))}}) {change,delta->
                    change.consume();val old=endpoints ?: return@detectDragGestures;val value=if(index==0)old.first else old.second;val at=coordinates.toView(value.x,value.y);val updated=coordinates.toPage(at.first+delta.x,at.second+delta.y);val point=Offset(updated.first.coerceIn(0f,1f),updated.second.coerceIn(0f,1f));endpoints=if(index==0) point to old.second else old.first to point
                }},contentAlignment=Alignment.Center) {Box(Modifier.size(16.dp).background(Color(0xFF246BFC),CircleShape))}
        }}
        if(zoom>1)TextButton(onClick={zoom=1f;pan=Offset.Zero},modifier=Modifier.align(Alignment.TopEnd)) {Text("重置缩放")}
    }
}


