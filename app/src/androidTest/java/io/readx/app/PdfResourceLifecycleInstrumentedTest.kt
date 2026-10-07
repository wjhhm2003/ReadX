package io.readx.app

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.readx.app.pdf.NativePdfSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfResourceLifecycleInstrumentedTest {
    @Test fun repeatedCloseIsSafeAndRenderingNeverUsesAClosedSource() = runBlocking {
        val file=fixture()
        val source=NativePdfSource(file)
        try {
            assertEquals(1,source.count)
            val page=source.render(0,384)
            try {assertTrue(page.bitmap.width>0);assertTrue(page.bitmap.height>0)} finally {page.bitmap.recycle()}
            coroutineScope {List(3) {async {source.close()}}.awaitAll()}
            source.close()
            var rejected=false
            try {source.render(0)} catch(e:IllegalStateException) {rejected=true}
            assertTrue("Closed sources must fail before calling PdfRenderer",rejected)
        } finally {source.close();file.delete()}
    }

    @Test fun replacementSourceRemainsUsableWhenTheOldLeaseCloses() = runBlocking {
        val file=fixture()
        val previous=NativePdfSource(file)
        val replacement=NativePdfSource(file)
        try {
            previous.close()
            val page=replacement.render(0,384)
            try {assertTrue(page.bitmap.width>0);assertTrue(page.bitmap.height>0)} finally {page.bitmap.recycle()}
        } finally {previous.close();replacement.close();file.delete()}
    }

    private fun fixture():File {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>()
        val file=File.createTempFile("pdf-resource-generated-",".pdf",app.cacheDir)
        val document=PdfDocument()
        try {
            val page=document.startPage(PdfDocument.PageInfo.Builder(600,850,1).create())
            page.canvas.drawText("Generated PDF resource lifecycle fixture",40f,100f,Paint().apply {textSize=20f})
            document.finishPage(page)
            file.outputStream().use {document.writeTo(it)}
        } finally {document.close()}
        return file
    }
}
