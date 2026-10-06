package io.readx.app.pdf

import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/** RGB inversion only; alpha, source PDF, cached bitmaps and annotation colours are unchanged. */
internal object PdfNightMode {
    val matrix = floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    )
    val filter = ColorFilter.colorMatrix(ColorMatrix(matrix))
    @RequiresApi(31)
    fun effect():RenderEffect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
}
