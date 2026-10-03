package io.readx.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun sampleDirectorySettingsAndSearchWork() {
        val prefs = compose.activity.getSharedPreferences("reader-settings", 0)
        val oldTheme = prefs.getString("theme", null)
        try {
        if (compose.onAllNodesWithText("欢迎使用 ReadX").fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodesWithText("欢迎使用 ReadX").onLast().performClick()
        } else {
            compose.onNodeWithContentDescription("书架操作").performClick()
            compose.onNodeWithText("导入示例").performClick()
        }
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("目录").performClick()
        compose.onNodeWithText("第三章 下一步").performClick()
        compose.onAllNodesWithText("第三章 下一步").onFirst().assertIsDisplayed()
        compose.onNodeWithContentDescription("排版").performClick()
        compose.onNodeWithText("暖色").performClick()
        compose.onNodeWithText("阅读设置").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithContentDescription("书内搜索").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("安静")
        compose.waitUntil(15000) { compose.onAllNodesWithText("欢迎使用 ReadX · 第二章 给阅读留一点空间").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("欢迎使用 ReadX · 第二章 给阅读留一点空间").performClick()
        compose.onAllNodesWithText("第二章 给阅读留一点空间").onFirst().assertIsDisplayed()
        compose.onNodeWithContentDescription("返回书架").performClick()
        compose.onNodeWithText("ReadX").assertIsDisplayed()
        compose.onAllNodesWithText("欢迎使用 ReadX").onLast().assertIsDisplayed()
        } finally {
            prefs.edit().apply { if (oldTheme == null) remove("theme") else putString("theme", oldTheme) }.commit()
        }
    }
}
