@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.readx.app.ui

import io.readx.app.BuildConfig
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
    val bundledState by vm.bundledModelState.collectAsStateWithLifecycle()
    val busy by vm.modelBusy.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val tasks by vm.conversionTasks.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val licenses by vm.licenses.collectAsStateWithLifecycle()
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(),vm::importModels)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(if (BuildConfig.BUNDLED_OCR) "PDF 转为电子书 · 内置 OCR 版" else "PDF 转为电子书",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("将 PDF 转为电子书",Modifier.weight(1f))
            Switch(settings.pdfToEpubEnabled,{ enabled->
                vm.preferences.update(settings.copy(pdfToEpubEnabled=enabled))
                if(enabled && Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
            },Modifier.testTag("pdf-to-epub-switch"))
        }
        Text("开启后按需生成独立 EPUB，原 PDF 不变。复杂内容保留原图；扫描页需本地 OCR 模型，不联网、不上传。",style=MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf(
                "chi_sim+eng" to "简中＋英文",
                "chi_tra+eng" to "繁中＋英文",
                "chi_tra" to "繁体中文",
                "chi_sim" to "简体中文",
                "eng" to "英文"
            ).forEach { (key, label) ->
                FilterChip(settings.ocrLanguages == key, { vm.preferences.update(settings.copy(ocrLanguages = key)) }, { Text(label) })
            }
        }
        val active = downloads.any { !it.state.isFinished }
        listOf("chi_sim" to "简中", "chi_tra" to "繁中", "eng" to "英文").forEach { (key, label) ->
            val ready = models[key] != null
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("$label（$key.traineddata）：${if (ready) "已就绪" else "未导入"}", style = MaterialTheme.typography.bodySmall)
                if (!ready && settings.onlineModels) {
                    TextButton(onClick = { vm.downloadModel(key) }, enabled = !active) {
                        Text("下载${label}模型", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (BuildConfig.BUNDLED_OCR) Text(bundledState, style=MaterialTheme.typography.bodySmall)
        if(bundledState.contains("失败")) TextButton(onClick={vm.retryBundledModels()}, enabled=!busy) {Text("重新部署内置模型")}
        OutlinedButton(onClick={import.launch(arrayOf("*/*"))},enabled=!busy,modifier=Modifier.testTag("import-ocr-model")) {Text(if(busy) "校验模型中…" else (if (BuildConfig.BUNDLED_OCR) "替换 / 导入 OCR 模型" else "导入 OCR 模型"))}
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {Text("允许在线模型下载",Modifier.weight(1f));Switch(settings.onlineModels,vm::onlineModels,Modifier.testTag("online-model-switch"))}
        Text("默认关闭。仅主动下载固定版本的官方模型，校验大小和 SHA-256；不上传书籍/笔记。关闭取消未完成下载，不删除已有模型。",style=MaterialTheme.typography.bodySmall)
        val download=downloads.lastOrNull()
        if(settings.onlineModels) OutlinedButton(onClick=vm::downloadModels,enabled=!active) {Text(if(active) "下载/后台等待…" else "下载所选语言模型（固定版本）")}
        if(download!=null) {
            val bytes=download.progress.getLong("bytes",0);val total=download.progress.getLong("total",0)
            if(active && total>0) {LinearProgressIndicator(progress={bytes.toFloat()/total},modifier=Modifier.fillMaxWidth());Text("$bytes / $total 字节",style=MaterialTheme.typography.labelSmall)}
            download.outputData.getString("error")?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            if(download.state==androidx.work.WorkInfo.State.SUCCEEDED) Text("所选模型已校验并启用",style=MaterialTheme.typography.bodySmall)
        }
        Text("仅接受 chi_sim、chi_tra、eng.traineddata；单文件最多 64 MiB。" + (if (BuildConfig.BUNDLED_OCR) "本 APK 已内置三个模型，无需手动导入。" else "模型不随 APK 提供。"),style=MaterialTheme.typography.bodySmall)
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
        if(row.stage in listOf("WAITING_MODEL","FAILED","CANCELLED")) TextButton(onClick={vm.resumeConversion(row.id)}) {Text(if(row.stage=="FAILED") "重试失败页" else "从检查点继续")}
        if(row.stage in listOf("FAILED","CANCELLED","WAITING_MODEL")) Text("已完成页不会整书重扫；改变识别模型后需要重新识别受影响页。",style=MaterialTheme.typography.bodySmall)
        if(row.active) TextButton(onClick={vm.cancelConversion(row.id)}) {Text("取消转换")}
        Text("退出弹层不取消后台任务。原书的进度与批注不会迁移或删除。",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick=vm::dismissConversion) {Text("后台继续 / 关闭")}},dismissButton={TextButton(onClick={
        vm.dismissConversion();context.startActivity(Intent(context,PdfActivity::class.java).putExtra("bookId",row.sourceBookId))
    },enabled=row.sourceBookId!=null) {Text("先读原 PDF")}})
}
