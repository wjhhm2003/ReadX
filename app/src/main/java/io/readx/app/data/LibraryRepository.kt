package io.readx.app.data

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.room.withTransaction
import io.readx.app.reader.BookParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

class LibraryRepository(private val context: Context, private val db: LibraryDatabase) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dao = db.library()
    fun persistPosition(id: String, chapter: Int, fraction: Float) = applicationScope.launch {
        dao.savePosition(id, chapter, fraction.coerceIn(0f, 1f), System.currentTimeMillis())
    }
    val books = dao.observeBooks()
    private val importLock = Mutex()
    private val root = File(context.filesDir, "books").apply { mkdirs() }
    fun directory(id: String): File {
        require(UUID.fromString(id).toString() == id) { "非法书籍 ID" }
        return File(root, id)
    }
    fun source(book: Book) = File(directory(book.id), "source." + book.format.lowercase())
    fun content(id: String) = File(directory(id), "content")

    suspend fun import(uri: Uri): Book = withContext(Dispatchers.IO) {
        val name = (if (uri.scheme == "content") context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } else null) ?: uri.lastPathSegment ?: "未命名"
        context.contentResolver.openInputStream(uri)?.use { ingest(name, it) } ?: error("无法读取所选文件")
    }

    suspend fun sample(): Book = withContext(Dispatchers.IO) {
        context.assets.open("welcome.txt").use { ingest("欢迎使用 ReadX.txt", it) }
    }

    private suspend fun ingest(name: String, input: InputStream): Book = importLock.withLock {
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in setOf("epub", "txt", "pdf")) { "请选择 EPUB、TXT 或 PDF 文件" }
        val id = UUID.randomUUID().toString()
        val directory = directory(id).apply { mkdirs() }
        try {
            val file = File(directory, "source.$extension")
            val digest = MessageDigest.getInstance("SHA-256")
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var bytes = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytes += n
                    require(bytes <= 256L * 1024 * 1024) { "文件超过首版 256 MB 的导入上限" }
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                }
                require(bytes > 0) { "文件为空" }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            dao.byFingerprint(hash)?.let { existing -> directory.deleteRecursively(); return@withLock existing }
            val parsed = when (extension) {
                "txt" -> BookParser.txt(file, content(id))
                "epub" -> BookParser.epub(file, content(id))
                else -> {
                    val header = ByteArray(1024)
                    val n = file.inputStream().use { it.read(header) }
                    require(String(header, 0, n.coerceAtLeast(0), Charsets.ISO_8859_1).contains("%PDF-")) { "不是有效的 PDF 文件" }
                    null
                }
            }
            val book = Book(id, hash, parsed?.title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                parsed?.author.orEmpty(), extension.uppercase(), name, coverPath = parsed?.cover, totalUnits = parsed?.chapters?.size ?: 0)
            coroutineContext.ensureActive()
            db.withTransaction {
                dao.insertBook(book)
                dao.insertChapters(parsed?.chapters?.mapIndexed { index, chapter ->
                    Chapter(id, index, chapter.title, chapter.href, chapter.text)
                }.orEmpty())
            }
            book
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    suspend fun cover(book: Book): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val coverFile = if (book.format == "PDF") {
                val thumbnail = File(directory(book.id), "thumbnail.jpg")
                if (!thumbnail.exists()) {
                    ParcelFileDescriptor.open(source(book), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        PdfRenderer(fd).use { pdf ->
                            if (book.totalUnits != pdf.pageCount) dao.saveTotalUnits(book.id, pdf.pageCount)
                            pdf.openPage(0).use { page ->
                                val scale = minOf(320f / page.width, 480f / page.height)
                                val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val temp = File(directory(book.id), "thumbnail.tmp")
                                    temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                                    if (!temp.renameTo(thumbnail)) temp.delete()
                                } finally { bitmap.recycle() }
                            }
                        }
                    }
                }
                thumbnail
            } else book.coverPath?.let { BookParser.safeFile(content(book.id), it) }
            coverFile?.takeIf { it.isFile }?.let { file ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 960) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    suspend fun delete(book: Book) = withContext(Dispatchers.IO) {
        importLock.withLock { dao.delete(book.id); directory(book.id).deleteRecursively() }
    }
}
