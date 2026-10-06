package io.readx.app.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReadingTheme(val label: String) { SYSTEM("跟随系统"), DAY("日间"), NIGHT("夜间"), BLACK("纯黑"), MINT("浅绿"), WARM("暖色") }
enum class ThemeAccent(val label: String, val light: Long, val dark: Long) {
    BLUE("蓝色", 0xFF246BFC, 0xFFB8CCFF), GREEN("绿色", 0xFF34734B, 0xFFA8D5B3),
    PURPLE("紫色", 0xFF7756AE, 0xFFD4BEFF), ORANGE("橙色", 0xFFA65316, 0xFFFFBC88),
}
enum class PdfReadingLayout(val label: String) { VERTICAL("纵向连续"), HORIZONTAL("横向单页") }
enum class ReadingLayout(val label: String) { PAGED("分页"), SCROLL("滚动") }

data class ReaderSettings(
    val accent: ThemeAccent = ThemeAccent.BLUE,
    val dynamicColors: Boolean = false,
    val customAccent: String = "",
    val annotationColor: String = "#FFD240",
    val pdfToEpubEnabled: Boolean = false,
    val ocrLanguages: String = "chi_sim+eng",
    val pdfLayout: PdfReadingLayout = PdfReadingLayout.VERTICAL,
    val theme: ReadingTheme = ReadingTheme.SYSTEM,
    val layout: ReadingLayout = ReadingLayout.PAGED,
    val fontSize: Float = 20f,
    val lineHeight: Float = 1.8f,
    val margin: Float = 24f,
    val serif: Boolean = true,
    val fontId:String?=null,
    val onlineModels:Boolean=false,
    val shelfGrid:Boolean=false,
    val shelfSort:String="RECENT",
    val appTheme:ReadingTheme=ReadingTheme.SYSTEM,
)

class ReaderPreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("reader-settings", Context.MODE_PRIVATE)
    private fun read() = ReaderSettings(
        accent = ThemeAccent.entries.firstOrNull { it.name == prefs.getString("accent", "BLUE") } ?: ThemeAccent.BLUE,
        dynamicColors = prefs.getBoolean("dynamicColors", false),
        customAccent = prefs.getString("customAccent", "").orEmpty().takeIf {it.matches(Regex("#[0-9A-Fa-f]{6}"))}.orEmpty(),
        annotationColor = io.readx.app.data.MarkColor.normalize(prefs.getString("annotationColor", "#FFD240").orEmpty()),
        pdfToEpubEnabled = prefs.getBoolean("pdfToEpubEnabled", false),
        ocrLanguages = prefs.getString("ocrLanguages","chi_sim+eng").orEmpty().takeIf {it in listOf("chi_sim+eng","chi_tra+eng","chi_tra","chi_sim","eng")} ?: "chi_sim+eng",
        pdfLayout = PdfReadingLayout.entries.firstOrNull { it.name == prefs.getString("pdfLayout", "VERTICAL") } ?: PdfReadingLayout.VERTICAL,
        theme = ReadingTheme.entries.firstOrNull { it.name == prefs.getString("theme", "SYSTEM") } ?: ReadingTheme.SYSTEM,
        layout = ReadingLayout.entries.firstOrNull { it.name == prefs.getString("layout", "PAGED") } ?: ReadingLayout.PAGED,
        fontSize = prefs.getFloat("fontSize", 20f).coerceIn(14f, 32f),
        lineHeight = prefs.getFloat("lineHeight", 1.8f).coerceIn(1.2f, 2.6f),
        margin = prefs.getFloat("margin", 24f).coerceIn(12f, 48f),
        serif = prefs.getBoolean("serif", true),
        fontId=prefs.getString("fontId",null)?.takeIf {it.matches(Regex("[0-9a-f]{64}"))},
        onlineModels=prefs.getBoolean("onlineModels",false),
        shelfGrid=prefs.getBoolean("shelfGrid",false),
        shelfSort=prefs.getString("shelfSort","RECENT").orEmpty(),
        appTheme=ReadingTheme.entries.firstOrNull {it.name==prefs.getString("appTheme","SYSTEM")} ?: ReadingTheme.SYSTEM,
    )
    private val state = MutableStateFlow(read())
    // SharedPreferences keeps listeners weakly: hold it while this wrapper is alive, without an Activity leak.
    // MainActivity and PdfActivity must see each other's palette/layout changes instead of overwriting stale values.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = read() }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }
    val settings = state.asStateFlow()
    fun reset() = update(ReaderSettings())

    fun update(value: ReaderSettings) {
        if(!value.onlineModels && state.value.onlineModels) io.readx.app.conversion.OcrDownloadPolicy.cancel(context)
        state.value = value
        prefs.edit().putBoolean("pdfToEpubEnabled", value.pdfToEpubEnabled).putString("ocrLanguages",value.ocrLanguages).putString("accent", value.accent.name).putBoolean("dynamicColors", value.dynamicColors)
            .putString("customAccent",value.customAccent).putString("annotationColor",value.annotationColor)
            .putString("pdfLayout", value.pdfLayout.name).putString("theme", value.theme.name).putString("layout", value.layout.name)
            .putFloat("fontSize", value.fontSize).putFloat("lineHeight", value.lineHeight)
            .putFloat("margin", value.margin).putBoolean("serif", value.serif)
            .putBoolean("onlineModels",value.onlineModels).putString("fontId",value.fontId).putBoolean("shelfGrid",value.shelfGrid).putString("shelfSort",value.shelfSort)
            .putString("appTheme",value.appTheme.name).apply()
    }
}
