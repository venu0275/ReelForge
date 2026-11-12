package com.reelforge.editor

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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

    /** Taps the text itself, whichever button or tile it sits in. */
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
    fun settings_screenDirectionIsAppliedAndRemembered() {
        waitForText("Settings")
        tap("Settings")
        waitForText("Screen direction")
        tap("Landscape")
        rule.waitUntil(10_000) {
            rule.activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        val stored = com.reelforge.editor.data.SettingsStore(rule.activity).load()
        org.junit.Assert.assertEquals(com.reelforge.editor.data.ScreenMode.LANDSCAPE, stored.screen)
        tap("Portrait")
        rule.waitUntil(10_000) {
            rule.activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        tap("Auto")
        rule.waitUntil(10_000) {
            rule.activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        tap("Done")
        waitForText("New project")
    }

    @Test
    fun createProject_emptyEditor_rotation_andToolPanel() {
        // Home → new project dialog → editor.
        waitForText("ReelForge")
        tap("New project")
        waitForText("Create")
        tap("Create")
        waitForText("Start by adding media")

        // Negative: with no clips there is nothing to export, undo, play or decorate.
        rule.onNodeWithText("Export").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Undo").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Redo").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Play").assertIsNotEnabled()
        rule.onNodeWithText("Media", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Add media").assertIsDisplayed()

        // Landscape keeps the project open and shows the menu in the side pane.
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        waitForText("Start by adding media")
        rule.onNodeWithText("Media", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Export").assertIsNotEnabled()

        // Open a tool in landscape: it replaces the menu, and its back arrow returns to the menu.
        tap("Music", scroll = true)
        waitForText("Choose audio")
        rule.onNodeWithContentDescription("Close tool").performClick()
        waitForText("Start by adding media")

        // Back to portrait: same project, menu under the timeline, tool opens as a sheet.
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        waitForText("Start by adding media")
        rule.onNodeWithText("Media", useUnmergedTree = true).assertIsDisplayed()
        tap("Music", scroll = true)
        waitForText("Choose audio")
    }
}
