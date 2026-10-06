package io.readx.app.pdf

import kotlin.math.abs

enum class CropHandle { LEFT,TOP,RIGHT,BOTTOM,TOP_LEFT,TOP_RIGHT,BOTTOM_LEFT,BOTTOM_RIGHT }
object CropHandles {
    fun move(rect:CropRect,handle:CropHandle,dx:Float,dy:Float):CropRect {
        val left=handle in listOf(CropHandle.LEFT,CropHandle.TOP_LEFT,CropHandle.BOTTOM_LEFT)
        val right=handle in listOf(CropHandle.RIGHT,CropHandle.TOP_RIGHT,CropHandle.BOTTOM_RIGHT)
        val top=handle in listOf(CropHandle.TOP,CropHandle.TOP_LEFT,CropHandle.TOP_RIGHT)
        val bottom=handle in listOf(CropHandle.BOTTOM,CropHandle.BOTTOM_LEFT,CropHandle.BOTTOM_RIGHT)
        return rect.copy(left=if(left)(rect.left+dx).coerceIn(0f,rect.right-.051f) else rect.left,
            right=if(right)(rect.right+dx).coerceIn(rect.left+.051f,1f) else rect.right,
            top=if(top)(rect.top+dy).coerceIn(0f,rect.bottom-.051f) else rect.top,
            bottom=if(bottom)(rect.bottom+dy).coerceIn(rect.top+.051f,1f) else rect.bottom)
    }
    fun snap(rect:CropRect,detected:CropRect,thresholdX:Float,thresholdY:Float):CropRect {
        fun magnet(value:Float,edges:List<Float>,threshold:Float)=edges.minByOrNull {abs(it-value)}?.takeIf {abs(it-value)<=threshold} ?: value
        val l=magnet(rect.left,listOf(0f,detected.left),thresholdX).coerceAtMost(rect.right-.051f)
        val r=magnet(rect.right,listOf(1f,detected.right),thresholdX).coerceAtLeast(l+.051f)
        val t=magnet(rect.top,listOf(0f,detected.top),thresholdY).coerceAtMost(rect.bottom-.051f)
        val b=magnet(rect.bottom,listOf(1f,detected.bottom),thresholdY).coerceAtLeast(t+.051f)
        return CropRect(l,t,r,b)
    }
}
