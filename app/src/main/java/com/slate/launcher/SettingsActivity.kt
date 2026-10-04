package com.slate.launcher

import android.app.ActivityManager
import android.app.Dialog
import android.app.LocaleConfig
import android.app.LocaleManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.slate.launcher.MainActivity.Companion.isColorLight
import com.slate.launcher.MainActivity.Companion.parseColorSafe
import com.slate.launcher.shortcuts.PinnedShortcutStore
import com.slate.launcher.shortcuts.ShortcutDestination
import com.slate.launcher.shortcuts.ShortcutSourceAppPickerDialog
import com.slate.launcher.widgets.ContactShortcut
import com.slate.launcher.widgets.ContactShortcutStore
import com.slate.launcher.widgets.WidgetPickerDialog
import java.io.File
import java.text.Collator
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var switchDoubleTap: MaterialSwitch
    /** Tracked across onResume calls so the OS resync in [syncShortcutHostPermission] fires
     * only on a false→true transition, not on every resume while already granted. */
    private var wasShortcutHostPermissionGranted: Boolean = false
    private lateinit var createBackupLauncher: ActivityResultLauncher<String>
    private lateinit var openBackupLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var importFontLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var importWidgetFontLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var requestRoleLauncher: ActivityResultLauncher<Intent>
    private lateinit var batteryExemptLauncher: ActivityResultLauncher<Intent>
    private lateinit var pickContactLauncher: ActivityResultLauncher<Intent>
    private lateinit var requestCallPhoneLauncher: ActivityResultLauncher<String>
    private lateinit var requestReadContactsLauncher: ActivityResultLauncher<String>
    // Tracks whether the user is currently adding a "call" or "sms" shortcut so the picker
    // callback knows which widget kind to construct.
    private var pendingShortcutType: ContactShortcut.Type? = null
    // Re-runs the biometric-row visibility/reconcile closure from setupSecurity. Captured on
    // setup; invoked from syncPermissionToggles so a biometric-enrollment change made outside
    // Slate (Android system Settings) is detected on the next onResume - mirrors the
    // syncAccessibilityToggle pattern but without an `awaiting*` flag (biometric grant is
    // entirely in-app, no system-settings round-trip).
    private var biometricReconcile: (() -> Unit)? = null

    // Captured during setupSearch; lets [attachContactSearchListener] (a class member
    // function defined outside setupSearch's closure) refresh the search-section gates
    // after the user flips the Search-contacts toggle, so the dependent Google-only sub-
    // row updates its enabled/alpha/sub-label state immediately. Null until setupSearch
    // runs; nulled implicitly when the activity is destroyed.
    private var pendingSearchGateRefresh: (() -> Unit)? = null
    // awaitingAccessibilityPermission and awaitingNotificationPermission
    // are persisted in PreferencesManager to survive process death

    companion object {
        private val MIN_SIZES     = (8..24).toList()
        private val MAX_SIZES     = (20..60).toList()
        private val LINE_SPACINGS = (0..24).toList()
        private val WORD_SPACINGS = (2..28).toList()
        // Widget-strip typography ranges. Text size capped at 32 - labels are short, so a
        // narrower range than the app list is appropriate. Word gap floored at 2 (mirrors
        // WORD_SPACINGS) to prevent adjacent widget labels from running into each other.
        private val WIDGET_TEXT_SIZES = (10..32).toList()
        private val WIDGET_LINE_GAPS  = (0..20).toList()
        private val WIDGET_WORD_GAPS  = (2..28).toList()

        private val GESTURE_SLOTS = listOf(
            Triple(1, Direction.UP,    R.string.settings_gesture_one_finger_up),
            Triple(1, Direction.DOWN,  R.string.settings_gesture_one_finger_down),
            Triple(1, Direction.LEFT,  R.string.settings_gesture_one_finger_left),
            Triple(1, Direction.RIGHT, R.string.settings_gesture_one_finger_right),
        )

        /**
         * (saved value, picker label) for the choices that are saved as a word. A picker takes
         * the value from here by position and never works it out from the label.
         */
        private val ALIGNMENT_LABELS = listOf(
            "left" to R.string.common_left,
            "center" to R.string.common_center,
            "right" to R.string.common_right,
        )
        private val EDGE_LABELS = listOf(
            "top" to R.string.common_top,
            "bottom" to R.string.common_bottom,
        )

        data class FontOption(val key: String, val displayName: String)

        val FONTS = listOf(
            FontOption("gf:tex_gyre_adventor_bold", "TeX Gyre Adventor Bold"),
            FontOption("gf:roboto",                 "Roboto"),
            FontOption("gf:noto_sans",              "Noto Sans"),
            FontOption("gf:coming_soon",            "Coming Soon"),
            FontOption("gf:cutive_mono",            "Cutive Mono"),
            FontOption("sans-serif",                "Sans-serif"),
            FontOption("serif",                     "Serif"),
            FontOption("monospace",                 "Monospace"),
            FontOption("cursive",                   "Cursive"),
        )
        val WEIGHTS = listOf(
            300 to R.string.settings_weight_light,
            400 to R.string.settings_weight_regular,
            500 to R.string.settings_weight_medium,
            700 to R.string.settings_weight_bold,
        )

        data class ColorPreset(val bg: String, val text: String)
        val PRESETS = listOf(
            ColorPreset("#000000", "#808080"),
            ColorPreset("#101010", "#808080"),
            ColorPreset("#d0d0d0", "#263238"),
            ColorPreset("#0f3460", "#d0d0d0"),
        )

        // (pref value, picker label) - order here drives picker order and the default-on-unknown
        // fallback in `folderStyleDisplayLabel`. Keep Chevron first so it doubles as the default.
        val FOLDER_STYLE_LABELS: List<Pair<String, Int>> = listOf(
            PreferencesManager.FOLDER_STYLE_CHEVRON  to R.string.settings_folder_style_chevron,
            PreferencesManager.FOLDER_STYLE_SLASH    to R.string.settings_folder_style_slash,
            PreferencesManager.FOLDER_STYLE_BULLET   to R.string.settings_folder_style_bullet,
            PreferencesManager.FOLDER_STYLE_BRACKETS to R.string.settings_folder_style_brackets,
            PreferencesManager.FOLDER_STYLE_COUNT    to R.string.settings_folder_style_count,
            PreferencesManager.FOLDER_STYLE_PLAIN    to R.string.settings_folder_style_plain,
        )

        /**
         * Order is the picker order, so the default sits first exactly as Chevron does in
         * FOLDER_STYLE_LABELS. One of the four hand-maintained enumerations of these eight
         * values; see the constants in PreferencesManager for the other three.
         */
        val WORK_MARKER_LABELS: List<Pair<String, Int>> = listOf(
            PreferencesManager.WORK_MARKER_BRACKETS to R.string.settings_work_marker_brackets,
            PreferencesManager.WORK_MARKER_WORD     to R.string.settings_work_marker_word,
            PreferencesManager.WORK_MARKER_DAGGER   to R.string.settings_work_marker_dagger,
            PreferencesManager.WORK_MARKER_STAR     to R.string.settings_work_marker_star,
            PreferencesManager.WORK_MARKER_DOT      to R.string.settings_work_marker_dot,
            PreferencesManager.WORK_MARKER_SQUARE   to R.string.settings_work_marker_square,
            PreferencesManager.WORK_MARKER_DIAMOND  to R.string.settings_work_marker_diamond,
            PreferencesManager.WORK_MARKER_NONE     to R.string.settings_work_marker_none,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createBackupLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri -> if (uri != null) saveBackup(uri) }

        openBackupLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) loadBackup(uri) }

        // One launcher per destination rather than a single launcher plus a remembered-target
        // field: SAF can have this process killed while the document picker is foregrounded,
        // and only the ActivityResultRegistry survives that. A plain field would come back
        // null on restore and the imported file would land on the wrong preference.
        importFontLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) importFont(uri, FontTarget.APP) }

        importWidgetFontLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) importFont(uri, FontTarget.WIDGET) }

        requestRoleLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { /* result handled; just return to settings */ }

        batteryExemptLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { updateBatteryBanner() }

        // Picker on Phone.CONTENT_URI returns a URI directly to the selected Phone row, with a
        // one-shot read grant. This avoids needing READ_CONTACTS (the system grants temporary
        // access to that exact row), and naturally resolves contacts with multiple numbers since
        // the user picks the specific number in the picker UI.
        pickContactLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { onContactPicked(it) }
            } else {
                pendingShortcutType = null
            }
        }

        // CALL_PHONE runtime grant. Owned by the Direct-call setup flow - we register the
        // launcher unconditionally (must be done before STARTED), but it is only `launch`ed
        // when the user toggles Direct call ON without an existing grant. The callback writes
        // the final pref + switch state; the detach-set-reattach inside the callback prevents
        // the silent revert from re-firing the listener.
        requestCallPhoneLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val switch = findViewById<MaterialSwitch>(R.id.switchDirectCall)
            switch.setOnCheckedChangeListener(null)
            prefs.directCallEnabled = granted
            switch.isChecked = granted
            attachDirectCallListener(switch)
            // Trigger sub-row appears only when both master Quick toggles AND Direct call are
            // on. On a denial we flip the master back to OFF so it must also be hidden.
            val rowTrigger = findViewById<View>(R.id.rowDirectCallTrigger)
            rowTrigger.visibility =
                if (granted && prefs.quickStripEnabled) View.VISIBLE else View.GONE
            if (!granted) {
                Toast.makeText(
                    this,
                    getString(R.string.settings_permission_required_for_direct_call),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Mirrors the CALL_PHONE flow. Fires after the system READ_CONTACTS prompt resolves;
        // writes the final pref + switch state and detach-set-reattaches the listener so the
        // silent revert doesn't re-fire the user-toggle callback. The permanent-deny case
        // ("Don't ask again") is detected via shouldShowRequestPermissionRationale and offers
        // a Settings deep-link instead of leaving the user with a silent no-op toggle.
        requestReadContactsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val switch = findViewById<MaterialSwitch>(R.id.switchContactSearch)
            switch.setOnCheckedChangeListener(null)
            if (granted) {
                prefs.contactSearchEnabled = true
                switch.isChecked = true
                // Parent toggle now on - un-grey the Google-only sub-row.
                pendingSearchGateRefresh?.invoke()
            } else {
                prefs.contactSearchEnabled = false
                switch.isChecked = false
                // shouldShowRequestPermissionRationale returns false in two cases: never asked,
                // OR the user picked "Don't ask again". Since we just asked, false here means
                // permanent denial - offer the Settings deep-link rather than a Toast that
                // leaves the user stuck.
                if (!androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                        this, android.Manifest.permission.READ_CONTACTS
                    )
                ) {
                    showContactSearchSettingsDialog()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.settings_permission_required_to_enable_contact_search),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                // Parent toggle ended OFF - keep the Google-only sub-row greyed.
                pendingSearchGateRefresh?.invoke()
            }
            attachContactSearchListener(switch)
        }

        prefs = PreferencesManager(this)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        switchDoubleTap = findViewById(R.id.switchDoubleTap)

        applyBackgroundColor()
        setupTextSize()
        setupTypography()
        setupColors()
        setupGestures()
        setupSearch()
        setupBackup()
        setupGeneral()
        setupQuickStrip()
        setupAppShortcuts()
        setupSecurity()
        setupLockLongPressMenus()
        setupAbout()
        setupBatteryBanner()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        // Prevent android.view.WindowLeaked if any of our owned dialogs is showing during a
        // configuration change.
        com.slate.launcher.widgets.WidgetPickerDialog.dismissActive()
        com.slate.launcher.widgets.WidgetArrangeDialog.dismissActive()
        GuidedTourManager.dismissActive()
        keepHiddenInRecentsDialog?.dismiss()
        keepHiddenInRecentsDialog = null
        includePrivateDialog?.dismiss()
        includePrivateDialog = null
        PinEntryDialog.dismissActive()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.followSystemTheme) {
            applySystemThemeColors()
            applyBackgroundColor()
        }
        updateDefaultLauncherRow()
        syncPermissionToggles()
        updateBatteryBanner()
        syncShortcutHostPermission()
    }

    /**
     * The shortcut-host permission is tied to Slate's current default-launcher status, which can
     * change any time the user visits system Settings - not a one-time grant. On a false→true
     * transition (the user just set Slate as default, or switched back to it), re-assert any
     * already-pinned shortcuts at the OS level in case they were cleared while permission was
     * absent. A no-op if nothing needs re-pinning.
     */
    private fun syncShortcutHostPermission() {
        val launcherApps = PinnedShortcutStore.launcherApps(this) ?: return
        val granted = PinnedShortcutStore.hasShortcutHostPermissionSafe(launcherApps)
        if (granted && !wasShortcutHostPermissionGranted) {
            PinnedShortcutStore.resyncAllWithOs(prefs, launcherApps)
        }
        wasShortcutHostPermissionGranted = granted
    }

    private fun syncPermissionToggles() {
        syncAccessibilityToggle()
        syncNotificationToggle()
        // Biometric reconcile: the user might have removed their fingerprint / face
        // enrollment from Android system Settings while Slate Settings was in the background.
        // The closure is set during setupSecurity and re-runs applyVisibility, which now
        // flips `prefs.biometricEnabled` off when enrollment is gone. No-op when biometric
        // is still available or pref is already false.
        biometricReconcile?.invoke()
        // Contact-search reconcile: same shape - pref flips off (with switch silent-revert)
        // when READ_CONTACTS has been revoked while Settings was in the background.
        syncContactSearchToggle()
    }

    private fun syncAccessibilityToggle() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        if (prefs.awaitingAccessibilityPermission && accessibilityEnabled) {
            // Returning from permission grant flow and service is enabled - auto-enable
            prefs.doubleTapToLock = true
            switchDoubleTap.setOnCheckedChangeListener(null)
            switchDoubleTap.isChecked = true
            setupDoubleTapListener()
            prefs.awaitingAccessibilityPermission = false
        } else if (prefs.awaitingAccessibilityPermission && !accessibilityEnabled) {
            // Returned from settings but service not detected yet - retry after delay
            // (service binding can lag behind the secure setting on some OEMs)
            prefs.awaitingAccessibilityPermission = false
            switchDoubleTap.postDelayed({
                if (isAccessibilityServiceEnabled()) {
                    prefs.doubleTapToLock = true
                    switchDoubleTap.setOnCheckedChangeListener(null)
                    switchDoubleTap.isChecked = true
                    setupDoubleTapListener()
                }
            }, 500)
        } else if (prefs.doubleTapToLock && !accessibilityEnabled) {
            // Permission was revoked externally - but give the service a moment
            // to bind before aggressively disabling the toggle
            switchDoubleTap.postDelayed({
                if (!isAccessibilityServiceEnabled()) {
                    prefs.doubleTapToLock = false
                    switchDoubleTap.setOnCheckedChangeListener(null)
                    switchDoubleTap.isChecked = false
                    setupDoubleTapListener()
                }
            }, 500)
        }
    }

    /**
     * Both notification sub-rows - the highlight colour and the ignore-silenced toggle - refine
     * a feature that may be off, so they appear and disappear with it. Centralised because five
     * separate paths flip this (the toggle itself, a permission grant, a revoke, and two
     * deferred re-checks) and every one of them has to move both rows.
     */
    private fun setNotifSubRowsVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowNotifHighlight)?.visibility = v
        findViewById<View>(R.id.rowIgnoreSilentNotifs)?.visibility = v
    }

    private fun syncNotificationToggle() {
        val notifEnabled = isNotificationListenerEnabled()
        val switchNotif = findViewById<MaterialSwitch>(R.id.switchNotifColor) ?: return

        if (prefs.awaitingNotificationPermission && notifEnabled) {
            prefs.notificationColorEnabled = true
            switchNotif.setOnCheckedChangeListener(null)
            switchNotif.isChecked = true
            setupNotifListener(switchNotif)
            setNotifSubRowsVisible(true)
            prefs.awaitingNotificationPermission = false
        } else if (prefs.awaitingNotificationPermission && !notifEnabled) {
            prefs.awaitingNotificationPermission = false
            switchNotif.postDelayed({
                if (isNotificationListenerEnabled()) {
                    prefs.notificationColorEnabled = true
                    switchNotif.setOnCheckedChangeListener(null)
                    switchNotif.isChecked = true
                    setupNotifListener(switchNotif)
                    setNotifSubRowsVisible(true)
                }
            }, 500)
        } else if (prefs.notificationColorEnabled && !notifEnabled) {
            switchNotif.postDelayed({
                if (!isNotificationListenerEnabled()) {
                    prefs.notificationColorEnabled = false
                    switchNotif.setOnCheckedChangeListener(null)
                    switchNotif.isChecked = false
                    setupNotifListener(switchNotif)
                    setNotifSubRowsVisible(false)
                }
            }, 500)
        }
    }

    private fun updateDefaultLauncherRow() {
        val sub = findViewById<TextView>(R.id.labelDefaultLauncherSub) ?: return
        sub.text = if (isAlreadyDefaultLauncher())
            getString(R.string.settings_slate_is_your_default_launcher)
        else
            getString(R.string.settings_set_as_default_launcher_summary)
    }

    // ── Background ───────────────────────────────────────────────

    private fun applyBackgroundColor() {
        val color = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(color)
        val drawable = ColorDrawable(color)

        window.setBackgroundDrawable(drawable)
        findViewById<View>(android.R.id.content).setBackgroundColor(color)
        supportActionBar?.setBackgroundDrawable(drawable)
        applySystemBarColors(color)

        val primary   = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")
        val accent    = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")

        val title = SpannableString(getString(R.string.settings_title)).apply {
            setSpan(ForegroundColorSpan(primary), 0, length, 0)
        }
        supportActionBar?.title = title

        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        applyTextColors(root, primary, secondary, accent)

        val dividerColor = if (isLight) Color.parseColor("#22000000") else Color.parseColor("#22FFFFFF")
        listOf(R.id.divider0, R.id.divider1, R.id.divider1b, R.id.divider2,
               R.id.divider3, R.id.divider3b, R.id.divider3c, R.id.divider3d,
               R.id.divider4, R.id.divider5, R.id.divider6).forEach { id ->
            findViewById<View>(id)?.setBackgroundColor(dividerColor)
        }

        applySwitchColors(
            switchDoubleTap,
            findViewById(R.id.switchSearch),
            findViewById(R.id.switchSearchOnHome),
            findViewById(R.id.switchHideStatusBar),
            findViewById(R.id.switchSortByUsage),
            findViewById(R.id.switchLockOrientation),
            findViewById(R.id.switchNotifColor),
            findViewById(R.id.switchIgnoreSilentNotifs),
            findViewById(R.id.switchShowWorkApps),
            findViewById(R.id.switchSuppressWorkMarker),
            findViewById(R.id.switchSyncToLockscreen),
            findViewById(R.id.switchFollowSystemTheme),
            findViewById(R.id.switchAlphaFastScroll),
            findViewById(R.id.switchHiddenAppsSecurity),
            findViewById(R.id.switchBiometric),
            findViewById(R.id.switchLockLongPressMenus),
            findViewById(R.id.switchKeepHiddenInRecents),
            findViewById(R.id.switchQuickStrip),
            findViewById(R.id.switchDirectCall),
            findViewById(R.id.switchQuickStripDivider),
            findViewById(R.id.switchIncludePrivateInBackup),
            findViewById(R.id.switchContactSearch),
            findViewById(R.id.switchGoogleContactsOnly)
        )
    }

    private fun applySwitchColors(vararg switches: MaterialSwitch?) {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        val accent   = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val trackOff = if (isLight) Color.parseColor("#CCCCCC") else Color.parseColor("#555555")
        val thumbOff = if (isLight) Color.parseColor("#FFFFFF") else Color.parseColor("#888888")

        val thumbStates = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.WHITE, thumbOff)
        )
        val trackStates = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, trackOff)
        )
        switches.filterNotNull().forEach { sw ->
            sw.thumbTintList = thumbStates
            sw.trackTintList = trackStates
        }
    }

    private fun applyTextColors(
        view: android.view.View,
        primary: Int, secondary: Int, accent: Int
    ) {
        when (view) {
            is android.widget.EditText -> {
                view.setTextColor(primary)
                view.setHintTextColor(secondary)
            }
            is TextView -> {
                val looksAllCaps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) view.isAllCaps else true
                val isSectionLabel = looksAllCaps && view.typeface?.isBold == true
                val isDimmed = view.alpha < 0.99f
                view.setTextColor(when {
                    isSectionLabel -> accent
                    isDimmed       -> secondary
                    else           -> primary
                })
            }
            is android.view.ViewGroup -> {
                if (view.tag == "no_theme") return
                for (i in 0 until view.childCount) {
                    applyTextColors(view.getChildAt(i), primary, secondary, accent)
                }
            }
        }
    }

    // ── Text Size ────────────────────────────────────────────────

    private fun setupTextSize() {
        val minSeekBar = findViewById<SeekBar>(R.id.minFontSeekBar)
        val minLabel = findViewById<TextView>(R.id.minFontLabel)
        val maxSeekBar = findViewById<SeekBar>(R.id.maxFontSeekBar)
        val maxLabel = findViewById<TextView>(R.id.maxFontLabel)

        // Normalize any inverted Min/Max that may have been persisted (e.g., from a backup
        // imported before the cross-clamp listeners shipped, or from a much older app version
        // that allowed inversion). Pull Max up to Min so the slider state is always valid by
        // the time the sliders render. The overlap zone 20–24 sp is present in both MIN_SIZES
        // (8–24) and MAX_SIZES (20–60), so this assignment always lands on a valid index.
        if (prefs.maxFontSize < prefs.minFontSize) {
            prefs.maxFontSize = prefs.minFontSize
        }

        minSeekBar.max = MIN_SIZES.size - 1
        minSeekBar.progress = MIN_SIZES.indexOf(prefs.minFontSize)
            .takeIf { it >= 0 } ?: MIN_SIZES.indexOf(PreferencesManager.DEFAULT_MIN_FONT_SIZE).coerceAtLeast(0)
        minLabel.text = getString(R.string.unit_sp, prefs.minFontSize)

        maxSeekBar.max = MAX_SIZES.size - 1
        maxSeekBar.progress = MAX_SIZES.indexOf(prefs.maxFontSize)
            .takeIf { it >= 0 } ?: MAX_SIZES.indexOf(PreferencesManager.DEFAULT_MAX_FONT_SIZE).coerceAtLeast(0)
        maxLabel.text = getString(R.string.unit_sp, prefs.maxFontSize)

        // Cross-clamp: when the user drags Min above Max (or Max below Min), pull the other
        // slider along visibly. The two slider ranges overlap in 20–24 sp, so the indexOf
        // lookup on the paired list always succeeds for any reachable cross-point.
        //
        // The programmatic `progress = …` does NOT recursively fire the paired listener:
        // `seekBarListener` guards on `fromUser`, so user-initiated and programmatic updates
        // are distinguishable. No feedback loop, no debouncing needed.
        //
        // Updating the pref BEFORE the visual progress so any other reader (e.g., the home
        // renderer if it ever woke up mid-update) sees the consistent state.
        minSeekBar.setOnSeekBarChangeListener(seekBarListener { p ->
            val newMin = MIN_SIZES[p]
            prefs.minFontSize = newMin
            minLabel.text = getString(R.string.unit_sp, newMin)
            if (newMin > prefs.maxFontSize) {
                prefs.maxFontSize = newMin
                maxSeekBar.progress = MAX_SIZES.indexOf(newMin).coerceAtLeast(0)
                maxLabel.text = getString(R.string.unit_sp, newMin)
            }
        })
        maxSeekBar.setOnSeekBarChangeListener(seekBarListener { p ->
            val newMax = MAX_SIZES[p]
            prefs.maxFontSize = newMax
            maxLabel.text = getString(R.string.unit_sp, newMax)
            if (newMax < prefs.minFontSize) {
                prefs.minFontSize = newMax
                minSeekBar.progress = MIN_SIZES.indexOf(newMax).coerceAtLeast(0)
                minLabel.text = getString(R.string.unit_sp, newMax)
            }
        })

        val lineSeekBar = findViewById<SeekBar>(R.id.lineSpacingSeekBar)
        val lineLabel   = findViewById<TextView>(R.id.lineSpacingLabel)
        val wordSeekBar = findViewById<SeekBar>(R.id.wordSpacingSeekBar)
        val wordLabel   = findViewById<TextView>(R.id.wordSpacingLabel)

        lineSeekBar.max = LINE_SPACINGS.size - 1
        lineSeekBar.progress = LINE_SPACINGS.indexOf(prefs.lineSpacing)
            .takeIf { it >= 0 } ?: LINE_SPACINGS.indexOf(PreferencesManager.DEFAULT_LINE_SPACING).coerceAtLeast(0)
        lineLabel.text = getString(R.string.unit_dp, prefs.lineSpacing)

        wordSeekBar.max = WORD_SPACINGS.size - 1
        wordSeekBar.progress = WORD_SPACINGS.indexOf(prefs.wordSpacing)
            .takeIf { it >= 0 } ?: WORD_SPACINGS.indexOf(PreferencesManager.DEFAULT_WORD_SPACING).coerceAtLeast(0)
        wordLabel.text = getString(R.string.unit_dp, prefs.wordSpacing)

        lineSeekBar.setOnSeekBarChangeListener(seekBarListener { p ->
            prefs.lineSpacing = LINE_SPACINGS[p]
            lineLabel.text = getString(R.string.unit_dp, LINE_SPACINGS[p])
        })
        wordSeekBar.setOnSeekBarChangeListener(seekBarListener { p ->
            prefs.wordSpacing = WORD_SPACINGS[p]
            wordLabel.text = getString(R.string.unit_dp, WORD_SPACINGS[p])
        })

        applyHomescreenViewToTextSize()
    }

    /**
     * In Flow mode both Min and Max sliders are meaningful (scale by usage). In Minimal List mode
     * every app is one uniform size, driven by `prefs.maxFontSize`, so the Min slider is hidden
     * and the Max slider's label reads "Size" instead of "Maximum".
     */
    private fun applyHomescreenViewToTextSize() {
        val isList = prefs.homescreenView == PreferencesManager.VIEW_LIST
        findViewById<View>(R.id.rowMinFontSize)?.visibility =
            if (isList) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.labelMaximum)?.text =
            if (isList) getString(R.string.settings_size) else getString(R.string.settings_maximum)
    }

    private fun seekBarListener(onChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChanged(p) }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    // ── Typography ───────────────────────────────────────────────

    private fun setupTypography() {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")

        val fontValue = findViewById<TextView>(R.id.fontValue)
        val weightValue = findViewById<TextView>(R.id.weightValue)

        fontValue.setTextColor(secondary)
        weightValue.setTextColor(secondary)

        fontValue.text = fontDisplayName(prefs.fontFamily)
        weightValue.text = getString(
            WEIGHTS.find { it.first == prefs.fontWeight }?.second
                ?: R.string.settings_weight_regular
        )

        val fontItems = FONTS.map { it.displayName } + listOf(
            getString(R.string.settings_font_import_from_storage)
        )

        findViewById<android.view.View>(R.id.rowFont).setOnClickListener {
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_font),
                items = fontItems,
                bgColor = prefs.backgroundColor
            ) { index, label ->
                if (index < FONTS.size) {
                    prefs.fontFamily = FONTS[index].key
                    fontValue.text = label
                } else {
                    importFontLauncher.launch(arrayOf("*/*"))
                }
            }.show()
        }

        _fontValueRef = fontValue

        val alignmentValue = findViewById<TextView>(R.id.alignmentValue)
        alignmentValue.setTextColor(secondary)
        alignmentValue.text = alignmentLabel(prefs.textAlignment)

        findViewById<android.view.View>(R.id.rowAlignment).setOnClickListener {
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_alignment),
                items = ALIGNMENT_LABELS.map { getString(it.second) },
                bgColor = prefs.backgroundColor
            ) { index, label ->
                prefs.textAlignment = ALIGNMENT_LABELS[index].first
                alignmentValue.text = label
            }.show()
        }

        findViewById<android.view.View>(R.id.rowWeight).setOnClickListener {
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_weight),
                items = WEIGHTS.map { getString(it.second) },
                bgColor = prefs.backgroundColor
            ) { index, label ->
                prefs.fontWeight = WEIGHTS[index].first
                weightValue.text = label
            }.show()
        }

        // Folder style - picker maps human labels to the FOLDER_STYLE_* pref values.
        val folderStyleValue = findViewById<TextView>(R.id.folderStyleValue)
        folderStyleValue.setTextColor(secondary)
        folderStyleValue.text = folderStyleDisplayLabel(prefs.folderStyle)

        findViewById<android.view.View>(R.id.rowFolderStyle).setOnClickListener {
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_folder_style),
                items = FOLDER_STYLE_LABELS.map { getString(it.second) },
                bgColor = prefs.backgroundColor,
                // Right-column preview shows exactly how the marker renders for a folder named
                // "Work". Order MUST match FOLDER_STYLE_LABELS one-for-one - the dialog falls
                // back to single-column on a size mismatch, so the alignment is enforced by
                // building this list from the same source.
                secondaryItems = FOLDER_STYLE_LABELS.map { (key, _) ->
                    folderStylePreview(key)
                }
            ) { index, label ->
                prefs.folderStyle = FOLDER_STYLE_LABELS[index].first
                folderStyleValue.text = label
            }.show()
        }

        // Selection style - labels, keys and previews all come from SelectionMarker.STYLES.
        val selectionStyleValue = findViewById<TextView>(R.id.selectionStyleValue)
        selectionStyleValue.setTextColor(secondary)
        selectionStyleValue.text = getString(SelectionMarker.styleFor(prefs.selectionStyle).label)

        findViewById<android.view.View>(R.id.rowSelectionStyle).setOnClickListener {
            val styles = SelectionMarker.STYLES
            val paint = android.graphics.Paint()
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_selection_style),
                items = styles.map { getString(it.label) },
                bgColor = prefs.backgroundColor,
                secondaryItems = styles.map {
                    "${SelectionMarker.glyphFor(it, paint)} ${getString(R.string.sample_app_name)}"
                }
            ) { index, label ->
                prefs.selectionStyle = styles[index].key
                selectionStyleValue.text = label
            }.show()
        }
    }

    /**
     * Resolve the persisted marker pref to its row label. Falls back to the FIRST entry, which
     * is Brackets, so an unknown stored value shows the same label the renderer will actually
     * produce - WorkMarker's `else` arm is brackets too.
     */
    private fun workMarkerDisplayLabel(value: String): String =
        getString(
            WORK_MARKER_LABELS.firstOrNull { it.first == value }?.second
                ?: WORK_MARKER_LABELS.first().second
        )

    /** The label of a saved alignment. An unknown value reads as Center, as it renders. */
    private fun alignmentLabel(value: String): String = getString(
        (ALIGNMENT_LABELS.firstOrNull { it.first == value } ?: ALIGNMENT_LABELS[1]).second
    )

    /** The label of a saved screen edge. An unknown value reads as Top, as it renders. */
    private fun edgeLabel(value: String): String =
        getString((EDGE_LABELS.firstOrNull { it.first == value } ?: EDGE_LABELS[0]).second)

    /**
     * Fixed sample labels for the Settings preview. Chosen to span the four widget label
     * shapes used on the home strip: short state toggle, long state toggle, Name:value with
     * a time, Name:value with a percentage. They are the widgets' own strings, so the preview
     * is faithful - what the user sees here is what they get there. Independent of the user's
     * actual widget selection so the preview is deterministic.
     */
    private fun previewSampleLabels(): List<String> = listOf(
        getString(R.string.widget_wifi),
        getString(R.string.widget_bluetooth),
        getString(R.string.widget_clock_value, "12:34"),
        getString(R.string.widget_battery_value, 65),
    )

    /** Resolve the persisted pref value to the user-visible row label. */
    private fun folderStyleDisplayLabel(value: String): String =
        getString(
            FOLDER_STYLE_LABELS.firstOrNull { it.first == value }?.second
                ?: FOLDER_STYLE_LABELS.first().second
        )

    /**
     * Sample render of each folder marker style for the picker preview column. Mirrors the
     * `folderLabel` helper in AppDrawerFragment but with a fixed sample folder name and a
     * fixed visible-count so the preview is stable. Uses a regular space (not NBSP) for the
     * bullet/count styles - orphan-wrap protection only matters in Flow's wrapping paragraph,
     * not inside a single dialog row.
     */
    private fun folderStylePreview(styleKey: String): String {
        val name = getString(R.string.work_profile_label)
        return when (styleKey) {
            PreferencesManager.FOLDER_STYLE_SLASH    -> "$name/"
            PreferencesManager.FOLDER_STYLE_BULLET   -> "• $name"
            PreferencesManager.FOLDER_STYLE_BRACKETS -> "[$name]"
            PreferencesManager.FOLDER_STYLE_COUNT    -> "$name (5)"
            PreferencesManager.FOLDER_STYLE_PLAIN    -> name
            else                                     -> "$name ›"
        }
    }

    /** Which font preference an asynchronously-completed [importFont] should write to. */
    private enum class FontTarget { APP, WIDGET }

    private var _fontValueRef: TextView? = null

    // The quick-strip font row's label and live preview are refreshed by local functions
    // declared inside setupQuickStrip, so an import that completes later has no way to reach
    // them. Same class-field delegation idiom as pendingSearchGateRefresh above.
    private var pendingWidgetFontRefresh: (() -> Unit)? = null

    private fun fontDisplayName(key: String): String = when {
        key.startsWith("/") -> File(key).nameWithoutExtension
        else -> FONTS.find { it.key == key }?.displayName ?: getString(R.string.common_default)
    }

    private fun importFont(uri: Uri, target: FontTarget) {
        try {
            val rawName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            } ?: "custom_font.ttf"

            val fontDir = File(filesDir, "fonts").apply { mkdirs() }
            val destFile = File(fontDir, rawName)
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }

            val displayName = destFile.nameWithoutExtension
            when (target) {
                FontTarget.APP -> {
                    prefs.fontFamily = destFile.absolutePath
                    _fontValueRef?.text = displayName
                }
                FontTarget.WIDGET -> {
                    prefs.widgetFontFamily = destFile.absolutePath
                    pendingWidgetFontRefresh?.invoke()
                }
            }
            Toast.makeText(
                this, getString(R.string.settings_font_imported, displayName), Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.common_import_failed, e.message), Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Colors ───────────────────────────────────────────────────

    private fun setupColors() {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")
        val borderColor = if (isLight) Color.parseColor("#BBBBBB") else Color.parseColor("#444444")
        val density = resources.displayMetrics.density

        val bgDisplay   = findViewById<TextView>(R.id.bgColorDisplay)
        val bgSwatch    = findViewById<View>(R.id.bgColorSwatch)
        val textDisplay = findViewById<TextView>(R.id.textColorDisplay)
        val textSwatch  = findViewById<View>(R.id.textColorSwatch)
        val folderDisplay = findViewById<TextView>(R.id.folderTextColorDisplay)
        val folderSwatch  = findViewById<View>(R.id.folderTextColorSwatch)

        bgDisplay.setTextColor(secondary)
        textDisplay.setTextColor(secondary)
        folderDisplay.setTextColor(secondary)

        fun updateBgSwatch(hex: String) {
            bgDisplay.text = hex
            bgSwatch.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6f * density
                setColor(parseColorSafe(hex))
                setStroke((1.5f * density).toInt(), borderColor)
            }
        }

        fun updateTextSwatch(hex: String) {
            textDisplay.text = hex
            textSwatch.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6f * density
                setColor(parseColorSafe(hex, Color.GRAY))
                setStroke((1.5f * density).toInt(), borderColor)
            }
        }

        // Unset means "same as App text", so until a folder color is chosen this row simply
        // mirrors the row above it, and keeps mirroring it when that one changes.
        fun updateFolderSwatch() {
            val hex = prefs.folderTextColor ?: prefs.appTextColor
            folderDisplay.text = hex
            folderSwatch.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6f * density
                setColor(parseColorSafe(hex, Color.GRAY))
                setStroke((1.5f * density).toInt(), borderColor)
            }
        }

        updateBgSwatch(prefs.backgroundColor)
        updateTextSwatch(prefs.appTextColor)
        updateFolderSwatch()

        fun syncLockscreenIfNeeded(colorInt: Int) {
            if (!prefs.syncToLockscreen) return
            val ok = MainActivity.applyColorToLockscreen(this, colorInt)
            if (!ok) Toast.makeText(
                this,
                getString(R.string.settings_could_not_set_lockscreen_wallpaper),
                Toast.LENGTH_SHORT
            ).show()
        }

        // Entire row opens the picker - no keyboard input
        fun openBgPicker() {
            ColorPickerDialog(
                context = this,
                title = getString(R.string.settings_background),
                initialColor = prefs.backgroundColor,
                bgColor = prefs.backgroundColor
            ) { hex ->
                prefs.followSystemTheme = false
                prefs.backgroundColor = hex
                updateBgSwatch(hex)
                applyBackgroundColor()
                syncLockscreenIfNeeded(parseColorSafe(hex))
                setupColors()
            }.show()
        }

        fun openTextPicker() {
            ColorPickerDialog(
                context = this,
                title = getString(R.string.settings_app_text_title),
                initialColor = prefs.appTextColor,
                bgColor = prefs.backgroundColor
            ) { hex ->
                prefs.followSystemTheme = false
                prefs.appTextColor = hex
                updateTextSwatch(hex)
                setupColors()
            }.show()
        }

        // Unlike the two pickers above, this one leaves Follow system theme alone. That
        // feature rewrites only the background and app text colors, so a folder color is not
        // something it would overwrite on the next resume.
        fun openFolderTextPicker() {
            ColorPickerDialog(
                context = this,
                title = getString(R.string.settings_folder_text_title),
                initialColor = prefs.folderTextColor ?: prefs.appTextColor,
                bgColor = prefs.backgroundColor,
                // Reset is the way back to "same as App text", offered only once there is a
                // folder color to clear.
                showReset = prefs.folderTextColor != null,
                onReset = {
                    prefs.folderTextColor = null
                    setupColors()
                }
            ) { hex ->
                prefs.folderTextColor = hex
                setupColors()
            }.show()
        }

        findViewById<View>(R.id.rowBgColor).setOnClickListener { openBgPicker() }
        findViewById<View>(R.id.rowTextColor).setOnClickListener { openTextPicker() }
        findViewById<View>(R.id.rowFolderTextColor).setOnClickListener { openFolderTextPicker() }

        // Follow system theme toggle
        val switchFollowSystem = findViewById<MaterialSwitch>(R.id.switchFollowSystemTheme)
        switchFollowSystem.isChecked = prefs.followSystemTheme
        switchFollowSystem.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                switchFollowSystem.isChecked = false
                this@SettingsActivity.showFollowSystemThemeDialog(switchFollowSystem)
            } else {
                prefs.followSystemTheme = false
            }
        }

        // Apply to lockscreen toggle
        val switchSyncToLockscreen = findViewById<MaterialSwitch>(R.id.switchSyncToLockscreen)
        switchSyncToLockscreen.isChecked = prefs.syncToLockscreen
        switchSyncToLockscreen.setOnCheckedChangeListener { _, checked ->
            prefs.syncToLockscreen = checked
            if (checked) {
                val ok = MainActivity.applyColorToLockscreen(this, parseColorSafe(prefs.backgroundColor))
                if (!ok) {
                    Toast.makeText(
                        this,
                        getString(R.string.settings_could_not_set_lockscreen_wallpaper),
                        Toast.LENGTH_SHORT
                    ).show()
                    prefs.syncToLockscreen = false
                    switchSyncToLockscreen.isChecked = false
                }
            }
        }

        // Preset tiles
        val presetIds = listOf(R.id.preset1, R.id.preset2, R.id.preset3, R.id.preset4)
        presetIds.forEachIndexed { i, id ->
            val preset = PRESETS[i]
            val tile = findViewById<TextView>(id)
            tile.setTextColor(Color.parseColor(preset.text))
            tile.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setColor(Color.parseColor(preset.bg))
                setStroke((1f * density).toInt(), borderColor)
            }
            tile.setOnClickListener {
                prefs.followSystemTheme = false
                prefs.backgroundColor = preset.bg
                prefs.appTextColor = preset.text
                updateBgSwatch(preset.bg)
                updateTextSwatch(preset.text)
                applyBackgroundColor()
                syncLockscreenIfNeeded(parseColorSafe(preset.bg))
                setupColors()
            }
        }
    }


    private fun isSystemDarkMode(): Boolean {
        val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun showFollowSystemThemeDialog(switchFollowSystem: MaterialSwitch) {
        val dialog = Dialog(this, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = resources.displayMetrics.density

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        dialog.findViewById<TextView>(R.id.dialogTitle)?.apply {
            text = getString(R.string.settings_follow_system_theme_title)
            setTextColor(accent)
        }

        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.settings_follow_system_theme_body)
            setTextColor(primary)
        }

        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.settings_follow_system_theme_note)
            setTextColor(secondary)
        }

        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener { dialog.dismiss() }
        }

        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            text = getString(R.string.settings_enable)
            setTextColor(accent)
            setOnClickListener {
                dialog.dismiss()
                prefs.followSystemTheme = true
                switchFollowSystem.setOnCheckedChangeListener(null)
                switchFollowSystem.isChecked = true
                setupColors()
                applySystemThemeColors()
                applyBackgroundColor()
                setupColors()
            }
        }

        dialog.show()
    }

    private fun applySystemThemeColors() {
        val preset = if (isSystemDarkMode()) PRESETS[1] else PRESETS[2]
        prefs.backgroundColor = preset.bg
        prefs.appTextColor = preset.text
    }

    private fun applySystemBarColors(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color
        val isLight = isColorLight(color)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLight
            isAppearanceLightNavigationBars = isLight
        }
    }

    // ── Gestures ─────────────────────────────────────────────────

    private fun setupGestures() {
        switchDoubleTap.isChecked = prefs.doubleTapToLock
        setupDoubleTapListener()

        val container = findViewById<LinearLayout>(R.id.gesturesContainer)
        val inflater = LayoutInflater.from(this)

        GESTURE_SLOTS.forEach { (fingers, dir, label) ->
            val row = inflater.inflate(R.layout.item_gesture_row, container, false)
            val labelView = row.findViewById<TextView>(R.id.gestureLabel)
            val actionView = row.findViewById<TextView>(R.id.gestureAction)

            labelView.text = getString(label)
            actionView.text = resolveGestureLabel(prefs.getGestureAction(fingers, dir))

            val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
            val textColor = if (isLight) Color.BLACK else Color.WHITE
            val accentColor = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")
            labelView.setTextColor(textColor)
            actionView.setTextColor(accentColor)

            row.setOnClickListener {
                showGestureActionPicker(fingers, dir, actionView)
            }
            container.addView(row)
        }
    }

    private fun setupDoubleTapListener() {
        switchDoubleTap.setOnCheckedChangeListener { _, checked ->
            if (checked && !isAccessibilityServiceEnabled()) {
                switchDoubleTap.isChecked = false
                showAccessibilityDialog()
            } else {
                prefs.doubleTapToLock = checked
            }
        }
    }

    private fun showAccessibilityDialog() {
        val dialog = Dialog(this, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = resources.displayMetrics.density

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        dialog.findViewById<TextView>(R.id.dialogTitle)?.setTextColor(accent)

        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.settings_accessibility_body)
            setTextColor(primary)
        }

        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.settings_accessibility_privacy)
            setTextColor(secondary)
        }

        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener { dialog.dismiss() }
        }

        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            setTextColor(accent)
            setOnClickListener {
                dialog.dismiss()
                prefs.awaitingAccessibilityPermission = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        dialog.show()
    }

    // Kept as a thin wrapper for readability - the 4 call sites within Settings already use
    // this name. The OEM-compat logic lives in [SlateAccessibilityService.isEnabled] so the
    // home reconciliation in AppDrawerFragment.onResume can share it.
    private fun isAccessibilityServiceEnabled(): Boolean =
        SlateAccessibilityService.isEnabled(this)

    // ── Search ───────────────────────────────────────────────────

    private fun setupSearch() {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")

        val switchEnable = findViewById<MaterialSwitch>(R.id.switchSearch)
        val rowOnHome = findViewById<View>(R.id.rowSearchOnHome)
        val switchOnHome = findViewById<MaterialSwitch>(R.id.switchSearchOnHome)
        val labelOnHomeSub = findViewById<TextView>(R.id.labelSearchOnHomeSub)
        val rowPosition = findViewById<View>(R.id.rowSearchBarPosition)
        val positionValue = findViewById<TextView>(R.id.searchBarPositionValue)
        val labelSearchBarPositionSub = findViewById<TextView>(R.id.labelSearchBarPositionSub)
        val rowContactSearch = findViewById<View>(R.id.rowContactSearch)
        val switchContactSearch = findViewById<MaterialSwitch>(R.id.switchContactSearch)
        val labelContactSearchSub = findViewById<TextView>(R.id.labelContactSearchSub)
        val rowGoogleOnly = findViewById<View>(R.id.rowGoogleContactsOnly)
        val switchGoogleOnly = findViewById<MaterialSwitch>(R.id.switchGoogleContactsOnly)
        val labelGoogleOnlySub = findViewById<TextView>(R.id.labelGoogleContactsOnlySub)

        positionValue.setTextColor(secondary)
        positionValue.text = edgeLabel(prefs.searchBarPosition)

        // Normalize a contradictory persisted state (e.g., from a backup that predates this
        // gate, or manually-edited JSON) where Show-on-home is true while Search is off. Bring
        // the sub-option down to false so the visible state matches the gate below.
        if (!prefs.searchEnabled && prefs.showSearchBarOnHome) {
            prefs.showSearchBarOnHome = false
        }
        // Same normalisation for contact search - it can't exist without master Search either.
        if (!prefs.searchEnabled && prefs.contactSearchEnabled) {
            prefs.contactSearchEnabled = false
        }

        // Cross-row gate for the Search section. "Show on home" can't exist without Search
        // itself, so we visibly disable + grey the row when the master is off, and the sub-
        // label explains how to unlock it. Pattern matches the Sort-by-usage × Fast-scroll
        // gate added earlier - keeps the dependency self-documenting instead of relying on a
        // silent auto-enable (the old behaviour, which surprised users).
        val defaultOnHomeSub = getString(R.string.settings_show_on_home_screen_summary)
        val blockedOnHomeSub = getString(R.string.settings_turn_on_search_to_enable)
        val defaultContactSub = getString(R.string.settings_search_contacts_summary)
        val blockedContactSub = getString(R.string.settings_turn_on_search_to_enable)
        val defaultPositionSub = getString(R.string.settings_top_or_bottom_of_the_screen)
        val blockedPositionSub = getString(R.string.settings_turn_on_search_to_enable)
        val defaultGoogleOnlySub =
            getString(R.string.settings_google_contacts_only_summary)
        val blockedGoogleOnlyMasterSub = getString(R.string.settings_turn_on_search_to_enable)
        val blockedGoogleOnlyParentSub =
            getString(R.string.settings_turn_on_search_contacts_to_enable)
        fun refreshSearchGates() {
            val masterOn = prefs.searchEnabled
            switchOnHome.isEnabled = masterOn
            rowOnHome.alpha = if (masterOn) 1f else 0.4f
            labelOnHomeSub.text = if (masterOn) defaultOnHomeSub else blockedOnHomeSub
            switchContactSearch.isEnabled = masterOn
            rowContactSearch.alpha = if (masterOn) 1f else 0.4f
            labelContactSearchSub.text =
                if (masterOn) defaultContactSub else blockedContactSub
            // Position governs both the swipe-up (transient) search bar AND the
            // show-on-home (persistent) bar. Greyed (not hidden) when master is off so
            // the row stays in its natural place in the section.
            rowPosition.alpha = if (masterOn) 1f else 0.4f
            rowPosition.isClickable = masterOn
            labelSearchBarPositionSub.text =
                if (masterOn) defaultPositionSub else blockedPositionSub
            // Two-level gate for the Google-only filter: the row needs BOTH the master
            // Search toggle AND the Search-contacts sub-toggle to be on. Sub-label tells the
            // user which switch they need to flip first.
            val googleOnlyAvailable = masterOn && prefs.contactSearchEnabled
            switchGoogleOnly.isEnabled = googleOnlyAvailable
            rowGoogleOnly.alpha = if (googleOnlyAvailable) 1f else 0.4f
            labelGoogleOnlySub.text = when {
                !masterOn -> blockedGoogleOnlyMasterSub
                !prefs.contactSearchEnabled -> blockedGoogleOnlyParentSub
                else -> defaultGoogleOnlySub
            }
        }
        // Stored as a class-level reference via a member function delegation so the
        // contact-search listener (a private member function defined outside this scope)
        // can still trigger a gate refresh when the user flips Search-contacts on or off.
        // The closure captures all the views above; assigning to a class field lets
        // attachContactSearchListener invoke it after the pref change settles.
        pendingSearchGateRefresh = ::refreshSearchGates

        switchEnable.isChecked = prefs.searchEnabled
        switchEnable.setOnCheckedChangeListener { _, checked ->
            prefs.searchEnabled = checked
            if (!checked) {
                // Master OFF cascades the sub-options off so they can't linger as stale-but-
                // disabled "ON" states (which would also show the wrong slider in the gate).
                prefs.showSearchBarOnHome = false
                switchOnHome.setOnCheckedChangeListener(null)
                switchOnHome.isChecked = false
                attachOnHomeListener(switchOnHome)

                prefs.contactSearchEnabled = false
                switchContactSearch.setOnCheckedChangeListener(null)
                switchContactSearch.isChecked = false
                attachContactSearchListener(switchContactSearch)
            }
            refreshSearchGates()
        }

        switchOnHome.isChecked = prefs.showSearchBarOnHome
        attachOnHomeListener(switchOnHome)

        // Initial state - reconcile against the live READ_CONTACTS grant before attaching the
        // listener. If the user revoked permission externally between sessions, the pref flips
        // off here silently and the switch renders OFF on this open.
        if (prefs.contactSearchEnabled &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_CONTACTS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            prefs.contactSearchEnabled = false
        }
        switchContactSearch.isChecked = prefs.contactSearchEnabled
        attachContactSearchListener(switchContactSearch)

        // Google-only sub-toggle. No permission flow here (it's a pure preference), so the
        // listener just shows the consent dialog on ON and silently reverts on Cancel.
        // Persists independent of [prefs.contactSearchEnabled] - disabling contact search
        // leaves this pref alone so the user's filter choice survives a parent-toggle
        // round-trip. Refer to the Plan agent's #2 decision in the plan file.
        switchGoogleOnly.isChecked = prefs.googleContactsOnly
        attachGoogleContactsOnlyListener(switchGoogleOnly)

        rowPosition.setOnClickListener {
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_search_bar_position),
                items = EDGE_LABELS.map { getString(it.second) },
                bgColor = prefs.backgroundColor
            ) { index, label ->
                prefs.searchBarPosition = EDGE_LABELS[index].first
                positionValue.text = label
            }.show()
        }

        // Initial gate pass - handles a freshly-opened Settings.
        refreshSearchGates()
    }

    /**
     * Wires the "Search contacts" toggle with the silent-revert idiom. On toggle ON we always
     * show the in-app consent dialog first (Slate's privacy promise), then check the system
     * permission. If already granted, the system prompt is suppressed and the pref flips on
     * directly. If not granted, the system prompt fires via [requestReadContactsLauncher],
     * whose callback writes the final state.
     *
     * On Cancel of the consent dialog (or any dismissal), the switch silently reverts via
     * detach-set-reattach. The user never sees the system permission prompt unless they
     * explicitly tapped "Turn on".
     */
    private fun attachContactSearchListener(switch: MaterialSwitch) {
        switch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                showContactSearchConsentDialog(
                    onConfirm = {
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                            this, android.Manifest.permission.READ_CONTACTS
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            // Already granted from a previous session - skip the system prompt
                            // but still set the pref to true; the in-app consent dialog was
                            // the user's authoritative opt-in.
                            prefs.contactSearchEnabled = true
                            // Refresh the search-section gates so the Google-only sub-row
                            // un-greys (now that its parent is on). The launcher-callback
                            // path covers the deny / permanent-deny branches symmetrically.
                            pendingSearchGateRefresh?.invoke()
                        } else {
                            requestReadContactsLauncher.launch(
                                android.Manifest.permission.READ_CONTACTS
                            )
                        }
                    },
                    onCancel = {
                        switch.setOnCheckedChangeListener(null)
                        switch.isChecked = false
                        attachContactSearchListener(switch)
                    }
                )
            } else {
                prefs.contactSearchEnabled = false
                // Toggle OFF - the Google-only sub-row immediately greys out, but the
                // pref itself stays untouched so re-enabling Search contacts later
                // restores the user's prior filter choice.
                pendingSearchGateRefresh?.invoke()
            }
        }
    }

    /**
     * Wires the "Google contacts only" toggle. Pure-preference toggle (no permission flow),
     * so the listener just shows the explanatory dialog on ON and silently reverts on
     * Cancel. Same detach-set-reattach idiom as [attachContactSearchListener]. The pref is
     * NOT cascade-cleared when the parent Search-contacts toggle goes off - the user's
     * filter choice persists across parent-toggle round-trips.
     */
    private fun attachGoogleContactsOnlyListener(switch: MaterialSwitch) {
        switch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                showGoogleContactsOnlyConsentDialog(
                    onConfirm = { prefs.googleContactsOnly = true },
                    onCancel = {
                        switch.setOnCheckedChangeListener(null)
                        switch.isChecked = false
                        attachGoogleContactsOnlyListener(switch)
                    }
                )
            } else {
                prefs.googleContactsOnly = false
            }
        }
    }

    /**
     * Friendly explanation dialog for the "Google contacts only" toggle. Mirrors the shape
     * of [showContactSearchConsentDialog] but with copy that names the trade-off concretely
     * - duplicates go away, but users without a Google account see no results.
     */
    private fun showGoogleContactsOnlyConsentDialog(
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        val dialog = Dialog(this, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = resources.displayMetrics.density

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        dialog.findViewById<TextView>(R.id.dialogTitle)?.apply {
            text = getString(R.string.settings_google_contacts_only_title)
            setTextColor(accent)
        }
        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.settings_google_contacts_only_body)
            setTextColor(primary)
        }
        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.settings_google_contacts_only_note)
            setTextColor(secondary)
        }

        var consumed = false
        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener {
                consumed = true
                dialog.dismiss()
                onCancel()
            }
        }
        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            text = getString(R.string.settings_turn_on)
            setTextColor(accent)
            setOnClickListener {
                consumed = true
                dialog.dismiss()
                onConfirm()
            }
        }
        dialog.setOnDismissListener { if (!consumed) onCancel() }
        dialog.show()
    }

    /**
     * In-app disclosure dialog shown BEFORE the system READ_CONTACTS prompt fires. Required
     * by Play Store's Prominent Disclosure policy and Slate's brand promise - the user reads
     * the privacy contract here, not in the OS-rendered permission prompt (which only shows
     * the boilerplate "Allow Slate to access your contacts?" line).
     */
    private fun showContactSearchConsentDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
        val dialog = Dialog(this, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = resources.displayMetrics.density

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        dialog.findViewById<TextView>(R.id.dialogTitle)?.apply {
            text = getString(R.string.settings_search_contacts_title)
            setTextColor(accent)
        }
        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.settings_search_contacts_body)
            setTextColor(primary)
        }
        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.settings_search_contacts_privacy)
            setTextColor(secondary)
        }

        var consumed = false
        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener {
                consumed = true
                dialog.dismiss()
                onCancel()
            }
        }
        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            text = getString(R.string.settings_turn_on)
            setTextColor(accent)
            setOnClickListener {
                consumed = true
                dialog.dismiss()
                onConfirm()
            }
        }
        dialog.setOnDismissListener { if (!consumed) onCancel() }
        dialog.show()
    }

    /**
     * Shown after a permanent-deny ("Don't ask again") of the READ_CONTACTS prompt. Offers a
     * Settings deep-link to the app's permission page so the user can re-grant manually. The
     * pref is already flipped off by the launcher callback before this dialog appears, so OK
     * here just dismisses.
     */
    private fun showContactSearchSettingsDialog() {
        val dialog = Dialog(this, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = resources.displayMetrics.density

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        dialog.findViewById<TextView>(R.id.dialogTitle)?.apply {
            text = getString(R.string.settings_permission_needed_title)
            setTextColor(accent)
        }
        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.settings_permission_needed_body)
            setTextColor(primary)
        }
        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.settings_permission_needed_note)
            setTextColor(secondary)
        }
        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            text = getString(R.string.dialog_open_settings)
            setTextColor(accent)
            setOnClickListener {
                dialog.dismiss()
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }
        }
        dialog.show()
    }

    /**
     * Reconcile the `contactSearchEnabled` pref against the live READ_CONTACTS grant. Called
     * from [syncPermissionToggles] so a permission revoked via Android system Settings (while
     * Slate Settings was in the background) is detected the next time the user opens this
     * activity. Silent reconciliation - no Toast, just flip the pref + switch off. Matches the
     * pattern used by [reconcileDoubleTapPref] on the home side.
     */
    private fun syncContactSearchToggle() {
        if (!prefs.contactSearchEnabled) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        prefs.contactSearchEnabled = false
        val switch = findViewById<MaterialSwitch>(R.id.switchContactSearch) ?: return
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = false
        attachContactSearchListener(switch)
        // Parent toggle flipped off externally - re-grey the Google-only sub-row.
        pendingSearchGateRefresh?.invoke()
    }

    /**
     * The Show-on-home listener. Extracted so the master's cascade can detach-set-reattach
     * (silent revert pattern) without duplicating the listener body.
     *
     * Note: the old silent `if (checked && !searchEnabled) searchEnabled = true` auto-enable
     * branch is gone. With the gate in place, `switchOnHome.isEnabled = false` when the master
     * is off, so a user-initiated `checked=true` is impossible while Search is disabled.
     */
    private fun attachOnHomeListener(switchOnHome: MaterialSwitch) {
        switchOnHome.setOnCheckedChangeListener { _, checked ->
            prefs.showSearchBarOnHome = checked
        }
    }

    // ── Gesture action picker ─────────────────────────────────────

    private fun showGestureActionPicker(
        fingers: Int,
        dir: Direction,
        actionView: TextView
    ) {
        val labels = GestureAction.staticActions.map { it.staticLabel(this) } + listOf(
            getString(R.string.settings_gesture_open_app)
        )
        SlateListDialog(
            context = this,
            title = getString(R.string.settings_gesture_action),
            items = labels,
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            if (index == GestureAction.staticActions.size) {
                showAppPicker(fingers, dir, actionView)
            } else {
                val action = GestureAction.staticActions[index]
                prefs.setGestureAction(fingers, dir, action)
                actionView.text = action.staticLabel(this)
            }
        }.show()
    }

    private fun showAppPicker(fingers: Int, dir: Direction, actionView: TextView) {
        val apps = AppRepository(this, prefs).getAllApps()
        SlateListDialog(
            context = this,
            title = getString(R.string.settings_choose_app),
            items = apps.map { it.name },
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            val app = apps[index]
            val action = GestureAction.OpenApp(app.key)
            prefs.setGestureAction(fingers, dir, action)
            actionView.text = app.name
        }.show()
    }

    private fun resolveGestureLabel(action: GestureAction): String =
        when (action) {
            is GestureAction.OpenApp -> try {
                val info = packageManager.getApplicationInfo(action.key, 0)
                packageManager.getApplicationLabel(info).toString()
            } catch (_: Exception) { action.key }
            else -> action.staticLabel(this)
        }

    // ── Backup & Restore ─────────────────────────────────────────

    private fun setupBackup() {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        val primary = if (isLight) Color.BLACK else Color.WHITE

        findViewById<View>(R.id.rowExportBackup).apply {
            setOnClickListener { createBackupLauncher.launch("slate_backup.json") }
            (this as? LinearLayout)?.let {
                for (i in 0 until it.childCount)
                    (it.getChildAt(i) as? TextView)?.setTextColor(primary)
            }
        }
        findViewById<View>(R.id.rowImportBackup).apply {
            setOnClickListener { openBackupLauncher.launch(arrayOf("application/json", "*/*")) }
        }

        // Include-private toggle. Default OFF; turning ON requires confirming a consent dialog
        // because it widens what a backup file leaks. Cancel reverts the switch silently via
        // the same detach-listener idiom used by the biometric toggle in setupSecurity().
        val switchInclude = findViewById<MaterialSwitch>(R.id.switchIncludePrivateInBackup)
        val attachIncludeListener: () -> Unit
        var attachStub: (() -> Unit)? = null
        fun setIncludeSilently(checked: Boolean) {
            switchInclude.setOnCheckedChangeListener(null)
            switchInclude.isChecked = checked
            attachStub?.invoke()
        }
        attachIncludeListener = {
            switchInclude.setOnCheckedChangeListener { _, checked ->
                if (!checked) {
                    prefs.includePrivateInBackup = false
                } else {
                    setIncludeSilently(false)

                    if (!includePrivateGateInFlight) {
                        includePrivateGateInFlight = true
                        showIncludePrivateConsentDialog(
                            onConfirm = {
                                prefs.includePrivateInBackup = true
                                setIncludeSilently(true)
                                includePrivateGateInFlight = false
                            },
                            onCancel = { includePrivateGateInFlight = false }
                        )
                    }
                }
            }
        }
        attachStub = attachIncludeListener
        // Initial state - sync UI with prefs before attaching the listener.
        switchInclude.setOnCheckedChangeListener(null)
        switchInclude.isChecked = prefs.includePrivateInBackup
        attachIncludeListener()
    }

    private fun saveBackup(uri: Uri) {
        try {
            val json = BackupManager(prefs).toJson()
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
            Toast.makeText(
                this, getString(R.string.backup_saved), Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.common_export_failed, e.message), Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Orchestrates the import flow:
     *   1. Parse the file. NOTHING is written to prefs until we know the PIN outcome.
     *   2. If the file has no private bundle, apply the non-private bundle and finish.
     *   3. If the file has a private bundle, gate the apply step on the PIN dialog:
     *        - Verify success → apply non-private + private. (Hidden apps restored.)
     *        - Skip / Cancel → apply non-private only. (User-explicit choice.)
     *        - Three wrong attempts → REFUSE the entire import; apply nothing. The user
     *          could not prove they own the backup, so we won't trust any of its data.
     *   4. Case B (device already has a PIN): show a conflict-warning dialog first.
     *
     * Wrong-PIN attempts in this flow are session-local and do NOT touch the device's own PIN
     * lockout counters (those belong to the home-gate gate, not the import gate).
     */
    private fun loadBackup(uri: Uri) {
        val mgr = BackupManager(prefs)
        val contents: BackupManager.BackupContents
        try {
            val json = contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() } ?: return
            contents = mgr.parse(json)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.common_import_failed, importErrorText(this, e)),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val privateBundle = contents.privateBundle
        if (privateBundle == null) {
            // No private bundle to gate on - apply non-private and finish.
            mgr.applyNonPrivate(contents)
            finishImport(ImportOutcome.SETTINGS_RESTORED)
            return
        }

        val pinManager = PinManager(prefs)
        if (!pinManager.hasPin()) {
            // Case A - no device PIN to overwrite. Go straight to the PIN-verify dialog.
            showImportPinDialog(contents, privateBundle)
        } else {
            // Case B - the device already has its own PIN. Warn the user that restoring will
            // replace it, otherwise an unaware user could lose their current PIN trying to
            // "help" by entering a PIN they happen to remember from the backup.
            showImportPrivateConflictDialog(
                onCancel = {
                    // User-explicit choice to skip the private bundle. Apply non-private.
                    mgr.applyNonPrivate(contents)
                    finishImport(ImportOutcome.PRIVATE_SKIPPED)
                },
                onContinue = { showImportPinDialog(contents, privateBundle) }
            )
        }
    }

    /**
     * Show the consent dialog that fires when the user tries to turn on
     * "Include hidden apps in backups". Reuses the dialog_accessibility_info.xml template.
     */
    private fun showIncludePrivateConsentDialog(
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        val dialog = Dialog(this, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = resources.displayMetrics.density

        // Unreachable after setContentView, but bailing without reporting a cancel would strand
        // includePrivateGateInFlight at true and silently block every later attempt.
        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup
            ?: run { onCancel(); return }
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        dialog.findViewById<TextView>(R.id.dialogTitle)?.apply {
            text = getString(R.string.settings_include_hidden_apps_title)
            setTextColor(accent)
        }
        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.settings_include_hidden_apps_body)
            setTextColor(primary)
        }
        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.settings_include_hidden_apps_note)
            setTextColor(secondary)
        }

        var consumed = false
        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener {
                consumed = true
                dialog.dismiss()
                onCancel()
            }
        }
        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            text = getString(R.string.settings_turn_on)
            setTextColor(accent)
            setOnClickListener {
                consumed = true
                dialog.dismiss()
                onConfirm()
            }
        }
        // Back press or outside-tap counts as Cancel. The switch has already been reverted by
        // the caller, so this only has to release the in-flight guard.
        dialog.setOnDismissListener {
            includePrivateDialog = null
            if (!consumed) onCancel()
        }
        includePrivateDialog = dialog
        dialog.show()
    }

    /** Guards against a second tap stacking a second consent dialog while one is already open. */
    private var includePrivateGateInFlight = false

    /** Held only so [onDestroy] can dismiss it; a rotation would otherwise leak the window. */
    private var includePrivateDialog: Dialog? = null

    /**
     * Case B confirmation: user has their own PIN on the device, and the backup is asking to
     * replace it (along with biometric + hidden-apps set). [onContinue] proceeds to the PIN-
     * verify dialog. [onCancel] keeps the device state untouched and finalises the import.
     */
    private fun showImportPrivateConflictDialog(
        onCancel: () -> Unit,
        onContinue: () -> Unit
    ) {
        val dialog = Dialog(this, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = resources.displayMetrics.density

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        dialog.findViewById<TextView>(R.id.dialogTitle)?.apply {
            text = getString(R.string.settings_replace_pin_title)
            setTextColor(accent)
        }
        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.settings_replace_pin_body)
            setTextColor(primary)
        }
        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.settings_replace_pin_note)
            setTextColor(secondary)
        }

        var consumed = false
        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener {
                consumed = true
                dialog.dismiss()
                onCancel()
            }
        }
        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            text = getString(R.string.settings_restore)
            setTextColor(accent)
            setOnClickListener {
                consumed = true
                dialog.dismiss()
                onContinue()
            }
        }
        // Back-press / outside-tap dismissal counts as Cancel, mirroring the consent dialog.
        dialog.setOnDismissListener { if (!consumed) onCancel() }
        dialog.show()
    }

    private enum class ImportOutcome {
        /** No private bundle in the file (or device-PIN conflict was cancelled): non-private applied. */
        SETTINGS_RESTORED,
        /** Private bundle present, user explicitly skipped it: non-private applied. */
        PRIVATE_SKIPPED,
        /** Private bundle verified: non-private + private both applied. */
        PRIVATE_RESTORED,
    }

    /**
     * Verify the backup's PIN in-memory via [PinManager.verifyAgainst]. The dialog allows up
     * to 3 attempts.
     *   - Correct PIN → apply non-private + private, finish as PRIVATE_RESTORED.
     *   - Cancel / back / outside tap → apply non-private only (user-explicit skip),
     *     finish as PRIVATE_SKIPPED.
     *   - Three wrong attempts → REFUSE the import, apply nothing, finish as REFUSED.
     *
     * Wrong-attempt counting is session-local - this flow never touches the device's own
     * pinFailedAttempts / pinLockout state.
     */
    private fun showImportPinDialog(
        contents: BackupManager.BackupContents,
        privateBundle: BackupManager.PrivateBundle
    ) {
        var attempts = 0
        val maxAttempts = 3
        // Track whether the user consumed the dialog via Verify; outside-tap or back
        // dismissal falls into onCancel, which finalises the import as a user-explicit skip.
        // The 3-wrong path bypasses onCancel and finishes as REFUSED directly.
        var settled = false

        val mgr = BackupManager(prefs)

        val onConfirm: (CharArray) -> Unit = { pin ->
            attempts++
            // verifyAgainst zeroes the PIN buffer on return.
            val ok = PinManager.verifyAgainst(
                pin = pin,
                hashBase64 = privateBundle.pinHash,
                saltBase64 = privateBundle.pinSalt,
                iters = privateBundle.pinIterations,
            )
            if (ok) {
                settled = true
                mgr.applyNonPrivate(contents)
                mgr.applyPrivate(privateBundle)
                finishImport(ImportOutcome.PRIVATE_RESTORED)
            } else if (attempts >= maxAttempts) {
                // Three wrong attempts - REFUSE the entire import. Apply nothing. The user
                // could not prove they own the backup, so we won't trust any of its data -
                // not even the non-private prefs. Show a proper modal so the outcome can't
                // be missed (the previous Toast-only signal was too quiet).
                settled = true
                showImportRefusedDialog()
            } else {
                // Reopen a fresh dialog with the inline red error visible. Reusing the closed
                // dialog would require state-resetting the PinEntryDialog; constructing a new
                // one is simpler and guarantees a clean input.
                showImportPinDialogAfterFailure(contents, privateBundle, attempts, maxAttempts)
            }
        }
        val onCancel: () -> Unit = {
            if (!settled) {
                // User-explicit cancel / back / outside tap before exhausting attempts:
                // treat as user choice to skip the private bundle, apply non-private only.
                mgr.applyNonPrivate(contents)
                finishImport(ImportOutcome.PRIVATE_SKIPPED)
            }
        }

        PinEntryDialog(
            context = this,
            bgColor = prefs.backgroundColor,
            title = getString(R.string.settings_restore_hidden_apps_title),
            message = getString(R.string.settings_restore_hidden_apps_body),
            confirmLabel = getString(R.string.settings_verify),
            onConfirm = onConfirm,
            onCancel = onCancel,
        ).show()
    }

    /**
     * Reopen the PIN dialog after a wrong attempt, with the error message visible. Kept
     * separate from the initial-show path so the wrong-attempt branch doesn't have to
     * juggle the previous dialog's lifecycle inside its own onConfirm callback.
     */
    private fun showImportPinDialogAfterFailure(
        contents: BackupManager.BackupContents,
        privateBundle: BackupManager.PrivateBundle,
        attemptsSoFar: Int,
        maxAttempts: Int
    ) {
        val remaining = maxAttempts - attemptsSoFar
        var attempts = attemptsSoFar
        var settled = false
        val mgr = BackupManager(prefs)

        val dialog = PinEntryDialog(
            context = this,
            bgColor = prefs.backgroundColor,
            title = getString(R.string.settings_restore_hidden_apps_title),
            // Body stays static across retries - the wrong-PIN feedback lives in the inline
            // error line (red) via setError below, which is the conventional pattern for form
            // validation errors and lets the user keep their bearings between attempts.
            message = getString(R.string.settings_restore_hidden_apps_body),
            confirmLabel = getString(R.string.settings_verify),
            onConfirm = { pin ->
                attempts++
                val ok = PinManager.verifyAgainst(
                    pin = pin,
                    hashBase64 = privateBundle.pinHash,
                    saltBase64 = privateBundle.pinSalt,
                    iters = privateBundle.pinIterations,
                )
                if (ok) {
                    settled = true
                    mgr.applyNonPrivate(contents)
                    mgr.applyPrivate(privateBundle)
                    finishImport(ImportOutcome.PRIVATE_RESTORED)
                } else if (attempts >= maxAttempts) {
                    settled = true
                    showImportRefusedDialog()
                } else {
                    showImportPinDialogAfterFailure(contents, privateBundle, attempts, maxAttempts)
                }
            },
            onCancel = {
                if (!settled) {
                    mgr.applyNonPrivate(contents)
                    finishImport(ImportOutcome.PRIVATE_SKIPPED)
                }
            },
        )
        dialog.show()
        // setError must run AFTER show() - the dialog's content view is inflated lazily inside
        // show() → onCreate(), so the error TextView isn't findable until then.
        dialog.setError(
            resources.getQuantityString(
                R.plurals.settings_wrong_pin_attempts_left, remaining, remaining
            )
        )
    }

    /**
     * Modal shown after three wrong PINs at import. Reuses the standard accessibility-info
     * layout but hides the Cancel button and renames Continue to OK - the user has no choice
     * here, only an acknowledgement. The activity recreates on dismiss so the import button
     * resets cleanly.
     */
    private fun showImportRefusedDialog() {
        val dialog = Dialog(this, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        // Force the user to acknowledge - back-press still dismisses but at least an outside
        // tap doesn't silently close the only signal that the import was rejected.
        dialog.setCanceledOnTouchOutside(false)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = resources.displayMetrics.density

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        dialog.findViewById<TextView>(R.id.dialogTitle)?.apply {
            text = getString(R.string.settings_import_refused_title)
            setTextColor(accent)
        }
        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.settings_import_refused_body)
            setTextColor(primary)
        }
        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.settings_import_refused_note)
            setTextColor(secondary)
        }
        // Single-OK refusal: Cancel button has no useful semantic here.
        dialog.findViewById<TextView>(R.id.btnCancel)?.visibility = View.GONE
        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            text = getString(R.string.common_ok)
            setTextColor(accent)
            setOnClickListener { dialog.dismiss() }
        }
        // recreate() runs on dismiss (back-press or OK) so the Settings UI resets to a clean
        // state. Nothing was written to prefs during this import, so the activity comes back
        // exactly as it was before the user picked the file.
        dialog.setOnDismissListener { recreate() }
        dialog.show()
    }

    /**
     * Final step for non-refusal outcomes: Toast the right message and `recreate()` so the
     * Settings activity re-renders with the imported prefs. The refusal path uses the modal
     * [showImportRefusedDialog] instead of this Toast.
     */
    private fun finishImport(outcome: ImportOutcome) {
        val msg = when (outcome) {
            ImportOutcome.SETTINGS_RESTORED -> getString(R.string.backup_settings_restored)
            ImportOutcome.PRIVATE_SKIPPED -> getString(R.string.backup_restored_hidden_apps_skipped)
            ImportOutcome.PRIVATE_RESTORED -> getString(R.string.backup_hidden_apps_restored)
        }
        // Every applyNonPrivate() call site funnels here, so this is the single place to
        // re-assert restored pinned shortcuts at the OS level. A no-op if permission is
        // currently absent (common right after a fresh-device restore) - the onResume
        // false→true transition handler below picks it up once the user re-grants it.
        PinnedShortcutStore.resyncAllWithOs(prefs, PinnedShortcutStore.launcherApps(this))
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        recreate()
    }

    // ── General ───────────────────────────────────────────────────

    /**
     * The language row. Android 13 and later keep one language per app, and the row reads and
     * sets that, so it stays in step with the system's own App languages page. The choices are
     * the languages in res/xml/locales_config.xml. Older versions have no such setting: the
     * row is hidden there and Slate follows the phone's language.
     */
    private fun setupLanguage(secondary: Int) {
        val row = findViewById<View>(R.id.rowLanguage)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            row.visibility = View.GONE
            return
        }
        val localeManager = getSystemService(LocaleManager::class.java)
        val systemDefault = getString(R.string.settings_language_system_default)
        val value = findViewById<TextView>(R.id.languageValue)
        val current = localeManager.applicationLocales
        value.setTextColor(secondary)
        value.text = if (current.isEmpty) systemDefault else languageName(current[0])

        row.setOnClickListener {
            val languages = supportedLanguages()
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_language),
                items = listOf(systemDefault) + languages.map { languageName(it) },
                bgColor = prefs.backgroundColor
            ) { index, label ->
                value.text = label
                localeManager.applicationLocales =
                    if (index == 0) LocaleList.getEmptyLocaleList()
                    else LocaleList(languages[index - 1])
            }.show()
        }
    }

    /** The languages Slate ships, ordered by their own names. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun supportedLanguages(): List<Locale> {
        val locales = LocaleConfig(this).supportedLocales ?: return emptyList()
        return List(locales.size()) { locales[it] }
            .sortedWith(compareBy(Collator.getInstance()) { languageName(it) })
    }

    /** A language's name in that language, capitalised as Android's own language page does. */
    private fun languageName(locale: Locale): String =
        locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }

    private fun setupGeneral() {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")
        val density = resources.displayMetrics.density

        setupLanguage(secondary)

        // Sort by usage - also gates Alphabetical fast scroll (see refreshAlphaFastScrollGate
        // below). Local closures are used to keep the cross-row dependency explicit and
        // co-located with both setters.
        val switchSortUsage = findViewById<MaterialSwitch>(R.id.switchSortByUsage)
        val labelSortByUsageSub = findViewById<TextView>(R.id.labelSortByUsageSub)
        switchSortUsage.isChecked = prefs.sortByUsage
        // Listener registered AFTER the gate closures are declared below so the listener can
        // call refreshAlphaFastScrollGate() / refreshMostUsedPositionGate() - see below.

        // Most-used position (Top/Bottom) - gated by Sort by usage, same visible-but-greyed
        // style as Search bar position (not Quick strip position's fully-hidden style), so the
        // option stays discoverable even before the user turns Sort by usage on.
        val rowMostUsedPosition = findViewById<View>(R.id.rowMostUsedPosition)
        val mostUsedPositionValue = findViewById<TextView>(R.id.mostUsedPositionValue)
        val labelMostUsedPositionSub = findViewById<TextView>(R.id.labelMostUsedPositionSub)
        mostUsedPositionValue.setTextColor(secondary)

        // Cross-row gate for "Show most used at": visible but greyed + non-clickable when
        // Sort by usage is off (matches Search bar position's style), full opacity + clickable
        // when on. Also keeps the "Sort by most used" sub-label itself direction-aware, since
        // every comparable sub-label in this file already changes dynamically.
        val defaultSortUsageSub = getString(R.string.settings_sort_by_most_used_summary)
        fun refreshMostUsedPositionGate() {
            val enabled = prefs.sortByUsage
            rowMostUsedPosition.alpha = if (enabled) 1f else 0.4f
            rowMostUsedPosition.isClickable = enabled
            labelMostUsedPositionSub.text =
                if (enabled) getString(R.string.settings_show_most_used_at_summary)
                else getString(R.string.settings_turn_on_sort_by_most_used)
            mostUsedPositionValue.text = edgeLabel(prefs.mostUsedPosition)
            labelSortByUsageSub.text = if (enabled) {
                if (prefs.mostUsedPosition == "bottom")
                    getString(R.string.settings_most_used_appear_at_bottom)
                else
                    getString(R.string.settings_most_used_appear_at_top)
            } else {
                defaultSortUsageSub
            }
        }

        rowMostUsedPosition.setOnClickListener {
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_show_most_used_at),
                items = EDGE_LABELS.map { getString(it.second) },
                bgColor = prefs.backgroundColor
            ) { index, _ ->
                prefs.mostUsedPosition = EDGE_LABELS[index].first
                refreshMostUsedPositionGate()
            }.show()
        }

        // Lock orientation
        val switchLockOrientation = findViewById<MaterialSwitch>(R.id.switchLockOrientation)
        switchLockOrientation.isChecked = prefs.lockOrientation
        switchLockOrientation.setOnCheckedChangeListener { _, checked ->
            prefs.lockOrientation = checked
            requestedOrientation = if (checked)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // Notification highlight + color sub-row
        val switchNotif = findViewById<MaterialSwitch>(R.id.switchNotifColor)
        val rowNotifHighlight = findViewById<View>(R.id.rowNotifHighlight)
        val notifColorSwatch = findViewById<View>(R.id.notifColorSwatch)
        val notifColorValue = findViewById<TextView>(R.id.notifColorValue)

        notifColorValue.setTextColor(secondary)

        fun updateNotifSwatch(hex: String) {
            notifColorValue.text = hex
            notifColorSwatch.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 5f * density
                setColor(parseColorSafe(hex))
                val borderColor = if (isLight) Color.parseColor("#BBBBBB") else Color.parseColor("#444444")
                setStroke((1.5f * density).toInt(), borderColor)
            }
        }

        updateNotifSwatch(prefs.notificationHighlightColor)
        setNotifSubRowsVisible(prefs.notificationColorEnabled)

        // Ignore-silenced sub-row. Purely a display filter - the listener tracks silent and
        // alerting notifications either way, so flipping this only needs the list redrawn,
        // which happens on the next AppDrawerFragment.onResume.
        val switchIgnoreSilent = findViewById<MaterialSwitch>(R.id.switchIgnoreSilentNotifs)
        switchIgnoreSilent.isChecked = prefs.ignoreSilentNotifications
        switchIgnoreSilent.setOnCheckedChangeListener { _, checked ->
            prefs.ignoreSilentNotifications = checked
        }

        switchNotif.isChecked = prefs.notificationColorEnabled
        setupNotifListener(switchNotif)

        rowNotifHighlight.setOnClickListener {
            ColorPickerDialog(
                context = this,
                title = getString(R.string.settings_highlight_color),
                initialColor = prefs.notificationHighlightColor,
                bgColor = prefs.backgroundColor
            ) { hex ->
                prefs.notificationHighlightColor = hex
                updateNotifSwatch(hex)
            }.show()
        }

        // Status bar toggle
        val switchStatusBar = findViewById<MaterialSwitch>(R.id.switchHideStatusBar)
        switchStatusBar.isChecked = prefs.hideStatusBar
        switchStatusBar.setOnCheckedChangeListener { _, checked ->
            prefs.hideStatusBar = checked
        }

        // Homescreen view + alphabetical fast scroll sub-option
        val homescreenViewValue = findViewById<TextView>(R.id.homescreenViewValue)
        val rowAlphaFastScroll = findViewById<View>(R.id.rowAlphaFastScroll)
        val switchAlphaFastScroll = findViewById<MaterialSwitch>(R.id.switchAlphaFastScroll)
        val labelAlphaFastScrollSub = findViewById<TextView>(R.id.labelAlphaFastScrollSub)

        homescreenViewValue.setTextColor(secondary)
        homescreenViewValue.text = homescreenViewLabel(prefs.homescreenView)

        // Cross-row gate for "Alphabetical fast scroll":
        //   - HIDDEN when homescreenView != list (the feature is list-only).
        //   - VISIBLE + DISABLED + greyed when sortByUsage is on, because alphabetical fast
        //     scroll only makes sense over an alphabetical list. The sub-label changes to tell
        //     the user how to enable it. Keeping the row visible (rather than hiding it again)
        //     preserves discoverability - if we hid it on `sortByUsage`, users who enable
        //     sort-by-usage would think the feature vanished.
        //   - VISIBLE + ENABLED otherwise.
        // The pref value `alphabeticalFastScroll` is PRESERVED across both transitions so the
        // toggle re-lights at the user's previous position when they switch back to alphabetical
        // sort. Matches how `notificationHighlightColor` persists when highlight is off.
        val defaultSubLabel = getString(R.string.settings_alphabetical_fast_scroll_summary)
        val blockedSubLabel = getString(R.string.settings_turn_off_sort_by_most_used)
        fun refreshAlphaFastScrollGate() {
            val isList = prefs.homescreenView == PreferencesManager.VIEW_LIST
            rowAlphaFastScroll.visibility = if (isList) View.VISIBLE else View.GONE
            if (!isList) return
            val blocked = prefs.sortByUsage
            switchAlphaFastScroll.isEnabled = !blocked
            rowAlphaFastScroll.alpha = if (blocked) 0.4f else 1f
            labelAlphaFastScrollSub.text = if (blocked) blockedSubLabel else defaultSubLabel
        }

        setupWorkProfileRows()

        findViewById<View>(R.id.rowHomescreenView).setOnClickListener {
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_homescreen_view),
                items = listOf(
                    getString(R.string.settings_flow), getString(R.string.settings_minimal_list)
                ),
                bgColor = prefs.backgroundColor
            ) { index, label ->
                prefs.homescreenView =
                    if (index == 0) PreferencesManager.VIEW_FLOW else PreferencesManager.VIEW_LIST
                homescreenViewValue.text = label
                refreshAlphaFastScrollGate()
                applyHomescreenViewToTextSize()
            }.show()
        }

        switchAlphaFastScroll.isChecked = prefs.alphabeticalFastScroll
        switchAlphaFastScroll.setOnCheckedChangeListener { _, checked ->
            prefs.alphabeticalFastScroll = checked
        }

        // Wire the Sort-by-usage listener here (the switch was declared earlier; we register the
        // listener now because it must also refresh the fast-scroll and most-used-position gates,
        // whose closures live in this scope).
        switchSortUsage.setOnCheckedChangeListener { _, checked ->
            prefs.sortByUsage = checked
            refreshAlphaFastScrollGate()
            refreshMostUsedPositionGate()
        }

        // Initial state for the gates - covers a fresh open of Settings.
        refreshAlphaFastScrollGate()
        refreshMostUsedPositionGate()

        // Default launcher row
        findViewById<View>(R.id.rowDefaultLauncher).setOnClickListener {
            requestDefaultLauncher()
        }
    }

    private fun homescreenViewLabel(mode: String): String = when (mode) {
        PreferencesManager.VIEW_LIST -> getString(R.string.settings_minimal_list)
        else -> getString(R.string.settings_flow)
    }

    private fun setupNotifListener(switchNotif: MaterialSwitch) {
        switchNotif.setOnCheckedChangeListener { _, checked ->
            if (checked && !isNotificationListenerEnabled()) {
                switchNotif.isChecked = false
                showNotificationDialog()
            } else {
                prefs.notificationColorEnabled = checked
                setNotifSubRowsVisible(checked)
            }
        }
    }

    private fun showNotificationDialog() {
        val dialog = Dialog(this, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = resources.displayMetrics.density

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        dialog.findViewById<TextView>(R.id.dialogTitle)?.apply {
            text = getString(R.string.settings_notification_access_title)
            setTextColor(accent)
        }

        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.settings_notification_access_body)
            setTextColor(primary)
        }

        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.settings_notification_access_privacy)
            setTextColor(secondary)
        }

        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener { dialog.dismiss() }
        }

        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            setTextColor(accent)
            setOnClickListener {
                dialog.dismiss()
                prefs.awaitingNotificationPermission = true
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        }

        dialog.show()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, SlateNotificationService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun requestDefaultLauncher() {
        if (isAlreadyDefaultLauncher()) {
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_already_the_default_launcher),
                items = listOf(
                    getString(R.string.settings_already_default_body),
                    getString(R.string.common_ok)
                ),
                bgColor = prefs.backgroundColor
            ) { _, _ -> }.show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                requestRoleLauncher.launch(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                )
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun isAlreadyDefaultLauncher(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(
            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )
        return info?.activityInfo?.packageName == packageName
    }

    // ── Quick toggles strip ───────────────────────────────────────

    private fun setupQuickStrip() {
        val switch = findViewById<MaterialSwitch>(R.id.switchQuickStrip)
        val rowPosition = findViewById<View>(R.id.rowQuickStripPosition)
        val positionValue = findViewById<TextView>(R.id.quickStripPositionValue)
        val rowChooseWidgets = findViewById<View>(R.id.rowChooseWidgets)
        val labelChooseValue = findViewById<TextView>(R.id.labelChooseWidgetsValue)
        val rowArrangeWidgets = findViewById<View>(R.id.rowArrangeWidgets)
        val rowDirectCall = findViewById<View>(R.id.rowDirectCall)
        val switchDirectCall = findViewById<MaterialSwitch>(R.id.switchDirectCall)
        val rowDirectCallTrigger = findViewById<View>(R.id.rowDirectCallTrigger)
        val directCallTriggerValue = findViewById<TextView>(R.id.directCallTriggerValue)
        val rowDivider = findViewById<View>(R.id.rowQuickStripDivider)
        val switchDivider = findViewById<MaterialSwitch>(R.id.switchQuickStripDivider)
        val previewHeader = findViewById<TextView>(R.id.widgetPreviewHeader)
        val previewLayout = findViewById<FlexboxLayout>(R.id.widgetPreview)
        val rowFont = findViewById<View>(R.id.rowWidgetFont)
        val fontValueLabel = findViewById<TextView>(R.id.widgetFontValue)
        val rowWeight = findViewById<View>(R.id.rowWidgetWeight)
        val weightValueLabel = findViewById<TextView>(R.id.widgetWeightValue)
        val rowAlignment = findViewById<View>(R.id.rowWidgetAlignment)
        val alignmentValueLabel = findViewById<TextView>(R.id.widgetAlignmentValue)
        val rowTextSize = findViewById<View>(R.id.rowWidgetTextSize)
        val sbTextSize = findViewById<SeekBar>(R.id.widgetTextSizeSeekBar)
        val lbTextSize = findViewById<TextView>(R.id.widgetTextSizeLabel)
        val rowLineGap = findViewById<View>(R.id.rowWidgetLineGap)
        val sbLineGap = findViewById<SeekBar>(R.id.widgetLineGapSeekBar)
        val lbLineGap = findViewById<TextView>(R.id.widgetLineGapLabel)
        val rowWordGap = findViewById<View>(R.id.rowWidgetWordGap)
        val sbWordGap = findViewById<SeekBar>(R.id.widgetWordGapSeekBar)
        val lbWordGap = findViewById<TextView>(R.id.widgetWordGapLabel)

        fun isLight() = isColorLight(parseColorSafe(prefs.backgroundColor))
        fun secondary() =
            if (isLight()) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")

        fun refreshChooseValueLabel() {
            val count = prefs.quickStripWidgets.size
            labelChooseValue.text =
                if (count == 0) getString(R.string.settings_widgets_none_selected)
                else resources.getQuantityString(R.plurals.settings_widgets_enabled, count, count)
            labelChooseValue.setTextColor(secondary())
        }

        fun refreshPositionValue() {
            positionValue.text =
                if (prefs.quickStripPosition == "top") getString(R.string.common_top)
                else getString(R.string.common_bottom)
            positionValue.setTextColor(secondary())
        }

        fun applyVisibility() {
            val on = prefs.quickStripEnabled
            rowPosition.visibility = if (on) View.VISIBLE else View.GONE
            rowChooseWidgets.visibility = if (on) View.VISIBLE else View.GONE
            // Arrange row hides when there's nothing to arrange: 0 or 1 widget enabled.
            rowArrangeWidgets.visibility =
                if (on && prefs.quickStripWidgets.size >= 2) View.VISIBLE else View.GONE
            // Direct call sub-row mirrors the master. The Trigger sub-sub-row appears only when
            // BOTH master Quick toggles AND Direct call are on - showing the trigger picker
            // while Direct call is off would be meaningless.
            rowDirectCall.visibility = if (on) View.VISIBLE else View.GONE
            rowDirectCallTrigger.visibility =
                if (on && prefs.directCallEnabled) View.VISIBLE else View.GONE
            // Divider toggle mirrors the master gate - the divider can't exist without a strip.
            rowDivider.visibility = if (on) View.VISIBLE else View.GONE
            // Preview + typography controls (pickers and sliders) mirror the master gate - only
            // meaningful when the strip is going to render at all.
            previewHeader.visibility = if (on) View.VISIBLE else View.GONE
            previewLayout.visibility = if (on) View.VISIBLE else View.GONE
            rowFont.visibility       = if (on) View.VISIBLE else View.GONE
            rowWeight.visibility     = if (on) View.VISIBLE else View.GONE
            rowAlignment.visibility  = if (on) View.VISIBLE else View.GONE
            rowTextSize.visibility = if (on) View.VISIBLE else View.GONE
            rowLineGap.visibility  = if (on) View.VISIBLE else View.GONE
            rowWordGap.visibility  = if (on) View.VISIBLE else View.GONE
        }

        refreshChooseValueLabel()
        refreshPositionValue()
        applyVisibility()

        switch.setOnCheckedChangeListener(null)
        switch.isChecked = prefs.quickStripEnabled
        switch.setOnCheckedChangeListener { _, checked ->
            prefs.quickStripEnabled = checked
            applyVisibility()
        }

        rowPosition.setOnClickListener {
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_position),
                items = EDGE_LABELS.map { getString(it.second) },
                bgColor = prefs.backgroundColor
            ) { index, _ ->
                prefs.quickStripPosition = EDGE_LABELS[index].first
                refreshPositionValue()
            }.show()
        }

        rowChooseWidgets.setOnClickListener {
            WidgetPickerDialog(
                context = this,
                prefs = prefs,
                onChanged = {
                    // Picker can change both selection and count → refresh dependent UI.
                    refreshChooseValueLabel()
                    applyVisibility()
                },
                onAddShortcut = { type -> launchContactPickerFor(type) }
            ).show()
        }

        rowArrangeWidgets.setOnClickListener {
            com.slate.launcher.widgets.WidgetArrangeDialog(
                context = this,
                prefs = prefs,
                onChanged = { /* order changes; counts and visibility don't */ }
            ).show()
        }

        // ── Direct call ────────────────────────────────────────────────
        fun directCallTriggerLabel(value: String) = when (value) {
            "longPress" -> getString(R.string.settings_long_press)
            else -> getString(R.string.settings_tap)
        }
        fun refreshDirectCallTriggerValue() {
            directCallTriggerValue.text = directCallTriggerLabel(prefs.directCallTrigger)
            directCallTriggerValue.setTextColor(secondary())
        }

        switchDirectCall.isChecked = prefs.directCallEnabled
        refreshDirectCallTriggerValue()
        attachDirectCallListener(switchDirectCall)

        rowDirectCallTrigger.setOnClickListener {
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_trigger),
                items = listOf(
                    getString(R.string.settings_tap), getString(R.string.settings_long_press)
                ),
                bgColor = prefs.backgroundColor
            ) { index, _ ->
                prefs.directCallTrigger = if (index == 0) "tap" else "longPress"
                refreshDirectCallTriggerValue()
            }.show()
        }

        switchDivider.setOnCheckedChangeListener(null)
        switchDivider.isChecked = prefs.quickStripDividerEnabled
        switchDivider.setOnCheckedChangeListener { _, checked ->
            prefs.quickStripDividerEnabled = checked
            // Home re-renders on next onResume via applyChromeLayout(); no extra trigger needed.
        }

        // ── Live preview + picker rows ──────────────────────────────────
        // The preview is a small FlexboxLayout populated with fixed sample labels so the
        // rendering is deterministic for every user. It mirrors the home strip's styling via
        // [Typography.applyWidgetStyle] - single source of truth for "how a widget looks".
        fun refreshPreview() {
            val bg = parseColorSafe(prefs.backgroundColor)
            previewLayout.setBackgroundColor(bg)
            previewLayout.flexDirection = FlexDirection.ROW
            previewLayout.flexWrap = FlexWrap.WRAP
            previewLayout.alignItems = AlignItems.CENTER
            previewLayout.justifyContent = when (prefs.widgetTextAlignment) {
                "left" -> JustifyContent.FLEX_START
                "right" -> JustifyContent.FLEX_END
                else -> JustifyContent.CENTER
            }
            previewLayout.removeAllViews()
            val density = resources.displayMetrics.density
            val textColor = parseColorSafe(
                prefs.appTextColor,
                if (isColorLight(bg)) Color.BLACK else Color.WHITE
            )
            previewSampleLabels().forEach { sample ->
                previewLayout.addView(TextView(this).apply {
                    text = sample
                    setTextColor(textColor)
                    gravity = Gravity.CENTER
                    isClickable = false
                    isFocusable = false
                    Typography.applyWidgetStyle(this, prefs, this@SettingsActivity, density)
                })
            }
        }

        // Resolve the persisted (family, weight, alignment) values to their human-readable row
        // labels. `widgetFontFamily=""` and `widgetFontWeight=0` are sentinels meaning "use theme
        // default" - both render as "Default" in the row value.
        fun widgetFontDisplayName(key: String): String = when {
            key.isEmpty() -> getString(R.string.common_default)
            key.startsWith("/") -> File(key).nameWithoutExtension
            else -> FONTS.find { it.key == key }?.displayName ?: getString(R.string.common_default)
        }
        fun widgetWeightDisplayName(weight: Int): String =
            getString(WEIGHTS.find { it.first == weight }?.second ?: R.string.common_default)

        fun refreshFontValueLabel() {
            fontValueLabel.text = widgetFontDisplayName(prefs.widgetFontFamily)
            fontValueLabel.setTextColor(secondary())
        }
        fun refreshWeightValueLabel() {
            weightValueLabel.text = widgetWeightDisplayName(prefs.widgetFontWeight)
            weightValueLabel.setTextColor(secondary())
        }
        fun refreshAlignmentValueLabel() {
            alignmentValueLabel.text = alignmentLabel(prefs.widgetTextAlignment)
            alignmentValueLabel.setTextColor(secondary())
        }

        refreshFontValueLabel()
        refreshWeightValueLabel()
        refreshAlignmentValueLabel()

        // An imported font arrives via importWidgetFontLauncher long after this function has
        // returned, so hand importFont a way back to these two local closures.
        pendingWidgetFontRefresh = {
            refreshFontValueLabel()
            refreshPreview()
        }

        // Font picker - "Default" is index 0, FONTS follow 1-to-1, and the trailing entry
        // imports a TTF from storage (matching the app-name font row). The import branch does
        // not refresh here: the file is chosen asynchronously, so importFont refreshes instead
        // via pendingWidgetFontRefresh once the copy has actually landed.
        rowFont.setOnClickListener {
            val items = listOf(getString(R.string.common_default)) + FONTS.map { it.displayName } +
                listOf(getString(R.string.settings_font_import_from_storage))
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_font),
                items = items,
                bgColor = prefs.backgroundColor
            ) { index, _ ->
                if (index > FONTS.size) {
                    importWidgetFontLauncher.launch(arrayOf("*/*"))
                } else {
                    prefs.widgetFontFamily =
                        if (index == 0) "" else FONTS[index - 1].key
                    refreshFontValueLabel()
                    refreshPreview()
                }
            }.show()
        }

        // Weight picker - same Default-prepend pattern as the font picker.
        rowWeight.setOnClickListener {
            val items =
                listOf(getString(R.string.common_default)) + WEIGHTS.map { getString(it.second) }
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_weight),
                items = items,
                bgColor = prefs.backgroundColor
            ) { index, _ ->
                prefs.widgetFontWeight =
                    if (index == 0) 0 else WEIGHTS[index - 1].first
                refreshWeightValueLabel()
                refreshPreview()
            }.show()
        }

        // Alignment picker - mirrors the apps' Alignment dialog (left/center/right).
        rowAlignment.setOnClickListener {
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_alignment),
                items = ALIGNMENT_LABELS.map { getString(it.second) },
                bgColor = prefs.backgroundColor
            ) { index, _ ->
                prefs.widgetTextAlignment = ALIGNMENT_LABELS[index].first
                refreshAlignmentValueLabel()
                refreshPreview()
            }.show()
        }

        // Widget typography sliders. Out-of-range stored values (e.g., from an older backup with
        // different range bounds) fall back to the default index - pref on disk stays untouched
        // until the user moves the slider. Labels use the same "{value}{unit}" idiom as the
        // existing typography sliders (sp for font, dp for padding).
        fun initSlider(
            seekBar: SeekBar,
            label: TextView,
            values: List<Int>,
            current: Int,
            default: Int,
            @StringRes unit: Int
        ) {
            seekBar.max = values.size - 1
            seekBar.progress = values.indexOf(current)
                .takeIf { it >= 0 } ?: values.indexOf(default).coerceAtLeast(0)
            label.text = getString(unit, current)
        }

        initSlider(
            sbTextSize, lbTextSize, WIDGET_TEXT_SIZES,
            prefs.widgetTextSize, PreferencesManager.DEFAULT_WIDGET_TEXT_SIZE, R.string.unit_sp
        )
        initSlider(
            sbLineGap, lbLineGap, WIDGET_LINE_GAPS,
            prefs.widgetLineGap, PreferencesManager.DEFAULT_WIDGET_LINE_GAP, R.string.unit_dp
        )
        initSlider(
            sbWordGap, lbWordGap, WIDGET_WORD_GAPS,
            prefs.widgetWordGap, PreferencesManager.DEFAULT_WIDGET_WORD_GAP, R.string.unit_dp
        )

        sbTextSize.setOnSeekBarChangeListener(seekBarListener { p ->
            val v = WIDGET_TEXT_SIZES[p]
            prefs.widgetTextSize = v
            lbTextSize.text = getString(R.string.unit_sp, v)
            refreshPreview()
        })
        sbLineGap.setOnSeekBarChangeListener(seekBarListener { p ->
            val v = WIDGET_LINE_GAPS[p]
            prefs.widgetLineGap = v
            lbLineGap.text = getString(R.string.unit_dp, v)
            refreshPreview()
        })
        sbWordGap.setOnSeekBarChangeListener(seekBarListener { p ->
            val v = WIDGET_WORD_GAPS[p]
            prefs.widgetWordGap = v
            lbWordGap.text = getString(R.string.unit_dp, v)
            refreshPreview()
        })

        // Initial preview population so the user sees a rendered strip the moment the section
        // becomes visible - no need to touch a control first.
        refreshPreview()
    }

    /**
     * Wire the Direct-call switch. Toggling ON requests CALL_PHONE if not already granted; the
     * launcher's callback owns the final pref / switch state. Detach-set-reattach pattern in
     * the callback prevents recursion on a silent revert.
     *
     * Toggling OFF writes the pref to false immediately - no permission interaction. Also
     * hides the Trigger sub-row.
     */
    private fun attachDirectCallListener(switchDirectCall: MaterialSwitch) {
        switchDirectCall.setOnCheckedChangeListener { _, checked ->
            val rowTrigger = findViewById<View>(R.id.rowDirectCallTrigger)
            if (!checked) {
                prefs.directCallEnabled = false
                rowTrigger.visibility = View.GONE
                return@setOnCheckedChangeListener
            }
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.CALL_PHONE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) {
                prefs.directCallEnabled = true
                rowTrigger.visibility = View.VISIBLE
            } else {
                // Don't write the pref yet - the launcher callback owns the final state. The
                // Trigger row stays hidden until the grant succeeds.
                requestCallPhoneLauncher.launch(android.Manifest.permission.CALL_PHONE)
            }
        }
    }

    /** Launch the system contact picker pre-filtered on Phone rows. */
    private fun launchContactPickerFor(type: ContactShortcut.Type) {
        pendingShortcutType = type
        val intent = Intent(
            Intent.ACTION_PICK,
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        )
        runCatching { pickContactLauncher.launch(intent) }
            .onFailure {
                pendingShortcutType = null
                Toast.makeText(
                    this, getString(R.string.settings_no_contacts_app_available), Toast.LENGTH_SHORT
                ).show()
            }
    }

    /**
     * Resolve the URI returned by `Intent(ACTION_PICK, Phone.CONTENT_URI)` into a phone number and
     * display name, then persist a new [ContactShortcut] and auto-enable it in the strip. The URI
     * carries a temporary read grant, so this query works without `READ_CONTACTS`.
     */
    private fun onContactPicked(uri: android.net.Uri) {
        val type = pendingShortcutType ?: return
        pendingShortcutType = null
        val projection = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY
        )
        val (number, displayName, lookupKey) = runCatching {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@runCatching Triple<String?, String?, String?>(null, null, null)
                Triple(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getString(2)
                )
            } ?: Triple<String?, String?, String?>(null, null, null)
        }.getOrDefault(Triple(null, null, null))

        if (number.isNullOrBlank() || displayName.isNullOrBlank() || lookupKey.isNullOrBlank()) {
            Toast.makeText(
                this,
                getString(R.string.settings_couldnt_read_the_selected_contact),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val shortcut = ContactShortcut(
            type = type,
            // The contact lookup_key is stable across edits, merges, syncs; we use it as the
            // widget's identity rather than the volatile row _ID.
            lookupUri = lookupKey,
            displayName = displayName,
            number = number
        )
        ContactShortcutStore.add(prefs, shortcut)

        // Auto-enable the new shortcut in the strip so the user immediately sees it.
        val current = prefs.quickStripWidgets.toMutableList()
        if (!current.contains(shortcut.id)) {
            current.add(shortcut.id)
            prefs.quickStripWidgets = current
        }
        WidgetPickerDialog.refreshActive()
    }

    // ── App shortcuts ──────────────────────────────────────────────

    private fun setupAppShortcuts() {
        findViewById<View>(R.id.rowAddShortcutToStrip).setOnClickListener {
            openShortcutPicker(ShortcutDestination.WIDGET_STRIP)
        }
        findViewById<View>(R.id.rowAddShortcutToAppList).setOnClickListener {
            openShortcutPicker(ShortcutDestination.APP_LIST)
        }
    }

    private fun openShortcutPicker(destination: ShortcutDestination) {
        val launcherApps = PinnedShortcutStore.launcherApps(this)
        if (launcherApps == null || !PinnedShortcutStore.hasShortcutHostPermissionSafe(launcherApps)) {
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_set_slate_as_your_default_launcher),
                items = listOf(
                    getString(R.string.settings_needs_default_launcher_body),
                    getString(R.string.settings_open_launcher_settings)
                ),
                bgColor = prefs.backgroundColor
            ) { index, _ ->
                if (index == 1) requestDefaultLauncher()
            }.show()
            return
        }
        ShortcutSourceAppPickerDialog.show(
            activity = this,
            prefs = prefs,
            launcherApps = launcherApps,
            destination = destination
        )
    }

    // ── Hidden apps security ──────────────────────────────────────

    private fun setupSecurity() {
        val pinManager = PinManager(prefs)
        val switchMaster = findViewById<MaterialSwitch>(R.id.switchHiddenAppsSecurity)
        val switchBio = findViewById<MaterialSwitch>(R.id.switchBiometric)
        val rowBio = findViewById<View>(R.id.rowBiometric)
        val rowChangePin = findViewById<View>(R.id.rowChangePin)
        val labelBioSub = findViewById<TextView>(R.id.labelBiometricSub)

        // Mutually-recursive listener setup so we can re-attach after a silent revert.
        var attachMaster: () -> Unit = {}
        var attachBio: () -> Unit = {}

        fun setMasterSilently(value: Boolean) {
            switchMaster.setOnCheckedChangeListener(null)
            switchMaster.isChecked = value
            attachMaster()
        }
        fun setBioSilently(value: Boolean) {
            switchBio.setOnCheckedChangeListener(null)
            switchBio.isChecked = value
            attachBio()
        }

        // applyVisibility runs after setBioSilently is declared because its reconcile branch
        // (below) invokes setBioSilently. Kotlin local functions cannot forward-reference
        // each other across blocks.
        fun applyVisibility() {
            val hasPin = pinManager.hasPin()
            val bioActive = prefs.hiddenAppsSecurityEnabled && hasPin
            val changePinActive = hasPin && (prefs.hiddenAppsSecurityEnabled || prefs.lockLongPressMenusEnabled)
            rowBio.visibility = if (bioActive) View.VISIBLE else View.GONE
            rowChangePin.visibility = if (changePinActive) View.VISIBLE else View.GONE
            val bioAvailable = AuthGate.canUseBiometric(this)
            // Reconcile stale pref: if the user removed their biometric enrollment from
            // Android system Settings while we were elsewhere, `prefs.biometricEnabled` is
            // still true but `canUseBiometric` now returns false. Without this flip the
            // switch renders as a confusing checked-but-greyed-out state. Functionally
            // AuthGate.authenticate already falls back to PIN - this fixes the UI to tell
            // the truth.
            if (prefs.biometricEnabled && !bioAvailable) {
                prefs.biometricEnabled = false
                setBioSilently(false)
            }
            switchBio.isEnabled = bioAvailable
            labelBioSub.text = if (bioAvailable) {
                getString(R.string.settings_use_biometric_summary)
            } else {
                getString(R.string.settings_no_biometric_enrolled_on_this_device)
            }
        }

        // Expose the reconcile entry-point for syncPermissionToggles. The lambda closes over
        // every local in this setupSecurity invocation; if the activity is recreated (e.g.,
        // after a backup restore), setupSecurity runs again and reassigns this field with a
        // fresh closure pointing at the new locals.
        biometricReconcile = { applyVisibility() }

        attachMaster = {
            switchMaster.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    // A PIN already alive and in current use by the sibling toggle is reused
                    // silently rather than reset - resetting it here would change the PIN out
                    // from under "Lock long-press" without that feature's own consent.
                    // Gated on the sibling's OWN enabled flag, not bare hasPin(), so a stray
                    // orphaned hash left over from some prior state is never silently adopted
                    // without the user having confirmed they still know it.
                    if (prefs.lockLongPressMenusEnabled && pinManager.hasPin()) {
                        prefs.hiddenAppsSecurityEnabled = true
                        setMasterSilently(true)
                        applyVisibility()
                    } else {
                        PinFlow.setupNew(
                            activity = this,
                            prefs = prefs,
                            pinManager = pinManager,
                            message = getString(R.string.settings_set_pin_for_hidden_apps),
                            onComplete = {
                                prefs.hiddenAppsSecurityEnabled = true
                                setMasterSilently(true)
                                applyVisibility()
                            },
                            onCancel = { setMasterSilently(false) }
                        )
                    }
                } else {
                    PinFlow.verifyExisting(
                        activity = this,
                        prefs = prefs,
                        pinManager = pinManager,
                        title = getString(R.string.settings_disable_lock),
                        onSuccess = {
                            prefs.hiddenAppsSecurityEnabled = false
                            prefs.biometricEnabled = false
                            // Only if "Lock long-press" isn't also keeping this PIN
                            // alive. Clearing it unconditionally would silently disarm that
                            // toggle too, while its own switch kept reading "on" until Settings
                            // was next reopened - a real, found-in-review hazard, not a guess.
                            if (!prefs.lockLongPressMenusEnabled) pinManager.clear()
                            setBioSilently(false)
                            setMasterSilently(false)
                            applyVisibility()
                        },
                        onCancel = { setMasterSilently(true) }
                    )
                }
            }
        }

        attachBio = {
            switchBio.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    if (!AuthGate.canUseBiometric(this)) {
                        Toast.makeText(
                            this,
                            getString(R.string.settings_no_biometric_enrolled_on_this_device),
                            Toast.LENGTH_SHORT
                        ).show()
                        setBioSilently(false)
                        return@setOnCheckedChangeListener
                    }
                    // Require PIN before enabling biometric. Without this, anyone holding the
                    // unlocked phone could add their own biometric and gain ongoing access.
                    PinFlow.verifyExisting(
                        activity = this,
                        prefs = prefs,
                        pinManager = pinManager,
                        title = getString(R.string.settings_enable_biometric),
                        onSuccess = {
                            AuthGate.verifyBiometric(
                                activity = this,
                                title = getString(R.string.settings_enable_biometric),
                                subtitle = getString(R.string.settings_confirm_biometric_to_enable),
                                onSuccess = {
                                    prefs.biometricEnabled = true
                                    setBioSilently(true)
                                },
                                onCancel = { setBioSilently(false) }
                            )
                        },
                        onCancel = { setBioSilently(false) }
                    )
                } else {
                    prefs.biometricEnabled = false
                }
            }
        }

        // Initial state - sync UI with prefs and attach listeners
        switchMaster.setOnCheckedChangeListener(null)
        switchMaster.isChecked = prefs.hiddenAppsSecurityEnabled && pinManager.hasPin()
        switchBio.setOnCheckedChangeListener(null)
        switchBio.isChecked = prefs.biometricEnabled
        applyVisibility()
        attachMaster()
        attachBio()

        rowChangePin.setOnClickListener {
            PinFlow.changePin(
                activity = this,
                prefs = prefs,
                pinManager = pinManager,
                onComplete = {
                    Toast.makeText(
                        this, getString(R.string.settings_pin_changed), Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }

        setupKeepHiddenInRecents()
    }

    /**
     * "Lock long-press" - PIN-gates the home long-press menu, each app's long-press menu,
     * folder and pinned-shortcut long-press menus (see AppDrawerFragment.showHomeLongPressDialog
     * / showAppMenu / showFolderMenu / showShortcutMenu), and the "Open settings" gesture action
     * (see executeGestureAction), using the SAME PIN as "Lock hidden apps" but never biometric,
     * regardless of [PreferencesManager.biometricEnabled].
     *
     * Independent of setupSecurity()'s master toggle: either can be on, off, or both, and
     * [PinManager.hasPin] is the only thing that ever ties them together. The enable/disable
     * guards here are the exact mirror of setupSecurity()'s own guards - see the comments there
     * for why an unconditional PinFlow.setupNew or an unconditional pinManager.clear() would be
     * wrong once a PIN can be kept alive by either toggle.
     *
     * No consent dialog on enable, unlike "Keep hidden apps in Recents" or "Include hidden apps
     * in backups": those toggles WEAKEN an existing protection and this one only adds one,
     * exactly like "Lock hidden apps" itself, which has no consent dialog either.
     */
    private fun setupLockLongPressMenus() {
        val pinManager = PinManager(prefs)
        val switch = findViewById<MaterialSwitch>(R.id.switchLockLongPressMenus)

        var attach: () -> Unit = {}
        fun setSilently(value: Boolean) {
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = value
            attach()
        }

        attach = {
            switch.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    // Both sub-branches call biometricReconcile()?.invoke() afterwards, matching
                    // the disable branch below - rowChangePin's visibility depends on this
                    // toggle too now, and skipping the refresh here (a real bug caught by
                    // review) left "Change PIN" hidden after enabling this toggle from a cold
                    // state, until Settings happened to be reopened.
                    if (prefs.hiddenAppsSecurityEnabled && pinManager.hasPin()) {
                        prefs.lockLongPressMenusEnabled = true
                        setSilently(true)
                        biometricReconcile?.invoke()
                    } else {
                        PinFlow.setupNew(
                            activity = this,
                            prefs = prefs,
                            pinManager = pinManager,
                            message = getString(R.string.settings_set_pin_for_long_press),
                            onComplete = {
                                prefs.lockLongPressMenusEnabled = true
                                setSilently(true)
                                biometricReconcile?.invoke()
                            },
                            onCancel = { setSilently(false) }
                        )
                    }
                } else {
                    PinFlow.verifyExisting(
                        activity = this,
                        prefs = prefs,
                        pinManager = pinManager,
                        title = getString(R.string.settings_disable_long_press_lock),
                        onSuccess = {
                            prefs.lockLongPressMenusEnabled = false
                            // Hidden Apps' own master toggle, its biometric flag, and the PIN
                            // itself are untouched - this toggle only ever turns itself off.
                            if (!prefs.hiddenAppsSecurityEnabled) pinManager.clear()
                            setSilently(false)
                            // Re-run setupSecurity()'s own applyVisibility(), exposed via the
                            // same reconcile hook onResume already uses for the biometric-
                            // enrollment-staleness check - rowChangePin's visibility depends on
                            // this toggle too now, and setupSecurity() runs before this function
                            // in onCreate, so the hook is guaranteed non-null by the time any
                            // user interaction can reach here.
                            biometricReconcile?.invoke()
                        },
                        onCancel = { setSilently(true) }
                    )
                }
            }
        }

        switch.setOnCheckedChangeListener(null)
        switch.isChecked = prefs.lockLongPressMenusEnabled && pinManager.hasPin()
        attach()
    }

    /**
     * The four rows of the Work profile section, which is its own section below App shortcuts.
     *
     * The section is ALWAYS visible, deliberately, including on devices with no work profile. It
     * was briefly gated on whether one existed; that gate was removed on purpose, because a
     * setting the user cannot find is worse than one that turns out not to apply to them, and
     * deciding which case they are in costs a binder call on every Settings open.
     *
     * The gating that remains is FUNCTIONAL rather than visual: tapping "Group work apps" with
     * no work profile, or with the switch off, finds an empty enumeration and says so. Nothing
     * silently does nothing.
     */
    private fun setupWorkProfileRows() {
        val rowGroup = findViewById<View>(R.id.rowGroupWorkApps)
        val switchShow = findViewById<MaterialSwitch>(R.id.switchShowWorkApps)

        switchShow.isChecked = prefs.showWorkApps

        switchShow.setOnCheckedChangeListener { _, checked ->
            prefs.showWorkApps = checked
        }

        val markerValue = findViewById<TextView>(R.id.workMarkerValue)
        // Same derivation the other value-column rows use (see setupTypography); there is no
        // shared accessor for it, each setup function computes it locally.
        val secondary =
            if (isColorLight(parseColorSafe(prefs.backgroundColor))) Color.parseColor("#555555")
            else Color.parseColor("#AAAAAA")
        markerValue.setTextColor(secondary)
        markerValue.text = workMarkerDisplayLabel(prefs.workMarkerStyle)

        findViewById<View>(R.id.rowWorkMarker).setOnClickListener {
            SlateListDialog(
                context = this,
                title = getString(R.string.settings_work_app_marker),
                items = WORK_MARKER_LABELS.map { getString(it.second) },
                bgColor = prefs.backgroundColor,
                // The preview calls the REAL composer, not a copy of its branch table, which is
                // why WorkMarker.decorate takes plain strings. folderStylePreview duplicates
                // folderLabel's branches and the two can silently drift; this cannot.
                secondaryItems = WORK_MARKER_LABELS.map { (key, _) ->
                    WorkMarker.decorate(
                        getString(R.string.sample_app_name),
                        getString(R.string.work_profile_label),
                        key
                    )
                }
            ) { index, label ->
                prefs.workMarkerStyle = WORK_MARKER_LABELS[index].first
                markerValue.text = label
            }.show()
        }

        val switchSuppress = findViewById<MaterialSwitch>(R.id.switchSuppressWorkMarker)
        switchSuppress.isChecked = prefs.suppressWorkMarkerInFolder
        switchSuppress.setOnCheckedChangeListener { _, checked ->
            prefs.suppressWorkMarkerInFolder = checked
        }

        rowGroup.setOnClickListener {
            val repository = AppRepository(this, prefs)
            val work = repository.workAppsForGrouping()
            if (work.isEmpty()) {
                Toast.makeText(
                    this, getString(R.string.settings_no_work_apps_to_group), Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val before = FolderStore.keysInAnyFolder(prefs)
            WorkGrouping.groupOnDemand(this, prefs, work)
            val moved = FolderStore.keysInAnyFolder(prefs).size - before.size
            Toast.makeText(
                this,
                if (moved > 0) resources.getQuantityString(
                    R.plurals.settings_grouped_work_apps, moved, moved
                )
                else getString(R.string.settings_work_apps_already_in_folders),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * "Keep hidden apps in Recents" - the one Settings control that deliberately weakens a
     * privacy behaviour, so turning it ON is gated behind an explicit acknowledgement.
     *
     * The invariant is that the switch renders ON only while the pref is true. It is held by
     * driving the switch back to OFF as the very first statement of the ON branch, before any
     * dialog is shown. Every abandonment path - Cancel, back, tapping outside, or the activity
     * being destroyed mid-dialog - is then a no-op rather than something that has to undo a
     * visual state, and no cancel callback has to fire for the UI to stay correct. That last
     * point matters: an activity torn down while a dialog is open does not reliably deliver
     * a dismiss callback, but setupSecurity() re-reads the pref as false on recreate anyway.
     */
    private fun setupKeepHiddenInRecents() {
        val switchRecents = findViewById<MaterialSwitch>(R.id.switchKeepHiddenInRecents)

        // Mutually referential, so setSilently is declared before it is defined: the listener
        // installed by setSilently calls onToggled, and onToggled calls setSilently to revert.
        lateinit var setSilently: (Boolean) -> Unit

        val onToggled: (Boolean) -> Unit = { checked ->
            if (!checked) {
                // Off restores the safer behaviour, so it is immediate and ungated.
                prefs.keepHiddenAppsInRecents = false
            } else {
                // Revert first and unconditionally: the pref is only written after the user
                // confirms, and this is the single statement holding "the switch never renders
                // ON while the pref is false". Not null-guarded on purpose - failing open here
                // would leave the switch ON with the pref off.
                setSilently(false)

                // After the revert, so a stray second tap in the frame before the dialog window
                // becomes touchable is still visually undone before being dropped.
                if (!keepHiddenInRecentsGateInFlight) {
                    keepHiddenInRecentsGateInFlight = true
                    showKeepHiddenInRecentsConsent(
                        onConfirm = {
                            prefs.keepHiddenAppsInRecents = true
                            setSilently(true)
                            keepHiddenInRecentsGateInFlight = false
                        },
                        onCancel = { keepHiddenInRecentsGateInFlight = false }
                    )
                }
            }
        }

        // Flip the switch without re-entering the listener. Assigning isChecked invokes the
        // listener synchronously, so it is detached around the write and reinstalled after.
        setSilently = { checked ->
            switchRecents.setOnCheckedChangeListener(null)
            switchRecents.isChecked = checked
            switchRecents.setOnCheckedChangeListener { _, isChecked -> onToggled(isChecked) }
        }

        setSilently(prefs.keepHiddenAppsInRecents)
    }

    /** Guards against a second tap stacking a second consent dialog while one is already open. */
    private var keepHiddenInRecentsGateInFlight = false

    /** Held only so [onDestroy] can dismiss it; a rotation would otherwise leak the window. */
    private var keepHiddenInRecentsDialog: Dialog? = null

    /**
     * Consent dialog whose confirm button is inert until the acknowledgement is ticked. The
     * button is a TextView, so "disabled" is both isEnabled (which blocks the click) and an
     * alpha change (which is the only visible signal, since the colour is a plain int with no
     * disabled state).
     */
    private fun showKeepHiddenInRecentsConsent(onConfirm: () -> Unit, onCancel: () -> Unit) {
        val dialog = Dialog(this, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_consent_checkbox)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = resources.displayMetrics.density

        // Looked up by its own id rather than via dialogTitle.parent: the title sits inside a
        // ScrollView here, so walking up from it would paint the scroll content, not the dialog.
        // Unreachable after setContentView, but bailing without reporting a cancel would strand
        // keepHiddenInRecentsGateInFlight at true and silently block every later attempt.
        val root = dialog.findViewById<View>(R.id.dialogRoot)
            ?: run { onCancel(); return }
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        dialog.findViewById<TextView>(R.id.dialogTitle)?.apply {
            text = getString(R.string.settings_show_in_recents_title)
            setTextColor(accent)
        }

        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.settings_show_in_recents_body)
            setTextColor(primary)
        }

        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.settings_show_in_recents_note)
            setTextColor(secondary)
        }

        val check = dialog.findViewById<MaterialCheckBox>(R.id.checkUnderstand)
        val confirm = dialog.findViewById<TextView>(R.id.btnContinue)

        // The label is the checkbox's own text rather than a sibling TextView, so TalkBack sees
        // one node with the right role and checked state, and the whole thing is one 48dp
        // target. A separate label plus a row click listener reads as a generic clickable with
        // no checkable state.
        check?.apply {
            text = getString(R.string.settings_i_understand_what_this_does)
            setTextColor(primary)
            buttonTintList = ColorStateList.valueOf(accent)
            // The tick is a separate drawable from the box, tinted by buttonIconTint, which
            // defaults to colorOnPrimary off the SYSTEM day/night theme while the box is tinted
            // from Slate's own background. Those disagree whenever the user's Slate theme and
            // the system theme differ, which can render the tick near-invisible. Painting it
            // with the dialog background guarantees contrast against the filled box.
            buttonIconTintList = ColorStateList.valueOf(bg)
        }

        confirm?.apply {
            text = getString(R.string.settings_turn_on)
            setTextColor(accent)
            isEnabled = false
            alpha = 0.4f  // same greyed-out value the gated Settings rows use
        }
        check?.setOnCheckedChangeListener { _, isChecked ->
            confirm?.isEnabled = isChecked
            confirm?.alpha = if (isChecked) 1f else 0.4f
        }

        // Exactly one of onConfirm / onCancel must run, whatever route closes the dialog.
        // setOnDismissListener covers Cancel, back, and an outside tap in one place; `consumed`
        // stops the confirm path also reporting a cancel when it dismisses.
        var consumed = false
        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener { dialog.dismiss() }
        }
        confirm?.setOnClickListener {
            consumed = true
            dialog.dismiss()
            onConfirm()
        }
        dialog.setOnDismissListener {
            keepHiddenInRecentsDialog = null
            if (!consumed) onCancel()
        }

        keepHiddenInRecentsDialog = dialog
        dialog.show()
    }

    // ── Battery restriction banner ────────────────────────────────

    private fun isBackgroundRestricted(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val notIgnoring = pm?.isIgnoringBatteryOptimizations(packageName) == false
        val explicitlyRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.isBackgroundRestricted == true
        } else false
        return notIgnoring || explicitlyRestricted
    }

    private fun shouldShowBatteryBanner(): Boolean {
        if (prefs.batteryBannerDismissedPermanently) return false
        if (!prefs.doubleTapToLock && !prefs.notificationColorEnabled) return false
        return isBackgroundRestricted()
    }

    private fun setupBatteryBanner() {
        updateBatteryBanner()

        val btnUnrestrict = findViewById<android.widget.TextView>(R.id.btnUnrestrict)
        val btnDismiss = findViewById<android.widget.TextView>(R.id.btnBatteryDismiss)
        val banner = findViewById<View>(R.id.batteryBanner)

        btnUnrestrict?.setOnClickListener { requestBatteryExemption() }

        btnDismiss?.setOnClickListener {
            showBatteryDismissDialog(onDismissOnce = {
                banner?.visibility = View.GONE
            })
        }

        // Style "FIX THIS" button background
        val density = resources.displayMetrics.density
        btnUnrestrict?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 4f * density
            setColor(Color.parseColor("#33FFFFFF"))
        }
    }

    private fun updateBatteryBanner() {
        val banner = findViewById<View>(R.id.batteryBanner) ?: return

        if (!shouldShowBatteryBanner()) {
            banner.visibility = View.GONE
            return
        }

        banner.visibility = View.VISIBLE

        val needsMakerNote = Build.MANUFACTURER.lowercase().let {
            it.contains("xiaomi") || it.contains("redmi") || it.contains("huawei") ||
            it.contains("honor") || it.contains("samsung") || it.contains("oppo") ||
            it.contains("vivo") || it.contains("oneplus")
        }

        findViewById<android.widget.TextView>(R.id.batteryBannerMessage)?.text = getString(
            if (needsMakerNote) R.string.settings_battery_banner_message_with_maker_note
            else R.string.settings_battery_banner_message
        )
    }

    private fun requestBatteryExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            batteryExemptLauncher.launch(intent)
        } catch (_: Exception) {
            try {
                batteryExemptLauncher.launch(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            } catch (_: Exception) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }
    }

    private fun showBatteryDismissDialog(onDismissOnce: () -> Unit) {
        val dialog = Dialog(this, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#CC5500") else Color.parseColor("#FF8C42")
        val density = resources.displayMetrics.density

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        dialog.findViewById<android.widget.TextView>(R.id.dialogTitle)?.apply {
            text = getString(R.string.settings_hide_warning_title)
            setTextColor(accent)
        }

        dialog.findViewById<android.widget.TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.settings_hide_warning_body)
            setTextColor(primary)
        }

        dialog.findViewById<android.widget.TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.settings_hide_warning_note)
            setTextColor(secondary)
        }

        dialog.findViewById<android.widget.TextView>(R.id.btnCancel)?.apply {
            text = getString(R.string.settings_dismiss_once)
            setTextColor(secondary)
            setOnClickListener {
                dialog.dismiss()
                onDismissOnce()
            }
        }

        dialog.findViewById<android.widget.TextView>(R.id.btnContinue)?.apply {
            text = getString(R.string.settings_dont_remind_again)
            setTextColor(accent)
            setOnClickListener {
                dialog.dismiss()
                prefs.batteryBannerDismissedPermanently = true
                updateBatteryBanner()
            }
        }

        dialog.show()
    }

    // ── About ─────────────────────────────────────────────────────

    private fun setupAbout() {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "-" }
        findViewById<TextView>(R.id.labelAppVersion)?.apply {
            text = getString(R.string.settings_version_value, versionName)
            setTextColor(secondary)
        }

        findViewById<View>(R.id.rowGithub)?.setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/roufsyed/Slate-Minimal-Launcher"))
            )
        }

        findViewById<View>(R.id.rowGuidedTour)?.setOnClickListener {
            // Manual re-run resets to step 0 and shows immediately.
            GuidedTourManager.show(this, prefs)
        }
    }
}
