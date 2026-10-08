package com.slate.launcher

import android.widget.EditText
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33])
class ColorPickerInteractionTest {
    @Test fun onlyApplyRemembersTheTypedColor() {
        val controller = Robolectric.buildActivity(HomeSelectionTest.HostActivity::class.java).setup()
        val activity = controller.get()
        val prefs = PreferencesManager(activity)
        var applied: String? = null

        fun picker() = ColorPickerDialog(
            context = activity, title = "Color", initialColor = "#000000", bgColor = "#000000"
        ) { applied = it }.apply { show() }

        try {
            picker().apply {
                findViewById<EditText>(R.id.hexInput).setText("#ABCDEF")
                findViewById<View>(R.id.btnCancel).performClick()
            }
            assertNull(applied)
            assertTrue(prefs.customColors.isEmpty())

            picker().apply {
                findViewById<EditText>(R.id.hexInput).setText("#ABCDEF")
                findViewById<View>(R.id.btnApply).performClick()
            }
            assertEquals("#ABCDEF", applied)
            assertEquals(listOf("#ABCDEF"), prefs.customColors)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun resetRunsItsActionWithoutAddingARecentColor() {
        val controller = Robolectric.buildActivity(HomeSelectionTest.HostActivity::class.java).setup()
        val activity = controller.get()
        val prefs = PreferencesManager(activity)
        var reset = false
        var applied = false
        try {
            val dialog = ColorPickerDialog(
                context = activity, title = "Color", initialColor = "#ABCDEF", bgColor = "#000000",
                showReset = true, onReset = { reset = true }
            ) { applied = true }
            dialog.show()
            dialog.findViewById<View>(R.id.btnReset).performClick()
            assertTrue(reset)
            assertFalse(applied)
            assertTrue(prefs.customColors.isEmpty())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
