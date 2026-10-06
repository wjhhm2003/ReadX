@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.pdf.ExperimentalPdfApi::class)
package io.readx.app.pdf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.pdf.PdfDocument
import io.readx.app.data.Book
import io.readx.app.data.LibraryRepository
import kotlinx.coroutines.*
import kotlin.math.*

@Composable
internal fun PdfCropDialog(book:Book,page:Int,document:PdfDocument?,advanced:Boolean,repository:LibraryRepository,config:PdfCropConfig,save:(PdfCropConfig)->Unit,dismiss:()->Unit) {
    var draft by remember {mutableStateOf(config)}
    var edit by remember {mutableStateOf(config.resolve(page))}
    var touched by remember {mutableStateOf(false)}
    var range by remember {mutableStateOf(CropScope.PAGE)}
    var rangeMenu by remember {mutableStateOf(false)}
    var preview by remember {mutableStateOf<RenderedPdfPage?>(null)}
    var detected by remember {mutableStateOf(CropRect.FULL)}
    var failed by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(book.id,page,document) {
        if(advanced && document==null)return@LaunchedEffect
        var source:CroppedPdfSource?=null
        try {
            withContext(Dispatchers.IO) {source=CroppedPdfSource(document,repository.source(book))}
            preview=source!!.render(page)
            val low=source!!.render(page,true).bitmap
            detected=withContext(Dispatchers.Default) {val pixels=IntArray(low.width*low.height);low.getPixels(pixels,0,low.width,0,0,low.width,low.height);AutoPdfCrop.detect(pixels,low.width,low.height)}
        }catch(e:CancellationException) {throw e}catch(e:Exception) {failed=e.message ?: "原页预览失败"}
        finally {source?.let {withContext(NonCancellable) {it.close()}}}
    }
    ModalBottomSheet(onDismissRequest=dismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(horizontal=16.dp)) {
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text("裁边 · 原文第 ${page+1} 页",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                TextButton(onClick=dismiss) {Text("取消")}
                TextButton(onClick={save(if(touched) draft.manual(page,edit,range) else draft);dismiss()},enabled=preview!=null) {Text("应用")}
            }
            Text("拖动四边或四角手柄；靠近原页/检测边缘自动吸附。仅修改阅读视图。",style=MaterialTheme.typography.bodySmall)
            val image=preview
            var size by remember {mutableStateOf(IntSize.Zero)}
            if(image!=null) Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal=8.dp,vertical=12.dp).onSizeChanged {size=it}) {
            Canvas(Modifier.fillMaxSize().semantics {contentDescription="原页裁边预览"}.onSizeChanged {size=it}
            ) {
                val scale=min(size.width.toFloat()/image.bitmap.width,size.height.toFloat()/image.bitmap.height)
                val w=image.bitmap.width*scale;val h=image.bitmap.height*scale;val x=(size.width-w)/2f;val y=(size.height-h)/2f
                drawImage(image.bitmap.asImageBitmap(),dstOffset=IntOffset(x.toInt(),y.toInt()),dstSize=IntSize(w.toInt(),h.toInt()))
                val l=x+edit.left*w;val r=x+edit.right*w;val t=y+edit.top*h;val b=y+edit.bottom*h
                drawRect(Color(0x66000000),Offset(x,y),Size(edit.left*w,h));drawRect(Color(0x66000000),Offset(r,y),Size((1-edit.right)*w,h))
                drawRect(Color(0x66000000),Offset(l,y),Size(r-l,t-y));drawRect(Color(0x66000000),Offset(l,b),Size(r-l,y+h-b))
                drawRect(Color(0xFF246BFC),Offset(l,t),Size(r-l,b-t),style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                listOf(Offset(l,t),Offset(r,t),Offset(l,b),Offset(r,b),Offset((l+r)/2,t),Offset((l+r)/2,b),Offset(l,(t+b)/2),Offset(r,(t+b)/2)).forEach {point->drawCircle(Color.White,9.dp.toPx(),point);drawCircle(Color(0xFF246BFC),9.dp.toPx(),point,style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))}
            }
            val scale=if(size.width>0 && size.height>0)min(size.width.toFloat()/image.bitmap.width,size.height.toFloat()/image.bitmap.height) else 0f
            val w=image.bitmap.width*scale;val h=image.bitmap.height*scale;val x=(size.width-w)/2f;val y=(size.height-h)/2f
            val controls=listOf(
                Triple(CropHandle.LEFT,"左侧",Offset(x+edit.left*w,y+(edit.top+edit.bottom)*h/2)),Triple(CropHandle.TOP,"上侧",Offset(x+(edit.left+edit.right)*w/2,y+edit.top*h)),
                Triple(CropHandle.RIGHT,"右侧",Offset(x+edit.right*w,y+(edit.top+edit.bottom)*h/2)),Triple(CropHandle.BOTTOM,"下侧",Offset(x+(edit.left+edit.right)*w/2,y+edit.bottom*h)),
                Triple(CropHandle.TOP_LEFT,"左上",Offset(x+edit.left*w,y+edit.top*h)),Triple(CropHandle.TOP_RIGHT,"右上",Offset(x+edit.right*w,y+edit.top*h)),
                Triple(CropHandle.BOTTOM_LEFT,"左下",Offset(x+edit.left*w,y+edit.bottom*h)),Triple(CropHandle.BOTTOM_RIGHT,"右下",Offset(x+edit.right*w,y+edit.bottom*h)))
            if(scale>0) controls.forEach {(handle,label,position)->
                Box(Modifier.offset {IntOffset(position.x.toInt()-24.dp.roundToPx(),position.y.toInt()-24.dp.roundToPx())}.size(48.dp).systemGestureExclusion()
                    .pointerInput(handle,image,size) {
                        var raw=edit
                        detectDragGestures(onDragStart={raw=edit}) {change,delta->
                            change.consume();touched=true;draft=draft.copy(enabled=true)
                            raw=CropHandles.move(raw,handle,delta.x/w,delta.y/h)
                            edit=CropHandles.snap(raw,detected,10.dp.toPx()/w,10.dp.toPx()/h)
                        }
                    }.semantics {
                    contentDescription="裁边${label}手柄"
                    customActions=listOf(androidx.compose.ui.semantics.CustomAccessibilityAction("向内微调") {
                        val dx=if(handle in listOf(CropHandle.LEFT,CropHandle.TOP_LEFT,CropHandle.BOTTOM_LEFT)).01f else if(handle in listOf(CropHandle.RIGHT,CropHandle.TOP_RIGHT,CropHandle.BOTTOM_RIGHT))-.01f else 0f
                        val dy=if(handle in listOf(CropHandle.TOP,CropHandle.TOP_LEFT,CropHandle.TOP_RIGHT)).01f else if(handle in listOf(CropHandle.BOTTOM,CropHandle.BOTTOM_LEFT,CropHandle.BOTTOM_RIGHT))-.01f else 0f
                        edit=CropHandles.move(edit,handle,dx,dy);touched=true;draft=draft.copy(enabled=true);true
                    })
                })
            }
            } else Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=androidx.compose.ui.Alignment.Center) {if(failed!=null)Text(failed!!)else CircularProgressIndicator()}
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text("启用",Modifier.weight(1f));Switch(draft.enabled,{draft=draft.copy(enabled=it)})
                Text("自动",Modifier.padding(start=12.dp));Switch(draft.automatic,{draft=draft.copy(enabled=true,automatic=it)})
                Box {TextButton(onClick={rangeMenu=true}) {Text(range.label)};DropdownMenu(rangeMenu,{rangeMenu=false}) {CropScope.entries.forEach {value->DropdownMenuItem(text={Text(value.label)},onClick={range=value;rangeMenu=false})}}}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                TextButton(onClick={edit=detected;touched=true;draft=draft.copy(enabled=true)}) {Text("贴合检测边缘")}
                TextButton(onClick={edit=CropRect.FULL;touched=true}) {Text("整页")}
                TextButton(onClick={draft=draft.reset(page,range);edit=draft.resolve(page,detected);touched=false}) {Text("清除规则")}
            }
        }
    }
}

@Composable
internal fun CroppedPdfSearchDialog(document:PdfDocument,selected:(List<PdfBox>)->Unit,dismiss:()->Unit) {
    var query by remember {mutableStateOf("")}
    var matches by remember {mutableStateOf(emptyList<List<PdfBox>>())}
    var searching by remember {mutableStateOf(false)}
    var failure by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(query) {
        matches=emptyList();failure=null
        if(query.isBlank())return@LaunchedEffect
        searching=true
        try {delay(250)
            for(first in 0 until document.pageCount step 16) {
                ensureActive();val found=document.searchDocument(query.take(1000),first..min(first+15,document.pageCount-1))
                val next=ArrayList<List<PdfBox>>()
                for(i in 0 until found.size()) {val page=found.keyAt(i);val info=document.getPageInfo(page);found.valueAt(i).forEach {match->next+=match.bounds.mapNotNull {PdfLocators.normalize(page,it,info.width,info.height)}}}
                matches=(matches+next).take(100);if(matches.size>=100)break;yield()
            }
        } catch(e:CancellationException) {throw e} catch(_:Exception) {failure="搜索失败；扫描件没有文字层时需另行 OCR"} finally {searching=false}
    }
    AlertDialog(onDismissRequest=dismiss,title={Text("PDF 文字层搜索")},text={Column {
        OutlinedTextField(query,{query=it},label={Text("搜索原 PDF 文字层")},singleLine=true)
        if(searching)LinearProgressIndicator(Modifier.fillMaxWidth())
        failure?.let {Text(it)}
        LazyColumn(Modifier.heightIn(max=300.dp)) {items(matches.size) {i->TextButton(onClick={selected(matches[i]);dismiss()}) {Text("第 ${(matches[i].firstOrNull()?.page ?: 0)+1} 页 · 命中 ${i+1}")}}}
        if(!searching && query.isNotBlank() && matches.isEmpty())Text("未找到文字。扫描页默认不能全文搜索。")
    }},confirmButton={TextButton(onClick=dismiss) {Text("关闭")}})
}
