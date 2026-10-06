package io.readx.app.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.SparseArray
import android.view.View
import io.readx.app.data.Annotation
import io.readx.app.data.MarkColor

private data class RenderedOverlayMark(
    val annotation: Annotation,
    val boxes: List<PdfBox>,
    val color: Int,
    val isHighlight: Boolean,
    val isNote: Boolean
)

/** Non-interactive overlay; bounds are viewport-relative, normalized to each PDF page. */
internal class PdfAnnotationOverlay(context: Context): View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val noteDashEffect=android.graphics.DashPathEffect(floatArrayOf(5f,4f),0f)
    private val strokeWidthPx=2f*resources.displayMetrics.density
    private var marks=emptyList<Pair<Annotation,List<PdfBox>>>()
    private var highlightMarks=emptyList<RenderedOverlayMark>()
    private var lineMarks=emptyList<RenderedOverlayMark>()
    private var flashBoxes=emptyList<PdfBox>()
    fun flash(boxes:List<PdfBox>) {flashBoxes=boxes;invalidate()}
    private var locations=SparseArray<RectF>()
    init {isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    fun updateAnnotations(annotations: List<Annotation>) {
        val decoded=annotations.sortedBy {maxOf(it.updatedAt,it.createdAt)}.map {ann->
            val parsedColor=try {android.graphics.Color.parseColor(MarkColor.normalize(ann.color))} catch(_:Exception) {0xFFFFD240.toInt()}
            RenderedOverlayMark(
                annotation=ann,
                boxes=PdfLocators.decode(ann.locator),
                color=parsedColor,
                isHighlight=ann.kind=="HIGHLIGHT",
                isNote=ann.kind=="NOTE"
            )
        }
        highlightMarks=decoded.filter {it.isHighlight}
        lineMarks=decoded.filter {!it.isHighlight && (it.annotation.kind=="UNDERLINE" || it.isNote)}
        marks=decoded.map {it.annotation to it.boxes}
        invalidate()
    }
    fun updateViewport(values: SparseArray<RectF>) {
        val count=values.size()
        val next=SparseArray<RectF>(count)
        for(i in 0 until count) {
            val key=values.keyAt(i)
            val src=values.valueAt(i)
            val existing=locations[key]
            if(existing!=null) {
                existing.set(src)
                next.put(key,existing)
            } else {
                next.put(key,RectF(src))
            }
        }
        locations=next
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if(flashBoxes.isNotEmpty()) {
            paint.color=0x55246BFC;paint.pathEffect=null
            flashBoxes.forEach {b->locations[b.page]?.let {p->canvas.drawRect(p.left+b.left*p.width(),p.top+b.top*p.height(),p.left+b.right*p.width(),p.top+b.bottom*p.height(),paint)}}
        }
        if(highlightMarks.isNotEmpty()) {
            val layer=canvas.saveLayerAlpha(null,82)
            paint.pathEffect=null
            highlightMarks.forEach {mark->
                paint.color=mark.color
                mark.boxes.forEach {box->locations[box.page]?.let {page->
                    canvas.drawRect(page.left+box.left*page.width(),page.top+box.top*page.height(),page.left+box.right*page.width(),page.top+box.bottom*page.height(),paint)
                }}
            }
            canvas.restoreToCount(layer)
        }
        if(lineMarks.isNotEmpty()) {
            paint.strokeWidth=strokeWidthPx
            lineMarks.forEach {mark->
                paint.color=mark.color
                paint.pathEffect=if(mark.isNote) noteDashEffect else null
                mark.boxes.forEach {box->locations[box.page]?.let {page->
                    canvas.drawLine(page.left+box.left*page.width(),page.top+box.bottom*page.height(),page.left+box.right*page.width(),page.top+box.bottom*page.height(),paint)
                }}
            }
        }
    }

    fun selectionWindowBounds(boxes:List<PdfBox>):androidx.compose.ui.geometry.Rect? {
        val rectangles=boxes.mapNotNull {b->locations[b.page]?.let {p->RectF(p.left+b.left*p.width(),p.top+b.top*p.height(),p.left+b.right*p.width(),p.top+b.bottom*p.height())}}.filter {it.bottom>0 && it.top<height && it.right>0 && it.left<width}
        if(rectangles.isEmpty())return null
        val origin=IntArray(2);getLocationInWindow(origin)
        return androidx.compose.ui.geometry.Rect(origin[0]+rectangles.minOf {it.left}.coerceAtLeast(0f),origin[1]+rectangles.minOf {it.top}.coerceAtLeast(0f),origin[0]+rectangles.maxOf {it.right}.coerceAtMost(width.toFloat()),origin[1]+rectangles.maxOf {it.bottom}.coerceAtMost(height.toFloat()))
    }
    fun hit(x: Float,y: Float): Annotation? = marks.asReversed().firstOrNull {(_,boxes)->boxes.any {b->locations[b.page]?.let {p->x in (p.left+b.left*p.width())..(p.left+b.right*p.width()) && y in (p.top+b.top*p.height())..(p.top+b.bottom*p.height())} ?: false}}?.first
}
