package io.readx.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.readx.app.ReadXApplication
import io.readx.app.data.Book
import io.readx.app.data.Chapter
import io.readx.app.data.Bookmark
import io.readx.app.data.Annotation
import io.readx.app.data.AnnotationIdentity
import io.readx.app.data.MarkColor
import io.readx.app.reader.TextAnchor
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ReaderLocation(val chapter: Int, val fraction: Float, val anchor: TextAnchor? = null)

data class ReaderSession(val book: Book, val chapters: List<Chapter>, val chapter: Int = book.chapterIndex, val fraction: Float = book.scrollFraction, val target: String? = null, val find: String? = null, val occurrence: Int = 0, val navigationId: Long = 0, val anchor: TextAnchor? = null, val returnStack: List<ReaderLocation> = emptyList(), val requestedPage: Int? = null,val flashAnchor:Boolean=false)
data class SearchHit(val book: Book, val chapter: Chapter, val snippet: String, val query: String, val occurrence: Int)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val readx = app as ReadXApplication
    val conversions = readx.conversions
    val conversionTasks = conversions.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val models = readx.ocrModels.models
    val bundledModelState = readx.ocrModels.bundledState
    private val _conversionId = MutableStateFlow<String?>(null)
    val conversionId = _conversionId.asStateFlow()
    private val _modelBusy = MutableStateFlow(false)
    val modelBusy = _modelBusy.asStateFlow()
    val repository = (app as ReadXApplication).repository
    val books = repository.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val annotations = repository.dao.observeAnnotations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bookmarks = repository.dao.observeBookmarks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val fontStore=io.readx.app.reader.LocalFontStore(app)
    private val _fonts=MutableStateFlow<List<io.readx.app.reader.LocalFont>>(emptyList())
    val fonts=_fonts.asStateFlow()
    init {viewModelScope.launch {_fonts.value=fontStore.list();val stored=ReaderPreferences(app);if(!io.readx.app.reader.LocalFontStore.prepare(app,stored.settings.value.fontId))stored.update(stored.settings.value.copy(fontId=null))}}
    fun importFont(uri:Uri)=viewModelScope.launch {
        try {val font=fontStore.import(uri);_fonts.value=fontStore.list();preferences.update(settings.value.copy(fontId=font.id));notify("已导入并启用本地字体")}
        catch(e:CancellationException) {throw e} catch(e:Exception) {notify(e.message ?: "字体导入失败")}
    }
    fun deleteFont(font:io.readx.app.reader.LocalFont)=viewModelScope.launch {
        try {
            fontStore.delete(font.id)
            _fonts.value=fontStore.list()
            if(settings.value.fontId==font.id) {
                preferences.update(settings.value.copy(fontId=null))
            }
            notify("已删除字体「${font.name}」")
        } catch(e:CancellationException) {throw e} catch(e:Exception) {notify(e.message ?: "字体删除失败")}
    }
    fun changeCover(book:Book,uri:Uri)=viewModelScope.launch {try {repository.changeCover(book.id,uri);notify("已更换封面（不修改原书）")}catch(e:CancellationException) {throw e}catch(e:Exception) {notify(e.message ?: "封面更新失败")}}
    fun exportAnnotations(book:Book,uri:Uri,markdown:Boolean)=viewModelScope.launch {try {repository.exportAnnotations(book.id,uri,markdown);notify("批注已导出")}catch(e:CancellationException) {throw e}catch(e:Exception) {notify(e.message ?: "批注导出失败")}}
    fun copyAnnotations(book:Book)=viewModelScope.launch {try {
        val value=repository.annotationText(book.id,true)
        (getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("ReadX 批注",value));notify("已复制整书批注")
    }catch(e:CancellationException) {throw e}catch(e:Exception) {notify(e.message ?: "复制失败")}}
    val downloads=androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(io.readx.app.conversion.OcrDownloadPolicy.NAME).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    fun onlineModels(enabled:Boolean) {
        preferences.update(settings.value.copy(onlineModels=enabled))
        if(!enabled)io.readx.app.conversion.OcrDownloadPolicy.cancel(getApplication())
    }
    fun downloadModels() {try {io.readx.app.conversion.OcrDownloadPolicy.enqueue(getApplication(),settings.value.ocrLanguages)}catch(e:Exception) {notify(e.message ?: "无法下载模型")}}
    val preferences = ReaderPreferences(app)
    private var latestFraction = 0f
    private var latestAnchor: TextAnchor? = null
    private var navigationSerial = 0L
    private fun nextNavigationId() = ++navigationSerial
    val settings = preferences.settings
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _reader = MutableStateFlow<ReaderSession?>(null)
    val reader = _reader.asStateFlow()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val readerAnnotations = _reader.map {it?.book?.id}.distinctUntilChanged().flatMapLatest {id->if(id==null) flowOf(emptyList()) else repository.dao.annotationsForBook(id)}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    fun textAnchor(anchor: TextAnchor?) { if(anchor!=null) latestAnchor=anchor }
    fun switchTextEngine(engine: String, anchor: TextAnchor?) = viewModelScope.launch {
        val session=_reader.value ?: return@launch
        if(session.book.format!="TXT" || engine !in listOf("NATIVE","WEBVIEW")) return@launch
        repository.dao.saveTextEngine(session.book.id,engine)
        latestAnchor=anchor
        _reader.value=session.copy(book=session.book.copy(textEngine=engine),anchor=anchor,fraction=latestFraction,navigationId=nextNavigationId(),requestedPage=null,flashAnchor=false)
    }
    private val _hits = MutableStateFlow<List<SearchHit>>(emptyList())
    val hits = _hits.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching = _searching.asStateFlow()
    private var searchJob: Job? = null
    private var openJob: Job? = null
    private var positionJob: Job? = null

    fun addBookmark() = viewModelScope.launch {
        val s = _reader.value ?: return@launch
        val mark = Bookmark(UUID.randomUUID().toString(), s.book.id, s.chapter, latestFraction, s.chapters[s.chapter].title,textAnchor=latestAnchor?.json()?.toString())
        repository.dao.insertPositionBookmark(mark)
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
                latestAnchor=bookmark.textAnchor?.let(TextAnchor::parse)
                _reader.value = ReaderSession(book, chapters, bookmark.chapter.coerceIn(chapters.indices), bookmark.fraction, navigationId = nextNavigationId(),anchor=bookmark.textAnchor?.let(TextAnchor::parse))
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
    private val _licenses=MutableStateFlow<String?>(null)
    val licenses=_licenses.asStateFlow()
    fun closeLicenses() {_licenses.value=null}
    fun showLicenses()=viewModelScope.launch {
        _licenses.value=withContext(Dispatchers.IO) {
            val assets = getApplication<Application>().assets
            assets.list("licenses").orEmpty().filter { it.endsWith(".txt") }.sorted().joinToString("\n\n") {name->
                "${name.removeSuffix(".txt")}\n" + assets.open("licenses/$name").reader().use {it.readText()}
            }
        }
    }
    fun convertPdf(book: Book) = viewModelScope.launch {
        try { readx.ocrModels.ensureBundledModels(); _conversionId.value = conversions.start(book.id,readx.ocrModels.snapshot(settings.value.ocrLanguages)) }
        catch(e: CancellationException) {throw e} catch(_: Exception) {notify("无法开始转换，请使用原版阅读")}
    }
    fun showConversion(id: String) { if(id.matches(Regex("[a-f0-9]{64}"))) _conversionId.value=id }
    fun dismissConversion() {_conversionId.value=null}
    fun cancelConversion(id: String) = viewModelScope.launch { conversions.cancel(id) }
    fun resumeConversion(id: String) = viewModelScope.launch {
        try {_conversionId.value=conversions.resume(id)} catch(e: CancellationException) {throw e} catch(_: Exception) {notify("无法继续：请检查原 PDF 和模型")}
    }
    fun retryBundledModels() = viewModelScope.launch {
        _modelBusy.value = true
        try { readx.ocrModels.ensureBundledModels() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { notify("内置模型部署失败，可重试或手动导入") }
        finally { _modelBusy.value = false }
    }
    fun importModels(uris: List<Uri>) = viewModelScope.launch {
        _modelBusy.value=true
        try {
            withContext(Dispatchers.IO) {uris.forEach {readx.ocrModels.import(it)}}
            notify("OCR 模型已导入")
        } catch(e: CancellationException) {throw e} catch(e: Exception) {notify(e.message ?: "模型导入失败")}
        finally {_modelBusy.value=false}
    }
    fun exportConverted(bookId: String, uri: Uri) = viewModelScope.launch {
        try {conversions.export(bookId,uri);notify("EPUB 已导出")}
        catch(e: CancellationException) {throw e} catch(_: Exception) {notify("导出失败，请重新选择保存位置")}
    }
    fun open(book: Book) {
        io.readx.app.reader.ReaderPerformance.begin()
        openJob?.cancel()
        openJob = viewModelScope.launch {
            try {
                val latest = repository.dao.book(book.id) ?: return@launch
                io.readx.app.reader.LocalFontStore.prepare(getApplication(),settings.value.fontId)
                val chapters = repository.dao.chapters(book.id)
                io.readx.app.reader.ReaderPerformance.mark("chapter_prepared")
                if (chapters.isEmpty()) return@launch
                latestFraction = latest.scrollFraction
                latestAnchor=latest.readingAnchor?.let(TextAnchor::parse)
                _reader.value = ReaderSession(latest, chapters, latest.chapterIndex.coerceIn(chapters.indices), navigationId = nextNavigationId(),anchor=latestAnchor)
                repository.dao.savePosition(book.id, _reader.value!!.chapter, latest.scrollFraction, System.currentTimeMillis())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notify(e.message ?: "打开失败") }
        }
    }
    fun chapter(index: Int, target: String? = null, fraction: Float = 0f, requestedPage: Int? = null) {
        val session = _reader.value ?: return
        if (index !in session.chapters.indices) return
        latestAnchor=null
        _reader.value = session.copy(chapter = index, fraction = fraction, target = target, find = null, anchor = null, navigationId = nextNavigationId(),requestedPage=requestedPage,flashAnchor=false)
        savePosition(session.book.id, index, fraction)
    }
    fun followLink(index: Int, target: String?, sourceFraction: Float, sourceAnchor: TextAnchor? = null) {
        val session = _reader.value ?: return
        if (index !in session.chapters.indices) return
        latestAnchor=null
        val origin = ReaderLocation(session.chapter, sourceFraction.coerceIn(0f, 1f), sourceAnchor)
        _reader.value = session.copy(chapter = index, fraction = 0f, target = target, find = null, anchor = null,
            navigationId = nextNavigationId(), requestedPage=null, returnStack = (session.returnStack + origin).takeLast(32))
        savePosition(session.book.id, index, 0f)
    }
    fun returnFromLink() {
        val session = _reader.value ?: return
        val origin = session.returnStack.lastOrNull() ?: return
        latestAnchor=origin.anchor
        _reader.value = session.copy(chapter = origin.chapter, fraction = origin.fraction, target = null, find = null, anchor = origin.anchor,
            navigationId = nextNavigationId(), requestedPage=null, returnStack = session.returnStack.dropLast(1))
        savePosition(session.book.id, origin.chapter, origin.fraction)
    }
    fun addTextAnnotation(kind: String, anchor: TextAnchor, note: String, fraction: Float, color: String = settings.value.annotationColor) = viewModelScope.launch {
        val s = _reader.value ?: return@launch
        if (kind !in listOf("HIGHLIGHT", "UNDERLINE", "NOTE")) return@launch
        repository.dao.upsertAnnotation(Annotation(UUID.randomUUID().toString(), s.book.id, kind, s.chapter,
            fraction, s.chapters[s.chapter].title, anchor.quote, note.take(16000), anchor.json().put("href",s.chapters[s.chapter].href).toString(),color=MarkColor.normalize(color)))
        notify("已保存批注")
    }
    fun removeTextMarks(anchor: TextAnchor, ids: List<String>, kind: String? = null) = viewModelScope.launch {
        val session=_reader.value ?: return@launch
        val rows=repository.dao.annotations(session.book.id)
        val selected=rows.filter {it.chapter==session.chapter && it.id in ids && (kind==null || it.kind==kind)}
        repository.dao.cancelMarkers(session.book.id,selected.map {it.id})
        notify("已取消标记")
    }
    fun recolorAnnotation(annotation: Annotation,color: String)=viewModelScope.launch {
        repository.dao.replaceAnnotation(annotation.copy(color=MarkColor.normalize(color),updatedAt=System.currentTimeMillis()))
    }
    fun deleteAnnotation(annotation: Annotation) = viewModelScope.launch {
        if (annotation.kind=="BOOKMARK") repository.dao.removePositionBookmark(annotation.id)
        else repository.dao.deleteAnnotation(annotation.id)
    }
    fun updateAnnotation(annotation: Annotation, note: String) = viewModelScope.launch {
        repository.dao.updateAnnotationNote(annotation.id, note.take(16000))
    }
    fun openAnnotation(annotation: Annotation) {
        openJob?.cancel()
        openJob = viewModelScope.launch {
            val book = repository.dao.book(annotation.bookId) ?: return@launch
            val chapters = repository.dao.chapters(book.id)
            if (chapters.isEmpty()) return@launch
            latestFraction = annotation.fraction
            _reader.value = ReaderSession(book, chapters, annotation.chapter.coerceIn(chapters.indices), annotation.fraction,
                navigationId = nextNavigationId(), anchor = TextAnchor.parse(annotation.locator),flashAnchor=true)
        }
    }
    fun isCurrentNavigation(id: String, navigationId: Long): Boolean =
        _reader.value?.let { it.book.id == id && it.navigationId == navigationId } == true

    fun position(id: String, chapter: Int, fraction: Float) {
        val current = _reader.value ?: return
        if (current.book.id != id || current.chapter != chapter) return
        latestFraction = fraction
        // Do not mutate reader state on every scroll; that would rebuild the WebView.
        positionJob?.cancel()
        positionJob = viewModelScope.launch { delay(350); repository.dao.saveTextPosition(id, chapter, fraction.coerceIn(0f, 1f), System.currentTimeMillis(),latestAnchor?.json()?.toString()) }
    }
    fun savePosition(id: String, chapter: Int, fraction: Float) {
        val current = _reader.value
        if (current != null && (current.book.id != id || current.chapter != chapter)) return
        positionJob?.cancel()
        latestFraction = fraction
        positionJob = repository.persistPosition(id, chapter, fraction,latestAnchor?.json()?.toString())
    }
    fun close() {
        openJob?.cancel()
        // Persist the last live position before removing the view. A detached WebView may clamp scrollX
        // during teardown, so its later onRelease must not overwrite this snapshot with a stale page.
        _reader.value?.let { savePosition(it.book.id, it.chapter, latestFraction) }
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
