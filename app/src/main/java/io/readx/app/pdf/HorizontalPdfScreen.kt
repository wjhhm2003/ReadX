@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)
package io.readx.app.pdf

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.nativeCanvas
import io.readx.app.data.MarkColor
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.pdf.PdfDocument
import androidx.pdf.content.PdfPageTextContent
import io.readx.app.data.Annotation
import io.readx.app.data.Book
import io.readx.app.data.LibraryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.min

/** Normalized page-space boxes survive zoom, rotation of the device, and different rendering resolutions. */
data class PdfBox(val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)
data class PdfSelection(val quote: String, val boxes: List<PdfBox>,val existingIds: List<String> = emptyList(),val windowBounds: androidx.compose.ui.geometry.Rect? = null)
object PdfLocators {
    fun encode(boxes: List<PdfBox>): String {
        val values=JSONArray(); boxes.forEach { b->values.put(JSONObject().put("page",b.page).put("left",b.left).put("top",b.top).put("right",b.right).put("bottom",b.bottom)) }
        return JSONObject().put("format","PDF").put("rects",values).toString()
    }
    fun decode(value: String): List<PdfBox> = runCatching {
        val json=JSONObject(value); require(json.optString("format")=="PDF")
        val values=json.getJSONArray("rects"); require(values.length() in 1..2000)
        (0 until values.length()).map { i->val b=values.getJSONObject(i)
            PdfBox(b.getInt("page"),b.getDouble("left").toFloat(),b.getDouble("top").toFloat(),b.getDouble("right").toFloat(),b.getDouble("bottom").toFloat()).also {
                require(it.page>=0 && listOf(it.left,it.top,it.right,it.bottom).all { n->n.isFinite() && n in 0f..1f } && it.left<it.right && it.top<it.bottom)
            }
        }
    }.getOrDefault(emptyList())
    fun normalize(page: Int, rect: RectF, width: Int, height: Int): PdfBox? {
        val box=PdfBox(page,(rect.left/width).coerceIn(0f,1f),(rect.top/height).coerceIn(0f,1f),(rect.right/width).coerceIn(0f,1f),(rect.bottom/height).coerceIn(0f,1f))
        return box.takeIf { it.left<it.right && it.top<it.bottom }
    }
}

/** PdfRenderer fallback is used only when the advanced document service is unavailable. */
internal class NativePdfSource(file: File) {
    private val descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer=try { PdfRenderer(descriptor) } catch(e: Exception) {descriptor.close();throw e}
    private val lock=Mutex()
    private var closed=false
    val count=renderer.pageCount
    suspend fun render(index: Int, target: Int = 1280): RenderedPdfPage=withContext(Dispatchers.IO) { lock.withLock {
        check(!closed) {"PDF 文档已关闭"}
        renderer.openPage(index).use { p->
            val size=renderSize(p.width,p.height,target)
            val bitmap=Bitmap.createBitmap(size.width,size.height,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE);p.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            RenderedPdfPage(bitmap,p.width,p.height)
        }
    } }
    @SuppressLint("NewApi")
    suspend fun select(index: Int, start: PointF,end: PointF): PdfSelection?=withContext(Dispatchers.IO) { lock.withLock {
        check(!closed) {"PDF 文档已关闭"}
        if(Build.VERSION.SDK_INT<35) return@withLock null
        renderer.openPage(index).use { p->
            val selection=p.selectContent(android.graphics.pdf.models.selection.SelectionBoundary(android.graphics.Point(start.x.toInt(),start.y.toInt())),android.graphics.pdf.models.selection.SelectionBoundary(android.graphics.Point(end.x.toInt(),end.y.toInt()))) ?: return@use null
            val texts=selection.selectedTextContents
            val boxes=texts.flatMap { it.bounds }.mapNotNull { PdfLocators.normalize(index,it,p.width,p.height) }
            PdfSelection(texts.joinToString("\n") {it.text}.take(16384),boxes).takeIf { it.boxes.isNotEmpty() }
        }
    } }
    suspend fun close()=withContext(NonCancellable+Dispatchers.IO) {lock.withLock {
        if(!closed) {closed=true;try {renderer.close()} finally {descriptor.close()}}
    }}
}
internal data class RenderedPdfPage(val bitmap: Bitmap,val width: Int,val height: Int)
internal fun renderSize(width: Int,height: Int,target: Int = 1280): Size {
    require(width>0 && height>0) {"PDF 页面尺寸无效"}
    val scale=min(target.toFloat()/width,(target*1.8f)/height)
    return Size((width*scale).toInt().coerceAtLeast(1),(height*scale).toInt().coerceAtLeast(1))
}

@Composable
fun HorizontalPdfScreen(book: Book, repository: LibraryRepository, document: PdfDocument?, useAdvanced: Boolean,
    annotations: List<Annotation>, requestedPage: Int?, onPage: (Int,Int)->Unit, onSelection: (PdfSelection)->Unit,onTapPage: ()->Unit = {}, flashBoxes:List<PdfBox> = emptyList(),inverted:Boolean=false,activeSelection:PdfSelection?=null) {
    var native by remember(book.id) { mutableStateOf<NativePdfSource?>(null) }
    var error by remember(book.id) { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(book.id,useAdvanced) {
        if(!useAdvanced) {
            var opened: NativePdfSource?=null
            try {
                withContext(Dispatchers.IO) {opened=NativePdfSource(repository.source(book))}
                native=opened;opened=null
            } catch(e: CancellationException) {throw e} catch(e: Exception) {error=e.message?:"PDF 无法打开"}
            finally {opened?.let {withContext(NonCancellable) {it.close()}}}
        }
    }
    val ownedNative=native
    DisposableEffect(ownedNative) { onDispose { if(ownedNative!=null) repository.closePdfResource {ownedNative.close()} } }
    val count=document?.pageCount ?: native?.count ?: 0
    if(count<=0) { Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {if(error!=null) Text(error!!,Modifier.padding(24.dp)) else CircularProgressIndicator()};return }
    val pager=rememberPagerState(initialPage=(requestedPage ?: book.chapterIndex).coerceIn(0,count-1),pageCount={count})
    LaunchedEffect(requestedPage) { requestedPage?.let { pager.scrollToPage(it.coerceIn(0,count-1)) } }
    LaunchedEffect(pager.settledPage,count) {onPage(pager.settledPage,count);delay(350);repository.dao.savePosition(book.id,pager.settledPage,0f,System.currentTimeMillis())}
    DisposableEffect(book.id,pager) {onDispose {repository.persistPosition(book.id,pager.settledPage,0f)}}
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HorizontalPager(pager,Modifier.weight(1f),beyondViewportPageCount=1) { page ->
            var rendered by remember(page,document,native) {mutableStateOf<RenderedPdfPage?>(null)}
            var pageError by remember(page) {mutableStateOf<String?>(null)}
            LaunchedEffect(page,document,native) {
                try {
                    rendered=if(document!=null) {
                        val info=document.getPageInfo(page)
                        val source=document.getPageBitmapSource(page)
                        try {RenderedPdfPage(source.getBitmap(renderSize(info.width,info.height)),info.width,info.height)}
                        finally {withContext(NonCancellable+Dispatchers.IO) {source.close()}}
                    } else native?.render(page)
                } catch(e: CancellationException) {throw e} catch(e: Exception) {pageError=e.message?:"页面渲染失败"}
            }
            val image=rendered
            if(image==null) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {if(pageError!=null) Text(pageError!!) else CircularProgressIndicator()}
            else {
                var size by remember {mutableStateOf(IntSize.Zero)}
                var zoom by remember(page) {mutableFloatStateOf(1f)}
                var pan by remember(page) {mutableStateOf(Offset.Zero)}
                val transform=rememberTransformableState { scale,offset,_->zoom=(zoom*scale).coerceIn(1f,5f);pan=if(zoom<=1f) Offset.Zero else pan+offset }
                val fit=if(size.width>0 && size.height>0) min(size.width.toFloat()/image.width,size.height.toFloat()/image.height) else 1f
                val left=(size.width-image.width*fit)/2
                val top=((size.height-image.height*fit)/2).coerceAtLeast(0f)
                fun toPdf(offset: Offset)=PointF(((offset.x-pan.x)/zoom-left).div(fit).coerceIn(0f,image.width.toFloat()),(((offset.y-pan.y)/zoom-top)/fit).coerceIn(0f,image.height.toFloat()))
                var origin by remember(page) {mutableStateOf(Offset.Zero)}
                fun withBounds(value:PdfSelection):PdfSelection {
                    val boxes=value.boxes.filter {it.page==page}
                    if(boxes.isEmpty())return value
                    val x1=(left+boxes.minOf {it.left}*image.width*fit)*zoom+pan.x
                    val y1=(top+boxes.minOf {it.top}*image.height*fit)*zoom+pan.y
                    val x2=(left+boxes.maxOf {it.right}*image.width*fit)*zoom+pan.x
                    val y2=(top+boxes.maxOf {it.bottom}*image.height*fit)*zoom+pan.y
                    return value.copy(windowBounds=androidx.compose.ui.geometry.Rect(origin.x+x1,origin.y+y1,origin.x+x2,origin.y+y2))
                }
                val textSelection=rememberPdfTextSelection(page,image.width.toFloat(),image.height.toFloat(),{a,b->
                    if(document!=null) {
                        val picked=document.getSelectionBounds(page,a,b)
                        val texts=picked?.selectedContents?.filterIsInstance<PdfPageTextContent>().orEmpty()
                        PdfSelection(texts.joinToString("\n") {it.text}.take(16384),texts.flatMap {it.bounds}.mapNotNull {PdfLocators.normalize(page,it,image.width,image.height)}).takeIf {it.boxes.isNotEmpty()}
                    } else native?.select(page,a,b)
                },{onSelection(withBounds(it))},{pageError=it})
                LaunchedEffect(activeSelection) {textSelection.sync(activeSelection)}
                Box(Modifier.fillMaxSize().testTag("pdf-page-$page").onGloballyPositioned {origin=it.positionInWindow()}.onSizeChanged {size=it}.transformable(transform,canPan={zoom>1f})
                    .pointerInput(page,fit,top,zoom,pan,annotations) {
                        detectTapGestures(onLongPress={textSelection.begin(toPdf(it))},onTap={where->
                            if(textSelection.picked!=null) {textSelection.cancel();onTapPage();return@detectTapGestures}
                            val direction = when {
                                where.x < size.width / 3f -> -1
                                where.x >= size.width * 2 / 3f -> 1
                                else -> 0
                            }
                            if (direction != 0) {
                                if (!pager.isScrollInProgress) {
                                    val target = (pager.currentPage + direction).coerceIn(0, count - 1)
                                    if (target != pager.currentPage) scope.launch { pager.animateScrollToPage(target) }
                                }
                            } else {
                                val point=toPdf(where);val nx=point.x/image.width;val ny=point.y/image.height
                                val found=annotations.filter {a->PdfLocators.decode(a.locator).any {it.page==page && nx in it.left..it.right && ny in it.top..it.bottom}}
                                if(found.isNotEmpty()) {val newest=found.maxBy {maxOf(it.updatedAt,it.createdAt)};onSelection(PdfSelection(newest.quote,PdfLocators.decode(newest.locator),found.map {it.id}))}
                                else onTapPage()
                            }
                        })
                    }
                    .pointerInput(page,fit,top,zoom,pan,document,native) {
                        detectDragGesturesAfterLongPress(onDragStart={textSelection.begin(toPdf(it))},
                            onDragCancel={textSelection.cancel()},onDragEnd={textSelection.finish()}) {change,_->
                            change.consume();textSelection.extend(toPdf(change.position))
                        }
                    }) {
                    Canvas(Modifier.fillMaxSize().graphicsLayer {scaleX=zoom;scaleY=zoom;translationX=pan.x;translationY=pan.y;transformOrigin=TransformOrigin(0f,0f)}) {
                        drawImage(image.bitmap.asImageBitmap(),dstOffset=IntOffset(left.toInt(),top.toInt()),dstSize=IntSize((image.width*fit).toInt(),(image.height*fit).toInt()),colorFilter=if(inverted) PdfNightMode.filter else null)
                        flashBoxes.filter {it.page==page}.forEach {b->drawRect(Color(0x55246BFC),Offset(left+b.left*image.width*fit,top+b.top*image.height*fit),androidx.compose.ui.geometry.Size((b.right-b.left)*image.width*fit,(b.bottom-b.top)*image.height*fit))}
                        val sorted=annotations.sortedBy {maxOf(it.updatedAt,it.createdAt)}
                        val layerPaint=android.graphics.Paint().apply {alpha=82}
                        val layer=drawContext.canvas.nativeCanvas.saveLayer(null,layerPaint)
                        sorted.filter {it.kind=="HIGHLIGHT"}.forEach {annotation->PdfLocators.decode(annotation.locator).filter {it.page==page}.forEach {b->
                            drawRect(Color(android.graphics.Color.parseColor(MarkColor.normalize(annotation.color))),Offset(left+b.left*image.width*fit,top+b.top*image.height*fit),androidx.compose.ui.geometry.Size((b.right-b.left)*image.width*fit,(b.bottom-b.top)*image.height*fit))
                        }}
                        drawContext.canvas.nativeCanvas.restoreToCount(layer)
                        sorted.filter {it.kind=="UNDERLINE" || it.kind=="NOTE"}.forEach {annotation->PdfLocators.decode(annotation.locator).filter {it.page==page}.forEach {b->
                            drawLine(Color(android.graphics.Color.parseColor(MarkColor.normalize(annotation.color))),Offset(left+b.left*image.width*fit,top+b.bottom*image.height*fit),Offset(left+b.right*image.width*fit,top+b.bottom*image.height*fit),strokeWidth=2.dp.toPx(),pathEffect=if(annotation.kind=="NOTE") androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f,4f)) else null)
                        }}

                    }
                    PdfTextSelectionHandles(textSelection,page,
                        {x,y->Offset((left+x*image.width*fit)*zoom+pan.x,(top+y*image.height*fit)*zoom+pan.y)},{toPdf(it)})
                    if(zoom>1f) TextButton(onClick={zoom=1f;pan=Offset.Zero},modifier=Modifier.align(Alignment.TopEnd)) {Text("重置缩放")}
                }
            }
        }
    }
}
