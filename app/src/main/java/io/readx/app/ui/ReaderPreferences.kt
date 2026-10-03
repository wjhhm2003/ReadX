package io.readx.app.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReadingTheme(val label: String) { SYSTEM("跟随系统"), DAY("日间"), NIGHT("夜间"), BLACK("纯黑"), WARM("暖色") }
enum class ReadingLayout(val label: String) { PAGED("分页"), SCROLL("滚动") }

data class ReaderSettings(
    val theme: ReadingTheme = ReadingTheme.SYSTEM,
    val layout: ReadingLayout = ReadingLayout.PAGED,
    val fontSize: Float = 20f,
    val lineHeight: Float = 1.8f,
    val margin: Float = 24f,
    val serif: Boolean = true,
)

class ReaderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("reader-settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(ReaderSettings(
        theme = ReadingTheme.entries.firstOrNull { it.name == prefs.getString("theme", "SYSTEM") } ?: ReadingTheme.SYSTEM,
        layout = ReadingLayout.entries.firstOrNull { it.name == prefs.getString("layout", "PAGED") } ?: ReadingLayout.PAGED,
        fontSize = prefs.getFloat("fontSize", 20f).coerceIn(14f, 32f),
        lineHeight = prefs.getFloat("lineHeight", 1.8f).coerceIn(1.2f, 2.6f),
        margin = prefs.getFloat("margin", 24f).coerceIn(12f, 48f),
        serif = prefs.getBoolean("serif", true),
    ))
    val settings = state.asStateFlow()
    fun reset() = update(ReaderSettings())

    fun update(value: ReaderSettings) {
        state.value = value
        prefs.edit().putString("theme", value.theme.name).putString("layout", value.layout.name)
            .putFloat("fontSize", value.fontSize).putFloat("lineHeight", value.lineHeight)
            .putFloat("margin", value.margin).putBoolean("serif", value.serif).apply()
    }
}
