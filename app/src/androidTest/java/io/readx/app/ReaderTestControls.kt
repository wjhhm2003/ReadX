package io.readx.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Exercise the real center-tap and panel routes; tests must not force production chrome on. */
internal fun ComposeTestRule.showTextReaderControls() {
    if (onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isEmpty()) {
        onNodeWithTag("reader-content").performTouchInput { click(center) }
        waitUntil(5000) { onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isNotEmpty() }
    }
}

internal fun ComposeTestRule.openFullReaderSettings() {
    showTextReaderControls()
    if (onAllNodesWithText("全部设置").fetchSemanticsNodes().isEmpty()) {
        onNodeWithContentDescription("排版").performClick()
    }
    onNodeWithText("全部设置").performClick()
    onNodeWithTag("reading-settings-sheet").assertIsDisplayed()
}

/** Toggle the real native PDF center-tap route, not a test-only visibility switch. */
internal fun ComposeTestRule.showPdfControls(scenario: androidx.test.core.app.ActivityScenario<io.readx.app.pdf.PdfActivity>) {
    if (onAllNodesWithContentDescription("跳转页码").fetchSemanticsNodes().isNotEmpty()) return
    var x = 0f; var y = 0f
    scenario.onActivity { activity ->
        val view = activity.window.decorView
        val origin = IntArray(2); view.getLocationOnScreen(origin)
        x = origin[0] + view.width * .5f; y = origin[1] + view.height * .5f
    }
    val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    val downTime = android.os.SystemClock.uptimeMillis()
    for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
        val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
        instrumentation.sendPointerSync(event); event.recycle()
    }
    waitUntil(5000) { onAllNodesWithContentDescription("跳转页码").fetchSemanticsNodes().isNotEmpty() }
}
