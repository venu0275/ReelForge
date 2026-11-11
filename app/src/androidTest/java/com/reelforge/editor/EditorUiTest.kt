package com.reelforge.editor

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reelforge.editor.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test of the screens: create a project, check the empty editor, rotate, open a tool. */
@RunWith(AndroidJUnit4::class)
class EditorUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String) {
        rule.waitUntil(15_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun rotate(orientation: Int) {
        rule.activity.runOnUiThread { rule.activity.requestedOrientation = orientation }
        Thread.sleep(2_000)
        rule.waitForIdle()
    }

    @Test
    fun createProject_emptyEditor_rotation_andToolPanel() {
        // Home → new project dialog → editor.
        rule.onNodeWithText("New project").performClick()
        rule.onNodeWithText("Create").performClick()
        waitForText("Add videos or photos to begin")

        // Negative: with no clips there is nothing to export or undo.
        rule.onNodeWithText("Export").assertIsNotEnabled()
        rule.onNodeWithText("Undo").assertIsNotEnabled()
        rule.onNodeWithText("Redo").assertIsNotEnabled()

        // Landscape keeps the project open and shows the tools in the side pane.
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        waitForText("Add videos or photos to begin")
        rule.onNodeWithText("Media").assertIsDisplayed()
        rule.onNodeWithText("Export").assertIsNotEnabled()

        // Open a tool in landscape: it appears in the side pane with a Close button.
        rule.onNodeWithText("Music").performScrollTo().performClick()
        waitForText("Choose audio")
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("Media").assertIsDisplayed()

        // Back to portrait: same project, tools in the bottom row.
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        waitForText("Add videos or photos to begin")
        rule.onNodeWithText("Media").assertIsDisplayed()
        rule.onNodeWithText("Music").performScrollTo().performClick()
        waitForText("Choose audio")
    }
}
