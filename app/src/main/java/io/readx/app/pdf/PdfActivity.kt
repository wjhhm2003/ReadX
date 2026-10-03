@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.pdf.ExperimentalPdfApi::class)
package io.readx.app.pdf

import android.annotation.SuppressLint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.util.SparseArray
import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import io.readx.app.ui.MarkColorPicker
import io.readx.app.data.MarkColor
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Palette
import io.readx.app.ui.ReadingTheme
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import io.readx.app.data.Annotation
import io.readx.app.ui.AnnotationEditor
import io.readx.app.ui.PdfReadingLayout
import androidx.pdf.selection.model.TextSelection
import androidx.pdf.selection.SelectionMenuComponent
import java.util.UUID
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import androidx.pdf.PdfDocument
import androidx.pdf.content.ExternalLink
import androidx.pdf.view.PdfView
import androidx.pdf.viewer.fragment.PdfViewerFragment
import io.readx.app.ReadXApplication
import io.readx.app.data.Book
import io.readx.app.ui.ReadXTheme
import io.readx.app.ui.ReaderPreferences
import kotlinx.coroutines.*

@SuppressLint("NewApi") // The advanced fragment is only instantiated after capability checks.
class PdfActivity : AppCompatActivity() {
    private var book by mutableStateOf<Book?>(null)
    private var pageCount by mutableIntStateOf(0)
    private var page by mutableIntStateOf(0)
    private var status by mutableStateOf("正在打开 PDF…")
    private var chrome by mutableStateOf(false)
    private var colorPanel by mutableStateOf(false)
    private var notesOpen by mutableStateOf(false)
    private var jump by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var document by mutableStateOf<PdfDocument?>(null)
    private var annotations by mutableStateOf<List<Annotation>>(emptyList())
    private var selection by mutableStateOf<PdfSelection?>(null)
    private var noteSelection by mutableStateOf<PdfSelection?>(null)
    private var requestedPage by mutableStateOf<Int?>(null)
    private var viewer: ReadXPdfFragment? = null
    private val repository get() = (application as ReadXApplication).repository
    private val preferences by lazy { ReaderPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        val id=intent.getStringExtra("bookId") ?: run {finish();return}
        requestedPage=if(intent.hasExtra("page")) intent.getIntExtra("page",0) else null
        val forceBasic=io.readx.app.BuildConfig.DEBUG && intent.getBooleanExtra("forceBasicForTest",false)
        val supportsAdvanced=!forceBasic && Build.VERSION.SDK_INT>=31 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S)>=13
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        ViewCompat.setOnApplyWindowInsetsListener(root) {view,insets->
            val safe=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime=insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left,safe.top,safe.right,maxOf(safe.bottom,ime.bottom));WindowInsetsCompat.CONSUMED
        }
        val container=if(supportsAdvanced) FragmentContainerView(this).apply {this.id=PDF_CONTAINER_ID} else null
        val header=ComposeView(this).apply {setContent {
            val settings by preferences.settings.collectAsState()
            ReadXTheme(settings,reading=true) {
                val background=MaterialTheme.colorScheme.background.toArgb()
                SideEffect {root.setBackgroundColor(background)}
                Box(Modifier.fillMaxSize()) {
                    if(!chrome) Text(if(pageCount>0) "${page+1} / $pageCount" else "加载中…",Modifier.align(Alignment.BottomEnd).padding(end=20.dp,bottom=5.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    AnimatedVisibility(chrome,modifier=Modifier.align(Alignment.TopStart)) {
                        Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surfaceContainer.copy(alpha=.94f),modifier=Modifier.padding(10.dp)) {IconButton(onClick={finish()}) {Icon(Icons.AutoMirrored.Rounded.ArrowBack,"返回书库")}}
                    }
                    AnimatedVisibility(chrome,modifier=Modifier.align(Alignment.BottomCenter)) {
                        Surface(color=MaterialTheme.colorScheme.surfaceContainer) {Column {
                            if(colorPanel) Column(Modifier.padding(16.dp)) {
                                Text("标记颜色",style=MaterialTheme.typography.titleSmall);MarkColorPicker(settings.annotationColor) {preferences.update(settings.copy(annotationColor=it))}
                                Text("PDF 保持原文颜色，不是反色或文字重排。",style=MaterialTheme.typography.bodySmall)
                            }
                            Row(Modifier.fillMaxWidth().height(64.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                                IconButton(onClick={notesOpen=true}) {Icon(Icons.Rounded.EditNote,"本书批注")}
                                IconButton(onClick={if(pageCount>0) jump=true},enabled=pageCount>0) {Icon(Icons.Rounded.Numbers,"跳转页码")}
                                IconButton(onClick={preferences.update(settings.copy(pdfLayout=if(settings.pdfLayout==PdfReadingLayout.VERTICAL) PdfReadingLayout.HORIZONTAL else PdfReadingLayout.VERTICAL))}) {Icon(if(settings.pdfLayout==PdfReadingLayout.HORIZONTAL) Icons.Rounded.ViewAgenda else Icons.Rounded.ViewCarousel,"切换 PDF 横向或纵向阅读")}
                                IconButton(onClick={colorPanel=!colorPanel}) {Icon(Icons.Rounded.Palette,"标记颜色")}
                                if(supportsAdvanced && settings.pdfLayout==PdfReadingLayout.VERTICAL) IconButton(onClick={viewer?.isTextSearchActive=true}) {Icon(Icons.Rounded.Search,"PDF 搜索")}
                            }
                        }}
                    }
                }
                if(jump) PageJumpDialog(pageCount,{target->requestedPage=target;viewer?.go(target);jump=false}) {jump=false}
                selection?.let { picked->AlertDialog(onDismissRequest={selection=null},title={Text(if(picked.quote.isBlank()) "区域批注" else "选中文字")},text={Column {
                    Text(if(picked.quote.isBlank()) "该区域未提取到文字，将保存区域标记；这不是 OCR。" else picked.quote.take(1000),maxLines=6)
                    MarkColorPicker(annotations.firstOrNull {it.id in picked.existingIds}?.color ?: settings.annotationColor) {hex->
                        preferences.update(settings.copy(annotationColor=hex))
                        annotations.firstOrNull {it.id in picked.existingIds}?.let {old->lifecycleScope.launch {repository.dao.replaceAnnotation(old.copy(color=hex,updatedAt=System.currentTimeMillis()))}}
                    }
                    if(picked.existingIds.isNotEmpty()) TextButton(onClick={lifecycleScope.launch {repository.dao.cancelMarkers(id,picked.existingIds)};selection=null}) {Text("取消标记")}
                    TextButton(onClick={saveAnnotation("HIGHLIGHT",picked,"");selection=null}) {Text("荧光笔")}
                    TextButton(onClick={saveAnnotation("UNDERLINE",picked,"");selection=null}) {Text("下划线")}
                    TextButton(onClick={noteSelection=picked;selection=null}) {Text("写批注")}
                }},confirmButton={TextButton(onClick={selection=null}) {Text("取消")}}) }
                noteSelection?.let {picked->AnnotationEditor(picked.quote.ifBlank {"PDF 区域批注"},"") {note->if(note!=null) saveAnnotation("NOTE",picked,note);noteSelection=null} }
                if(notesOpen) ModalBottomSheet(onDismissRequest={notesOpen=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
                    Column(Modifier.fillMaxHeight().padding(horizontal=20.dp)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Text("本书批注",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall);TextButton(onClick={notesOpen=false}) {Text("完成")}}
                        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            items(annotations.size,key={annotations[it].id}) {i->val mark=annotations[i];io.readx.app.ui.AnnotationCard(mark,
                                {requestedPage=mark.chapter;viewer?.go(mark.chapter);notesOpen=false},
                                {lifecycleScope.launch {repository.dao.deleteAnnotation(mark.id)}},
                                {note->lifecycleScope.launch {repository.dao.updateAnnotationNote(mark.id,note)}})
                            }
                        }
                    }
                }
                error?.let {message->AlertDialog(onDismissRequest={error=null},title={Text("PDF 打开失败")},text={Text(message)},confirmButton={TextButton(onClick={error=null;finish()}) {Text("返回书库")}})}
            }
        }}
        val content=FrameLayout(this)
        root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        if(container!=null) content.addView(container,FrameLayout.LayoutParams(-1,-1))
        val body=ComposeView(this)
        content.addView(body,FrameLayout.LayoutParams(-1,-1))
        content.addView(header,FrameLayout.LayoutParams(-1,-1))
        body.setContent {
            val settings by preferences.settings.collectAsState()
            val horizontal=settings.pdfLayout==PdfReadingLayout.HORIZONTAL || !supportsAdvanced
            SideEffect {
                if(container!=null) {
                    if(horizontal && container.visibility==View.VISIBLE) requestedPage=page
                    if(!horizontal && container.visibility==View.INVISIBLE) viewer?.go(page)
                    container.visibility=if(horizontal) View.INVISIBLE else View.VISIBLE
                }
                body.visibility=if(horizontal) View.VISIBLE else View.GONE
            }
            ReadXTheme(settings,reading=true) {
                if(horizontal) book?.let {loaded->HorizontalPdfScreen(loaded,repository,document,supportsAdvanced,annotations,requestedPage,
                    {current,count->page=current;pageCount=count}, {selection=it}, {chrome=!chrome})}
            }
        }
        setContentView(root)
        lifecycleScope.launch {repeatOnLifecycle(Lifecycle.State.STARTED) {
            repository.dao.annotationsForBook(id).collect {annotations=it;viewer?.annotationsChanged(it)}
        }}
        lifecycleScope.launch {
            val loaded=repository.dao.book(id) ?: run {finish();return@launch};book=loaded;page=requestedPage ?: loaded.chapterIndex
            if(container!=null) {
                val existing=supportFragmentManager.findFragmentByTag("pdf") as? ReadXPdfFragment
                viewer=existing ?: ReadXPdfFragment().apply {arguments=Bundle().apply {putString("bookId",id);putInt("initialPage",page)}}
                if(existing==null) supportFragmentManager.beginTransaction().replace(container.id,viewer!!,"pdf").commitNow()
                if(existing==null) viewer!!.documentUri=FileProvider.getUriForFile(this@PdfActivity,packageName+".files",repository.source(loaded))
                else {document=viewer!!.currentDocument();pageCount=document?.pageCount ?: 0}
                viewer?.annotationsChanged(annotations)
            }
        }
    }
    fun loaded(pdf: PdfDocument) {document=pdf;pageCount=pdf.pageCount;status="";book?.let {loaded->lifecycleScope.launch {repository.dao.saveTotalUnits(loaded.id,pdf.pageCount)}}}
    fun toggleChrome() {chrome=!chrome}
    fun openMarked(annotation: Annotation) {selection=PdfSelection(annotation.quote,PdfLocators.decode(annotation.locator),listOf(annotation.id))}
    fun isHorizontal() = preferences.settings.value.pdfLayout==PdfReadingLayout.HORIZONTAL
    fun pageChanged(index: Int) {if(preferences.settings.value.pdfLayout==PdfReadingLayout.VERTICAL) page=index}
    fun annotate(kind: String, picked: TextSelection) {
        val pdf=document ?: return
        lifecycleScope.launch {
            try {
                val boxes=picked.bounds.take(2000).mapNotNull {rect->val info=pdf.getPageInfo(rect.pageNum);PdfLocators.normalize(rect.pageNum,RectF(rect.left,rect.top,rect.right,rect.bottom),info.width,info.height)}
                val selected=PdfSelection(picked.text.toString().take(16384),boxes)
                if(boxes.isNotEmpty()) selection=selected
            } catch(e: CancellationException) {throw e} catch(e: Exception) {failed(e)}
        }
    }
    private fun saveAnnotation(kind: String, picked: PdfSelection,note: String) {
        val loaded=book ?: return;val page=picked.boxes.firstOrNull()?.page ?: return
        lifecycleScope.launch {repository.dao.upsertAnnotation(Annotation(UUID.randomUUID().toString(),loaded.id,kind,page,0f,"第${page+1}页",picked.quote,note,PdfLocators.encode(picked.boxes),color=MarkColor.normalize(preferences.settings.value.annotationColor)));Toast.makeText(this@PdfActivity,"已保存批注",Toast.LENGTH_SHORT).show()}
    }
    fun failed(cause: Throwable) {error=cause.message ?: "文档损坏、加密方式不支持或无法读取"}
    companion object {private const val PDF_CONTAINER_ID=0x71A001}
}

@SuppressLint("NewApi")
class ReadXPdfFragment : PdfViewerFragment() {
    private var currentView: PdfView? = null
    private var saveJob: Job? = null
    private var overlay: PdfAnnotationOverlay? = null
    private var marks: List<Annotation> = emptyList()
    private var restoringSavedViewport = false
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        restoringSavedViewport = savedInstanceState != null
        super.onViewCreated(view, savedInstanceState)
    }
    private val repository get() = (requireActivity().application as ReadXApplication).repository
    override fun onPdfViewCreated(pdfView: PdfView) {
        currentView = pdfView
        pdfView.verticalAlignment = PdfView.VERTICAL_ALIGNMENT_TOP
        val overlayView=PdfAnnotationOverlay(requireContext())
        overlay=overlayView;overlayView.updateAnnotations(marks)
        val parent=pdfView.parent
        if(parent is FrameLayout) parent.addView(overlayView,FrameLayout.LayoutParams(-1,-1))
        else {pdfView.overlay.add(overlayView);pdfView.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->overlayView.layout(0,0,pdfView.width,pdfView.height)}}
        var touchX=0f;var touchY=0f;var touchTime=0L
        pdfView.setOnTouchListener {_,event->
            if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN) {touchX=event.x;touchY=event.y;touchTime=event.eventTime}
            if(event.actionMasked==android.view.MotionEvent.ACTION_UP && event.eventTime-touchTime<300 && kotlin.math.abs(event.x-touchX)<12 && kotlin.math.abs(event.y-touchY)<12 && pdfView.currentSelection==null) {
                overlayView.hit(event.x,event.y)?.let {(activity as? PdfActivity)?.openMarked(it);return@setOnTouchListener true}
            }
            false
        }
        pdfView.addSelectionMenuItemPreparer(object: PdfView.SelectionMenuItemPreparer {
            override fun onPrepareSelectionMenuItems(components: MutableList<androidx.pdf.selection.ContextMenuComponent>) {
                if(pdfView.currentSelection !is TextSelection) return
                listOf("HIGHLIGHT" to "荧光笔","UNDERLINE" to "下划线","NOTE" to "写批注").forEach {(kind,label)->
                    if(components.none {it.key=="readx-$kind"}) components.add(SelectionMenuComponent("readx-$kind",label,label) {
                        (pdfView.currentSelection as? TextSelection)?.let {(activity as? PdfActivity)?.annotate(kind,it)}
                        close();pdfView.clearCurrentSelection()
                    })
                }
            }
        })
        pdfView.addOnViewportChangedListener(object : PdfView.OnViewportChangedListener {
            override fun onViewportChanged(firstVisiblePage: Int, visiblePagesCount: Int, pageLocations: SparseArray<RectF>, zoomLevel: Float) {
                overlay?.updateViewport(pageLocations)
                if (firstVisiblePage < 0 || (activity as? PdfActivity)?.isHorizontal()==true) return
                (activity as? PdfActivity)?.pageChanged(firstVisiblePage)
                saveJob?.cancel()
                val id = requireArguments().getString("bookId")!!
                saveJob = lifecycleScope.launch { delay(350); repository.dao.savePosition(id, firstVisiblePage, 0f, System.currentTimeMillis()) }
            }
        })
    }
    override fun onLoadDocumentSuccess(document: PdfDocument) {
        (activity as? PdfActivity)?.loaded(document)
        // The fragment sets PdfView.pdfDocument immediately after this callback.
        if (!restoringSavedViewport) currentView?.post {currentView?.scrollToPage(requireArguments().getInt("initialPage").coerceIn(0,document.pageCount-1))}
    }
    override fun onLoadDocumentError(error: Throwable) { (activity as? PdfActivity)?.failed(error) }
    override fun onRequestImmersiveMode(enterImmersive: Boolean) {(activity as? PdfActivity)?.toggleChrome()}
    override fun onLinkClicked(externalLink: ExternalLink): Boolean {
        Toast.makeText(context, "外部链接未打开：本版本仅访问本地内容", Toast.LENGTH_SHORT).show(); return true
    }
    fun go(page: Int) {saveJob?.cancel();currentView?.scrollToPage(page)}
    fun annotationsChanged(values: List<Annotation>) {marks=values;overlay?.updateAnnotations(values)}
    fun currentDocument(): PdfDocument?=currentView?.pdfDocument
    fun currentDocumentPageCount(): Int? = currentView?.pdfDocument?.pageCount
    override fun onStop() {
        val id = arguments?.getString("bookId")
        val page = currentView?.firstVisiblePage
        if (id != null && page != null && page >= 0 && (activity as? PdfActivity)?.isHorizontal()!=true) {
            saveJob?.cancel()
            repository.persistPosition(id, page, 0f)
        }
        super.onStop()
    }
    override fun onDestroyView() { overlay=null;currentView = null; super.onDestroyView() }
}

@Composable
private fun PageJumpDialog(count: Int, go: (Int) -> Unit, dismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val number = input.toIntOrNull()
    AlertDialog(onDismissRequest = dismiss, title = { Text("跳转页码") }, text = { OutlinedTextField(input, { input = it.filter(Char::isDigit).take(8) }, label = { Text("1–$count") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { go(number!! - 1) }, enabled = number != null && number in 1..count) { Text("跳转") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
