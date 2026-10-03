package io.readx.app

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.readx.app.data.LibraryDatabase
import io.readx.app.data.LibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RepositoryInstrumentedTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var file: File
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<ReadXApplication>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        repository = LibraryRepository(context, db)
        file = File.createTempFile("readx-test-", ".txt", context.cacheDir).apply { writeText("第一章 测试\n离线阅读正文\n第二章 搜索\n中文检索测试 " + System.nanoTime()) }
    }
    @After fun cleanup() = runBlocking {
        repository.books.first().forEach { repository.delete(it) }
        file.delete(); db.close()
    }
    @Test fun importDeduplicatesAndPersistsPosition() = runBlocking {
        val first = repository.import(Uri.fromFile(file))
        val again = repository.import(Uri.fromFile(file))
        assertEquals(first.id, again.id)
        assertEquals(1, repository.books.first().size)
        assertEquals(2, repository.dao.chapters(first.id).size)
        repository.dao.savePosition(first.id, 1, .42f, 1234L)
        assertEquals(1, repository.dao.book(first.id)!!.chapterIndex)
        assertEquals(.42f, repository.dao.book(first.id)!!.scrollFraction)
    }
    @Test fun editAndDeleteNeverModifyOriginal() = runBlocking {
        val original = file.readText()
        val book = repository.import(Uri.fromFile(file))
        repository.dao.edit(book.id, "修改的书名", "作者", "小说,喜欢")
        assertEquals("修改的书名", repository.dao.book(book.id)!!.title)
        repository.delete(book)
        assertNull(repository.dao.book(book.id))
        assertTrue(repository.dao.chapters(book.id).isEmpty())
        assertFalse(repository.directory(book.id).exists())
        assertEquals(original, file.readText())
    }
    @Test fun longChapterMetadataAndSearchChunksStayWithinCursorWindow() = runBlocking {
        file.writeText("第一章 长篇\n" + "中文正文".repeat(250000) + "末尾检索标记")
        val book = repository.import(Uri.fromFile(file))
        val chapters = repository.dao.chapters(book.id)
        assertEquals(1, chapters.size)
        assertEquals("", chapters.first().text)
        val tail = repository.dao.textChunk(book.id, 0, 999950, 200000).orEmpty()
        assertTrue(tail.contains("末尾检索标记"))
    }
    @Test fun corruptImportLeavesNoLibraryEntry() = runBlocking {
        file.writeText("   \n")
        try { repository.import(Uri.fromFile(file)); fail("Should reject blank file") } catch (_: IllegalArgumentException) { }
        assertTrue(repository.books.first().isEmpty())
    }
}
