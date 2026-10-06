package io.readx.app

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.readx.app.data.*
import io.readx.app.reader.*
import io.readx.app.ui.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PolishUiInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun mergedShelfGridExportBubbleAndExactSlider() {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>();val prefs=ReaderPreferences(app);val old=prefs.settings.value
        val clipboard=app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager;val oldClipboard=clipboard.primaryClip
        prefs.update(ReaderSettings())
        val file=File(app.cacheDir,"打磨验收样书-${System.nanoTime()}.txt").apply {writeText("第一章 打磨测试\n"+(1..110).joinToString("\n") {"自生成第 $it 段，中文与 emoji 😀用于真实操作验证，Hello reader selection。"}+"\n第二章 下一章\n"+"这是下一章。".repeat(300))}
        val book=runBlocking {app.repository.import(Uri.fromFile(file))}
        try {
            runBlocking {app.repository.dao.savePosition(book.id,0,0f,1)}
            ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {scenario->
                lateinit var vm:LibraryViewModel;scenario.onActivity {vm=ViewModelProvider(it)[LibraryViewModel::class.java]}
                compose.waitUntil(20000) {compose.onAllNodesWithText("网格").fetchSemanticsNodes().isNotEmpty()}
                compose.onAllNodesWithText("首页").assertCountEquals(0)
                compose.onNodeWithContentDescription("折叠或展开最近在读").performClick()
                compose.onNode(hasSetTextAction()).performTextInput(book.title)
                scenario.onActivity {androidx.core.view.WindowInsetsControllerCompat(it.window,it.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())}
                compose.onNodeWithText("网格").performClick();compose.waitUntil {prefs.settings.value.shelfGrid}
                compose.onNodeWithText("排序：最近阅读").performClick();compose.onNodeWithText("文件大小").performClick()
                compose.waitUntil {prefs.settings.value.shelfSort=="SIZE"}
                screenshot(app,"070-grid-generated.png")
                scenario.onActivity {vm.open(book)}
                lateinit var view:NativeReaderView
                await {var ok=false;scenario.onActivity {native(it.window.decorView)?.let {v->if(v.ready) {view=v;ok=true}}};ok}
                val anchor=runBlocking(Dispatchers.IO) {view.engine!!.source.anchor(view.currentOffset()+10,view.currentOffset()+30)!!}
                scenario.onActivity {vm.addTextAnnotation("NOTE",anchor,"自生成测试笔记",0f)}
                await {runBlocking {app.repository.dao.annotations(book.id)}.any {it.note=="自生成测试笔记"}}
                val exported=File(app.cacheDir,"polish-export.txt")
                runBlocking {app.repository.exportAnnotations(book.id,Uri.fromFile(exported),false)}
                assertTrue(exported.readText().contains("自生成测试笔记"));exported.delete()
                scenario.onActivity {vm.copyAnnotations(book)}
                await {(app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.contains("自生成测试笔记")==true}
                // Long press launches a compact anchored bubble, not a full quote dialog.
                scenario.onActivity {val t=SystemClock.uptimeMillis();val e=android.view.MotionEvent.obtain(t,t,android.view.MotionEvent.ACTION_DOWN,view.width*.45f,view.height*.35f,0);view.dispatchTouchEvent(e);e.recycle()}
                compose.waitUntil(15000) {compose.onAllNodesWithTag("selection-menu").fetchSemanticsNodes().isNotEmpty()}
                screenshot(app,"070-selection-generated.png")
                scenario.onActivity {view.clearSelection()}
                scenario.onActivity {val t=SystemClock.uptimeMillis();val e=android.view.MotionEvent.obtain(t,t,android.view.MotionEvent.ACTION_CANCEL,0f,0f,0);view.dispatchTouchEvent(e);e.recycle()}
                scenario.onActivity {val t=SystemClock.uptimeMillis();for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {val e=android.view.MotionEvent.obtain(t,t+40,action,view.width*.5f,view.height*.45f,0);view.dispatchTouchEvent(e);e.recycle()}}
                compose.waitUntil(30000) {compose.onAllNodesWithTag("reading-progress-slider").fetchSemanticsNodes().isNotEmpty()}
                await {view.engine!!.count!=null}
                compose.onNodeWithTag("exact-page-entry").performTouchInput {longClick()}
                compose.waitUntil(10000) {compose.onAllNodesWithText("精确页码").fetchSemanticsNodes().isNotEmpty()}
                compose.onNode(hasSetTextAction()).performTextClearance();compose.onNode(hasSetTextAction()).performTextInput("2")
                compose.onNodeWithText("跳转").performClick()
                await {view.pageInfo().first==2}
                screenshot(app,"070-progress-generated.png")
                scenario.onActivity {vm.close()}
            }
        } finally {if(oldClipboard!=null)clipboard.setPrimaryClip(oldClipboard) else clipboard.clearPrimaryClip();prefs.update(old);runBlocking {app.repository.delete(book)};file.delete()}
    }
    private fun native(v:View):NativeReaderView? {if(v is NativeReaderView)return v;if(v is ViewGroup)for(i in 0 until v.childCount)native(v.getChildAt(i))?.let {return it};return null}
    private fun await(test:()->Boolean) {compose.waitUntil(20000) {test()}}
    private fun screenshot(app:ReadXApplication,name:String) {val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();val f=File(app.getExternalFilesDir(null),"qa/$name").apply {parentFile!!.mkdirs()};f.outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
}
