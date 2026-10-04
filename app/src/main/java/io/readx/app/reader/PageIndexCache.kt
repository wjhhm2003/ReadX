package io.readx.app.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

/** Disposable, bounded cache, separate from Room and user progress. No book text or unused offsets. */
internal class PageIndexCache(root: File) {
    private val directory = File(root, "page-indices-v2")
    companion object {
        private val lock = Mutex()
        private const val MAGIC = 0x52585032
        private const val MAX_CHAPTERS = 10_000
        private const val MAX_PAGES = 1_000_000
    }
    private fun prefix(book: String) = layoutDigest(book)
    private fun file(book: String, key: String): File {
        require(key.matches(Regex("[0-9a-f]{64}")))
        return File(directory, "${prefix(book)}-$key.bin")
    }
    suspend fun load(book: String, key: String, chapters: Int): BookPageIndex? = withContext(Dispatchers.IO) {
        lock.withLock {
            val source = file(book, key)
            if (chapters !in 1..MAX_CHAPTERS || !source.isFile || source.length() != 8L + 4L * chapters) return@withLock null
            try {
                DataInputStream(source.inputStream().buffered()).use { input ->
                    if (input.readInt() != MAGIC || input.readInt() != chapters) return@withLock null
                    val counts = List(chapters) { input.readInt() }
                    if (counts.any { it !in 0..MAX_PAGES } || counts.sumOf { it.toLong() } > Int.MAX_VALUE) return@withLock null
                    source.setLastModified(System.currentTimeMillis())
                    BookPageIndex(counts.map { it.takeIf { count -> count > 0 } })
                }
            } catch (_: IOException) { null }
        }
    }
    suspend fun save(book: String, key: String, index: BookPageIndex) = withContext(Dispatchers.IO) {
        lock.withLock {
            coroutineContext.ensureActive()
            if (index.counts.size !in 1..MAX_CHAPTERS) return@withLock // Skip oversized disposable caches, not pagination.
            directory.mkdirs()
            val destination = file(book, key)
            val temporary = File.createTempFile("index-", ".tmp", directory)
            try {
                DataOutputStream(temporary.outputStream().buffered()).use { output ->
                    output.writeInt(MAGIC); output.writeInt(index.counts.size)
                    index.counts.forEach { output.writeInt(it ?: 0) }
                }
                coroutineContext.ensureActive()
                // Temp and destination share a filesystem. Never fall back to a torn in-place write.
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                val files = directory.listFiles { item -> item.extension == "bin" }.orEmpty().sortedByDescending { it.lastModified() }
                files.filter { it.name.startsWith(prefix(book) + "-") }.drop(5).forEach { it.delete() }
                directory.listFiles { item -> item.extension == "bin" }.orEmpty().sortedByDescending { it.lastModified() }.drop(24).forEach { it.delete() }
            } finally { temporary.delete() }
        }
    }
}
