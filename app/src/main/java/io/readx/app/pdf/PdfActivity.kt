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
import android.view.GestureDetector
import android.view.MotionEvent
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
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
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
import io.readx.app.ui.ReadXDesign
import io.readx.app.ui.ReadXTheme
import io.readx.app.ui.ReaderPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

@SuppressLint("NewApi") // The advanced fragment is only instantiated after capability checks.
class PdfActivity : AppCompatActivity() {
    private var cropConfig by mutableStateOf(PdfCropConfig())
    private var cropOpen by mutableStateOf(false)
    private var cropSearchOpen by mutableStateOf(false)
    private var searchBoxes by mutableStateOf<List<PdfBox>>(emptyList())
    private var originalFraction by mutableFloatStateOf(0f)
    private var book by mutableStateOf<Book?>(null)
    private var pageCount by mutableIntStateOf(0)
    internal var page by mutableIntStateOf(0)
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
    internal var requestedPage by mutableStateOf<Int?>(null)
    private var requestedPageJob: Job? = null
    private var topControlBounds:androidx.compose.ui.geometry.Rect?=null
    private var bottomControlBounds:androidx.compose.ui.geometry.Rect?=null
    private val exportModel by lazy {androidx.lifecycle.ViewModelProvider(this)[io.readx.app.ui.LibraryViewModel::class.java]}
    private var flashBoxes by mutableStateOf<List<PdfBox>>(emptyList())
    private var focusJob:Job?=null
    private var pendingAnnotationId:String?=null
    private var pdfPositionJob: Job? = null
    private var viewer: ReadXPdfFragment? = null
    private val repository get() = (application as ReadXApplication).repository
    private val preferences by lazy { ReaderPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        val id=intent.getStringExtra("bookId") ?: run {finish();return}
        pendingAnnotationId=intent.getStringExtra("annotationId")
        requestedPage=if(intent.hasExtra("page")) intent.getIntExtra("page",0) else null
        val forceBasic=io.readx.app.BuildConfig.DEBUG && intent.getBooleanExtra("forceBasicForTest",false)
        val supportsAdvanced=!forceBasic && Build.VERSION.SDK_INT>=31 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S)>=13
        var gestureBottom = 0
        val navigationPaint = android.graphics.Paint()
        // Native root owns PDF insets. Only paint its reserved system-bar strip; never add another content inset.
        val root=object : LinearLayout(this) {
            override fun dispatchDraw(canvas: android.graphics.Canvas) {
                super.dispatchDraw(canvas)
                if (gestureBottom > 0) canvas.drawRect(0f, (height - gestureBottom).toFloat(), width.toFloat(), height.toFloat(), navigationPaint)
            }
        }.apply {orientation=LinearLayout.VERTICAL}
        ViewCompat.setOnApplyWindowInsetsListener(root) {view,insets->
            val safe=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime=insets.getInsets(WindowInsetsCompat.Type.ime())
            gestureBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(safe.left,safe.top,safe.right,maxOf(safe.bottom,ime.bottom))
            view.invalidate()
            WindowInsetsCompat.CONSUMED
        }
        val container=if(supportsAdvanced) FragmentContainerView(this).apply {this.id=PDF_CONTAINER_ID} else null
        val header=ComposeView(this).apply {this.id=PDF_CONTROLS_ID;setContent {
            val settings by preferences.settings.collectAsState()
            ReadXTheme(if(settings.pdfInverted) settings.copy(theme=ReadingTheme.NIGHT) else settings,reading=true) {
                val background=MaterialTheme.colorScheme.background.toArgb()
                val navigationColor = if(chrome) MaterialTheme.colorScheme.surfaceContainerHigh.toArgb() else background
                SideEffect {
                    viewer?.setInverted(settings.pdfInverted)
                    root.setBackgroundColor(background)
                    navigationPaint.color = navigationColor
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = navigationColor
                    root.invalidate()
                }
                Box(Modifier.fillMaxSize()) {
                    if(!chrome) Text(if(pageCount>0) "${page+1} / $pageCount" else "加载中…",Modifier.align(Alignment.BottomEnd).padding(end=20.dp,bottom=5.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    AnimatedVisibility(chrome,modifier=Modifier.align(Alignment.TopStart).onGloballyPositioned {topControlBounds=it.boundsInRoot()}) {
                        Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surfaceContainer.copy(alpha=.94f),contentColor=MaterialTheme.colorScheme.onSurface,modifier=Modifier.padding(10.dp)) {IconButton(onClick={finish()}) {Icon(Icons.AutoMirrored.Rounded.ArrowBack,"返回书库")}}
                    }
                    AnimatedVisibility(chrome,modifier=Modifier.align(Alignment.BottomCenter).onGloballyPositioned {bottomControlBounds=it.boundsInRoot()}) {
                        Surface(shape=ReadXDesign.readerPanelShape,color=MaterialTheme.colorScheme.surfaceContainerHigh,contentColor=MaterialTheme.colorScheme.onSurface) {Column {
                            io.readx.app.ui.ReadingProgressControl(
                                if(pageCount>0) page+1 else null,pageCount.takeIf {it>0},"原 PDF 页码",
                                {target->requestJump((target-1).coerceIn(0,(pageCount-1).coerceAtLeast(0)))},
                                {requestJump((page-1).coerceAtLeast(0))},
                                {requestJump((page+1).coerceAtMost((pageCount-1).coerceAtLeast(0)))},
                                page>0,page<pageCount-1)
                            if(colorPanel) Column(Modifier.padding(16.dp)) {
                                Text("标记颜色",style=MaterialTheme.typography.titleSmall);MarkColorPicker(settings.annotationColor) {preferences.update(settings.copy(annotationColor=it))}
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Text("PDF 反色（夜间模式）",Modifier.weight(1f))
                                    Switch(checked=settings.pdfInverted,onCheckedChange={preferences.update(settings.copy(pdfInverted=it))},modifier=Modifier.testTag("pdf-night-switch"))
                                }
                                Text("仅改变阅读显示，不修改原 PDF 或批注坐标。",style=MaterialTheme.typography.bodySmall)
                            }
                            Row(Modifier.fillMaxWidth().height(64.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                                IconButton(onClick={notesOpen=true}) {Icon(Icons.Rounded.EditNote,"本书批注")}
                                IconButton(onClick={if(pageCount>0) jump=true},enabled=pageCount>0) {Icon(Icons.Rounded.Numbers,"跳转页码")}
                                IconButton(onClick={requestedPage=page;preferences.update(settings.copy(pdfLayout=if(settings.pdfLayout==PdfReadingLayout.VERTICAL) PdfReadingLayout.HORIZONTAL else PdfReadingLayout.VERTICAL))}) {Icon(if(settings.pdfLayout==PdfReadingLayout.HORIZONTAL) Icons.Rounded.ViewAgenda else Icons.Rounded.ViewCarousel,"切换 PDF 横向或纵向阅读")}
                                FilledTonalIconToggleButton(checked=settings.pdfInverted,onCheckedChange={preferences.update(settings.copy(pdfInverted=it))}) {Icon(Icons.Rounded.Brightness6,"PDF 夜间反色")}
                                FilledTonalIconToggleButton(checked=colorPanel,onCheckedChange={colorPanel=it}) {Icon(Icons.Rounded.Palette,"标记颜色")}
                                IconButton(onClick={requestedPage=page;cropOpen=true}) {Icon(Icons.Rounded.Crop,"PDF 裁边")}
                                if(supportsAdvanced) IconButton(onClick={if(cropConfig.enabled || settings.pdfLayout==PdfReadingLayout.HORIZONTAL) cropSearchOpen=true else viewer?.isTextSearchActive=true}) {Icon(Icons.Rounded.Search,"PDF 搜索")}
                            }
                        }}
                    }
                }
                if(cropOpen) book?.let {loaded->PdfCropDialog(loaded,page,document,supportsAdvanced,repository,cropConfig,{value->requestedPage=page;cropConfig=value;lifecycleScope.launch {repository.dao.savePdfCrop(loaded.id,value.json())}},{cropOpen=false})}
                if(cropSearchOpen) document?.let {pdf->CroppedPdfSearchDialog(pdf,{boxes->searchBoxes=boxes;boxes.firstOrNull()?.let {hit->requestedPage=hit.page;originalFraction=hit.top;viewer?.go(hit.page)}},{cropSearchOpen=false})}
                if(jump) PageJumpDialog(pageCount,{target->requestJump(target);jump=false}) {jump=false}
                selection?.let {picked->PdfSelectionPopup(picked,{selection=null}) {
                    if(picked.quote.isBlank()) Text("区域选区 · 非 OCR 文字",style=MaterialTheme.typography.labelSmall)
                    val old=annotations.firstOrNull {it.id in picked.existingIds}
                    io.readx.app.ui.SelectionBubbleActions(old?.color ?: settings.annotationColor,
                        {hex->preferences.update(settings.copy(annotationColor=hex));old?.let {lifecycleScope.launch {repository.dao.replaceAnnotation(it.copy(color=hex,updatedAt=System.currentTimeMillis()))}}},
                        if(picked.quote.isNotBlank()) {{val clipboard=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager;clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ReadX 选段",picked.quote));selection=null}} else null,
                        {saveAnnotation("HIGHLIGHT",picked,"");selection=null},{saveAnnotation("UNDERLINE",picked,"");selection=null},
                        {noteSelection=picked;selection=null},annotations.any {it.id in picked.existingIds && it.kind=="NOTE"},
                        if(picked.existingIds.isNotEmpty()) {{lifecycleScope.launch {repository.dao.cancelMarkers(id,picked.existingIds)};selection=null}} else null,{selection=null})
                }}
                noteSelection?.let {picked->AnnotationEditor(picked.quote.ifBlank {"PDF 区域批注"},annotations.firstOrNull {it.id in picked.existingIds && it.kind=="NOTE"}?.note.orEmpty()) {note->if(note!=null) {
                    val old=annotations.firstOrNull {it.id in picked.existingIds && it.kind=="NOTE"}
                    if(old!=null) lifecycleScope.launch {repository.dao.updateAnnotationNote(old.id,note)} else saveAnnotation("NOTE",picked,note)
                };noteSelection=null} }
                if(notesOpen) ModalBottomSheet(onDismissRequest={notesOpen=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
                    Column(Modifier.fillMaxHeight().padding(horizontal=20.dp)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Text("本书批注",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall);book?.let {io.readx.app.ui.AnnotationExportActions(it,exportModel)};TextButton(onClick={notesOpen=false}) {Text("完成")}}
                        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            items(annotations.size,key={annotations[it].id}) {i->val mark=annotations[i];io.readx.app.ui.AnnotationCard(mark,
                                {focusAnnotation(mark);notesOpen=false},
                                {lifecycleScope.launch {repository.dao.deleteAnnotation(mark.id)}},
                                {note->lifecycleScope.launch {repository.dao.updateAnnotationNote(mark.id,note)}})
                            }
                        }
                    }
                }
                error?.let {message->AlertDialog(onDismissRequest={error=null},title={Text("PDF 打开失败")},text={Text(message)},confirmButton={TextButton(onClick={error=null;finish()}) {Text("返回书库")}})}
            }
        }}
        // The full-window Compose chrome is visually transparent outside controls, but its Android
        // host can still win touch dispatch. Route the reading stream explicitly in those regions.
        val content=object:FrameLayout(this@PdfActivity) {
            override fun dispatchTouchEvent(event:MotionEvent):Boolean {
                val overlayOpen=noteSelection!=null || cropOpen || cropSearchOpen || notesOpen || jump || error!=null
                val density=resources.displayMetrics.density
                val point=androidx.compose.ui.geometry.Offset(event.x,event.y)
                val onControls=chrome && (topControlBounds?.contains(point)==true || bottomControlBounds?.contains(point)==true || (bottomControlBounds==null && event.y>height-76*density))
                if(!overlayOpen && !onControls) {
                    val reading=(0 until childCount).map {getChildAt(it)}.lastOrNull {it!==header && it.visibility==View.VISIBLE}
                    if(reading!=null)return reading.dispatchTouchEvent(event)
                }
                return super.dispatchTouchEvent(event)
            }
        }
        root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        if(container!=null) content.addView(container,FrameLayout.LayoutParams(-1,-1))
        val body=ComposeView(this).apply {this.id=PDF_BODY_ID}
        content.addView(body,FrameLayout.LayoutParams(-1,-1))
        content.addView(header,FrameLayout.LayoutParams(-1,-1))
        body.setContent {
            val settings by preferences.settings.collectAsState()
            val custom=cropConfig.enabled || !supportsAdvanced
            val horizontal=settings.pdfLayout==PdfReadingLayout.HORIZONTAL || custom
            SideEffect {
                if(container!=null) {
                    if(horizontal && container.visibility==View.VISIBLE) requestedPage=page
                    if(!horizontal && container.visibility==View.INVISIBLE) viewer?.go(page,originalFraction)
                    container.visibility=if(horizontal) View.INVISIBLE else View.VISIBLE
                }
                body.visibility=if(horizontal) View.VISIBLE else View.GONE
            }
            ReadXTheme(if(settings.pdfInverted) settings.copy(theme=ReadingTheme.NIGHT) else settings,reading=true) {
                if(horizontal) book?.let {loaded->
                    if(custom) CroppedPdfScreen(loaded,repository,document,supportsAdvanced,cropConfig,settings.pdfLayout==PdfReadingLayout.VERTICAL,annotations,requestedPage,originalFraction,
                        {current,count,fraction->page=current;pageCount=count;originalFraction=fraction;pdfPositionJob?.cancel();pdfPositionJob=lifecycleScope.launch {delay(350);repository.dao.savePosition(loaded.id,current,fraction,System.currentTimeMillis())}},
                        {selection=it},{if(selection!=null) selection=null else chrome=!chrome},searchBoxes+flashBoxes,{Toast.makeText(this,it,Toast.LENGTH_SHORT).show()},settings.pdfInverted,selection)
                    else HorizontalPdfScreen(loaded,repository,document,supportsAdvanced,annotations,requestedPage,
                        {current,count->page=current;pageCount=count}, {selection=it}, {if(selection!=null) selection=null else chrome=!chrome},flashBoxes,settings.pdfInverted,selection)
                }
            }
        }
        setContentView(root)
        lifecycleScope.launch {repeatOnLifecycle(Lifecycle.State.STARTED) {
            repository.dao.annotationsForBook(id).collect {annotations=it;viewer?.annotationsChanged(it);pendingAnnotationId?.let {target->it.firstOrNull {a->a.id==target}?.let {a->pendingAnnotationId=null;focusAnnotation(a)}}}
        }}
        lifecycleScope.launch {
            val loaded=repository.dao.book(id) ?: run {finish();return@launch};book=loaded;page=requestedPage ?: loaded.chapterIndex;originalFraction=loaded.scrollFraction;cropConfig=PdfCropConfig.parse(loaded.pdfCropConfig)
            if(container!=null) {
                // The advanced PdfView derives bitmap size from its viewport. Do not load its
                // document before the actual safe-area container is measured: a zero-width
                // first frame can otherwise reach the sandbox renderer as a 0 x 0 request.
                container.awaitPdfViewport()
                val existing=supportFragmentManager.findFragmentByTag("pdf") as? ReadXPdfFragment
                viewer=existing ?: ReadXPdfFragment().apply {arguments=Bundle().apply {putString("bookId",id);putInt("initialPage",page)}}
                if(existing==null) supportFragmentManager.beginTransaction().replace(container.id,viewer!!,"pdf").commitNow()
                if(existing==null) {
                    viewer!!.requireView().awaitPdfViewport()
                    viewer!!.documentUri=FileProvider.getUriForFile(this@PdfActivity,packageName+".files",repository.source(loaded))
                }
                else {document=viewer!!.currentDocument();pageCount=document?.pageCount ?: 0}
                viewer?.annotationsChanged(annotations)
            }
        }
    }
    internal val currentOriginalPosition:Pair<Int,Float> get()=page to originalFraction
    override fun onStop() {pdfPositionJob?.cancel();book?.let {repository.persistPosition(it.id,page,originalFraction)};super.onStop()}
    private var pendingFocusAnnotation: Annotation? = null
    private fun focusAnnotation(annotation:Annotation) {
        val boxes=PdfLocators.decode(annotation.locator)
        requestedPage=annotation.chapter;originalFraction=boxes.firstOrNull()?.top ?: annotation.fraction
        if (document != null || isHorizontal() || viewer?.currentDocument() != null) {
            viewer?.go(annotation.chapter,originalFraction,smooth=true)
        } else {
            pendingFocusAnnotation = annotation
        }
        focusJob?.cancel();focusJob=lifecycleScope.launch {
            withTimeoutOrNull(5000) {androidx.compose.runtime.snapshotFlow {pageCount>0 && page==annotation.chapter}.filter {it}.first()}
            repeat(3) {flashBoxes=boxes;viewer?.flash(boxes);delay(240);flashBoxes=emptyList();viewer?.flash(emptyList());delay(140)}
        }
    }
    fun loaded(pdf: PdfDocument) {
        document=pdf;pageCount=pdf.pageCount;status=""
        book?.let {loaded->lifecycleScope.launch {repository.dao.saveTotalUnits(loaded.id,pdf.pageCount)}}
        pendingFocusAnnotation?.let { a ->
            pendingFocusAnnotation = null
            focusAnnotation(a)
        }
    }
    fun toggleChrome() {chrome=!chrome}
    fun openMarked(annotation: Annotation) {selection=PdfSelection(annotation.quote,PdfLocators.decode(annotation.locator),listOf(annotation.id),viewer?.selectionWindowBounds(PdfLocators.decode(annotation.locator)))}
    fun isHorizontal() = preferences.settings.value.pdfLayout==PdfReadingLayout.HORIZONTAL || cropConfig.enabled
    fun pageChanged(index: Int, fraction:Float=0f) {if(!isHorizontal()) {page=index;originalFraction=fraction}}
    fun requestJump(targetIndex: Int) {
        val target = targetIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        page = target
        requestedPage = target
        originalFraction = 0f
        viewer?.go(target)
        requestedPageJob?.cancel()
        requestedPageJob = lifecycleScope.launch {
            delay(1500)
            if (requestedPage == target) requestedPage = null
        }
    }
    fun clearCustomSelection() { selection = null }
    fun annotate(kind: String, picked: TextSelection) {
        val pdf=document ?: return
        lifecycleScope.launch {
            try {
                val boxes=picked.bounds.take(2000).mapNotNull {rect->val info=pdf.getPageInfo(rect.pageNum);PdfLocators.normalize(rect.pageNum,RectF(rect.left,rect.top,rect.right,rect.bottom),info.width,info.height)}
                val selected=PdfSelection(picked.text.toString().take(16384),boxes,windowBounds=viewer?.selectionWindowBounds(boxes))
                if(boxes.isNotEmpty()) {
                    if(kind=="NOTE") {
                        noteSelection=selected
                    } else {
                        saveAnnotation(kind,selected,"")
                    }
                }
            } catch(e: CancellationException) {throw e} catch(e: Exception) {failed(e)}
        }
    }
    private fun saveAnnotation(kind: String, picked: PdfSelection,note: String) {
        val loaded=book ?: return;val page=picked.boxes.firstOrNull()?.page ?: return
        lifecycleScope.launch {repository.dao.upsertAnnotation(Annotation(UUID.randomUUID().toString(),loaded.id,kind,page,0f,"第${page+1}页",picked.quote,note,PdfLocators.encode(picked.boxes),color=MarkColor.normalize(preferences.settings.value.annotationColor)));Toast.makeText(this@PdfActivity,"已保存批注",Toast.LENGTH_SHORT).show()}
    }
    fun failed(cause: Throwable) {error=cause.message ?: "文档损坏、加密方式不支持或无法读取"}
    companion object {private const val PDF_CONTAINER_ID=0x71A001;private const val PDF_CONTROLS_ID=0x71A002;private const val PDF_BODY_ID=0x71A003}
}

@SuppressLint("NewApi")
class ReadXPdfFragment : PdfViewerFragment() {
    private var currentView: PdfView? = null
    private var saveJob: Job? = null
    private var overlay: PdfAnnotationOverlay? = null
    private var marks: List<Annotation> = emptyList()
    private var restoringSavedViewport = false
    private var inverted = false
    private var filteredView:PdfView?=null
    fun setInverted(value:Boolean) {
        if(inverted==value && filteredView===currentView)return
        inverted=value
        filteredView=currentView
        currentView?.setRenderEffect(if(value) PdfNightMode.effect() else null)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        restoringSavedViewport = savedInstanceState != null
        super.onViewCreated(view, savedInstanceState)
    }
    private val repository get() = (requireActivity().application as ReadXApplication).repository
    override fun onPdfViewCreated(pdfView: PdfView) {
        currentView = pdfView
        setInverted(ReaderPreferences(requireContext()).settings.value.pdfInverted)
        pdfView.verticalAlignment = PdfView.VERTICAL_ALIGNMENT_TOP
        val overlayView=PdfAnnotationOverlay(requireContext())
        overlay=overlayView;overlayView.updateAnnotations(marks)
        val parent=pdfView.parent
        if(parent is FrameLayout) parent.addView(overlayView,FrameLayout.LayoutParams(-1,-1))
        else {pdfView.overlay.add(overlayView);pdfView.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->overlayView.layout(0,0,pdfView.width,pdfView.height)}}
        // The annotation listener replaces the fragment's internal tap detector. Handle single taps
        // explicitly instead of using immersive-mode state, which also changes during scrolling.
        val taps = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                val host = activity as? PdfActivity ?: return false
                if (host.isHorizontal() || pdfView.currentSelection != null) return false
                val marked = overlayView.hit(event.x, event.y)
                if (marked != null) host.openMarked(marked) else host.toggleChrome()
                return false
            }
        })
        pdfView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                (activity as? PdfActivity)?.let { host ->
                    if (host.requestedPage != null) host.requestedPage = null
                }
            }
            taps.onTouchEvent(event)
            // Never consume the PDF stream: scrolling, pinch/double-tap zoom and selection stay native.
            false
        }
        pdfView.addSelectionMenuItemPreparer(object: PdfView.SelectionMenuItemPreparer {
            override fun onPrepareSelectionMenuItems(components: MutableList<androidx.pdf.selection.ContextMenuComponent>) {
                (activity as? PdfActivity)?.clearCustomSelection()
                if(pdfView.currentSelection !is TextSelection) {
                    if(pdfView.currentSelection!=null) {
                        pdfView.clearCurrentSelection()
                        Toast.makeText(requireContext(),"此处没有可选文字；扫描页需先 OCR",Toast.LENGTH_SHORT).show()
                    }
                    return
                }
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
                val location=pageLocations[firstVisiblePage]
                val fraction=if(location!=null && location.height()>0) ((-location.top)/location.height()).coerceIn(0f,1f) else 0f
                val host = activity as? PdfActivity
                val req = host?.requestedPage
                if (req != null) {
                    if (firstVisiblePage == req) {
                        host.requestedPage = null
                        host.pageChanged(firstVisiblePage, fraction)
                    }
                } else {
                    host?.pageChanged(firstVisiblePage, fraction)
                }
                saveJob?.cancel()
                val id = requireArguments().getString("bookId")!!
                saveJob = lifecycleScope.launch { delay(350); repository.dao.savePosition(id, firstVisiblePage, fraction, System.currentTimeMillis()) }
            }
        })
    }
    private var pendingTarget: Pair<Int, Float>? = null

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        (activity as? PdfActivity)?.loaded(document)
        val pending = pendingTarget
        pendingTarget = null
        if (pending != null) {
            go(pending.first, pending.second, smooth = false)
        } else if (!restoringSavedViewport) {
            val initial = requireArguments().getInt("initialPage").coerceIn(0, (document.pageCount - 1).coerceAtLeast(0))
            currentView?.post {
                try { currentView?.scrollToPage(initial) } catch(_: Throwable) {}
            }
        }
    }
    override fun onLoadDocumentError(error: Throwable) { (activity as? PdfActivity)?.failed(error) }
    override fun onRequestImmersiveMode(enterImmersive: Boolean) {
        // ReadX owns its controls. AndroidX scroll-driven immersive requests must not toggle them.
    }
    override fun onLinkClicked(externalLink: ExternalLink): Boolean {
        Toast.makeText(context, "外部链接未打开：本版本仅访问本地内容", Toast.LENGTH_SHORT).show(); return true
    }
    fun go(page: Int, fraction: Float = 0f, smooth: Boolean = false) {
        saveJob?.cancel()
        val pdf = currentView?.pdfDocument
        if (pdf == null) {
            pendingTarget = page to fraction
            return
        }
        if (smooth) currentView?.let { it.alpha = .2f; it.animate().alpha(1f).setDuration(180).start() }
        val targetPage = page.coerceIn(0, (pdf.pageCount - 1).coerceAtLeast(0))
        if (fraction <= 0f) {
            currentView?.post {
                try { currentView?.scrollToPage(targetPage) } catch(_: Throwable) {}
            }
        } else {
            lifecycleScope.launch {
                try {
                    val info = pdf.getPageInfo(targetPage)
                    currentView?.post {
                        try {
                            currentView?.scrollToPosition(androidx.pdf.PdfPoint(targetPage, 0f, info.height * fraction))
                        } catch(_: Throwable) {
                            try { currentView?.scrollToPage(targetPage) } catch(_: Throwable) {}
                        }
                    }
                } catch(_: Throwable) {
                    currentView?.post {
                        try { currentView?.scrollToPage(targetPage) } catch(_: Throwable) {}
                    }
                }
            }
        }
    }
    fun annotationsChanged(values: List<Annotation>) {marks=values;overlay?.updateAnnotations(values)}
    fun flash(boxes:List<PdfBox>) {overlay?.flash(boxes)}
    fun selectionWindowBounds(boxes:List<PdfBox>)=overlay?.selectionWindowBounds(boxes)
    fun currentDocument(): PdfDocument?=currentView?.pdfDocument
    fun currentDocumentPageCount(): Int? = currentView?.pdfDocument?.pageCount
    override fun onStop() {
        val id = arguments?.getString("bookId")
        val page = currentView?.firstVisiblePage
        if (id != null && page != null && page >= 0 && (activity as? PdfActivity)?.isHorizontal()!=true) {
            saveJob?.cancel()
            // PdfActivity persists page and its original-coordinate fraction on stop.
        }
        super.onStop()
    }
    override fun onDestroyView() { overlay=null;filteredView=null;currentView = null; super.onDestroyView() }
}

@Composable
private fun PageJumpDialog(count: Int, go: (Int) -> Unit, dismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val number = input.toIntOrNull()
    AlertDialog(onDismissRequest = dismiss, title = { Text("跳转页码") }, text = { OutlinedTextField(input, { input = it.filter(Char::isDigit).take(8) }, label = { Text("1–$count") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { go(number!! - 1) }, enabled = number != null && number in 1..count) { Text("跳转") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

/** Suspend until the actual host/fragment viewport has a positive measured size. */
private suspend fun View.awaitPdfViewport() {
    withTimeout(5000) {
        suspendCancellableCoroutine<Unit> { continuation ->
            doOnLayout { measured ->
                if (continuation.isActive) {
                    if (measured.width > 0 && measured.height > 0) continuation.resumeWith(Result.success(Unit))
                    else continuation.resumeWith(Result.failure(IllegalStateException("PDF 阅读区域尚未完成有效测量")))
                }
            }
        }
    }
}
