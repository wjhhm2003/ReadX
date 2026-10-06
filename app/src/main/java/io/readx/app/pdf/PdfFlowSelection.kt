package io.readx.app.pdf

import android.graphics.PointF
import android.graphics.RectF

object PdfFlowSelection {
    fun orderPoints(a: PointF, b: PointF): Pair<PointF, PointF> {
        val start = if (a.y < b.y || (a.y == b.y && a.x <= b.x)) a else b
        val end = if (start === a) b else a
        return start to end
    }

    /**
     * Generates a linear flow of normalized bounding boxes across multiple lines
     * instead of a single 2D bounding rectangle.
     */
    fun buildFlowBoxes(
        page: Int,
        a: PointF,
        b: PointF,
        pageWidth: Float,
        pageHeight: Float,
        contentMarginLeft: Float = 0.08f,
        contentMarginRight: Float = 0.92f
    ): List<PdfBox> {
        if (pageWidth <= 0f || pageHeight <= 0f) return emptyList()
        val (start, end) = orderPoints(a, b)

        val sy = (start.y / pageHeight).coerceIn(0f, 1f)
        val ey = (end.y / pageHeight).coerceIn(0f, 1f)
        val sx = (start.x / pageWidth).coerceIn(0f, 1f)
        val ex = (end.x / pageWidth).coerceIn(0f, 1f)

        val estimatedLineHeight = 0.024f // ~2.4% of page height
        val deltaY = ey - sy

        if (deltaY < estimatedLineHeight * 0.85f) {
            // Single line
            val left = minOf(sx, ex)
            val right = maxOf(sx, ex).coerceAtLeast(left + 0.02f)
            val top = (sy - estimatedLineHeight * 0.1f).coerceIn(0f, 1f)
            val bottom = (top + estimatedLineHeight).coerceIn(0f, 1f)
            return listOf(PdfBox(page, left, top, right, bottom))
        }

        val boxes = mutableListOf<PdfBox>()
        val leftMargin = contentMarginLeft.coerceIn(0f, 0.45f)
        val rightMargin = contentMarginRight.coerceIn(0.55f, 1f)

        // 1. First line: from sx to rightMargin
        val firstTop = sy
        val firstBottom = (sy + estimatedLineHeight).coerceAtMost(ey)
        val firstLeft = sx.coerceIn(leftMargin, rightMargin)
        val firstRight = rightMargin
        if (firstRight > firstLeft && firstBottom > firstTop) {
            boxes.add(PdfBox(page, firstLeft, firstTop, firstRight, firstBottom))
        }

        // 2. Middle lines: full content width
        var curY = firstBottom
        while (curY + estimatedLineHeight <= ey) {
            boxes.add(PdfBox(page, leftMargin, curY, rightMargin, curY + estimatedLineHeight))
            curY += estimatedLineHeight
        }

        // 3. Last line: from leftMargin to ex
        val lastTop = curY
        val lastBottom = maxOf(curY + estimatedLineHeight, ey)
        val lastLeft = leftMargin
        val lastRight = ex.coerceIn(leftMargin, rightMargin)
        if (lastRight > lastLeft && lastBottom > lastTop) {
            boxes.add(PdfBox(page, lastLeft, lastTop, lastRight, lastBottom))
        } else if (boxes.isEmpty()) {
            boxes.add(PdfBox(page, leftMargin, lastTop, rightMargin, lastBottom))
        }

        return boxes
    }
}
