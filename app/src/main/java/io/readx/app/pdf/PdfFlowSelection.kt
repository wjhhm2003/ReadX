package io.readx.app.pdf

import android.graphics.PointF

/** Point ordering only. Selection boxes must come from the real PDF text layer. */
object PdfFlowSelection {
    fun orderPoints(a: PointF, b: PointF): Pair<PointF, PointF> {
        val start = if (a.y < b.y || (a.y == b.y && a.x <= b.x)) a else b
        val end = if (start === a) b else a
        return start to end
    }
}
