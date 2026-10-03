package io.readx.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.readx.app.ReadXApplication
import io.readx.app.data.Book
import io.readx.app.data.Chapter
import io.readx.app.data.Bookmark
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ReaderLocation(val chapter: Int, val fraction: Float)

data class ReaderSession(val book: Book, val chapters: List<Chapter>, val chapter: Int = book.chapterIndex, val fraction: Float = book.scrollFraction, val target: String? = null, val find: String? = null, val occurrence: Int = 0, val navigationId: Long = 0, val returnStack: List<ReaderLocation> = emptyList())
data class SearchHit(val book: Book, val chapter: Chapter, val snippet: String, val query: String, val occurrence: Int)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    val repository = (app as ReadXApplication).repository
    val books = repository.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bookmarks = repository.dao.observeBookmarks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val preferences = ReaderPreferences(app)
    private var latestFraction = 0f
    private var navigationSerial = 0L
    private fun nextNavigationId() = ++navigationSerial
    val settings = preferences.settings
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _reader = MutableStateFlow<ReaderSession?>(null)
    val reader = _reader.asStateFlow()
    private val _hits = MutableStateFlow<List<SearchHit>>(emptyList())
    val hits = _hits.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching = _searching.asStateFlow()
    private var searchJob: Job? = null
    private var openJob: Job? = null
    private var positionJob: Job? = null

    fun addBookmark() = viewModelScope.launch {
        val s = _reader.value ?: return@launch
        repository.dao.insertBookmark(Bookmark(UUID.randomUUID().toString(), s.book.id, s.chapter, latestFraction, s.chapters[s.chapter].title))
        notify("已添加书签")
    }
    fun removeBookmark(bookmark: Bookmark) = viewModelScope.launch { repository.dao.deleteBookmark(bookmark.id) }
    fun openBookmark(bookmark: Bookmark) {
        openJob?.cancel()
        openJob = viewModelScope.launch {
            val book = repository.dao.book(bookmark.bookId) ?: return@launch
            val chapters = repository.dao.chapters(book.id)
            if (chapters.isNotEmpty()) {
                latestFraction = bookmark.fraction
                _reader.value = ReaderSession(book, chapters, bookmark.chapter.coerceIn(chapters.indices), bookmark.fraction, navigationId = nextNavigationId())
            }
        }
    }
    fun clearMessage() { _message.value = null }
    fun notify(text: String) { _message.value = text }
    fun import(uris: List<Uri>) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            var success = 0
            val failures = mutableListOf<String>()
            try {
                uris.forEach { uri ->
                    try { repository.import(uri); success++ }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failures += e.message ?: "导入失败" }
                }
                notify(if (failures.isEmpty()) "已导入 $success 本（相同文件会自动去重）" else "成功 $success 本；" + failures.take(3).joinToString("；"))
            } finally { _busy.value = false }
        }
    }
    fun sample() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try { open(repository.sample()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notify(e.message ?: "示例加载失败") }
            finally { _busy.value = false }
        }
    }
    fun open(book: Book) {
        openJob?.cancel()
        openJob = viewModelScope.launch {
            try {
                val latest = repository.dao.book(book.id) ?: return@launch
                val chapters = repository.dao.chapters(book.id)
                if (chapters.isEmpty()) return@launch
                latestFraction = latest.scrollFraction
                _reader.value = ReaderSession(latest, chapters, latest.chapterIndex.coerceIn(chapters.indices), navigationId = nextNavigationId())
                repository.dao.savePosition(book.id, _reader.value!!.chapter, latest.scrollFraction, System.currentTimeMillis())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notify(e.message ?: "打开失败") }
        }
    }
    fun chapter(index: Int, target: String? = null, fraction: Float = 0f) {
        val session = _reader.value ?: return
        if (index !in session.chapters.indices) return
        _reader.value = session.copy(chapter = index, fraction = fraction, target = target, find = null, navigationId = nextNavigationId())
        savePosition(session.book.id, index, fraction)
    }
    fun followLink(index: Int, target: String?, sourceFraction: Float) {
        val session = _reader.value ?: return
        if (index !in session.chapters.indices) return
        val origin = ReaderLocation(session.chapter, sourceFraction.coerceIn(0f, 1f))
        _reader.value = session.copy(chapter = index, fraction = 0f, target = target, find = null,
            navigationId = nextNavigationId(), returnStack = (session.returnStack + origin).takeLast(32))
        savePosition(session.book.id, index, 0f)
    }
    fun returnFromLink() {
        val session = _reader.value ?: return
        val origin = session.returnStack.lastOrNull() ?: return
        _reader.value = session.copy(chapter = origin.chapter, fraction = origin.fraction, target = null, find = null,
            navigationId = nextNavigationId(), returnStack = session.returnStack.dropLast(1))
        savePosition(session.book.id, origin.chapter, origin.fraction)
    }
    fun isCurrentNavigation(id: String, navigationId: Long): Boolean =
        _reader.value?.let { it.book.id == id && it.navigationId == navigationId } == true

    fun position(id: String, chapter: Int, fraction: Float) {
        val current = _reader.value ?: return
        if (current.book.id != id || current.chapter != chapter) return
        latestFraction = fraction
        // Do not mutate reader state on every scroll; that would rebuild the WebView.
        positionJob?.cancel()
        positionJob = viewModelScope.launch { delay(350); repository.dao.savePosition(id, chapter, fraction.coerceIn(0f, 1f), System.currentTimeMillis()) }
    }
    fun savePosition(id: String, chapter: Int, fraction: Float) {
        val current = _reader.value
        if (current != null && (current.book.id != id || current.chapter != chapter)) return
        positionJob?.cancel()
        latestFraction = fraction
        positionJob = repository.persistPosition(id, chapter, fraction)
    }
    fun close() {
        openJob?.cancel()
        _reader.value = null
    }
    fun edit(book: Book, title: String, author: String, tags: String) = viewModelScope.launch {
        repository.dao.edit(book.id, title.trim().ifEmpty { book.title }, author.trim(), tags.trim())
    }
    fun delete(book: Book) = viewModelScope.launch {
        try { repository.delete(book) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { notify(e.message ?: "移除失败") }
    }
    fun search(query: String, bookId: String? = null) {
        searchJob?.cancel(); _hits.value = emptyList(); _searching.value = query.isNotBlank()
        searchJob = viewModelScope.launch {
            if (query.isBlank()) { _searching.value = false; return@launch }
            try {
                delay(250)
                val term = query.trim().take(1000)
                val found = withContext(Dispatchers.IO) {
                    val results = mutableListOf<SearchHit>()
                    val candidates = books.value.filter { bookId == null || it.id == bookId }
                    for (book in candidates) {
                        for (chapter in repository.dao.chapters(book.id)) {
                            ensureActive()
                            // Fetch bounded SQLite substrings: long chapters must not overflow CursorWindow.
                            val stride = 180000
                            var offset = 0
                            var occurrence = 0
                            while (results.size < 100) {
                                ensureActive()
                                val block = repository.dao.textChunk(book.id, chapter.ordinal, offset, 200000).orEmpty()
                                if (block.isEmpty()) break
                                var from = 0
                                while (results.size < 100) {
                                    val at = block.indexOf(term, from, ignoreCase = true)
                                    if (at < 0 || block.codePointCount(0, at) >= stride) break
                                    val snippet = block.substring((at - 40).coerceAtLeast(0), (at + term.length + 75).coerceAtMost(block.length))
                                    results += SearchHit(book, chapter, snippet, term, occurrence++)
                                    from = at + term.length
                                }
                                if (block.codePointCount(0, block.length) <= stride) break
                                offset += stride
                            }
                            if (results.size >= 100) break
                        }
                        if (results.size >= 100) break
                    }
                    results
                }
                _hits.value = found
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notify(e.message ?: "搜索失败") }
            finally { if (currentCoroutineContext().isActive) _searching.value = false }
        }
    }
    fun openHit(hit: SearchHit) {
        openJob?.cancel()
        openJob = viewModelScope.launch {
            val chapters = repository.dao.chapters(hit.book.id)
            _reader.value = ReaderSession(hit.book, chapters, hit.chapter.ordinal, 0f, find = hit.query, occurrence = hit.occurrence, navigationId = nextNavigationId())
        }
    }
}
