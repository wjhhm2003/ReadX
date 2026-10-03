package io.readx.app.reader

import io.readx.app.pdf.PdfBox
import org.junit.Assert.*
import org.junit.Test

class PageIndexTest {
    @Test fun incompleteIndexNeverPretendsToHaveAWholeBookTotal() {
        val index=BookPageIndex(listOf(3,null,5));assertNull(index.total);assertNull(index.globalPage(0,1));assertNull(index.locate(4));assertEquals(2,index.measured)
    }
    @Test fun wholeBookPagesMapAcrossChapterBoundaries() {
        val index=BookPageIndex(listOf(3,1,5));assertEquals(9,index.total);assertEquals(4,index.globalPage(1,1));assertEquals(0 to 3,index.locate(3));assertEquals(1 to 1,index.locate(4));assertEquals(2 to 1,index.locate(5));assertEquals(2 to 5,index.locate(100))
    }
}
