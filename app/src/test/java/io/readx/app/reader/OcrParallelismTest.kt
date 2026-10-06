package io.readx.app.reader

import io.readx.app.conversion.OcrParallelism
import org.junit.Assert.*
import org.junit.Test

class OcrParallelismTest {
    private val gib=1024L*1024*1024
    @Test fun usesAllAvailableCoresWhenMemoryAllows() {
        assertEquals(8,OcrParallelism.workers(8,1024,4*gib,false,100))
        assertEquals(8,OcrParallelism.workers(8,256,4*gib,false,100))
        assertEquals(4,OcrParallelism.workers(4,512,2*gib,false,100))
    }
    @Test fun lowMemoryAndSmallDevicesStayBounded() {
        assertEquals(1,OcrParallelism.workers(8,512,4*gib,true,100))
        assertEquals(1,OcrParallelism.workers(8,64,4*gib,false,100))
        assertEquals(2,OcrParallelism.workers(8,256,640L*1024*1024,false,100))
    }
    @Test fun noMoreWorkersThanPagesOrUsableCores() {
        assertEquals(2,OcrParallelism.workers(8,1024,4*gib,false,2))
        assertEquals(1,OcrParallelism.workers(0,0,0,false,0))
    }
}
