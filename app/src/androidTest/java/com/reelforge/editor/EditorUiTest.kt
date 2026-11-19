package com.reelforge.editor

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reelforge.editor.ui.MainActivity
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test of the screens: create a project, check the empty editor, rotate, open a tool. */
@RunWith(AndroidJUnit4::class)
class EditorUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** Media and notification access is granted up front, so no system dialog covers the app. */
    @org.junit.Before
    fun grantAccess() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 33) listOf(
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO", "android.permission.POST_NOTIFICATIONS",
        ) else listOf("android.permission.READ_EXTERNAL_STORAGE")
        permissions.forEach { runCatching { automation.grantRuntimePermission(pkg, it) } }
    }

    private fun waitForText(text: String) {
        rule.waitUntil(15_000) {
            // While the activity is being recreated (rotation, screen setting) there is briefly
            // no screen to search; keep waiting instead of failing.
            runCatching {
                rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private fun waitForDescription(description: String) {
        rule.waitUntil(15_000) {
            runCatching {
                rule.onAllNodesWithContentDescription(description, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
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
        waitForText("CutMitra")
        tap("New project")
        waitForText("Create")
        tap("Create")
        // A new, empty project opens the media picker first; close it to see the editor.
        waitForDescription("Close media")
        rule.onNodeWithContentDescription("Close media", useUnmergedTree = true).performClick()
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

        // Music with no music yet opens the music screen; closing it returns to the editor.
        tap("Music", scroll = true)
        waitForDescription("Close music")
        rule.onNodeWithContentDescription("Close music", useUnmergedTree = true).performClick()
        waitForText("Start by adding media")

        // Back to portrait: same project, menu under the timeline, tool opens as a sheet.
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        waitForText("Start by adding media")
        rule.onNodeWithText("Media", useUnmergedTree = true).assertIsDisplayed()
        tap("Music", scroll = true)
        waitForDescription("Close music")
    }
}
