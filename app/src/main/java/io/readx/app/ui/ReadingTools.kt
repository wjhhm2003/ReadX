@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.readx.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.readx.app.data.Book
import kotlin.math.roundToInt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

@Composable
internal fun AnnotationExportActions(book:Book,vm:LibraryViewModel) {
    var menu by remember {mutableStateOf(false)}
    val md=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) {uri->if(uri!=null)vm.exportAnnotations(book,uri,true)}
    val txt=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {uri->if(uri!=null)vm.exportAnnotations(book,uri,false)}
    Box {IconButton(onClick={menu=true}) {Icon(Icons.Rounded.IosShare,"导出整书批注")}
        DropdownMenu(menu,{menu=false}) {
            DropdownMenuItem(text={Text("导出 Markdown")},onClick={menu=false;md.launch(book.title.take(80)+"-批注.md")})
            DropdownMenuItem(text={Text("导出 TXT")},onClick={menu=false;txt.launch(book.title.take(80)+"-批注.txt")})
            DropdownMenuItem(text={Text("复制整书批注")},onClick={menu=false;vm.copyAnnotations(book)})
        }
    }
}

@Composable
internal fun LocalFontOptions(vm:LibraryViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle();val fonts by vm.fonts.collectAsStateWithLifecycle()
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {if(it!=null)vm.importFont(it)}
    var deletingFont by remember {mutableStateOf<io.readx.app.reader.LocalFont?>(null)}
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("本地字体",style=MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            FilterChip(selected=settings.fontId==null,onClick={vm.preferences.update(settings.copy(fontId=null))},label={Text("系统字体")})
            fonts.forEach {font->
                FilterChip(
                    selected=settings.fontId==font.id,
                    onClick={vm.preferences.update(settings.copy(fontId=font.id))},
                    label={Text(font.name.take(30))},
                    trailingIcon={
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription="删除字体 ${font.name}",
                            modifier=Modifier.size(16.dp).clickable {deletingFont=font}
                        )
                    }
                )
            }
        }
        TextButton(onClick={importer.launch(arrayOf("font/ttf","font/otf","application/x-font-ttf","application/octet-stream"))}) {Text("导入 TTF / OTF")}
        Text("复制到应用私有目录，最多16 MiB；仅改变 TXT / EPUB 排版，不修改原书或 PDF 字体。点击字体右侧 × 可删除。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    deletingFont?.let {font->
        AlertDialog(
            onDismissRequest={deletingFont=null},
            title={Text("删除本地字体？")},
            text={Text("将从私有存储中删除「${font.name}」；若当前正在使用，将自动恢复为系统字体。")},
            confirmButton={TextButton(onClick={vm.deleteFont(font);deletingFont=null}) {Text("删除")}},
            dismissButton={TextButton(onClick={deletingFont=null}) {Text("取消")}}
        )
    }
}

/** Only actual engine page totals can be entered. Unknown totals remain explicitly unknown. */
@Composable
internal fun ReadingProgressControl(current:Int?,total:Int?,label:String,onJump:(Int)->Unit,previous:()->Unit,next:()->Unit,canPrevious:Boolean,canNext:Boolean) {
    var preview by remember {mutableStateOf<Float?>(null)}
    val lastValue = remember { floatArrayOf((current ?: 1).toFloat()) }
    LaunchedEffect(current) { if (current != null && preview == null) lastValue[0] = current.toFloat() }
    var exact by remember {mutableStateOf(false)}
    var input by remember {mutableStateOf("")}
    val known=total!=null && total>0 && current!=null && current>0
    Column(Modifier.fillMaxWidth().padding(horizontal=12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=previous,enabled=canPrevious) {Icon(Icons.Rounded.SkipPrevious,"上一章节或原文页")}
            Text(label,Modifier.weight(1f),maxLines=1,style=MaterialTheme.typography.labelMedium)
            Text(if(known) "${preview?.roundToInt() ?: current} / $total" else "页码统计中",
                Modifier.testTag("exact-page-entry").combinedClickable(onClick={if(known) {input=current.toString();exact=true}},onLongClick={if(known) {input=current.toString();exact=true}}).padding(12.dp),style=MaterialTheme.typography.labelLarge)
            IconButton(onClick=next,enabled=canNext) {Icon(Icons.Rounded.SkipNext,"下一章节或原文页")}
        }
        Slider(
            value=preview ?: (current ?: 1).toFloat(),
            onValueChange={preview=it;lastValue[0]=it},
            onValueChangeFinished={onJump(lastValue[0].roundToInt());preview=null},
            valueRange=1f..(total ?: 2).coerceAtLeast(2).toFloat(),
            enabled=known && total!!>1,
            modifier=Modifier.height(32.dp).testTag("reading-progress-slider")
        )
    }
    if(exact) {
        val number=input.toIntOrNull()
        AlertDialog(
            onDismissRequest={exact=false},
            title={Text("精确页码")},
            text={
                OutlinedTextField(
                    value=input,
                    onValueChange={input=it.filter(Char::isDigit).take(8)},
                    label={Text("1–$total")},
                    singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number,imeAction=ImeAction.Go),
                    keyboardActions=KeyboardActions(onGo={if(number!=null && total!=null && number in 1..total) {onJump(number);exact=false}})
                )
            },
            confirmButton={TextButton(onClick={onJump(number!!);exact=false},enabled=number!=null && total!=null && number in 1..total) {Text("跳转")}},
            dismissButton={TextButton(onClick={exact=false}) {Text("取消")}}
        )
    }
}

