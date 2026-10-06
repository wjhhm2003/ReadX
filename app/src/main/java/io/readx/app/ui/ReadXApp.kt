@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package io.readx.app.ui

import android.content.Intent
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import io.readx.app.data.Book
import io.readx.app.data.Bookmark
import io.readx.app.data.Annotation
import io.readx.app.reader.TextAnchor
import io.readx.app.reader.BookPageIndex
import io.readx.app.reader.BookPageCounter
import io.readx.app.data.LibraryRepository
import io.readx.app.data.progress
import io.readx.app.pdf.PdfActivity
import io.readx.app.reader.WebReader
import io.readx.app.reader.ReaderController

@Composable
fun ReadXApp(vm: LibraryViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val session by vm.reader.collectAsStateWithLifecycle()
    val annotations=if(session==null) {val values by vm.annotations.collectAsStateWithLifecycle();values} else emptyList()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var readerChrome by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchBook by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.import(it) }
    fun open(book: Book) {
        if (book.format == "PDF" && settings.pdfToEpubEnabled) vm.convertPdf(book) else if (book.format == "PDF") context.startActivity(Intent(context, PdfActivity::class.java).putExtra("bookId", book.id)) else vm.open(book)
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    ReadXTheme(settings,reading=session!=null) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= ReadXDesign.railBreakpoint
            // The compact navigation bar owns the bottom system inset; it must paint through the handle area.
            // Library consumes IME once. The background reader excludes transient IME insets so
            // precise-page and note dialogs cannot repaginate the text underneath them.
            val contentInsets = if (session == null && !wide) WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                .union(WindowInsets.ime.only(WindowInsetsSides.Bottom)) else if(session!=null) WindowInsets.systemBars.union(WindowInsets.displayCutout) else WindowInsets.safeDrawing
            val snackbarClearance = if (session != null) 112.dp else if (wide) 16.dp else 80.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Scaffold(contentWindowInsets = contentInsets, snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = snackbarClearance)) }) { outer ->
                Box(Modifier.fillMaxSize().padding(outer).consumeWindowInsets(outer)) {
                    if (session == null) {
                        LibraryScreen(
                            books = books, annotations = annotations, repository = vm.repository, busy = busy, selectedTab = selectedTab,
                            import = { importer.launch(arrayOf("application/epub+zip", "text/plain", "application/pdf", "application/octet-stream")) },
                            sample = vm::sample, open = ::open,
                            search = { searchBook = null; searchOpen = true },
                            settings = { showSettings = true },
                            selectTab = { tab -> selectedTab = tab },
                            edit = vm::edit, delete = vm::delete, openAnnotation = { annotation ->
                                val book = books.firstOrNull { it.id == annotation.bookId }
                                if (book?.format == "PDF") {
                                    context.startActivity(Intent(context, PdfActivity::class.java).putExtra("bookId", book.id).putExtra("page", annotation.chapter).putExtra("annotationId", annotation.id))
                                } else {
                                    scope.launch {
                                        val loaded = book ?: vm.repository.dao.book(annotation.bookId)
                                        if (loaded?.format == "PDF") {
                                            context.startActivity(Intent(context, PdfActivity::class.java).putExtra("bookId", loaded.id).putExtra("page", annotation.chapter).putExtra("annotationId", annotation.id))
                                        } else {
                                            vm.openAnnotation(annotation)
                                        }
                                    }
                                }
                            }, removeAnnotation = vm::deleteAnnotation, editAnnotation = vm::updateAnnotation,
                            readerSettings = settings, updateSettings = vm.preferences::update, conversionSettings = { PdfConversionSettings(vm) }, vm=vm,
                        )
                    } else ImmersiveReaderScreen(session!!, settings, vm, { showSettings = true }, { searchBook = session!!.book.id; searchOpen = true }, { readerChrome = it })
                }
            }
            val navigationColor = if (session != null) {
                if (readerChrome) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background
            } else if (wide) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer
            SystemNavigationProtection(navigationColor, Modifier.align(Alignment.BottomCenter))
        }
        PdfConversionDialog(vm)
        if (showSettings) SettingsSheet(settings, vm.preferences::update, vm.preferences::reset, {LocalFontOptions(vm)}) { showSettings = false }
        if (searchOpen) SearchDialog(vm, searchBook) { searchOpen = false; vm.search("") }
    }
}

@Composable
private fun LibraryScreen(
    books: List<Book>, annotations: List<Annotation>, repository: LibraryRepository, busy: Boolean, selectedTab: Int, import: () -> Unit, sample: () -> Unit,
    open: (Book) -> Unit, search: () -> Unit, settings: () -> Unit, selectTab: (Int) -> Unit,
    edit: (Book, String, String, String) -> Any, delete: (Book) -> Any,
    openAnnotation: (Annotation) -> Unit, removeAnnotation: (Annotation) -> Any, editAnnotation: (Annotation,String) -> Any,
    readerSettings: ReaderSettings, updateSettings: (ReaderSettings) -> Unit, vm:LibraryViewModel, conversionSettings: @Composable () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("全部") }
    var annotationFilter by rememberSaveable { mutableStateOf("全部") }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Book?>(null) }
    var removing by remember { mutableStateOf<Book?>(null) }
    var sizes by remember {mutableStateOf(emptyMap<String,Long>())}
    LaunchedEffect(books.map {it.id}) {sizes=repository.fileSizes(books)}
    var recentExpanded by rememberSaveable {mutableStateOf(true)}
    var sortMenu by remember {mutableStateOf(false)}
    val sort=ShelfSort.entries.firstOrNull {it.name==readerSettings.shelfSort} ?: ShelfSort.RECENT
    val filtered = sortShelf(books,sort,sizes).filter { (filter == "全部" || it.format == filter) && (query.isBlank() || (it.title + it.author + it.tags).contains(query, true)) }
    val filteredAnnotations = remember(annotations, annotationFilter) {
        when (annotationFilter) {
            "高亮" -> annotations.filter { it.kind == "HIGHLIGHT" }
            "下划线" -> annotations.filter { it.kind == "UNDERLINE" }
            "笔记" -> annotations.filter { it.kind == "NOTE" }
            "书签" -> annotations.filter { it.kind == "BOOKMARK" }
            else -> annotations
        }
    }
    val recent = books.filter { it.lastReadAt > 0 }.sortedByDescending { it.lastReadAt }
    val showHome = selectedTab == 0
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= ReadXDesign.railBreakpoint
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                NavigationRail(modifier = Modifier.fillMaxHeight().testTag("library-rail"),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    header = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.padding(vertical = 16.dp), tint = MaterialTheme.colorScheme.primary) }) {
                    Spacer(Modifier.height(24.dp))
                    LibraryDestinations.forEachIndexed { tab, destination ->
                        NavigationRailItem(selected = selectedTab == tab, onClick = { selectTab(tab) },
                            icon = { Icon(destination.first, null) }, label = { Text(destination.second) })
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f), contentWindowInsets = WindowInsets(0),
                containerColor = MaterialTheme.colorScheme.surface,
                topBar = {
                    TopAppBar(windowInsets = WindowInsets(0),
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                        title = { Text("ReadX", style = MaterialTheme.typography.headlineLarge) }, actions = {
                            IconButton(onClick = search) { Icon(Icons.Rounded.Search, "全文搜索") }
                            FilledTonalIconButton(onClick = import, enabled = !busy) {
                                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Rounded.Add, "导入书籍")
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "书架操作") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("导入书籍") }, leadingIcon = { Icon(Icons.Rounded.Add, null) }, enabled = !busy, onClick = { menu = false; import() })
                                    DropdownMenuItem(text = { Text("导入示例") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null) }, enabled = !busy, onClick = { menu = false; sample() })
                                }
                            }
                        })
                },
                bottomBar = {
                    if (!wide) NavigationBar(windowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                        modifier = Modifier.testTag("library-navigation"),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                        LibraryDestinations.forEachIndexed { tab, destination ->
                            NavigationBarItem(selected = selectedTab == tab, onClick = { selectTab(tab) },
                                icon = { Icon(destination.first, null) }, label = { Text(destination.second) })
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(Modifier.widthIn(max = ReadXDesign.contentWidth).fillMaxHeight().fillMaxWidth().testTag("library-list"),
                        contentPadding = PaddingValues(start = ReadXDesign.gutter, end = ReadXDesign.gutter, top = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(ReadXDesign.gap)) {
                        if (selectedTab == 1) {
                            item {
                                SectionHeading(
                                    "批注",
                                    if (annotationFilter == "全部") "${annotations.size} 条" else "${filteredAnnotations.size} / ${annotations.size} 条"
                                )
                            }
                            if (annotations.isNotEmpty()) {
                                item {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("全部", "高亮", "下划线", "笔记", "书签").forEach { label ->
                                            FilterChip(
                                                selected = annotationFilter == label,
                                                onClick = { annotationFilter = label },
                                                label = { Text(label) },
                                                leadingIcon = if (annotationFilter == label) {
                                                    { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }
                                                } else null
                                            )
                                        }
                                    }
                                }
                            }
                            if (filteredAnnotations.isEmpty()) {
                                if (annotations.isEmpty()) {
                                    item { EmptySection("暂无批注", "阅读时长按选中文字，或添加位置书签。", Icons.Rounded.EditNote) }
                                } else {
                                    item {
                                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                            EmptySection("没有匹配的${annotationFilter}批注", "试试切换为全部或其他类型。", Icons.Rounded.Search)
                                            Spacer(Modifier.height(8.dp))
                                            TextButton(onClick = { annotationFilter = "全部" }) { Text("显示全部批注") }
                                        }
                                    }
                                }
                            } else {
                                filteredAnnotations.groupBy { it.bookId }.forEach { (bookId, marks) ->
                                    val book = books.firstOrNull { it.id == bookId }
                                    item(key = "annotation-book-$bookId") {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("${book?.title ?: "书籍"} · ${marks.size} 条", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                            if (book != null) AnnotationExportActions(book, vm)
                                        }
                                    }
                                    items(marks, key = { it.id }) { mark ->
                                        AnnotationCard(mark, { openAnnotation(mark) }, { removeAnnotation(mark) }, { note -> editAnnotation(mark, note) })
                                    }
                                }
                            }
                        } else if (selectedTab == 2) {
                            item { SectionHeading("设置") }
                            item {
                                Card(onClick = settings, shape = MaterialTheme.shapes.large,
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                    ListItem(headlineContent = { Text("阅读设置", style = MaterialTheme.typography.titleMedium) },
                                        supportingContent = { Text("分页与滚动、主题、字号与行距") },
                                        leadingContent = { Icon(Icons.Rounded.Tune, null) },
                                        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent,
                                            headlineColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            supportingColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            leadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            trailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer))
                                }
                            }
                            item { SettingsGroup { AppThemeOptions(readerSettings, updateSettings) } }
                            item { SettingsGroup { conversionSettings() } }
                            item { Text("ReadX · 本地阅读\nEPUB / TXT / PDF", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
                        } else {
                            if (showHome && recent.isNotEmpty()) {
                                item(key="recent-read-header") {Surface(color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()) {Column {
                                    Row(verticalAlignment=Alignment.CenterVertically) {Text("最近在读 · ${recent.size} 本",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);IconButton(onClick={recentExpanded=!recentExpanded}) {Icon(if(recentExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,"折叠或展开最近在读")}}
                                    if(recentExpanded) LazyRow(horizontalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=12.dp)) {
                                        items(recent.take(8),key={it.id}) {book->ContinueCard(book,repository,{open(book)},{editing=book},{removing=book})}
                                    }
                                }}}
                            }
                            item { SectionHeading(if (showHome) "书库" else "全部书籍", "${books.size} 本") }
                            if (books.isEmpty()) {
                                item { EmptySection("暂无书籍", "导入 EPUB、TXT 或 PDF，开始阅读。", Icons.AutoMirrored.Rounded.MenuBook,
                                    if (busy) "导入中…" else "导入书籍", import, !busy) }
                            } else {
                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("书名、作者或标签") }, singleLine = true,
                                            leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = MaterialTheme.shapes.large)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf("全部", "EPUB", "TXT", "PDF").forEach { value ->
                                                FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value) },
                                                    leadingIcon = if (filter == value) { { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) } } else null)
                                            }
                                        }
                                    }
                                }
                                // Home never inherits a hidden search/filter from the library destination.
                                val visible = filtered
                                if (visible.isEmpty()) item {
                                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                        EmptySection("没有匹配的书籍", "试试其他关键词或格式。", Icons.Rounded.Search)
                                        if (query.isNotBlank() || filter != "全部") {
                                            Spacer(Modifier.height(8.dp))
                                            TextButton(onClick = { query = ""; filter = "全部" }) { Text("清除搜索与格式筛选") }
                                        }
                                    }
                                }
                                item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                    TextButton(onClick={updateSettings(readerSettings.copy(shelfGrid=!readerSettings.shelfGrid))}) {Icon(if(readerSettings.shelfGrid)Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,null);Text(if(readerSettings.shelfGrid) "列表" else "网格")}
                                    Spacer(Modifier.weight(1f))
                                    Box {TextButton(onClick={sortMenu=true}) {Text("排序：${sort.label}")};DropdownMenu(sortMenu,{sortMenu=false}) {ShelfSort.entries.forEach {value->DropdownMenuItem(text={Text(value.label)},onClick={sortMenu=false;updateSettings(readerSettings.copy(shelfSort=value.name))})}}}
                                }}
                                if(readerSettings.shelfGrid) {
                                    val columns=if(wide) 3 else 2
                                    items(visible.chunked(columns),key={row->"grid-${row.first().id}"}) {row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                        row.forEach {book->Box(Modifier.weight(1f)) {GridBookCard(book,repository,{open(book)},{editing=book},{removing=book})}}
                                        repeat(columns-row.size) {Spacer(Modifier.weight(1f))}
                                    }}
                                } else items(visible, key = { it.id }) { book -> BookCard(book, repository, { open(book) }, { editing = book }, { removing = book }) }
                                item { Text("${visible.size} 本书", Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { book ->
        MetadataDialog(book, { title, author, tags, newCover ->
            edit(book, title, author, tags)
            if (newCover != null) vm.changeCover(book, newCover)
            editing = null
        }) { editing = null }
    }
    removing?.let { book -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除这本书？") }, text = { Text("将删除「" + book.title + "」在应用内的副本和阅读进度，不会删除原文件。") },
        confirmButton = { TextButton(onClick = { delete(book); removing = null }) { Text("移除") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
}

private val LibraryDestinations = listOf(
    Icons.Rounded.CollectionsBookmark to "书库",
    Icons.Rounded.EditNote to "批注", Icons.Rounded.Settings to "设置",
)

@Composable
private fun EmptySection(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    actionLabel: String? = null, action: () -> Unit = {}, enabled: Boolean = true) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(icon, null, Modifier.padding(20.dp).size(32.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (actionLabel != null) Button(onClick = action, enabled = enabled) { Text(actionLabel) }
        }
    }
}

@Composable
private fun BookMenu(edit: () -> Unit, remove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, "书籍操作") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("编辑信息与标签") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; edit() })
            DropdownMenuItem(text = { Text("移除书籍") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { menu = false; remove() })
        }
    }
}

private fun Book.progressLabel(): String = progress()?.let {
    if (format == "PDF") "$it%" else "约 $it%"
} ?: "第 ${chapterIndex + 1} 页"

@Composable
private fun ContinueCard(book: Book, repository: LibraryRepository, open: () -> Unit, edit: () -> Unit, remove: () -> Unit) {
    Card(modifier = Modifier.width(304.dp).combinedClickable(onClick=open,onLongClick=edit), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(book, repository, Modifier.width(48.dp).height(72.dp))
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(book.author.ifBlank { "未知作者" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BookMenu(edit, remove)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${book.format} · ${book.progressLabel()}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("继续阅读", style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp))
            }
            book.progress()?.let { progress ->
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f))
            }
        }
    }
}

@Composable
private fun GridBookCard(book:Book,repository:LibraryRepository,open:()->Unit,edit:()->Unit,remove:()->Unit) {
    Card(Modifier.fillMaxWidth().combinedClickable(onClick=open,onLongClick=edit),shape=MaterialTheme.shapes.large,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp)) {
            Cover(book,repository,Modifier.fillMaxWidth().height(160.dp))
            Text(book.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall,modifier=Modifier.heightIn(min=40.dp).padding(top=8.dp))
            Text(book.author.ifBlank {"未知作者"},maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
            Row(verticalAlignment=Alignment.CenterVertically) {Text(book.progressLabel(),Modifier.weight(1f),style=MaterialTheme.typography.labelSmall);BookMenu(edit,remove)}
        }
    }
}

@Composable
private fun BookCard(book: Book, repository: LibraryRepository, open: () -> Unit, edit: () -> Unit, remove: () -> Unit) {
    Card(modifier=Modifier.combinedClickable(onClick=open,onLongClick=edit), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(book, repository, Modifier.width(48.dp).height(72.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.author.ifBlank { "未知作者" }, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${book.format} · ${book.progressLabel()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BookMenu(edit, remove)
        }
    }
}

@Composable
private fun Cover(book: Book, repository: LibraryRepository, modifier: Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, book.id, book.coverPath) { value = repository.cover(book) }
    val image = bitmap
    if (image != null) {
        Image(image.asImageBitmap(), "${book.title} 封面", modifier.clip(MaterialTheme.shapes.small), contentScale = ContentScale.Fit)
        return
    }
    Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(8.dp))
            Text(book.format, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
internal fun AnnotationEditor(quote: String, originalNote: String, finish: (String?) -> Unit) {
    var note by remember { mutableStateOf(originalNote) }
    val keyboard=LocalSoftwareKeyboardController.current
    fun done(value: String?) {keyboard?.hide();finish(value)}
    AlertDialog(onDismissRequest={done(null)},title={Text("文字批注")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(quote.take(3000),style=MaterialTheme.typography.bodySmall,maxLines=8,overflow=TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp)); OutlinedTextField(note,{note=it.take(16000)},label={Text("笔记")},minLines=3,maxLines=8)
    }},confirmButton={TextButton(onClick={done(note)},enabled=note.isNotBlank()) {Text("保存")}},dismissButton={TextButton(onClick={done(null)}) {Text("取消")}})
}

@Composable
internal fun AnnotationCard(annotation: Annotation, open: () -> Unit, remove: () -> Unit, edit: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val parsedColor = remember(annotation.color) {
        runCatching { Color(android.graphics.Color.parseColor(annotation.color)) }.getOrNull()
    }
    Card(onClick = open, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (parsedColor != null && annotation.kind in listOf("HIGHLIGHT", "UNDERLINE")) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(parsedColor, androidx.compose.foundation.shape.CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when (annotation.kind) {
                        "HIGHLIGHT" -> "荧光笔"
                        "UNDERLINE" -> "下划线"
                        "NOTE" -> "文字笔记"
                        else -> "位置书签"
                    },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = {
                    val textToCopy = buildString {
                        if (annotation.quote.isNotBlank()) append(annotation.quote)
                        if (annotation.note.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append("笔记：").append(annotation.note)
                        }
                        if (isEmpty()) append(annotation.label)
                    }
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(textToCopy))
                    android.widget.Toast.makeText(context, "已复制批注内容", android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Rounded.ContentCopy, "复制批注")
                }
                if (annotation.kind != "BOOKMARK") IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "编辑批注") }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Rounded.DeleteOutline, "删除批注") }
            }
            Text(annotation.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (annotation.quote.isNotBlank()) Text(annotation.quote, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
            if (annotation.note.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(annotation.note, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (editing) AnnotationEditor(annotation.quote, annotation.note) { if (it != null) edit(it); editing = false }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("删除这条批注？") },
        text = { Text("仅删除应用内批注，不修改原书。") },
        confirmButton = { TextButton(onClick = { remove(); deleting = false }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } }
    )
}

@Composable
internal fun AppThemeOptions(settings: ReaderSettings, update: (ReaderSettings) -> Unit) {
    var custom by remember {mutableStateOf(false)}
    val dynamicSupported=android.os.Build.VERSION.SDK_INT>=31
    Column(Modifier.fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("深浅外观",style=MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(ReadingTheme.SYSTEM to "跟随系统", ReadingTheme.DAY to "日间", ReadingTheme.NIGHT to "夜间", ReadingTheme.BLACK to "纯黑").forEach {(mode,label)->
                FilterChip(selected=settings.appTheme==mode,onClick={update(settings.copy(appTheme=mode))},label={Text(label)})
            }
        }
        Text("强调色与壁纸",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {Text("动态取色");Text(if(dynamicSupported) "开启：完整使用系统壁纸色；关闭：使用下方主题色。" else "当前系统不支持动态取色，使用所选主题色。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Switch(settings.dynamicColors,{update(settings.copy(dynamicColors=it))},modifier=Modifier.testTag("dynamic-colors-switch"),enabled=dynamicSupported)
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ThemeAccent.entries.forEach {accent->FilterChip(selected=accent==settings.accent && settings.customAccent.isBlank(),onClick={update(settings.copy(accent=accent,customAccent=""))},enabled=!(settings.dynamicColors && dynamicSupported),label={Text(accent.label)},leadingIcon={Box(Modifier.size(16.dp).background(Color(accent.light), androidx.compose.foundation.shape.CircleShape))})}
            FilterChip(selected=settings.customAccent.isNotBlank(),onClick={custom=true},enabled=!(settings.dynamicColors && dynamicSupported),label={Text("自定义")})
        }
        Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth().padding(top=8.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("当前主题预览",color=MaterialTheme.colorScheme.onPrimaryContainer,style=MaterialTheme.typography.titleSmall)
                Text("强调色 #${"%06X".format(MaterialTheme.colorScheme.primary.toArgb() and 0xFFFFFF)}",color=MaterialTheme.colorScheme.onPrimaryContainer,style=MaterialTheme.typography.bodySmall)
                Button(onClick={custom=true},enabled=!(settings.dynamicColors && dynamicSupported),modifier=Modifier.padding(top=8.dp)) {Text("选择自定义颜色")}
            }
        }
    }
    if(custom) {
        val controller = rememberColorPickerController()
        var pickedHex by remember(settings.customAccent) {
            mutableStateOf(settings.customAccent.removePrefix("#").ifBlank { "7756AE" }.uppercase())
        }
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text("自定义主题色") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "拖动调色盘与亮度滑块选取主题色，系统会自动生成 Material 3 界面配色。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HsvColorPicker(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(horizontal = 8.dp),
                        controller = controller,
                        initialColor = runCatching { Color(("FF" + pickedHex).toLong(16)) }.getOrDefault(Color(0xFF7756AE)),
                        onColorChanged = { envelope ->
                            val hexStr = envelope.hexCode
                            if (hexStr.length >= 6) {
                                pickedHex = hexStr.takeLast(6).uppercase()
                            }
                        }
                    )
                    BrightnessSlider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(35.dp)
                            .padding(horizontal = 8.dp),
                        controller = controller,
                        initialColor = runCatching { Color(("FF" + pickedHex).toLong(16)) }.getOrDefault(Color(0xFF7756AE))
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(
                                    runCatching { Color(("FF" + pickedHex).toLong(16)) }.getOrDefault(Color.Gray),
                                    RoundedCornerShape(8.dp)
                                )
                        )
                        OutlinedTextField(
                            value = pickedHex,
                            onValueChange = { input ->
                                val clean = input.filter { it in "0123456789abcdefABCDEF" }.take(6).uppercase()
                                pickedHex = clean
                                if (clean.length == 6) {
                                    runCatching {
                                        controller.selectByColor(Color(("FF" + clean).toLong(16)), fromUser = true)
                                    }
                                }
                            },
                            label = { Text("颜色代码") },
                            prefix = { Text("#") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pickedHex.length == 6) {
                            update(settings.copy(customAccent = "#$pickedHex"))
                            custom = false
                        }
                    },
                    enabled = pickedHex.length == 6
                ) {
                    Text("应用")
                }
            },
            dismissButton = {
                TextButton(onClick = { custom = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SettingsSheet(settings: ReaderSettings, update: (ReaderSettings) -> Unit, reset: () -> Unit, fontOptions:@Composable ()->Unit, dismiss: () -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    var fontSize by remember(settings.fontSize) {mutableFloatStateOf(settings.fontSize)}
    var lineHeight by remember(settings.lineHeight) {mutableFloatStateOf(settings.lineHeight)}
    var margin by remember(settings.margin) {mutableFloatStateOf(settings.margin)}
    val latest by rememberUpdatedState(settings)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().testTag("reading-settings-sheet").verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("阅读设置", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { confirmReset = true }) { Text("重置") }
                TextButton(onClick = dismiss) { Text("完成") }
            }
            SettingsGroup {
                Text("阅读方式", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ReadingLayout.entries.forEachIndexed { i, layout ->
                        SegmentedButton(selected = layout == settings.layout, onClick = { update(settings.copy(layout = layout)) },
                            shape = SegmentedButtonDefaults.itemShape(i, ReadingLayout.entries.size)) { Text(layout.label) }
                    }
                }
                Text("PDF 阅读方式", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PdfReadingLayout.entries.forEach { layout -> FilterChip(selected = layout == settings.pdfLayout,
                        onClick = { update(settings.copy(pdfLayout = layout)) }, label = { Text(layout.label) }) }
                }
            }
            SettingsGroup {
                Text("主题", style = MaterialTheme.typography.titleMedium)
                Text("仅改变阅读纸张，应用主题色可在设置页单独调整。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingTheme.entries.forEach { theme -> FilterChip(selected = theme == settings.theme,
                        onClick = { update(settings.copy(theme = theme)) }, label = { Text(theme.label) }) }
                }
            }
            SettingsGroup {
                fontOptions()
                Text("文字排版", style = MaterialTheme.typography.titleMedium)
                Text("字号  ${fontSize.toInt()} sp", style = MaterialTheme.typography.labelLarge)
                Slider(fontSize, { fontSize = it }, modifier = Modifier.testTag("font-size-slider"),
                    onValueChangeFinished = { update(latest.copy(fontSize = fontSize)) }, valueRange = 14f..32f, steps = 17)
                Text("行距  ${"%.1f".format(lineHeight)}", style = MaterialTheme.typography.labelLarge)
                Slider(lineHeight, { lineHeight = it }, onValueChangeFinished = { update(latest.copy(lineHeight = lineHeight)) }, valueRange = 1.2f..2.6f, steps = 13)
                Text("页边距  ${margin.toInt()}", style = MaterialTheme.typography.labelLarge)
                Slider(margin, { margin = it }, onValueChangeFinished = { update(latest.copy(margin = margin)) }, valueRange = 12f..48f, steps = 8)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("使用衬线字体", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(settings.serif, { update(settings.copy(serif = it)) })
                }
            }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text("重置阅读设置？") },
        text = { Text("恢复默认阅读排版、蓝色主题、关闭动态取色和 PDF 纵向阅读。不会删除书籍、进度或书签。") },
        confirmButton = { TextButton(onClick = { reset(); confirmReset = false }) { Text("重置") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } })
}

@Composable
private fun MetadataDialog(book: Book, save: (String, String, String, android.net.Uri?) -> Unit, dismiss: () -> Unit) {
    var pendingCover by remember { mutableStateOf<android.net.Uri?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) pendingCover = uri }
    var title by remember { mutableStateOf(book.title) }
    var author by remember { mutableStateOf(book.author) }
    var tags by remember { mutableStateOf(book.tags) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("书籍信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("书名") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next)
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("作者") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next)
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("标签，以逗号分隔") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done)
                )
                if (pendingCover != null) {
                    Text("已选择新封面，点击「保存」生效", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { coverPicker.launch(arrayOf("image/*")) }) {
                    Text(if (pendingCover != null) "重新选择封面" else "选择自定义封面")
                }
            }
        },
        confirmButton = { TextButton(onClick = { save(title, author, tags, pendingCover) }, enabled = title.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
    )
}

@Composable
private fun SearchDialog(vm: LibraryViewModel, bookId: String?, dismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }; val hits by vm.hits.collectAsStateWithLifecycle(); val searching by vm.searching.collectAsStateWithLifecycle()
    LaunchedEffect(query, bookId) { vm.search(query, bookId) }
    val keyboard = LocalSoftwareKeyboardController.current
    val highlightColor = MaterialTheme.colorScheme.primary
    val highlightBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f)
    Dialog(onDismissRequest = dismiss) { Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) { Column(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 620.dp).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(if (bookId == null) "全文搜索" else "书内搜索", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); IconButton(onClick = dismiss) { Icon(Icons.Rounded.Close, "关闭搜索") } }
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(),
            placeholder = { Text("输入正文关键词") }, singleLine = true, shape = RoundedCornerShape(18.dp),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() })
        )
        Spacer(Modifier.height(12.dp))
        Text("当前搜索 EPUB / TXT 正文，最多显示 100 条。PDF 请使用阅读页内搜索。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(12.dp))
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth()) else if (query.isNotBlank() && hits.isEmpty()) Text("未找到匹配内容", Modifier.padding(vertical = 20.dp))
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) { items(hits) { hit ->
            val matchText = remember(hit.snippet, hit.query, highlightColor, highlightBg) {
                androidx.compose.ui.text.buildAnnotatedString {
                    val text = hit.snippet
                    val term = hit.query
                    if (term.isBlank()) { append(text); return@buildAnnotatedString }
                    var idx = 0
                    while (idx < text.length) {
                        val at = text.indexOf(term, idx, ignoreCase = true)
                        if (at < 0) { append(text.substring(idx)); break }
                        if (at > idx) append(text.substring(idx, at))
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = highlightColor, background = highlightBg))
                        append(text.substring(at, at + term.length))
                        pop()
                        idx = at + term.length
                    }
                }
            }
            Surface(onClick = { vm.openHit(hit); dismiss() }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(hit.book.title + " · " + hit.chapter.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(5.dp)); Text(matchText, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            } }
        } }
    } } }
}
