@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.readx.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val annotations by vm.annotations.collectAsStateWithLifecycle()
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
    ReadXTheme(settings,reading=session!=null) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing, snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = if (session == null) 88.dp else 112.dp)) }) { outer ->
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
                            val book=books.firstOrNull { it.id==annotation.bookId }
                            if(book?.format=="PDF") context.startActivity(Intent(context,PdfActivity::class.java).putExtra("bookId",book.id).putExtra("page",annotation.chapter))
                            else vm.openAnnotation(annotation)
                        }, removeAnnotation = vm::deleteAnnotation, editAnnotation = vm::updateAnnotation,
                        readerSettings = settings, updateSettings = vm.preferences::update,
                    )
                } else ImmersiveReaderScreen(session!!, settings, vm, { showSettings = true }, { searchBook = session!!.book.id; searchOpen = true })
            }
        }
        if (showSettings) SettingsSheet(settings, vm.preferences::update, vm.preferences::reset) { showSettings = false }
        if (searchOpen) SearchDialog(vm, searchBook) { searchOpen = false; vm.search("") }
    }
}

@Composable
private fun LibraryScreen(
    books: List<Book>, annotations: List<Annotation>, repository: LibraryRepository, busy: Boolean, selectedTab: Int, import: () -> Unit, sample: () -> Unit,
    open: (Book) -> Unit, search: () -> Unit, settings: () -> Unit, selectTab: (Int) -> Unit,
    edit: (Book, String, String, String) -> Any, delete: (Book) -> Any,
    openAnnotation: (Annotation) -> Unit, removeAnnotation: (Annotation) -> Any, editAnnotation: (Annotation,String) -> Any,
    readerSettings: ReaderSettings, updateSettings: (ReaderSettings) -> Unit,
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
                    Triple(Icons.Rounded.EditNote, "批注", 2),
                    Triple(Icons.Rounded.Settings, "设置", 3),
                ).forEach { (icon, label, tab) ->
                    NavigationBarItem(selected = selectedTab == tab, onClick = { selectTab(tab) }, icon = { Icon(icon, null) }, label = { Text(label) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
                }
            }
        },

    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (selectedTab == 2) {
                item { Text("批注", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                if (annotations.isEmpty()) item { EmptySection("暂无批注；阅读时长按选中文字，或添加位置书签。") }
                annotations.groupBy { it.bookId }.forEach { (bookId, marks) ->
                    val book=books.firstOrNull { it.id==bookId }
                    item(key="annotation-book-$bookId") { Text("${book?.title ?: "书籍"} · ${marks.size} 条", style=MaterialTheme.typography.titleMedium) }
                    items(marks,key={it.id}) { mark ->
                        AnnotationCard(mark, { openAnnotation(mark) }, { removeAnnotation(mark) }, { note -> editAnnotation(mark,note) })
                    }
                }
            } else if (selectedTab == 3) {
                item { Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                item { Card(onClick = settings, shape = RoundedCornerShape(22.dp)) { ListItem(headlineContent = { Text("阅读设置") }, supportingContent = { Text("分页与滚动、主题、字号与行距") }, leadingContent = { Icon(Icons.Rounded.Tune, null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) } }
                item { AppThemeOptions(readerSettings, updateSettings) }
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
    Card(onClick = open, modifier = Modifier.width(280.dp).height(110.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(book, repository, Modifier.width(50.dp).height(78.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp)); Text(book.author.ifBlank { "未知作者" }, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp)); Text("${book.format} · $progress", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=.8f))
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
    var editing by remember {mutableStateOf(false)}
    var deleting by remember {mutableStateOf(false)}
    Card(onClick=open,shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(when(annotation.kind) {"HIGHLIGHT"->"荧光笔"; "UNDERLINE"->"下划线"; "NOTE"->"文字笔记"; else->"位置书签"},Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                if(annotation.kind!="BOOKMARK") IconButton(onClick={editing=true}) {Icon(Icons.Rounded.Edit,"编辑批注")}
                IconButton(onClick={deleting=true}) {Icon(Icons.Rounded.DeleteOutline,"删除批注")}
            }
            Text(annotation.label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(annotation.quote.isNotBlank()) Text(annotation.quote,style=MaterialTheme.typography.bodyMedium,maxLines=5,overflow=TextOverflow.Ellipsis)
            if(annotation.note.isNotBlank()) {Spacer(Modifier.height(6.dp));Text(annotation.note,style=MaterialTheme.typography.bodySmall)}
        }
    }
    if(editing) AnnotationEditor(annotation.quote,annotation.note) { if(it!=null) edit(it); editing=false }
    if(deleting) AlertDialog(onDismissRequest={deleting=false},title={Text("删除这条批注？")},text={Text("仅删除应用内批注，不修改原书。")},confirmButton={TextButton(onClick={remove();deleting=false}) {Text("删除")}},dismissButton={TextButton(onClick={deleting=false}) {Text("取消")}})
}

@Composable
internal fun AppThemeOptions(settings: ReaderSettings, update: (ReaderSettings) -> Unit) {
    var custom by remember {mutableStateOf(false)}
    var hex by remember(settings.customAccent) {mutableStateOf(settings.customAccent.removePrefix("#"))}
    val dynamicSupported=android.os.Build.VERSION.SDK_INT>=31
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text("应用主题",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {Text("动态取色");Text(if(dynamicSupported) "开启：完整使用系统壁纸色；关闭：使用下方主题色。" else "当前系统不支持动态取色，使用所选主题色。",style=MaterialTheme.typography.bodySmall)}
            Switch(settings.dynamicColors,{update(settings.copy(dynamicColors=it))},enabled=dynamicSupported)
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ThemeAccent.entries.forEach {accent->FilterChip(selected=accent==settings.accent && settings.customAccent.isBlank(),onClick={update(settings.copy(accent=accent,customAccent=""))},enabled=!(settings.dynamicColors && dynamicSupported),label={Text(accent.label)})}
            FilterChip(selected=settings.customAccent.isNotBlank(),onClick={custom=true},enabled=!(settings.dynamicColors && dynamicSupported),label={Text("自定义")})
        }
        Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(top=12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("当前主题预览",color=MaterialTheme.colorScheme.onPrimaryContainer,style=MaterialTheme.typography.titleSmall)
                Text("强调色 #${"%06X".format(MaterialTheme.colorScheme.primary.toArgb() and 0xFFFFFF)}",color=MaterialTheme.colorScheme.onPrimaryContainer,style=MaterialTheme.typography.bodySmall)
                Button(onClick={custom=true},enabled=!(settings.dynamicColors && dynamicSupported),modifier=Modifier.padding(top=8.dp)) {Text("选择自定义颜色")}
            }
        }
    }
    if(custom) AlertDialog(onDismissRequest={custom=false},title={Text("自定义主题色")},text={Column {
        OutlinedTextField(hex,{hex=it.filter {c->c in "0123456789abcdefABCDEF"}.take(6)},label={Text("RGB 十六进制，例如 7756AE")},prefix={Text("#")},singleLine=true)
        if(hex.length==6) Box(Modifier.fillMaxWidth().padding(top=12.dp).height(40.dp).background(Color(("FF"+hex).toLong(16)),RoundedCornerShape(8.dp)))
    }},confirmButton={TextButton(onClick={update(settings.copy(customAccent="#${hex.uppercase()}"));custom=false},enabled=hex.length==6) {Text("应用")}},dismissButton={TextButton(onClick={custom=false}) {Text("取消")}})
}

@Composable
private fun SettingsSheet(settings: ReaderSettings, update: (ReaderSettings) -> Unit, reset: () -> Unit, dismiss: () -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    var fontSize by remember(settings.fontSize) {mutableFloatStateOf(settings.fontSize)}
    var lineHeight by remember(settings.lineHeight) {mutableFloatStateOf(settings.lineHeight)}
    var margin by remember(settings.margin) {mutableFloatStateOf(settings.margin)}
    val latest by rememberUpdatedState(settings)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("阅读设置", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); TextButton(onClick = { confirmReset = true }) { Text("重置") }; TextButton(onClick = dismiss) { Text("完成") } }
            Spacer(Modifier.height(14.dp))
            Text("阅读方式", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ReadingLayout.entries.forEach { layout -> FilterChip(selected = layout == settings.layout, onClick = { update(settings.copy(layout = layout)) }, label = { Text(layout.label) }) } }
            Spacer(Modifier.height(12.dp))
            Text("PDF 阅读方式", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PdfReadingLayout.entries.forEach { layout -> FilterChip(selected=layout==settings.pdfLayout,onClick={update(settings.copy(pdfLayout=layout))},label={Text(layout.label)}) } }
            Spacer(Modifier.height(12.dp))
            Text("主题", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ReadingTheme.entries.forEach { theme -> FilterChip(selected = theme == settings.theme, onClick = { update(settings.copy(theme = theme)) }, label = { Text(theme.label) }) } }
            Spacer(Modifier.height(12.dp)); Text("字号  ${fontSize.toInt()} sp", style = MaterialTheme.typography.labelLarge)
            Slider(fontSize,{fontSize=it},modifier=Modifier.testTag("font-size-slider"),onValueChangeFinished={update(latest.copy(fontSize=fontSize))},valueRange=14f..32f,steps=17)
            Text("行距  ${"%.1f".format(lineHeight)}", style = MaterialTheme.typography.labelLarge)
            Slider(lineHeight,{lineHeight=it},onValueChangeFinished={update(latest.copy(lineHeight=lineHeight))},valueRange=1.2f..2.6f,steps=13)
            Text("页边距  ${margin.toInt()}", style = MaterialTheme.typography.labelLarge)
            Slider(margin,{margin=it},onValueChangeFinished={update(latest.copy(margin=margin))},valueRange=12f..48f,steps=8)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("使用衬线字体", style = MaterialTheme.typography.bodyLarge); Switch(settings.serif, { update(settings.copy(serif = it)) }) }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text("重置阅读设置？") },
        text = { Text("恢复默认阅读排版、蓝色主题、关闭动态取色和 PDF 纵向阅读。不会删除书籍、进度或书签。") },
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
