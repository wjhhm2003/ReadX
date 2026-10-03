package io.readx.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.readx.app.data.Book
import io.readx.app.data.LibraryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private class BasicPdf(file: File) {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
    private val lock = Mutex()
    val count = renderer.pageCount
    suspend fun render(index: Int, width: Int): Bitmap = withContext(Dispatchers.IO) {
        lock.withLock {
            renderer.openPage(index).use { page ->
                val ratio = page.height.toFloat() / page.width
                val targetWidth = minOf(width.coerceIn(400, 1800), (2600f / ratio).toInt()).coerceAtLeast(1)
                val targetHeight = (targetWidth * ratio).toInt().coerceIn(1, 2600)
                Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE); page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }
    suspend fun close() = withContext(Dispatchers.IO) { lock.withLock { renderer.close(); descriptor.close() } }
}

@Composable
fun FallbackPdfScreen(book: Book, repository: LibraryRepository, pageChanged: (Int, Int) -> Unit) {
    var pdf by remember { mutableStateOf<BasicPdf?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(book.chapterIndex) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    val transform = rememberTransformableState { zoom, pan, _ -> scale = (scale * zoom).coerceIn(1f, 5f); dx += pan.x; dy += pan.y }
    LaunchedEffect(book.id) {
        try { pdf = withContext(Dispatchers.IO) { BasicPdf(repository.source(book)) }; page = page.coerceIn(0, pdf!!.count - 1) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "基础模式无法打开此 PDF：" + (e.message ?: "文件损坏或需要密码") }
    }
    DisposableEffect(pdf) {
        val instance = pdf
        onDispose { if(instance != null) repository.closePdfResource { instance.close() } }
    }
    LaunchedEffect(pdf, page) {
        val instance = pdf ?: return@LaunchedEffect
        bitmap = null; scale = 1f; dx = 0f; dy = 0f
        try {
            bitmap = instance.render(page, 1400)
            pageChanged(page, instance.count)
            repository.dao.savePosition(book.id, page, 0f, System.currentTimeMillis())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "页面渲染失败" }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f).fillMaxWidth().transformable(transform), contentAlignment = Alignment.TopCenter) {
            if (error != null) Text(error!!, Modifier.padding(24.dp))
            else if (bitmap == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else Image(bitmap!!.asImageBitmap(), "PDF 第 " + (page + 1) + " 页", Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = dx; translationY = dy }, alignment = Alignment.TopCenter)
        }
        Text("基础模式：支持翻页和缩放；选字、搜索及密码文档需较新的系统。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { page-- }, enabled = pdf != null && page > 0) { Text("上一页") }
            TextButton(onClick = { scale = 1f; dx = 0f; dy = 0f }) { Text("重置缩放") }
            TextButton(onClick = { page++ }, enabled = pdf != null && page < pdf!!.count - 1) { Text("下一页") }
        }
    }
}
