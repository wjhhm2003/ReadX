@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.readx.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.readx.app.data.Annotation
import io.readx.app.data.MarkColor
import io.readx.app.reader.*

private enum class ReaderPanel(val label: String) { CONTENTS("目录"), ANNOTATIONS("批注"), PROGRESS("进度"), PAPER("背景"), TYPE("排版") }

@Composable
internal fun ImmersiveReaderScreen(session: ReaderSession,settings: ReaderSettings,vm: LibraryViewModel,showSettings: ()->Unit,search: ()->Unit) {
    var chrome by rememberSaveable(session.book.id) {mutableStateOf(false)}
    var panel by rememberSaveable(session.book.id) {mutableStateOf<ReaderPanel?>(null)}
    var page by remember(session.book.id,session.navigationId,settings.layout) {mutableIntStateOf(1)}
    var chapterPages by remember(session.book.id,session.navigationId,settings.layout) {mutableIntStateOf(0)}
    var fraction by remember(session.book.id,session.navigationId) {mutableFloatStateOf(session.fraction)}
    var viewport by remember(session.book.id) {mutableStateOf(0 to 0)}
    var index by remember(session.book.id,viewport,settings.fontSize,settings.lineHeight,settings.margin,settings.serif) {mutableStateOf<BookPageIndex?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var retry by remember {mutableIntStateOf(0)}
    var seeking by remember(session.book.id,viewport,settings.layout) {mutableStateOf<Float?>(null)}
    var selection by remember(session.book.id,session.navigationId) {mutableStateOf<ReaderSelection?>(null)}
    var noteDraft by remember {mutableStateOf<Pair<TextAnchor,String>?>(null)}
    var deleteNotes by remember {mutableStateOf<List<Annotation>?>(null)}
    val annotations by vm.annotations.collectAsStateWithLifecycle()
    val bookMarks=annotations.filter {it.bookId==session.book.id}
    val controller=remember {ReaderController()}
    val chapter=session.chapters[session.chapter]
    val current=index?.globalPage(session.chapter,page)
    val total=index?.total
    val context=LocalContext.current
    fun clearSelection() {selection=null;controller.view?.clearReaderSelection()}
    fun dismissControls() {panel=null;chrome=false;clearSelection()}
    BackHandler {
        when {selection!=null->clearSelection();panel!=null->panel=null;chrome->dismissControls();session.returnStack.isNotEmpty()->vm.returnFromLink();else->vm.close()}
    }
    Box(Modifier.fillMaxSize()) {
        // Full measured safe area at all times. Controls overlay the page instead of reserving invisible bands.
        val reading=Modifier.fillMaxSize()
        if(settings.layout==ReadingLayout.PAGED) BookPageCounter(session.book,session.chapters,settings,vm.repository,viewport,reading,retry) {value,failure->index=value;error=failure}
        val ink="#%06X".format(MaterialTheme.colorScheme.onSurface.toArgb() and 0xFFFFFF)
        val paper="#%06X".format(MaterialTheme.colorScheme.background.toArgb() and 0xFFFFFF)
        WebReader(session,settings,vm.repository,ink,paper,reading.testTag("reader-content"),
            {ordinal,target->clearSelection();vm.chapter(ordinal,target)},
            {ordinal,target,origin,anchor->chrome=true;vm.followLink(ordinal,target,origin,anchor)},
            {w,h->viewport=w to h},bookMarks.filter {it.chapter==session.chapter},
            {zone->if(zone==0) {if(panel!=null) panel=null else chrome=!chrome} else {dismissControls();controller.turn(zone)}},
            {value->if(vm.isCurrentNavigation(session.book.id,session.navigationId)) selection=value},
            {kind,anchor->if(kind=="NOTE") noteDraft=anchor to "" else vm.addTextAnnotation(kind,anchor,"",fraction)},
            {position,final,local,count->
                if(vm.isCurrentNavigation(session.book.id,session.navigationId)) {
                    fraction=position;page=local;chapterPages=count
                    if(final) vm.savePosition(session.book.id,session.chapter,position) else vm.position(session.book.id,session.chapter,position)
                } else if(final && vm.reader.value==null) vm.savePosition(session.book.id,session.chapter,position)
            },vm::notify,controller)
        if(!chrome && selection==null) Text(
            if(chapterPages==0) "排版中…" else if(settings.layout==ReadingLayout.SCROLL) "${(fraction*100).toInt()}%" else if(current!=null && total!=null) "$current / $total" else "统计 ${index?.measured ?: 0}/${session.chapters.size}",
            modifier=Modifier.align(Alignment.BottomEnd).padding(end=20.dp,bottom=5.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedVisibility(chrome,modifier=Modifier.align(Alignment.TopStart),enter=fadeIn(),exit=fadeOut()) {
            Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surfaceContainer.copy(alpha=.94f),modifier=Modifier.padding(10.dp)) {
                IconButton(onClick={if(session.returnStack.isNotEmpty()) vm.returnFromLink() else vm.close()}) {Icon(Icons.AutoMirrored.Rounded.ArrowBack,if(session.returnStack.isNotEmpty()) "回到原处" else "返回书架")}
            }
        }
        AnimatedVisibility(chrome,modifier=Modifier.align(Alignment.BottomCenter),enter=slideInVertically {it}+fadeIn(),exit=slideOutVertically {it}+fadeOut()) {
            Surface(color=MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth()) {
                    AnimatedContent(targetState=panel,label="reader-panel",transitionSpec={fadeIn() togetherWith fadeOut()}) {active->
                        when(active) {
                            ReaderPanel.PROGRESS->Column(Modifier.padding(horizontal=24.dp,vertical=16.dp)) {
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                    val target=index?.locate(kotlin.math.round(seeking ?: current?.toFloat() ?: 1f).toInt())
                                    Text(target?.let {session.chapters[it.first].title} ?: chapter.title,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                                    Text(if(total!=null && current!=null && chapterPages>0) "${kotlin.math.round(seeking ?: current.toFloat()).toInt()} / $total" else "统计 ${index?.measured ?: 0}/${session.chapters.size}",style=MaterialTheme.typography.labelMedium)
                                }
                                if(total!=null && current!=null && settings.layout==ReadingLayout.PAGED) Slider(seeking ?: current.toFloat(),{seeking=it},
                                    onValueChangeFinished={index?.locate(kotlin.math.round(seeking ?: current.toFloat()).toInt())?.let {(ordinal,p)->
                                        clearSelection()
                                        if(ordinal==session.chapter) controller.jumpToPage(p) else vm.chapter(ordinal,fraction=if(index!!.counts[ordinal]!! == 1) 0f else (p-1f)/(index!!.counts[ordinal]!!-1))
                                    };seeking=null},valueRange=1f..total.coerceAtLeast(2).toFloat(),enabled=total>1 && chapterPages>0)
                                else Text(if(error!=null) "统计未完成：$error" else if(settings.layout==ReadingLayout.SCROLL) "滚动模式不显示固定页数" else "按当前排版统计中…",style=MaterialTheme.typography.bodySmall)
                                if(error!=null) TextButton(onClick={retry++}) {Text("重新统计")}
                                if(session.returnStack.isNotEmpty()) TextButton(onClick=vm::returnFromLink) {Text("回到原处")}
                            }
                            ReaderPanel.PAPER->ReaderPaperPanel(settings,vm.preferences::update)
                            ReaderPanel.TYPE->ReaderTypePanel(settings,vm.preferences::update,showSettings)
                            else->Spacer(Modifier.height(0.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(64.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceEvenly) {
                        ReaderPanel.entries.forEach {item->
                            IconButton(onClick={clearSelection();panel=if(panel==item) null else item}) {
                                val icon=when(item) {ReaderPanel.CONTENTS->Icons.AutoMirrored.Rounded.List;ReaderPanel.ANNOTATIONS->Icons.Rounded.EditNote;ReaderPanel.PROGRESS->Icons.Rounded.Tune;ReaderPanel.PAPER->Icons.Rounded.Brightness6;ReaderPanel.TYPE->Icons.Rounded.TextFields}
                                Icon(icon,item.label,tint=if(panel==item) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.size(27.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    if(panel==ReaderPanel.CONTENTS) ModalBottomSheet(onDismissRequest={panel=null},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        val list=rememberLazyListState(initialFirstVisibleItemIndex=session.chapter)
        Column(Modifier.fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("目录",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
                IconButton(onClick={panel=null;search()}) {Icon(Icons.Rounded.Search,"搜本书")}
                TextButton(onClick={panel=null}) {Text("完成")}
            }
            Text(session.book.title,Modifier.padding(horizontal=24.dp,vertical=12.dp),style=MaterialTheme.typography.titleMedium,maxLines=2)
            LazyColumn(Modifier.weight(1f),state=list,contentPadding=PaddingValues(bottom=24.dp)) {items(session.chapters,key={it.ordinal}) {item->
                ListItem(headlineContent={Text(item.title,color=if(item.ordinal==session.chapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)},
                    trailingContent={index?.globalPage(item.ordinal,1)?.let {Text(it.toString(),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}},
                    modifier=Modifier.clickable {vm.chapter(item.ordinal);panel=null;chrome=false})
                HorizontalDivider(Modifier.padding(horizontal=24.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f))
            }}
        }
    }
    if(panel==ReaderPanel.ANNOTATIONS) ModalBottomSheet(onDismissRequest={panel=null},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxHeight().padding(horizontal=20.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Text("本书批注",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall);IconButton(onClick={vm.addBookmark()}) {Icon(Icons.Rounded.BookmarkAdd,"添加位置书签")};TextButton(onClick={panel=null}) {Text("完成")}}
            if(bookMarks.isEmpty()) Text("长按正文即可划线或写想法。",Modifier.padding(24.dp))
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=24.dp)) {items(bookMarks,key={it.id}) {mark->
                AnnotationCard(mark,{vm.openAnnotation(mark);panel=null;chrome=false},{vm.deleteAnnotation(mark)},{note->vm.updateAnnotation(mark,note)})
            }}
        }
    }
    selection?.let {picked->
        val rows=bookMarks.filter {it.id in picked.annotationIds}
        val primary=rows.firstOrNull {it.id==picked.primaryId} ?: rows.maxByOrNull {maxOf(it.updatedAt,it.createdAt)}
        ReaderSelectionPopup(picked,controller,settings.annotationColor,primary,
            onColor={value->vm.preferences.update(settings.copy(annotationColor=value));if(picked.fromMark && primary!=null) vm.recolorAnnotation(primary,value)},
            copy={val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;clipboard.setPrimaryClip(ClipData.newPlainText("ReadX 选段",picked.anchor.quote));clearSelection();vm.notify("已复制所选文字")},
            highlight={vm.addTextAnnotation("HIGHLIGHT",picked.anchor,"",fraction);clearSelection()},
            underline={vm.addTextAnnotation("UNDERLINE",picked.anchor,"",fraction);clearSelection()},
            note={val old=rows.firstOrNull {it.kind=="NOTE"}?.note ?: primary?.note.orEmpty();noteDraft=picked.anchor to old;clearSelection()},
            remove={if(rows.any {it.kind=="HIGHLIGHT" || it.kind=="UNDERLINE"}) {vm.removeTextMarks(picked.anchor,picked.annotationIds);clearSelection()} else {deleteNotes=rows.filter {it.kind=="NOTE"};clearSelection()}},hasMark=rows.isNotEmpty(),dismiss=::clearSelection)
    }
    noteDraft?.let {(anchor,old)->AnnotationEditor(anchor.quote,old) {note->if(note!=null) vm.addTextAnnotation("NOTE",anchor,note,fraction);noteDraft=null} }
    deleteNotes?.let {rows->AlertDialog(onDismissRequest={deleteNotes=null},title={Text("删除选段的笔记？")},text={Text("会删除 ${rows.size} 条文字笔记，不修改原书。")},confirmButton={TextButton(onClick={rows.forEach(vm::deleteAnnotation);deleteNotes=null}) {Text("删除")}},dismissButton={TextButton(onClick={deleteNotes=null}) {Text("取消")}}) }
}

@Composable
private fun ReaderSelectionPopup(selection: ReaderSelection,controller: ReaderController,selectedColor: String,primary: Annotation?,onColor:(String)->Unit,copy:()->Unit,highlight:()->Unit,underline:()->Unit,note:()->Unit,remove:()->Unit,hasMark:Boolean,dismiss:()->Unit) {
    val density=LocalDensity.current.density
    val origin=IntArray(2);controller.view?.getLocationInWindow(origin)
    val r=selection.bounds
    val rect=Rect(origin[0]+r.left*density,origin[1]+r.top*density,origin[0]+r.right*density,origin[1]+r.bottom*density)
    val provider=remember(rect,density) {object:PopupPositionProvider {
        override fun calculatePosition(anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize):IntOffset {
            val margin=(12*density).toInt()
            val x=(rect.center.x-popupContentSize.width/2).toInt().coerceIn(margin,(windowSize.width-popupContentSize.width-margin).coerceAtLeast(margin))
            val above=rect.top-popupContentSize.height-margin
            val y=(if(above>=margin) above else rect.bottom+margin).toInt().coerceIn(margin,(windowSize.height-popupContentSize.height-margin).coerceAtLeast(margin))
            return IntOffset(x,y)
        }
    }}
    Popup(popupPositionProvider=provider,onDismissRequest=dismiss,properties=PopupProperties(focusable=false,dismissOnBackPress=false,dismissOnClickOutside=false)) {
        Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainerHighest,shadowElevation=8.dp,modifier=Modifier.widthIn(max=370.dp).testTag("selection-menu")) {
            Column(Modifier.padding(horizontal=8.dp,vertical=8.dp)) {
                Row(Modifier.width(340.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
                    SelectionAction(Icons.Rounded.ContentCopy,"复制",copy)
                    SelectionAction(Icons.Rounded.Highlight,"荧光笔",highlight)
                    SelectionAction(Icons.Rounded.FormatUnderlined,"划线",underline)
                    SelectionAction(Icons.Rounded.EditNote,if(primary?.note?.isNotBlank()==true) "编辑想法" else "写想法",note)
                    if(hasMark) SelectionAction(Icons.Rounded.DeleteOutline,"取消标记",remove)
                }
                HorizontalDivider(Modifier.padding(horizontal=8.dp,vertical=6.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.3f))
                MarkColorPicker(primary?.color ?: selectedColor,onColor)
            }
        }
    }
}
@Composable
private fun SelectionAction(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,action:()->Unit) {
    Column(Modifier.widthIn(min=52.dp).clickable(onClick=action).padding(horizontal=3.dp,vertical=5.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(icon,label,Modifier.size(24.dp));Spacer(Modifier.height(4.dp));Text(label,style=MaterialTheme.typography.labelSmall)
    }
}
@Composable
internal fun MarkColorPicker(selected:String,onColor:(String)->Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
        MarkColor.choices.forEach {hex->
            Box(Modifier.size(40.dp).clickable {onColor(hex)}.padding(5.dp).background(Color(android.graphics.Color.parseColor(hex)),CircleShape)
                .border(if(selected==hex) 3.dp else 0.dp,MaterialTheme.colorScheme.onSurface,CircleShape).testTag("mark-color-$hex"),contentAlignment=Alignment.Center) {
                if(selected==hex) Icon(Icons.Rounded.Check,"选中 $hex",Modifier.size(17.dp),tint=Color(0xFF222222))
            }
        }
    }
}
@Composable
private fun ReaderPaperPanel(settings:ReaderSettings,update:(ReaderSettings)->Unit) {
    Column(Modifier.padding(horizontal=20.dp,vertical=16.dp)) {
        Text("阅读背景",style=MaterialTheme.typography.titleSmall);Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            listOf(ReadingTheme.DAY to 0xFFFCFCFA,ReadingTheme.WARM to 0xFFF6EBD5,ReadingTheme.MINT to 0xFFE0F0D9,ReadingTheme.NIGHT to 0xFF151515,ReadingTheme.BLACK to 0xFF000000).forEach {(theme,raw)->
                Surface(onClick={update(settings.copy(theme=theme))},modifier=Modifier.weight(1f).height(48.dp),shape=RoundedCornerShape(13.dp),color=Color(raw),border=androidx.compose.foundation.BorderStroke(if(theme==settings.theme) 2.dp else 1.dp,if(theme==settings.theme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    Box(contentAlignment=Alignment.Center) {Text(theme.label,style=MaterialTheme.typography.labelSmall,color=if(theme in listOf(ReadingTheme.BLACK,ReadingTheme.NIGHT)) Color(0xFFBDBDBD) else Color(0xFF333333))}
                }
            }
        }
        Text("纸张颜色与应用主题色分别设置。",Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun ReaderTypePanel(settings:ReaderSettings,update:(ReaderSettings)->Unit,more:()->Unit) {
    var size by remember(settings.fontSize) {mutableFloatStateOf(settings.fontSize)}
    var margin by remember(settings.margin) {mutableFloatStateOf(settings.margin)}
    var line by remember(settings.lineHeight) {mutableFloatStateOf(settings.lineHeight)}
    val current by rememberUpdatedState(settings)
    Column(Modifier.padding(horizontal=20.dp,vertical=12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Text("字体大小 ${size.toInt()}",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);TextButton(onClick=more) {Text("全部设置")}}
        Slider(size,{size=it},onValueChangeFinished={update(current.copy(fontSize=size))},valueRange=14f..32f,steps=17,modifier=Modifier.testTag("font-size-slider"))
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {Text("页边距 ${margin.toInt()}",style=MaterialTheme.typography.labelMedium);Slider(margin,{margin=it},onValueChangeFinished={update(current.copy(margin=margin))},valueRange=12f..48f)}
            Column(Modifier.weight(1f)) {Text("行距 ${"%.1f".format(line)}",style=MaterialTheme.typography.labelMedium);Slider(line,{line=it},onValueChangeFinished={update(current.copy(lineHeight=line))},valueRange=1.2f..2.6f)}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            FilterChip(selected=settings.serif,onClick={update(settings.copy(serif=!settings.serif))},label={Text(if(settings.serif) "衬线字体" else "无衬线字体")})
            FilterChip(selected=settings.layout==ReadingLayout.PAGED,onClick={update(settings.copy(layout=if(settings.layout==ReadingLayout.PAGED) ReadingLayout.SCROLL else ReadingLayout.PAGED))},label={Text(settings.layout.label)})
        }
    }
}
