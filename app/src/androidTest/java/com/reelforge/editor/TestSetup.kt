package com.reelforge.editor

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Runs before the app starts: marks one-time tours as seen, so they do not play over the screens
 * the tests tap through.
 */
class SkipToursRule(private val seen: Boolean = true) : ExternalResource() {
    override fun before() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("ratioTourSeen", seen).commit()
    }
}
