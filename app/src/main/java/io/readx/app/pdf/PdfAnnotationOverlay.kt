package io.readx.app.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.SparseArray
import android.view.View
import io.readx.app.data.Annotation
import io.readx.app.data.MarkColor

/** Non-interactive overlay; bounds are viewport-relative, normalized to each PDF page. */
internal class PdfAnnotationOverlay(context: Context): View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var marks=emptyList<Pair<Annotation,List<PdfBox>>>()
    private var locations=SparseArray<RectF>()
    init {isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    fun updateAnnotations(annotations: List<Annotation>) {marks=annotations.sortedBy {maxOf(it.updatedAt,it.createdAt)}.map {it to PdfLocators.decode(it.locator)};invalidate()}
    fun updateViewport(values: SparseArray<RectF>) {
        locations=SparseArray<RectF>().also {copy->for(i in 0 until values.size()) copy.put(values.keyAt(i),RectF(values.valueAt(i)))}
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layer=canvas.saveLayer(null,Paint().apply {alpha=82})
        marks.filter {it.first.kind=="HIGHLIGHT"}.forEach {(mark,boxes)->boxes.forEach {box->locations[box.page]?.let {page->
            paint.color=android.graphics.Color.parseColor(MarkColor.normalize(mark.color));paint.pathEffect=null
            canvas.drawRect(page.left+box.left*page.width(),page.top+box.top*page.height(),page.left+box.right*page.width(),page.top+box.bottom*page.height(),paint)
        }}}
        canvas.restoreToCount(layer)
        marks.filter {it.first.kind=="UNDERLINE" || it.first.kind=="NOTE"}.forEach {(mark,boxes)->boxes.forEach {box->locations[box.page]?.let {page->
            paint.color=android.graphics.Color.parseColor(MarkColor.normalize(mark.color));paint.strokeWidth=2*resources.displayMetrics.density
            paint.pathEffect=if(mark.kind=="NOTE") android.graphics.DashPathEffect(floatArrayOf(5f,4f),0f) else null
            canvas.drawLine(page.left+box.left*page.width(),page.top+box.bottom*page.height(),page.left+box.right*page.width(),page.top+box.bottom*page.height(),paint)
        }}}
    }
    fun hit(x: Float,y: Float): Annotation? = marks.asReversed().firstOrNull {(_,boxes)->boxes.any {b->locations[b.page]?.let {p->x in (p.left+b.left*p.width())..(p.left+b.right*p.width()) && y in (p.top+b.top*p.height())..(p.top+b.bottom*p.height())} ?: false}}?.first
}
