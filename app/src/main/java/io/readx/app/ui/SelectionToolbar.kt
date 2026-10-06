package io.readx.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Compact anchored selection bubble; long quotes and editing live outside the toolbar. */
@Composable
internal fun SelectionBubbleActions(
    color: String,
    onColor: (String) -> Unit,
    copy: (() -> Unit)?,
    highlight: () -> Unit,
    underline: () -> Unit,
    note: () -> Unit,
    editing: Boolean,
    remove: (() -> Unit)?,
    dismiss: () -> Unit,
    extra: @Composable () -> Unit = {}
) {
    var options by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 360.dp).padding(vertical = 4.dp)) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            if (copy != null) IconButton(onClick = copy) { Icon(Icons.Rounded.ContentCopy, "复制") }
            IconButton(onClick = highlight) { Icon(Icons.Rounded.Highlight, "高亮") }
            IconButton(onClick = underline) { Icon(Icons.Rounded.FormatUnderlined, "划线") }
            IconButton(onClick = note) { Icon(Icons.Rounded.EditNote, if (editing) "编辑笔记" else "写笔记") }
            IconButton(onClick = { options = !options }) {
                Icon(if (options) Icons.Rounded.ExpandLess else Icons.Rounded.MoreHoriz, "颜色与选区设置")
            }
            if (remove != null) IconButton(onClick = remove) { Icon(Icons.Rounded.DeleteOutline, "取消标记") }
            IconButton(onClick = dismiss) { Icon(Icons.Rounded.Close, "关闭选区工具栏") }
        }
        if (options) {
            HorizontalDivider(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            MarkColorPicker(color, onColor)
            extra()
        }
    }
}
