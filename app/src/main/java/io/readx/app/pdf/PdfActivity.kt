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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@SuppressLint("NewApi") // PdfViewerFragment is only instantiated after the runtime extension check.
class PdfActivity : AppCompatActivity() {
    private var book by mutableStateOf<Book?>(null)
    private var pageCount by mutableIntStateOf(0)
    private var page by mutableIntStateOf(0)
    private var status by mutableStateOf("正在打开 PDF…")
    private var jump by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var viewer: ReadXPdfFragment? = null
    private val repository get() = (application as ReadXApplication).repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val id = intent.getStringExtra("bookId") ?: run { finish(); return }
        val forceBasicForTest = io.readx.app.BuildConfig.DEBUG && intent.getBooleanExtra("forceBasicForTest", false)
        val supportsAdvanced = !forceBasicForTest && Build.VERSION.SDK_INT >= 31 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
        // Create the container before suspending, so restored fragments always have a host view.
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Native root owns system/cutout/IME insets; child Compose/PDF views must not add them again.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        val header = ComposeView(this).apply { setContent {
            val settings = remember { ReaderPreferences(this@PdfActivity) }.settings.collectAsState()
            ReadXTheme(settings.value.theme) {
                Column {
                    TopAppBar(modifier = Modifier.height(56.dp), windowInsets = WindowInsets(0), title = {
                        Column {
                            Text(book?.title ?: "PDF", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(if (pageCount > 0) "第 " + (page + 1) + " / " + pageCount + " 页" else status, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                        navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回书库") } },
                        actions = {
                            if (supportsAdvanced) {
                                IconButton(onClick = { if (pageCount > 0) viewer?.isTextSearchActive = true }) { Icon(Icons.Rounded.Search, "PDF 搜索") }
                                IconButton(onClick = { if (pageCount > 0) jump = true }) { Icon(Icons.Rounded.Numbers, "跳转页码") }
                            }
                        })
                }
                if (jump) PageJumpDialog(pageCount, { viewer?.go(it); jump = false }) { jump = false }
                error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("PDF 打开失败") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null; finish() }) { Text("返回书库") } }) }
            }
        } }
        root.addView(header, LinearLayout.LayoutParams(-1, (56 * resources.displayMetrics.density).toInt()))
        val container = if (supportsAdvanced) FragmentContainerView(this).apply {
            this.id = PDF_CONTAINER_ID; root.addView(this, LinearLayout.LayoutParams(-1, 0, 1f))
        } else null
        if (!supportsAdvanced) {
            status = "基础 PDF 模式 · 当前系统不支持高级查看器"
            val body = ComposeView(this).apply { setContent {
                val settings = remember { ReaderPreferences(this@PdfActivity) }.settings.collectAsState()
                ReadXTheme(settings.value.theme) {
                    book?.let { FallbackPdfScreen(it, repository) { current, count -> page = current; pageCount = count } }
                }
            } }
            root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        lifecycleScope.launch {
            val loaded = repository.dao.book(id) ?: run { finish(); return@launch }
            book = loaded
            if (container != null) {
                val existing = supportFragmentManager.findFragmentByTag("pdf") as? ReadXPdfFragment
                viewer = existing ?: ReadXPdfFragment().apply { arguments = Bundle().apply { putString("bookId", id); putInt("initialPage", loaded.chapterIndex) } }
                if (existing == null) supportFragmentManager.beginTransaction().replace(container.id, viewer!!, "pdf").commitNow()
                if (existing == null) viewer!!.documentUri = FileProvider.getUriForFile(this@PdfActivity, packageName + ".files", repository.source(loaded))
                else viewer!!.currentDocumentPageCount()?.let { pageCount = it }
            }
        }
    }
    fun loaded(count: Int) {
        pageCount = count; status = ""
        book?.let { loaded -> lifecycleScope.launch { repository.dao.saveTotalUnits(loaded.id, count) } }
    }
    fun pageChanged(index: Int) { page = index }
    fun failed(cause: Throwable) { error = cause.message ?: "文档损坏、加密方式不支持或无法读取" }
    companion object { private const val PDF_CONTAINER_ID = 0x71A001 }
}

@SuppressLint("NewApi")
class ReadXPdfFragment : PdfViewerFragment() {
    private var currentView: PdfView? = null
    private var saveJob: Job? = null
    private var restoringSavedViewport = false
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        restoringSavedViewport = savedInstanceState != null
        super.onViewCreated(view, savedInstanceState)
    }
    private val repository get() = (requireActivity().application as ReadXApplication).repository
    override fun onPdfViewCreated(pdfView: PdfView) {
        currentView = pdfView
        pdfView.verticalAlignment = PdfView.VERTICAL_ALIGNMENT_TOP
        pdfView.addOnViewportChangedListener(object : PdfView.OnViewportChangedListener {
            override fun onViewportChanged(firstVisiblePage: Int, visiblePagesCount: Int, pageLocations: SparseArray<RectF>, zoomLevel: Float) {
                if (firstVisiblePage < 0) return
                (activity as? PdfActivity)?.pageChanged(firstVisiblePage)
                saveJob?.cancel()
                val id = requireArguments().getString("bookId")!!
                saveJob = lifecycleScope.launch { delay(350); repository.dao.savePosition(id, firstVisiblePage, 0f, System.currentTimeMillis()) }
            }
        })
    }
    override fun onLoadDocumentSuccess(document: PdfDocument) {
        (activity as? PdfActivity)?.loaded(document.pageCount)
        // The fragment sets PdfView.pdfDocument immediately after this callback.
        if (!restoringSavedViewport) currentView?.post { currentView?.scrollToPage(requireArguments().getInt("initialPage").coerceIn(0, document.pageCount - 1)) }
    }
    override fun onLoadDocumentError(error: Throwable) { (activity as? PdfActivity)?.failed(error) }
    override fun onRequestImmersiveMode(enterImmersive: Boolean) { /* Keep the app's close/search controls reachable. */ }
    override fun onLinkClicked(externalLink: ExternalLink): Boolean {
        Toast.makeText(context, "外部链接未打开：本版本仅访问本地内容", Toast.LENGTH_SHORT).show(); return true
    }
    fun go(page: Int) { currentView?.scrollToPage(page) }
    fun currentDocumentPageCount(): Int? = currentView?.pdfDocument?.pageCount
    override fun onStop() {
        val id = arguments?.getString("bookId")
        val page = currentView?.firstVisiblePage
        if (id != null && page != null && page >= 0) {
            saveJob?.cancel()
            repository.persistPosition(id, page, 0f)
        }
        super.onStop()
    }
    override fun onDestroyView() { currentView = null; super.onDestroyView() }
}

@Composable
private fun PageJumpDialog(count: Int, go: (Int) -> Unit, dismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val number = input.toIntOrNull()
    AlertDialog(onDismissRequest = dismiss, title = { Text("跳转页码") }, text = { OutlinedTextField(input, { input = it.filter(Char::isDigit).take(8) }, label = { Text("1–$count") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { go(number!! - 1) }, enabled = number != null && number in 1..count) { Text("跳转") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
