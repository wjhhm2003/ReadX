package io.readx.app.reader

import io.readx.app.pdf.*
import org.junit.Assert.*
import org.junit.Test

class PdfCropTest {
    @Test fun blankAndDarkAreNotCropped() {
        assertEquals(CropRect.FULL,AutoPdfCrop.detect(IntArray(10000) {0xFFFFFFFF.toInt()},100,100))
        assertEquals(CropRect.FULL,AutoPdfCrop.detect(IntArray(10000) {0xFF111111.toInt()},100,100))
    }
    @Test fun footnotePageNumberAndSideNoteAreRetained() {
        val p=IntArray(120*160) {0xFFFFFFFF.toInt()}
        for(y in 35..100)for(x in 30..80)p[y*120+x]=0xFF111111.toInt()
        for(y in 142..145)for(x in 55..59)p[y*120+x]=0xFF000000.toInt()
        for(y in 82..88)for(x in 100..102)p[y*120+x]=0xFF000000.toInt()
        val crop=AutoPdfCrop.detect(p,120,160)
        assertTrue(crop.left<30/120f);assertTrue(crop.right>103/120f);assertTrue(crop.bottom>146/160f)
        assertTrue(crop.top>0)
    }
    @Test fun originalCoordinatesRoundTripForCropZoomAndPan() {
        val t=PdfCoordinateTransform(CropRect(.1f,.2f,.9f,.85f),612f,792f,1.4f,17f,2.2f,-114f,73f)
        for(x in listOf(0f,.1f,.33f,1f))for(y in listOf(0f,.2f,.77f,1f)) {
            val view=t.toView(x,y);val original=t.toPage(view.first,view.second)
            assertEquals(x,original.first,.000001f);assertEquals(y,original.second,.000001f)
        }
    }
    @Test fun pageThenParityThenAllThenAutomatic() {
        val a=CropRect(.1f,.1f,.9f,.9f);val b=CropRect(.2f,.2f,.8f,.8f);val c=CropRect(.3f,.3f,.7f,.7f)
        val rules=PdfCropConfig(true,true,a,b,null,mapOf(0 to c))
        assertEquals(c,rules.resolve(0));assertEquals(a,rules.resolve(1));assertEquals(b,rules.resolve(2))
        assertEquals(b,rules.reset(0,CropScope.PAGE).resolve(0))
        assertEquals(CropRect.FULL,rules.copy(enabled=false).resolve(0))
        assertEquals(c,PdfCropConfig(true,true).resolve(0,c))
    }
    @Test fun invalidAndCollapsedCropsAreRejected() {assertThrows(IllegalArgumentException::class.java) {CropRect(.9f,0f,.5f,1f)};assertThrows(IllegalArgumentException::class.java) {CropRect(Float.NaN)}}
}
