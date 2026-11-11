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
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Taps the text itself, whichever button or chip it sits in. */
    private fun tap(text: String, scroll: Boolean = false) {
        val node = rule.onNodeWithText(text, useUnmergedTree = true)
        if (scroll) node.performScrollTo()
        node.performClick()
    }

    private fun rotate(orientation: Int) {
        rule.activity.runOnUiThread { rule.activity.requestedOrientation = orientation }
        Thread.sleep(2_000)
        rule.waitForIdle()
    }

    @Test
    fun createProject_emptyEditor_rotation_andToolPanel() {
        // Home → new project dialog → editor.
        waitForText("ReelForge")
        tap("New project")
        waitForText("Create")
        tap("Create")
        waitForText("Add videos or photos to begin")

        // Negative: with no clips there is nothing to export or undo.
        rule.onNodeWithText("Export").assertIsNotEnabled()
        rule.onNodeWithText("Undo").assertIsNotEnabled()
        rule.onNodeWithText("Redo").assertIsNotEnabled()

        // Landscape keeps the project open and shows the tools in the side pane.
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        waitForText("Add videos or photos to begin")
        rule.onNodeWithText("Media", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Export").assertIsNotEnabled()

        // Open a tool in landscape: it appears in the side pane with a Close button.
        tap("Music", scroll = true)
        waitForText("Choose audio")
        tap("Close")
        rule.onNodeWithText("Media", useUnmergedTree = true).assertIsDisplayed()

        // Back to portrait: same project, tools in the bottom row.
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        waitForText("Add videos or photos to begin")
        rule.onNodeWithText("Media", useUnmergedTree = true).assertIsDisplayed()
        tap("Music", scroll = true)
        waitForText("Choose audio")
    }
}
