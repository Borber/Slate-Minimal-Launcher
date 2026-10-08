package com.slate.launcher

import android.app.Dialog
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33], qualifiers = "en-w360dp-h640dp-mdpi")
class HomeSelectionTest {
    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Slate)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = android.R.id.content })
        }
    }

    @Test fun selectionTogglesWithoutLaunchingAndBackCancelsIt() = withHome { activity, fragment, prefs ->
        selectFirstApp(activity, fragment)
        assertEquals(View.VISIBLE, fragment.requireView().findViewById<View>(R.id.selectionBar).visibility)

        val second = appLabel(fragment, "Beta")
        assertTrue(second.createAccessibilityNodeInfo().isCheckable)
        assertFalse(second.createAccessibilityNodeInfo().isChecked)
        second.performClick()
        layout(fragment)
        assertTrue(appLabel(fragment, "Beta").createAccessibilityNodeInfo().isChecked)
        assertTrue(fragment.requireView().findViewById<TextView>(R.id.selectionCount).text.contains("2"))
        assertNull(shadowOf(activity).nextStartedActivity)

        activity.onBackPressedDispatcher.onBackPressed()
        layout(fragment)
        assertEquals(View.GONE, fragment.requireView().findViewById<View>(R.id.selectionBar).visibility)
        assertFalse(appLabel(fragment, "Beta").isSelected)
        assertTrue(prefs.pinnedApps.isEmpty())
    }

    @Test fun selectAllAndBulkPinUpdateTheVisibleList() = withHome { activity, fragment, prefs ->
        selectFirstApp(activity, fragment)
        fragment.requireView().findViewById<View>(R.id.selectionActions).performClick()
        choose(activity.getString(R.string.menu_select_all))
        layout(fragment)
        fragment.requireView().findViewById<View>(R.id.selectionActions).performClick()
        choose(activity.getString(R.string.code_pin_to_top))
        layout(fragment)

        assertEquals(setOf("test.alpha", "test.beta", "test.gamma"), prefs.pinnedApps)
        assertEquals(View.GONE, fragment.requireView().findViewById<View>(R.id.selectionBar).visibility)
    }

    @Test fun cancelingHidePreservesAppsAndConfirmingHideRemovesThem() = withHome { activity, fragment, prefs ->
        selectFirstApp(activity, fragment)
        fragment.requireView().findViewById<View>(R.id.selectionActions).performClick()
        choose(activity.getString(R.string.code_hide))
        ShadowDialog.getLatestDialog().findViewById<View>(R.id.btnCancel).performClick()
        assertTrue(prefs.hiddenApps.isEmpty())
        assertEquals(View.VISIBLE, fragment.requireView().findViewById<View>(R.id.selectionBar).visibility)

        fragment.requireView().findViewById<View>(R.id.selectionActions).performClick()
        choose(activity.getString(R.string.code_hide))
        ShadowDialog.getLatestDialog().findViewById<View>(R.id.btnContinue).performClick()
        layout(fragment)
        assertEquals(setOf("test.alpha"), prefs.hiddenApps)
        assertEquals(View.GONE, fragment.requireView().findViewById<View>(R.id.selectionBar).visibility)
        assertNull(textViews(fragment.requireView()).firstOrNull { it.text.toString() == "Alpha" })
    }

    @Test fun cancelingFirstUninstallStopsTheRemainingPrompts() = withHome { activity, fragment, _ ->
        selectFirstApp(activity, fragment)
        appLabel(fragment, "Beta").performClick()
        layout(fragment)
        fragment.requireView().findViewById<View>(R.id.selectionActions).performClick()
        choose(activity.getString(R.string.code_uninstall))
        ShadowDialog.getLatestDialog().findViewById<View>(R.id.btnContinue).performClick()

        val shadow = shadowOf(activity)
        val request = shadow.nextStartedActivityForResult
        assertNotNull(request)
        assertEquals(Intent.ACTION_DELETE, request.intent.action)
        shadow.receiveResult(request.intent, Activity.RESULT_CANCELED, null)
        ShadowLooper.idleMainLooper()
        assertNull(shadow.nextStartedActivityForResult)
    }

    @Test fun movingLastSelectedAppLeavesTheRemovedFolder() = withHome(configurePrefs = { prefs ->
        val source = FolderStore.createEmpty(prefs, "Source")
        FolderStore.addAppToFolder(prefs, source.id, "test.alpha")
        FolderStore.createEmpty(prefs, "Target")
    }) { activity, fragment, prefs ->
        textViews(fragment.requireView()).first { it.text.toString().startsWith("Source") }.performClick()
        layout(fragment)
        selectFirstApp(activity, fragment)
        fragment.requireView().findViewById<View>(R.id.selectionActions).performClick()
        choose(activity.getString(R.string.code_move_to_folder))
        choose("Target")
        layout(fragment)

        assertNull(FolderStore.all(prefs).firstOrNull { it.name == "Source" })
        assertEquals(listOf("test.alpha"), FolderStore.all(prefs).first { it.name == "Target" }.packages)
        assertNotNull(appLabel(fragment, "Beta"))
        assertNull(textViews(fragment.requireView()).firstOrNull {
            it.text.toString() == activity.getString(R.string.folder_back)
        })
    }

    @Test
    @Config(qualifiers = "en-w640dp-h360dp-land-mdpi")
    fun toolbarFitsLandscapeWithLargeText() = withHome(fontScale = 2f) { activity, fragment, _ ->
        selectFirstApp(activity, fragment)
        val root = fragment.requireView()
        val bar = root.findViewById<View>(R.id.selectionBar)
        val list = root.findViewById<HomeRecyclerView>(R.id.appList)
        assertTrue(bar.height >= 48)
        assertTrue(bar.top >= 0)
        assertTrue(list.paddingBottom >= bar.height)
        listOf(R.id.selectionActions, R.id.selectionCancel).forEach { id ->
            val button = bar.findViewById<View>(id)
            assertTrue(button.height >= 48)
            assertTrue(button.left >= 0)
            assertTrue(button.right <= bar.width)
        }
    }

    private fun withHome(
        fontScale: Float = 1f,
        configurePrefs: (PreferencesManager) -> Unit = {},
        block: (HostActivity, AppDrawerFragment, PreferencesManager) -> Unit
    ) {
        RuntimeEnvironment.setFontScale(fontScale)
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        val activity = controller.get()
        val prefs = PreferencesManager(activity)
        prefs.fontFamily = "sans-serif"
        prefs.maxFontSize = 24
        prefs.alphabeticalFastScroll = false
        configurePrefs(prefs)
        val manager = shadowOf(activity.packageManager)
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        listOf("Alpha", "Beta", "Gamma").forEach { label ->
            val packageName = "test.${label.lowercase()}"
            val application = ApplicationInfo().apply {
                this.packageName = packageName
                nonLocalizedLabel = label
                flags = ApplicationInfo.FLAG_INSTALLED
            }
            val info = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.Main"
                applicationInfo = application
                nonLocalizedLabel = label
                exported = true
            }
            manager.installPackage(PackageInfo().apply {
                this.packageName = packageName
                applicationInfo = application
                activities = arrayOf(info)
            })
            manager.addResolveInfoForIntent(intent, ResolveInfo().apply { activityInfo = info })
        }
        val fragment = AppDrawerFragment()
        activity.supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment).commitNow()
        layout(fragment)
        try {
            block(activity, fragment, prefs)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun selectFirstApp(activity: HostActivity, fragment: AppDrawerFragment) {
        appLabel(fragment, "Alpha").performLongClick()
        choose(activity.getString(R.string.menu_select))
        layout(fragment)
    }

    private fun layout(fragment: AppDrawerFragment) {
        val root = fragment.requireView()
        val config = root.resources.configuration
        val width = config.screenWidthDp
        val height = config.screenHeightDp
        repeat(3) {
            ShadowLooper.idleMainLooper()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, width, height)
        }
    }

    private fun appLabel(fragment: AppDrawerFragment, text: String): TextView =
        textViews(fragment.requireView()).first { it is SelectionTextView && it.text.toString() == text }

    private fun choose(text: String) {
        val dialog: Dialog = ShadowDialog.getLatestDialog()
        textViews(dialog.window!!.decorView).first { it.text.toString() == text }.performClick()
    }

    private fun textViews(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(textViews(view.getChildAt(i)))
    }
}
