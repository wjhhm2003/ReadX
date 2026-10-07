package io.readx.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.readx.app.ui.ReaderPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun sampleDirectorySettingsAndSearchWork() {
        val app = compose.activity.application as ReadXApplication
        val preferences = ReaderPreferences(app)
        val oldSettings = preferences.settings.value
        val priorIds = runBlocking { app.repository.dao.observeBooks().first().map { it.id }.toSet() }
        try {
            if (compose.onAllNodesWithText("欢迎使用 ReadX").fetchSemanticsNodes().isNotEmpty()) {
                compose.onAllNodesWithText("欢迎使用 ReadX").onLast().performClick()
            } else {
                compose.onNodeWithContentDescription("书架操作").performClick()
                compose.onNodeWithText("导入示例").performClick()
            }
            compose.waitUntil(15000) { compose.onAllNodesWithTag("reader-content").fetchSemanticsNodes().isNotEmpty() }
            compose.showTextReaderControls()
            compose.onNodeWithContentDescription("目录").performClick()
            compose.onNodeWithText("第三章 下一步").performClick()
            compose.openFullReaderSettings()
            compose.onNodeWithText("暖色").performClick()
            compose.onNodeWithText("阅读设置").assertIsDisplayed()
            compose.onNodeWithText("完成").performClick()
            compose.showTextReaderControls()
            compose.onNodeWithContentDescription("目录").performClick()
            compose.onNodeWithContentDescription("搜本书").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("安静")
            compose.waitUntil(15000) { compose.onAllNodesWithText("欢迎使用 ReadX · 第二章 给阅读留一点空间").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("欢迎使用 ReadX · 第二章 给阅读留一点空间").performClick()
            compose.showTextReaderControls()
            compose.onNodeWithContentDescription("目录").performClick()
            compose.onNodeWithText("第二章 给阅读留一点空间").assertIsDisplayed()
            compose.onNodeWithText("完成").performClick()
            compose.onNodeWithContentDescription("返回书架").performClick()
            compose.onNodeWithText("ReadX").assertIsDisplayed()
            compose.onAllNodesWithText("欢迎使用 ReadX").onLast().assertIsDisplayed()
        } finally {
            preferences.update(oldSettings)
            runBlocking {
                app.repository.dao.observeBooks().first().filter { it.id !in priorIds && it.title == "欢迎使用 ReadX" }
                    .forEach { app.repository.delete(it) }
            }
        }
    }
}
