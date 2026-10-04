@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.readx.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.readx.app.pdf.PdfActivity

@Composable
internal fun PdfConversionSettings(vm: LibraryViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val busy by vm.modelBusy.collectAsStateWithLifecycle()
    val tasks by vm.conversionTasks.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val licenses by vm.licenses.collectAsStateWithLifecycle()
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(),vm::importModels)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("PDF 转为电子书",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("将 PDF 转为电子书",Modifier.weight(1f))
            Switch(settings.pdfToEpubEnabled,{ enabled->
                vm.preferences.update(settings.copy(pdfToEpubEnabled=enabled))
                if(enabled && Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
            },Modifier.testTag("pdf-to-epub-switch"))
        }
        Text("开启后按需生成独立 EPUB，原 PDF 不变。复杂内容保留原图；扫描页需本地 OCR 模型，不联网、不上传。",style=MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("chi_sim+eng" to "简中＋英文","chi_tra+eng" to "繁中＋英文","eng" to "英文").forEach {(key,label)->FilterChip(settings.ocrLanguages==key,{vm.preferences.update(settings.copy(ocrLanguages=key))},{Text(label)})}
        }
        Text(listOf("chi_sim" to "简中","chi_tra" to "繁中","eng" to "英文").joinToString(" · ") {(key,label)->"$label：${if(models[key]!=null) "已导入" else "未导入"}"},style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={import.launch(arrayOf("*/*"))},enabled=!busy,modifier=Modifier.testTag("import-ocr-model")) {Text(if(busy) "校验模型中…" else "导入 OCR 模型")}
        Text("仅接受 chi_sim、chi_tra、eng.traineddata；单文件最多 64 MiB。模型不随 APK 提供。",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={vm.showLicenses()}) {Text("开源组件与许可证")}
        if(tasks.isNotEmpty()) Text("转换任务",style=MaterialTheme.typography.titleSmall)
        tasks.take(10).forEach {task->TextButton(onClick={vm.showConversion(task.id)}) {Text("${task.label} · ${task.completedPages}/${task.totalPages} 页")}}
    }
    licenses?.let { text -> AlertDialog(onDismissRequest=vm::closeLicenses,title={Text("第三方许可证")},text={Text(text,Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall)},confirmButton={TextButton(onClick=vm::closeLicenses) {Text("关闭")}}) }
}

@Composable
internal fun PdfConversionDialog(vm: LibraryViewModel) {
    val id by vm.conversionId.collectAsStateWithLifecycle()
    val taskId=id ?: return
    val flow=remember(taskId) {vm.conversions.observe(taskId)}
    val task by flow.collectAsStateWithLifecycle(null)
    val books by vm.books.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(),vm::importModels)
    val row=task ?: return
    LaunchedEffect(row.stage,row.resultBookId) {
        if(row.stage=="COMPLETE") books.firstOrNull {it.id==row.resultBookId}?.let {vm.dismissConversion();vm.open(it)}
    }
    LaunchedEffect(books,row.stage) {
        if(row.stage=="COMPLETE") books.firstOrNull {it.id==row.resultBookId}?.let {vm.dismissConversion();vm.open(it)}
    }
    AlertDialog(onDismissRequest=vm::dismissConversion,title={Text("PDF 转为电子书")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(row.label,Modifier.testTag("conversion-stage"))
        if(row.totalPages>0) {Text("已处理 ${row.completedPages}/${row.totalPages} 原文页");if(row.active) LinearProgressIndicator(progress={row.completedPages.toFloat()/row.totalPages},modifier=Modifier.fillMaxWidth())}
        else if(row.active) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(row.imagePages>0) Text("${row.imagePages} 页保留原图，不承诺复杂版式完全重排。",style=MaterialTheme.typography.bodySmall)
        if(row.error.isNotBlank()) Text(row.error)
        if(row.stage=="WAITING_MODEL") OutlinedButton(onClick={import.launch(arrayOf("*/*"))}) {Text("导入 OCR 模型")}
        if(row.stage in listOf("WAITING_MODEL","FAILED","CANCELLED")) TextButton(onClick={vm.resumeConversion(row.id)}) {Text("继续 / 重试")}
        if(row.active) TextButton(onClick={vm.cancelConversion(row.id)}) {Text("取消转换")}
        Text("退出弹层不取消后台任务。原书的进度与批注不会迁移或删除。",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick=vm::dismissConversion) {Text("后台继续 / 关闭")}},dismissButton={TextButton(onClick={
        vm.dismissConversion();context.startActivity(Intent(context,PdfActivity::class.java).putExtra("bookId",row.sourceBookId))
    },enabled=row.sourceBookId!=null) {Text("先读原 PDF")}})
}
