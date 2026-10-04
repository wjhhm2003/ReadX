@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.readx.app

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.readx.app.reader.LocalWebReader
import io.readx.app.ui.ReaderPreferences
import io.readx.app.ui.ReaderSettings
import io.readx.app.ui.ReadingTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ImmersiveReaderInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun fullViewportFloatingMenuColorsCancellationAndThemeReallyChange() {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>();val prefs=ReaderPreferences(app);val old=prefs.settings.value
        prefs.update(ReaderSettings(theme=ReadingTheme.BLACK))
        val title="沉浸批注验收 ${System.nanoTime()}";val file=File(app.cacheDir,"$title.epub")
        val paragraphs=(1..30).joinToString("") {"<p>这段正文由测试生成。第${it}段用于检查沉浸阅读、荧光笔、划线与颜色，不是用户的私人书籍内容。重复标记应当更新原记录，而不是使颜色越叠越深。</p>"}
        ZipOutputStream(file.outputStream()).use {zip->mapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
            "OPS/book.opf" to "<package xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>$title</dc:title></metadata><manifest><item id='c' href='chapter.xhtml'/></manifest><spine><itemref idref='c'/></spine></package>",
            "OPS/chapter.xhtml" to "<html><body><h1>第一章 界面验证</h1>$paragraphs</body></html>").forEach {(name,text)->zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry()}}
        val book=runBlocking {app.repository.import(Uri.fromFile(file))}
        try {
            ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {scenario->
                compose.waitUntil(15000) {compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()};compose.onAllNodesWithText(title).onLast().performClick()
                var reader:LocalWebReader?=null
                try {compose.waitUntil(15000) {scenario.onActivity {reader=findReader(it.window.decorView)};reader?.let {!it.restoring && it.alpha>.99f}==true}}
                catch(error: Throwable) {scenario.onActivity {reader=findReader(it.window.decorView);android.util.Log.e("ReadXUiTest","url=${reader?.url}, restoring=${reader?.restoring}, alpha=${reader?.alpha}, size=${reader?.width},${reader?.height}, range=${reader?.horizontalRange()}, generation=${reader?.loadGeneration}")};TestScreenshots.capture("reader-031-failure");throw error}
                compose.onAllNodesWithContentDescription("目录").assertCountEquals(0)
                var height=0;scenario.onActivity {height=reader!!.height;assertTrue(height>it.resources.displayMetrics.heightPixels*.8f)}
                TestScreenshots.capture("reader-031-immersive")
                compose.onNodeWithTag("reader-content").performTouchInput {click(center)};compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithContentDescription("目录").assertIsDisplayed()
                scenario.onActivity {assertEquals(height,reader!!.height)};TestScreenshots.capture("reader-031-dock")
                compose.onNodeWithTag("reader-content").performTouchInput {click(center)}
                var x=0f;var y=0f;scenario.onActivity {val loc=IntArray(2);reader!!.getLocationOnScreen(loc);val d=it.resources.displayMetrics.density;x=loc[0]+180*d;y=loc[1]+135*d}
                val instrumentation=InstrumentationRegistry.getInstrumentation();val now=SystemClock.uptimeMillis()
                val down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);instrumentation.sendPointerSync(down);down.recycle();Thread.sleep(650)
                val up=MotionEvent.obtain(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y,0);instrumentation.sendPointerSync(up);up.recycle()
                compose.waitUntil(8000) {compose.onAllNodesWithTag("selection-menu").fetchSemanticsNodes().isNotEmpty()}
                TestScreenshots.capture("reader-031-selection")
                compose.onNodeWithTag("mark-color-#75BEFF").performClick();compose.onNodeWithText("荧光笔").performClick()
                compose.waitUntil(8000) {runBlocking {app.repository.dao.annotations(book.id).any {it.kind=="HIGHLIGHT" && it.color=="#75BEFF"}}}
                val mark=runBlocking {app.repository.dao.annotations(book.id).single {it.kind=="HIGHLIGHT"}}
                runBlocking {app.repository.dao.upsertAnnotation(mark.copy(id="repeat-test"))};assertEquals(1,runBlocking {app.repository.dao.annotations(book.id).count {it.kind=="HIGHLIGHT"}})
                var visibleMarks=0
                compose.waitUntil(5000) {scenario.onActivity {reader!!.trusted("document.querySelectorAll('[data-readx-mark]').length") {visibleMarks=it.toIntOrNull()?:0}};visibleMarks>0}
                var bx=0f;var by=0f
                scenario.onActivity {reader!!.trusted("JSON.stringify(document.querySelector('[data-readx-mark]').getBoundingClientRect().toJSON())") {value->
                    val raw=org.json.JSONTokener(value).nextValue() as? String
                    if(raw!=null) {
                    val r=org.json.JSONObject(raw);val d=reader!!.resources.displayMetrics.density
                    val loc=IntArray(2);reader!!.getLocationOnScreen(loc);bx=loc[0]+(r.getDouble("x")+r.getDouble("width")/2).toFloat()*d;by=loc[1]+(r.getDouble("y")+r.getDouble("height")/2).toFloat()*d
                    }
                }}
                compose.waitUntil(5000) {bx>0 && by>0}
                val stamp=SystemClock.uptimeMillis();for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {val event=MotionEvent.obtain(stamp,SystemClock.uptimeMillis(),action,bx,by,0);instrumentation.sendPointerSync(event);event.recycle()}
                compose.waitUntil(8000) {compose.onAllNodesWithText("取消标记").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("取消标记").performClick()
                compose.waitUntil(8000) {runBlocking {app.repository.dao.annotations(book.id).none {it.kind=="HIGHLIGHT"}}}
                if(compose.onAllNodesWithContentDescription("返回书架").fetchSemanticsNodes().isEmpty()) {
                    compose.onNodeWithTag("reader-content").performTouchInput {click(center)}
                }
                compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("返回书架").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithContentDescription("返回书架").performClick()
                compose.onNode(hasText("设置") and hasClickAction()).performClick();compose.onNodeWithText("紫色").performClick()
                val purple = io.readx.app.ui.accentScheme(io.readx.app.ui.ThemeAccent.PURPLE,"",false).primary.toArgb() and 0xFFFFFF
                compose.onNodeWithText("强调色 #${"%06X".format(purple)}").assertIsDisplayed()
                if(android.os.Build.VERSION.SDK_INT>=31) {
                    compose.onNodeWithTag("dynamic-colors-switch").performClick()
                    val color=androidx.compose.material3.dynamicLightColorScheme(app).primary.toArgb() and 0xFFFFFF
                    compose.onNodeWithText("强调色 #${"%06X".format(color)}").assertIsDisplayed()
                }
                TestScreenshots.capture("theme-031-live")
            }
        } finally {runBlocking {app.repository.delete(book)};file.delete();prefs.update(old)}
    }
    private fun findReader(view:View):LocalWebReader? {if(view is LocalWebReader && view.isEnabled) return view;if(view is ViewGroup) for(i in 0 until view.childCount) findReader(view.getChildAt(i))?.let {return it};return null}
}
