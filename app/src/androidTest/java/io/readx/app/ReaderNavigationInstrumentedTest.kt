package io.readx.app

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.geometry.Offset
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ReaderNavigationInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun txtTapWholeBookProgressAndPersistentHighlight()=exercise("txt")
    @Test fun epubTapWholeBookProgressAndPersistentHighlight()=exercise("epub")
    private fun exercise(format: String) {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>()
        val preferences=ReaderPreferences(app);val old=preferences.settings.value;preferences.update(ReaderSettings())
        val title="全书批注验收 ${System.nanoTime()}"
        val text=(1..35).joinToString("\n\n") {"第${it}段。读书时长按选中文字，可以保存荧光笔、下划线与文字批注。翻页后返回，修改字号后重排，标记仍应对应原来的文字，不应该标到其他重复文字。"}
        val file=File(app.cacheDir,"$title.$format")
        if(format=="txt") file.writeText("第一章 触控批注\n$text\n\n第二章 全书进度\n$text\n$title")
        else {
            val entries=mapOf("META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
                "OPS/book.opf" to "<package xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>$title</dc:title></metadata><manifest><item id='a' href='a.xhtml'/><item id='b' href='b.xhtml'/></manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>",
                "OPS/a.xhtml" to "<html><body><h1>第一章 触控批注</h1>"+text.split("\n\n").joinToString("") {"<p>$it</p>"}+"<script>document.body.textContent='BAD SCRIPT';</script></body></html>",
                "OPS/b.xhtml" to "<html><body><h1>第二章 全书进度</h1>"+text.split("\n\n").joinToString("") {"<p>$it</p>"}+"</body></html>")
            ZipOutputStream(file.outputStream()).use {zip->entries.forEach {(name,value)->zip.putNextEntry(ZipEntry(name));zip.write(value.toByteArray());zip.closeEntry()}}
        }
        val book=runBlocking {app.repository.import(Uri.fromFile(file))}
        try {
            ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {scenario->
                compose.waitUntil(15000) {compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()}
                compose.onAllNodesWithText(title).onLast().performClick()
                waitReady(scenario)
                showChrome()
                compose.onNodeWithContentDescription("进度").performClick()
                compose.waitUntil(30000) {compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isNotEmpty()}
                compose.onAllNodesWithText("上一页").assertCountEquals(0);compose.onAllNodesWithText("下一页").assertCountEquals(0)
                compose.onNodeWithTag("reader-content").performTouchInput {click(Offset(width*.83f,height*.45f))}
                waitReady(scenario) {it.pageInfo().first==2}
                showChrome()
                compose.onNodeWithTag("reader-content").performTouchInput {click(center)}
                compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isEmpty()}
                compose.onNodeWithTag("reader-content").performTouchInput {click(center)}
                compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithContentDescription("目录").assertIsDisplayed()
                compose.onNodeWithTag("reader-content").performTouchInput {click(Offset(width*.17f,height*.45f))}
                waitReady(scenario) {it.pageInfo().first==1}
                var density=1f;scenario.onActivity {density=it.resources.displayMetrics.density}
                // A real long-press opens ReadX's controlled selection popup beside the text.
                var x=0f;var y=0f
                scenario.onActivity {activity->val reader=findReader(activity.window.decorView)!!;val location=IntArray(2);reader.getLocationOnScreen(location);x=location[0]+72*density;y=location[1]+128*density}
                val instrumentation=InstrumentationRegistry.getInstrumentation()
                val now=android.os.SystemClock.uptimeMillis()
                val down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0)
                instrumentation.sendPointerSync(down);down.recycle()
                Thread.sleep(650) // Real Android long-press timer, not Compose's virtual animation clock.

                val up=android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,x,y,0)
                instrumentation.sendPointerSync(up);up.recycle()
                compose.waitUntil(8000) {compose.onAllNodesWithText("荧光笔").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("荧光笔").performClick()
                compose.waitUntil(8000) {runBlocking {app.repository.dao.annotations(book.id).any {it.kind=="HIGHLIGHT"}}}
                var markCount=0
                compose.waitUntil(8000) {scenario.onActivity {findReader(it.window.decorView)?.trusted("document.querySelectorAll('[data-readx-mark]').length") {value->markCount=value.toIntOrNull()?:0}};markCount>0}
                TestScreenshots.capture("$format-030-highlight")
                val annotation=runBlocking {app.repository.dao.annotations(book.id).first {it.kind=="HIGHLIGHT"}}
                assertTrue(annotation.quote.isNotBlank());assertTrue(annotation.locator.contains("prefix"))
                showChrome()
                compose.onNodeWithContentDescription("进度").performClick()
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).performTouchInput {swipe(center,Offset(width-1f,centerY),500)}
                waitReady(scenario) {it.url.orEmpty().contains(if(format=="epub") "b.xhtml" else "chapter-1")}
                compose.onNodeWithContentDescription("返回书架").performClick()
                compose.onNode(hasText("批注") and hasClickAction()).performClick()
                compose.onAllNodesWithText("荧光笔").onFirst().assertIsDisplayed()
                compose.onNodeWithText(annotation.quote,useUnmergedTree=true).performClick()
                waitReady(scenario)
                markCount=0
                compose.waitUntil(8000) {scenario.onActivity {findReader(it.window.decorView)?.trusted("document.querySelectorAll('[data-readx-mark]').length") {value->markCount=value.toIntOrNull()?:0}};markCount>0}
                showChrome()
                compose.onNodeWithContentDescription("排版").performClick()
                compose.onNodeWithText("全部设置").performClick()
                compose.onNodeWithText("阅读设置").assertIsDisplayed()
                compose.onNodeWithText("纯黑").performClick();compose.onNodeWithText("完成").performClick();waitReady(scenario)
                compose.onNodeWithText("全部设置").performClick()
                val fontSlider = compose.onNode(hasTestTag("font-size-slider") and hasAnyAncestor(hasTestTag("reading-settings-sheet")))
                fontSlider.performScrollTo()
                fontSlider.performTouchInput {swipe(center,Offset(width-1f,centerY),500)}
                compose.onNodeWithText("完成").performClick();waitReady(scenario)
                assertEquals(32f,ReaderPreferences(app).settings.value.fontSize)
                markCount=0
                compose.waitUntil(8000) {scenario.onActivity {findReader(it.window.decorView)?.trusted("document.querySelectorAll('[data-readx-mark]').length") {value->markCount=value.toIntOrNull()?:0}};markCount>0}
                TestScreenshots.capture("$format-030-reader")
            }
        } finally {runBlocking {app.repository.delete(book)};file.delete();preferences.update(old)}
    }
    private fun showChrome() {
        if(compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("reader-content").performTouchInput {click(center)}
            compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isNotEmpty()}
        }
    }
    private fun waitReady(scenario: ActivityScenario<MainActivity>,condition:(LocalWebReader)->Boolean={true}) {
        compose.waitUntil(20000) {var ready=false;scenario.onActivity {ready=findReader(it.window.decorView)?.let {!it.restoring && !it.isTurning && it.alpha>.99f && condition(it)}==true};ready}
    }
    private fun findReader(view: View): LocalWebReader? {
        if(view is LocalWebReader && view.isEnabled) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) findReader(view.getChildAt(i))?.let {return it};return null
    }
}
