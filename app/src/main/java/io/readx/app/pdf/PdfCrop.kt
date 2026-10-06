package io.readx.app.pdf

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Fractions in ORIGINAL page space. No crop changes the source document or page identity. */
data class CropRect(val left:Float=0f,val top:Float=0f,val right:Float=1f,val bottom:Float=1f) {
    init {require(listOf(left,top,right,bottom).all {it.isFinite() && it in 0f..1f} && right-left>=.05f && bottom-top>=.05f)}
    val width get()=right-left
    val height get()=bottom-top
    companion object {val FULL=CropRect()}
}

/** Border-background estimate and conservative content projection on a <=384px preview. */
object AutoPdfCrop {
    fun detect(pixels:IntArray,width:Int,height:Int):CropRect {
        if(width<16 || height<16 || pixels.size!=width*height)return CropRect.FULL
        fun channels(c:Int)=intArrayOf(c shr 16 and 255,c shr 8 and 255,c and 255)
        val edge=ArrayList<Int>()
        for(x in 0 until width step 3) {edge+=pixels[x];edge+=pixels[(height-1)*width+x]}
        for(y in 0 until height step 3) {edge+=pixels[y*width];edge+=pixels[y*width+width-1]}
        val rgb=(0..2).map {channel->edge.map {channels(it)[channel]}.sorted().let {it[it.size/2]}}
        // Dark, colourful or nonuniform edges are uncertain: never invent a content crop.
        if(rgb.average()<175 || (rgb.maxOrNull()!!-rgb.minOrNull()!!)>35)return CropRect.FULL
        if(edge.count {c->channels(c).indices.any {abs(channels(c)[it]-rgb[it])>32}}>edge.size/10)return CropRect.FULL
        val rows=IntArray(height);val columns=IntArray(width);var total=0
        for(y in 0 until height) for(x in 0 until width) {
            val c=channels(pixels[y*width+x]);if((0..2).any {abs(c[it]-rgb[it])>35}) {rows[y]++;columns[x]++;total++}
        }
        if(total<max(12,width*height/5000) || total>width*height*.7)return CropRect.FULL
        // Retain even small page-number/footnote/side-note projections. Safety beats aggressive trimming.
        val l=columns.indexOfFirst {it>0};val r=columns.indexOfLast {it>0}
        val t=rows.indexOfFirst {it>0};val b=rows.indexOfLast {it>0}
        if(l<0 || r-l<width*.08 || b-t<height*.05)return CropRect.FULL
        val mx=max(4f,width*.025f);val my=max(4f,height*.025f)
        return runCatching {CropRect(((l-mx)/width).coerceAtLeast(0f),((t-my)/height).coerceAtLeast(0f),((r+1+mx)/width).coerceAtMost(1f),((b+1+my)/height).coerceAtMost(1f))}.getOrDefault(CropRect.FULL)
    }
}

/** Shared forward/inverse transform for drawing, gestures, links, results and sidecar markers. */
data class PdfCoordinateTransform(val crop:CropRect,val pageWidth:Float,val pageHeight:Float,val fit:Float,val left:Float=0f,val zoom:Float=1f,val panX:Float=0f,val panY:Float=0f,val top:Float=0f) {
    init {require(pageWidth>0 && pageHeight>0 && fit>0 && zoom>0)}
    fun toView(x:Float,y:Float):Pair<Float,Float> = ((x-crop.left)*pageWidth*fit+left)*zoom+panX to ((y-crop.top)*pageHeight*fit+top)*zoom+panY
    fun toPage(x:Float,y:Float):Pair<Float,Float> = (((x-panX)/zoom-left)/(pageWidth*fit)+crop.left) to (((y-panY)/zoom-top)/(pageHeight*fit)+crop.top)
}
