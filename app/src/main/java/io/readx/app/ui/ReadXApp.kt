@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.readx.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.readx.app.data.Book
import io.readx.app.data.Bookmark
import io.readx.app.data.LibraryRepository
import io.readx.app.data.progress
import io.readx.app.pdf.PdfActivity
import io.readx.app.reader.WebReader
import io.readx.app.reader.ReaderController

@Composable
fun ReadXApp(vm: LibraryViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val session by vm.reader.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchBook by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.import(it) }
    fun open(book: Book) {
        if (book.format == "PDF") context.startActivity(Intent(context, PdfActivity::class.java).putExtra("bookId", book.id)) else vm.open(book)
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    ReadXTheme(settings.theme) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing, snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = if (session == null) 88.dp else 56.dp)) }) { outer ->
            Box(Modifier.fillMaxSize().padding(outer).consumeWindowInsets(outer)) {
                if (session == null) {
                    LibraryScreen(
                        books = books, bookmarks = bookmarks, repository = vm.repository, busy = busy, selectedTab = selectedTab,
                        import = { importer.launch(arrayOf("application/epub+zip", "text/plain", "application/pdf", "application/octet-stream")) },
                        sample = vm::sample, open = ::open,
                        search = { searchBook = null; searchOpen = true },
                        settings = { showSettings = true },
                        selectTab = { tab -> selectedTab = tab; if (tab == 3) showSettings = true },
                        edit = vm::edit, delete = vm::delete, openBookmark = vm::openBookmark, removeBookmark = vm::removeBookmark,
                    )
                } else ReaderScreen(session!!, settings, vm, { showSettings = true }, { searchBook = session!!.book.id; searchOpen = true })
            }
        }
        if (showSettings) SettingsSheet(settings, vm.preferences::update, vm.preferences::reset) { showSettings = false }
        if (searchOpen) SearchDialog(vm, searchBook) { searchOpen = false; vm.search("") }
    }
}

@Composable
private fun LibraryScreen(
    books: List<Book>, bookmarks: List<Bookmark>, repository: LibraryRepository, busy: Boolean, selectedTab: Int, import: () -> Unit, sample: () -> Unit,
    open: (Book) -> Unit, search: () -> Unit, settings: () -> Unit, selectTab: (Int) -> Unit,
    edit: (Book, String, String, String) -> Any, delete: (Book) -> Any,
    openBookmark: (Bookmark) -> Unit, removeBookmark: (Bookmark) -> Any,
) {
    var filter by rememberSaveable { mutableStateOf("全部") }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Book?>(null) }
    var removing by remember { mutableStateOf<Book?>(null) }
    val filtered = books.filter { (filter == "全部" || it.format == filter) && (query.isBlank() || (it.title + it.author + it.tags).contains(query, true)) }
    val recent = books.filter { it.lastReadAt > 0 }.sortedByDescending { it.lastReadAt }
    val showHome = selectedTab == 0
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(windowInsets = WindowInsets(0), title = { Text("ReadX", fontSize = 34.sp, fontWeight = FontWeight.Bold) }, actions = {
                IconButton(onClick = search) { Icon(Icons.Rounded.Search, "全文搜索") }
                IconButton(onClick = import, enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Add, "导入书籍")
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "书架操作") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("导入书籍") }, enabled = !busy, onClick = { menu = false; import() })
                        DropdownMenuItem(text = { Text("导入示例") }, enabled = !busy, onClick = { menu = false; sample() })
                    }
                }
            })
        },
        bottomBar = {
            NavigationBar(windowInsets = WindowInsets(0), containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                listOf(
                    Triple(Icons.Rounded.Home, "首页", 0),
                    Triple(Icons.Rounded.CollectionsBookmark, "书库", 1),
                    Triple(Icons.Rounded.BookmarkBorder, "书签", 2),
                    Triple(Icons.Rounded.Settings, "设置", 3),
                ).forEach { (icon, label, tab) ->
                    NavigationBarItem(selected = selectedTab == tab, onClick = { selectTab(tab) }, icon = { Icon(icon, null) }, label = { Text(label) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
                }
            }
        },

    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (selectedTab == 2) {
                item { Text("书签", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                if (bookmarks.isEmpty()) item { EmptySection("暂无书签") }
                items(bookmarks, key = { it.id }) { mark ->
                    val book = books.firstOrNull { it.id == mark.bookId }
                    Card(onClick = { openBookmark(mark) }, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        ListItem(headlineContent = { Text(book?.title ?: "书籍", maxLines = 1) }, supportingContent = { Text(mark.label) }, trailingContent = { IconButton(onClick = { removeBookmark(mark) }) { Icon(Icons.Rounded.DeleteOutline, "删除书签") } }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    }
                }
            } else if (selectedTab == 3) {
                item { Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                item { Card(onClick = settings, shape = RoundedCornerShape(22.dp)) { ListItem(headlineContent = { Text("阅读设置") }, supportingContent = { Text("分页与滚动、主题、字号与行距") }, leadingContent = { Icon(Icons.Rounded.Tune, null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) } }
                item { Text("ReadX · 本地阅读\nEPUB / TXT / PDF", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(18.dp)) }
            } else {
                if (showHome && recent.isNotEmpty()) {
                    item { Text("继续阅读", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, start = 2.dp)) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                            items(recent.take(8), key = { it.id }) { book -> ContinueCard(book, repository, { open(book) }, { editing = book }, { removing = book }) }
                        }
                    }
                }
                item { Text(if (showHome) "书库" else "全部书籍", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, start = 2.dp)) }
                if (books.isEmpty()) {
                    item { EmptySection("暂无书籍") }
                } else {
                    if (!showHome) item {
                        Column {
                            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("书名、作者或标签") }, singleLine = true,
                                leadingIcon = { Icon(Icons.Rounded.FilterList, null) }, shape = RoundedCornerShape(22.dp))
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("全部", "EPUB", "TXT", "PDF").forEach { value ->
                                FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value) })
                            } }
                        }
                    }
                    if (filtered.isEmpty()) item { Text("没有匹配的书籍", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(filtered, key = { it.id }) { book -> BookCard(book, repository, { open(book) }, { editing = book }, { removing = book }) }
                    item { Text("${filtered.size} 本书", Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    editing?.let { book -> MetadataDialog(book, { title, author, tags -> edit(book, title, author, tags); editing = null }) { editing = null } }
    removing?.let { book -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除这本书？") }, text = { Text("将删除「" + book.title + "」在应用内的副本和阅读进度，不会删除原文件。") },
        confirmButton = { TextButton(onClick = { delete(book); removing = null }) { Text("移除") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
}

@Composable
private fun EmptySection(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 96.dp), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun ContinueCard(book: Book, repository: LibraryRepository, open: () -> Unit, edit: () -> Unit, remove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val progress = book.progress()?.let { "$it%" } ?: "第 ${book.chapterIndex + 1} 页"
    Card(onClick = open, modifier = Modifier.width(280.dp).height(110.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF44564F), contentColor = Color.White)) {
        Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(book, repository, Modifier.width(50.dp).height(78.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp)); Text(book.author.ifBlank { "未知作者" }, color = Color(0xFFDFE7E3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp)); Text("${book.format} · $progress", style = MaterialTheme.typography.labelLarge, color = Color(0xFFDFE7E3))
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, "书籍操作") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("编辑信息与标签") }, onClick = { menu = false; edit() })
                    DropdownMenuItem(text = { Text("移除书籍") }, onClick = { menu = false; remove() })
                }
            }
        }
    }
}

@Composable
private fun BookCard(book: Book, repository: LibraryRepository, open: () -> Unit, edit: () -> Unit, remove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = open, shape = RoundedCornerShape(22.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(book, repository, Modifier.width(50.dp).height(78.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp)); Text(book.author.ifBlank { "未知作者" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp)); Text(book.progress()?.let { "$it%" } ?: "第 ${book.chapterIndex + 1} 页", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, "书籍操作") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("编辑信息与标签") }, onClick = { menu = false; edit() })
                    DropdownMenuItem(text = { Text("移除书籍") }, onClick = { menu = false; remove() })
                }
            }
        }
    }
}

@Composable
private fun Cover(book: Book, repository: LibraryRepository, modifier: Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, book.id, book.coverPath) { value = repository.cover(book) }
    val image = bitmap
    if (image != null) {
        Image(image.asImageBitmap(), "${book.title} 封面", modifier.clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Fit)
        return
    }
    val coverColor = when(book.format) { "PDF" -> Color(0xFFCFD7D2); "EPUB" -> Color(0xFFDCD7C4); else -> Color(0xFFD7DFD0) }
    Box(modifier.background(Brush.linearGradient(listOf(coverColor, coverColor.copy(alpha = .68f))), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(28.dp), tint = Color(0xFF445449)); Spacer(Modifier.height(8.dp)); Text(book.format, style = MaterialTheme.typography.labelSmall, color = Color(0xFF445449)) }
    }
}

@Composable
private fun ReaderScreen(session: ReaderSession, settings: ReaderSettings, vm: LibraryViewModel, showSettings: () -> Unit, search: () -> Unit) {
    var toc by rememberSaveable { mutableStateOf(false) }
    var page by remember(session.book.id, session.navigationId, session.chapter, settings) { mutableIntStateOf(1) }
    var pages by remember(session.book.id, session.navigationId, session.chapter, settings) { mutableIntStateOf(0) }
    var fraction by remember(session.book.id, session.navigationId, session.chapter) { mutableFloatStateOf(session.fraction) }
    var showProgress by remember(session.book.id, session.navigationId, settings) { mutableStateOf(false) }
    val controller = remember { ReaderController() }
    val chapter = session.chapters[session.chapter]
    BackHandler { if (session.returnStack.isNotEmpty()) vm.returnFromLink() else vm.close() }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(windowInsets = WindowInsets(0), title = {
            Column {
                Text(session.book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (session.returnStack.isNotEmpty()) {
                    TextButton(onClick = vm::returnFromLink, modifier = Modifier.height(24.dp), contentPadding = PaddingValues(0.dp)) {
                        Text("回到原处", style = MaterialTheme.typography.labelSmall)
                    }
                } else Text(chapter.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }, navigationIcon = { IconButton(onClick = vm::close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回书架") } }, actions = {
            IconButton(onClick = { toc = true }) { Icon(Icons.AutoMirrored.Rounded.List, "目录") }
            IconButton(onClick = { vm.addBookmark() }) { Icon(Icons.Rounded.BookmarkAdd, "添加书签") }
            IconButton(onClick = search) { Icon(Icons.Rounded.Search, "书内搜索") }
            IconButton(onClick = showSettings) { Icon(Icons.Rounded.Tune, "排版") }
        }) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { if (settings.layout == ReadingLayout.PAGED) controller.turn(-1) else vm.chapter(session.chapter - 1) }, enabled = pages > 0 && (session.chapter > 0 || page > 1)) { Text(if (settings.layout == ReadingLayout.PAGED) "上一页" else "上一章") }
                    TextButton(onClick = { showProgress = true }, enabled = settings.layout == ReadingLayout.PAGED && pages > 0) {
                        Text(if (pages == 0) "正在排版…" else if (settings.layout == ReadingLayout.PAGED) "第$page/${pages}页" else "滚动 ${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { if (settings.layout == ReadingLayout.PAGED) controller.turn(1) else vm.chapter(session.chapter + 1) }, enabled = pages > 0 && (session.chapter < session.chapters.lastIndex || page < pages)) { Text(if (settings.layout == ReadingLayout.PAGED) "下一页" else "下一章") }
                }
            }
        },
    ) { padding ->
        val foreground = "#%06X".format(MaterialTheme.colorScheme.onSurface.toArgb() and 0xFFFFFF)
        val background = "#%06X".format(MaterialTheme.colorScheme.background.toArgb() and 0xFFFFFF)
        WebReader(session, settings, vm.repository, foreground, background,
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            { index, target -> vm.chapter(index, target) }, vm::followLink,
            { position, final, current, total ->
                // Ignore a released WebView from a previous link/chapter navigation, including same-chapter links.
                if (vm.isCurrentNavigation(session.book.id, session.navigationId)) {
                    fraction = position; page = current; pages = total
                    if (final) vm.savePosition(session.book.id, session.chapter, position)
                    else vm.position(session.book.id, session.chapter, position)
                } else if (final && vm.reader.value == null) {
                    // Leaving the reader must still flush the settled final page.
                    vm.savePosition(session.book.id, session.chapter, position)
                }
            }, vm::notify, controller)
    }
    if (showProgress && pages > 0) PageProgressSheet(page, pages, chapter.title, controller::jumpToPage) { showProgress = false }
    if (toc) ModalBottomSheet(onDismissRequest = { toc = false }) {
        Text("目录", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineSmall)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(session.chapters, key = { it.ordinal }) { item ->
                ListItem(headlineContent = { Text(item.title, color = if (item.ordinal == session.chapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    leadingContent = { Text((item.ordinal + 1).toString(), style = MaterialTheme.typography.labelLarge) }, modifier = Modifier.clickable { vm.chapter(item.ordinal); toc = false })
            }
        }
    }
}

@Composable
private fun PageProgressSheet(page: Int, pages: Int, title: String, jump: (Int) -> Unit, dismiss: () -> Unit) {
    var selected by remember(page, pages) { mutableFloatStateOf(page.toFloat()) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("章节进度", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = dismiss) { Text("完成") }
            }
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            val targetPage = kotlin.math.round(selected).toInt().coerceIn(1, pages)
            Text("第$targetPage/${pages}页", style = MaterialTheme.typography.titleMedium)
            Slider(value = selected, onValueChange = { selected = it },
                onValueChangeFinished = { jump(kotlin.math.round(selected).toInt().coerceIn(1, pages)) },
                valueRange = 1f..pages.coerceAtLeast(2).toFloat(), enabled = pages > 1)
            Text(if (pages == 1) "当前章节只有一页" else "拖动预览页码，松手跳转；页数仅对应当前章节和排版。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsSheet(settings: ReaderSettings, update: (ReaderSettings) -> Unit, reset: () -> Unit, dismiss: () -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("阅读设置", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); TextButton(onClick = { confirmReset = true }) { Text("重置") }; TextButton(onClick = dismiss) { Text("完成") } }
            Spacer(Modifier.height(14.dp))
            Text("阅读方式", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ReadingLayout.entries.forEach { layout -> FilterChip(selected = layout == settings.layout, onClick = { update(settings.copy(layout = layout)) }, label = { Text(layout.label) }) } }
            Spacer(Modifier.height(12.dp))
            Text("主题", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ReadingTheme.entries.forEach { theme -> FilterChip(selected = theme == settings.theme, onClick = { update(settings.copy(theme = theme)) }, label = { Text(theme.label) }) } }
            Spacer(Modifier.height(12.dp)); Text("字号  ${settings.fontSize.toInt()} sp", style = MaterialTheme.typography.labelLarge)
            Slider(settings.fontSize, { update(settings.copy(fontSize = it)) }, valueRange = 14f..32f, steps = 17)
            Text("行距  ${"%.1f".format(settings.lineHeight)}", style = MaterialTheme.typography.labelLarge)
            Slider(settings.lineHeight, { update(settings.copy(lineHeight = it)) }, valueRange = 1.2f..2.6f, steps = 13)
            Text("页边距  ${settings.margin.toInt()}", style = MaterialTheme.typography.labelLarge)
            Slider(settings.margin, { update(settings.copy(margin = it)) }, valueRange = 12f..48f, steps = 8)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("使用衬线字体", style = MaterialTheme.typography.bodyLarge); Switch(settings.serif, { update(settings.copy(serif = it)) }) }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text("重置阅读设置？") },
        text = { Text("恢复跟随系统主题、分页、20 sp 字号、1.8 倍行距、24 页边距和衬线字体。不会删除书籍、进度或书签。") },
        confirmButton = { TextButton(onClick = { reset(); confirmReset = false }) { Text("重置") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } })
}

@Composable
private fun MetadataDialog(book: Book, save: (String, String, String) -> Unit, dismiss: () -> Unit) {
    var title by remember { mutableStateOf(book.title) }; var author by remember { mutableStateOf(book.author) }; var tags by remember { mutableStateOf(book.tags) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("书籍信息") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text("书名") }, singleLine = true); OutlinedTextField(author, { author = it }, label = { Text("作者") }, singleLine = true); OutlinedTextField(tags, { tags = it }, label = { Text("标签，以逗号分隔") }, singleLine = true)
    } }, confirmButton = { TextButton(onClick = { save(title, author, tags) }, enabled = title.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
private fun SearchDialog(vm: LibraryViewModel, bookId: String?, dismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }; val hits by vm.hits.collectAsStateWithLifecycle(); val searching by vm.searching.collectAsStateWithLifecycle()
    LaunchedEffect(query, bookId) { vm.search(query, bookId) }
    Dialog(onDismissRequest = dismiss) { Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 3.dp) { Column(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 620.dp).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(if (bookId == null) "全文搜索" else "书内搜索", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); IconButton(onClick = dismiss) { Icon(Icons.Rounded.Close, "关闭搜索") } }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("输入正文关键词") }, singleLine = true, shape = RoundedCornerShape(18.dp)); Spacer(Modifier.height(12.dp))
        Text("当前搜索 EPUB / TXT 正文，最多显示 100 条。PDF 请使用阅读页内搜索。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(12.dp))
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth()) else if (query.isNotBlank() && hits.isEmpty()) Text("未找到匹配内容", Modifier.padding(vertical = 20.dp))
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) { items(hits) { hit ->
            Surface(onClick = { vm.openHit(hit); dismiss() }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(hit.book.title + " · " + hit.chapter.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(5.dp)); Text(hit.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            } }
        } }
    } } }
}
