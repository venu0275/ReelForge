package com.reelforge.editor

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
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

    @get:Rule(order = 0)
    val skipTours = SkipToursRule()

    @get:Rule(order = 1)
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
        waitForDescription("Settings")
        rule.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
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
        waitForText("Create project")
        tap("Create project")
        // A new, empty project opens the media picker first; close it to see the editor.
        waitForDescription("Close media")
        rule.onNodeWithContentDescription("Close media", useUnmergedTree = true).performClick()
        waitForText("Start by adding media")

        // The empty frame shows the app's name and the frame's shape.
        waitForText("16:9 · YouTube")

        // Negative: with no clips there is nothing to export, undo, play or decorate. Export and
        // the faded tools say why in a toast instead of doing nothing.
        tap("Export")
        waitForText("Add a video or photo first: tap Media")
        tap("Text", scroll = true)
        waitForText("Add a video or photo first: tap Media")
        rule.onNodeWithContentDescription("Undo").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Redo").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Play").assertIsNotEnabled()
        rule.onNodeWithText("Media", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Add media").assertIsDisplayed()

        // Landscape keeps the project open and shows the menu in the side pane.
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        waitForText("Start by adding media")
        rule.onNodeWithText("Media", useUnmergedTree = true).assertIsDisplayed()

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

    @Test
    fun bugReport_needsATitle_andBuildsTheEmail() {
        waitForDescription("Settings")
        rule.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
        waitForText("Report a bug")
        tap("Report a bug", scroll = true)
        waitForText("Sent by email to")
        // Negative: no title, nothing is sent and the button says what is missing.
        waitForText("Type an issue title to send")
        tap("Type an issue title to send")
        rule.onNodeWithText("No email app on this phone").assertDoesNotExist()
        // Positive: with a title the Send button is ready (it is not tapped: that would open a real
        // email app over the tests). The email it sends is checked directly instead.
        rule.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasText("Issue title"))
            .performTextInput("Test: export closes")
        waitForText("Send report")
        val report = com.reelforge.editor.ui.BugReportText("Test: export closes", "", "1. Tap Export", "App: CutMitra")
        val email = com.reelforge.editor.ui.BugReporter.emailFor(report, emptyList(), null)
        org.junit.Assert.assertEquals(android.content.Intent.ACTION_SENDTO, email.action)
        org.junit.Assert.assertEquals("mailto", email.data?.scheme)
        org.junit.Assert.assertEquals(
            listOf(com.reelforge.editor.ui.BugReporter.address),
            email.getStringArrayExtra(android.content.Intent.EXTRA_EMAIL)?.toList(),
        )
        org.junit.Assert.assertEquals("CutMitra – Test: export closes", email.getStringExtra(android.content.Intent.EXTRA_SUBJECT))
        org.junit.Assert.assertTrue(email.getStringExtra(android.content.Intent.EXTRA_TEXT)!!.contains("Steps to reproduce the issue:"))
        // The link itself carries the subject too, for email apps that only read the link.
        org.junit.Assert.assertTrue(email.data.toString().contains("subject=CutMitra"))
    }
}

/** The one-time Ratio tour (this class does not skip tours). */
@RunWith(AndroidJUnit4::class)
class RatioTourUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("ratioTourSeen", false).commit()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
                "android.permission.POST_NOTIFICATIONS")
                .forEach { runCatching { automation.grantRuntimePermission(pkg, it) } }
        }
    }

    private fun waitFor(timeout: Long = 15_000, check: () -> Boolean) = rule.waitUntil(timeout) {
        runCatching(check).getOrDefault(false)
    }

    private fun shown(text: String) =
        rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun ratioTour_plays_canBeSkipped_andIsRemembered() {
        // The activity may have read the settings before setUp; start the tour from Ratio's own
        // button if it does not start by itself.
        waitFor { shown("CutMitra") }
        rule.onNodeWithText("New project").performClick()
        waitFor { shown("Create project") }
        rule.onNodeWithText("Create project").performClick()
        waitFor { rule.onAllNodesWithContentDescription("Close media").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Close media").performClick()
        val started = runCatching { waitFor(8_000) { shown("Ratio changes your video's shape") } }.isSuccess
        if (!started) {
            rule.onNodeWithText("Ratio 16:9", useUnmergedTree = true).performScrollTo().performClick()
            waitFor { shown("Show me how") }
            rule.onNodeWithText("Show me how", substring = true).performClick()
            waitFor { shown("Ratio changes your video's shape") }
        }
        // Positive: the preview goes through the shapes; the card names them.
        waitFor { shown("16:9 YouTube") }
        // Skip ends it straight away.
        rule.onNodeWithText("Skip").performClick()
        waitFor(5_000) { !shown("Ratio changes your video's shape") }
        val stored = com.reelforge.editor.data.SettingsStore(rule.activity).load()
        org.junit.Assert.assertTrue(stored.ratioTourSeen)
    }
}
