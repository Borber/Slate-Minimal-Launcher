package com.slate.launcher

import android.app.Dialog
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
import java.io.File

class SettingsActivity : LocalizedActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var switchDoubleTap: MaterialSwitch
    /** Tracked across onResume calls so the OS resync in [syncShortcutHostPermission] fires
     * only on a false→true transition, not on every resume while already granted. */
    private var wasShortcutHostPermissionGranted: Boolean = false
    private lateinit var createBackupLauncher: ActivityResultLauncher<String>
    private lateinit var openBackupLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var importFontLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var requestRoleLauncher: ActivityResultLauncher<Intent>
    // Re-runs the biometric-row visibility/reconcile closure from setupSecurity. Captured on
    // setup; invoked from syncPermissionToggles so a biometric-enrollment change made outside
    // Slate (Android system Settings) is detected on the next onResume - mirrors the
    // syncAccessibilityToggle pattern but without an `awaiting*` flag (biometric grant is
    // entirely in-app, no system-settings round-trip).
    private var biometricReconcile: (() -> Unit)? = null

    companion object {
        private val MAX_SIZES     = (20..60).toList()
        private val LINE_SPACINGS = (0..24).toList()
        private val WORD_SPACINGS = (2..28).toList()
        private val GESTURE_SLOTS = listOf(
            Triple(1, Direction.UP,    "1 finger  ↑"),
            Triple(1, Direction.DOWN,  "1 finger  ↓"),
            Triple(1, Direction.LEFT,  "1 finger  ←"),
            Triple(1, Direction.RIGHT, "1 finger  →"),
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
            300 to R.string.code_light,
            400 to R.string.code_regular,
            500 to R.string.code_medium,
            700 to R.string.code_bold,
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
            PreferencesManager.FOLDER_STYLE_CHEVRON  to R.string.code_chevron,
            PreferencesManager.FOLDER_STYLE_SLASH    to R.string.code_slash,
            PreferencesManager.FOLDER_STYLE_BULLET   to R.string.code_bullet,
            PreferencesManager.FOLDER_STYLE_BRACKETS to R.string.code_brackets,
            PreferencesManager.FOLDER_STYLE_COUNT    to R.string.code_count,
            PreferencesManager.FOLDER_STYLE_PLAIN    to R.string.code_plain,
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

        importFontLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) importFont(uri) }

        requestRoleLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { /* result handled; just return to settings */ }

        prefs = PreferencesManager(this)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        switchDoubleTap = findViewById(R.id.switchDoubleTap)

        applyBackgroundColor()
        setupTextSize()
        setupTypography()
        setupColors()
        setupGestures()
        setupBackup()
        setupGeneral()
        setupLanguage()
        setupAppShortcuts()
        setupSecurity()
        setupLockLongPressMenus()
        setupAbout()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        // Prevent android.view.WindowLeaked if any of our owned dialogs is showing during a
        // configuration change.
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
        // Biometric reconcile: the user might have removed their fingerprint / face
        // enrollment from Android system Settings while Slate Settings was in the background.
        // The closure is set during setupSecurity and re-runs applyVisibility, which now
        // flips `prefs.biometricEnabled` off when enrollment is gone. No-op when biometric
        // is still available or pref is already false.
        biometricReconcile?.invoke()
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

    private fun updateDefaultLauncherRow() {
        val sub = findViewById<TextView>(R.id.labelDefaultLauncherSub) ?: return
        sub.text = if (isAlreadyDefaultLauncher())
            getString(R.string.code_slate_is_your_default_launcher)
        else
            getString(R.string.ui_open_system_launcher_picker)
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
            findViewById(R.id.switchHideStatusBar),
            findViewById(R.id.switchSortByUsage),
            findViewById(R.id.switchLockOrientation),
            findViewById(R.id.switchSyncToLockscreen),
            findViewById(R.id.switchFollowSystemTheme),
            findViewById(R.id.switchAlphaFastScroll),
            findViewById(R.id.switchHiddenAppsSecurity),
            findViewById(R.id.switchBiometric),
            findViewById(R.id.switchLockLongPressMenus),
            findViewById(R.id.switchKeepHiddenInRecents),
            findViewById(R.id.switchIncludePrivateInBackup)
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
        fun bindSeek(id: Int, labelId: Int, values: List<Int>, current: Int, unit: String,
                     save: (Int) -> Unit) {
            val seek = findViewById<SeekBar>(id)
            val label = findViewById<TextView>(labelId)
            seek.max = values.lastIndex
            seek.progress = values.indexOf(current).coerceAtLeast(0)
            label.text = "$current$unit"
            seek.setOnSeekBarChangeListener(seekBarListener { progress ->
                val value = values[progress]
                save(value)
                label.text = "$value$unit"
            })
        }
        bindSeek(R.id.maxFontSeekBar, R.id.maxFontLabel, MAX_SIZES, prefs.maxFontSize, "sp") {
            prefs.maxFontSize = it
        }
        bindSeek(R.id.lineSpacingSeekBar, R.id.lineSpacingLabel,
            LINE_SPACINGS, prefs.lineSpacing, "dp") { prefs.lineSpacing = it }
        bindSeek(R.id.wordSpacingSeekBar, R.id.wordSpacingLabel,
            WORD_SPACINGS, prefs.wordSpacing, "dp") { prefs.wordSpacing = it }
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
        weightValue.text = getString(WEIGHTS.find { it.first == prefs.fontWeight }?.second ?: R.string.code_regular)

        val fontItems = FONTS.map { it.displayName } + listOf(getString(R.string.code_import_from_storage))

        findViewById<android.view.View>(R.id.rowFont).setOnClickListener {
            SlateListDialog(
                context = this,
                title = getString(R.string.ui_font),
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
                title = getString(R.string.ui_alignment),
                items = listOf(getString(R.string.align_left), getString(R.string.align_center), getString(R.string.align_right)),
                bgColor = prefs.backgroundColor
            ) { index, _ ->
                prefs.textAlignment = listOf("left", "center", "right")[index]
                alignmentValue.text = alignmentLabel(prefs.textAlignment)
            }.show()
        }

        findViewById<android.view.View>(R.id.rowWeight).setOnClickListener {
            SlateListDialog(
                context = this,
                title = getString(R.string.ui_weight),
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
                title = getString(R.string.ui_folder_style),
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
    }

    private fun folderStyleDisplayLabel(value: String): String = getString(
        FOLDER_STYLE_LABELS.firstOrNull { it.first == value }?.second
            ?: FOLDER_STYLE_LABELS.first().second
    )

    private fun alignmentLabel(value: String): String = getString(when (value) {
        "left" -> R.string.align_left
        "right" -> R.string.align_right
        else -> R.string.align_center
    })

    /**
     * Sample render of each folder marker style for the picker preview column. Mirrors the
     * `folderLabel` helper in AppDrawerFragment but with a fixed sample folder name and a
     * fixed visible-count so the preview is stable. Uses a regular space (not NBSP) for the
     * bullet/count styles - orphan-wrap protection only matters in Flow's wrapping paragraph,
     * not inside a single dialog row.
     */
    private fun folderStylePreview(styleKey: String): String {
        val name = getString(R.string.sample_folder)
        return when (styleKey) {
            PreferencesManager.FOLDER_STYLE_SLASH    -> "$name/"
            PreferencesManager.FOLDER_STYLE_BULLET   -> "• $name"
            PreferencesManager.FOLDER_STYLE_BRACKETS -> "[$name]"
            PreferencesManager.FOLDER_STYLE_COUNT    -> "$name (5)"
            PreferencesManager.FOLDER_STYLE_PLAIN    -> name
            else                                     -> "$name ›"
        }
    }

    private var _fontValueRef: TextView? = null

    private fun fontDisplayName(key: String): String = when {
        key.startsWith("/") -> File(key).nameWithoutExtension
        else -> FONTS.find { it.key == key }?.displayName ?: getString(R.string.code_default)
    }

    private fun importFont(uri: Uri) {
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
            prefs.fontFamily = destFile.absolutePath
            _fontValueRef?.text = displayName
            Toast.makeText(this, getString(R.string.font_imported, displayName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_import, e.message), Toast.LENGTH_LONG).show()
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

        bgDisplay.setTextColor(secondary)
        textDisplay.setTextColor(secondary)

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

        updateBgSwatch(prefs.backgroundColor)
        updateTextSwatch(prefs.appTextColor)

        fun syncLockscreenIfNeeded(colorInt: Int) {
            if (!prefs.syncToLockscreen) return
            val ok = MainActivity.applyColorToLockscreen(this, colorInt)
            if (!ok) Toast.makeText(this, getString(R.string.code_could_not_set_lockscreen_wallpaper), Toast.LENGTH_SHORT).show()
        }

        // Entire row opens the picker - no keyboard input
        fun openBgPicker() {
            ColorPickerDialog(
                context = this,
                title = getString(R.string.ui_background),
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
                title = getString(R.string.code_app_text),
                initialColor = prefs.appTextColor,
                bgColor = prefs.backgroundColor
            ) { hex ->
                prefs.followSystemTheme = false
                prefs.appTextColor = hex
                updateTextSwatch(hex)
                setupColors()
            }.show()
        }

        findViewById<View>(R.id.rowBgColor).setOnClickListener { openBgPicker() }
        findViewById<View>(R.id.rowTextColor).setOnClickListener { openTextPicker() }

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
                    Toast.makeText(this, getString(R.string.code_could_not_set_lockscreen_wallpaper), Toast.LENGTH_SHORT).show()
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
            text = getString(R.string.code_follow_system_theme)
            setTextColor(accent)
        }

        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.theme_confirm_body)
            setTextColor(primary)
        }

        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.theme_confirm_note)
            setTextColor(secondary)
        }

        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener { dialog.dismiss() }
        }

        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            text = getString(R.string.action_enable)
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

            labelView.text = label
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
            text = getString(R.string.accessibility_consent_body)
            setTextColor(primary)
        }

        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.accessibility_consent_note)
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

    // ── Gesture action picker ─────────────────────────────────────

    private fun showGestureActionPicker(
        fingers: Int,
        dir: Direction,
        actionView: TextView
    ) {
        val labels = GestureAction.staticActions.map { it.label(this) } + listOf(getString(R.string.code_open_app))
        SlateListDialog(
            context = this,
            title = getString(R.string.code_gesture_action),
            items = labels,
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            if (index == GestureAction.staticActions.size) {
                showAppPicker(fingers, dir, actionView)
            } else {
                val action = GestureAction.staticActions[index]
                prefs.setGestureAction(fingers, dir, action)
                actionView.text = action.label(this)
            }
        }.show()
    }

    private fun showAppPicker(fingers: Int, dir: Direction, actionView: TextView) {
        val apps = AppRepository(this, prefs).getAllApps()
        SlateListDialog(
            context = this,
            title = getString(R.string.code_choose_app),
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
            else -> action.label(this)
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
            Toast.makeText(this, getString(R.string.code_backup_saved), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_export, e.message), Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, getString(R.string.error_import, e.message), Toast.LENGTH_LONG).show()
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
     * getString(R.string.ui_include_hidden_apps_in_backups). Reuses the dialog_accessibility_info.xml template.
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
            text = getString(R.string.code_include_hidden_apps)
            setTextColor(accent)
        }
        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.backup_private_body)
            setTextColor(primary)
        }
        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.backup_private_note)
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
            text = getString(R.string.code_turn_on)
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
            text = getString(R.string.code_replace_your_pin)
            setTextColor(accent)
        }
        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.backup_replace_body)
            setTextColor(primary)
        }
        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.backup_replace_note)
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
            text = getString(R.string.action_restore)
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
            title = getString(R.string.code_restore_hidden_apps),
            message = getString(R.string.backup_pin_prompt),
            confirmLabel = getString(R.string.action_verify),
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
            title = getString(R.string.code_restore_hidden_apps),
            // Body stays static across retries - the wrong-PIN feedback lives in the inline
            // error line (red) via setError below, which is the conventional pattern for form
            // validation errors and lets the user keep their bearings between attempts.
            message = getString(R.string.backup_pin_prompt),
            confirmLabel = getString(R.string.action_verify),
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
        dialog.setError(resources.getQuantityString(R.plurals.pin_attempts_left, remaining, remaining))
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
            text = getString(R.string.code_import_refused)
            setTextColor(accent)
        }
        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.backup_refused_body)
            setTextColor(primary)
        }
        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.backup_refused_note)
            setTextColor(secondary)
        }
        // Single-OK refusal: Cancel button has no useful semantic here.
        dialog.findViewById<TextView>(R.id.btnCancel)?.visibility = View.GONE
        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            text = getString(R.string.ui_ok)
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
            ImportOutcome.SETTINGS_RESTORED -> getString(R.string.code_settings_restored)
            ImportOutcome.PRIVATE_SKIPPED -> getString(R.string.code_settings_restored_hidden_apps_not_imported)
            ImportOutcome.PRIVATE_RESTORED -> getString(R.string.code_hidden_apps_restored)
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

    private fun setupGeneral() {
        val switchSort = findViewById<MaterialSwitch>(R.id.switchSortByUsage)
        val switchFast = findViewById<MaterialSwitch>(R.id.switchAlphaFastScroll)
        val fastRow = findViewById<View>(R.id.rowAlphaFastScroll)
        val fastSub = findViewById<TextView>(R.id.labelAlphaFastScrollSub)
        val positionRow = findViewById<View>(R.id.rowMostUsedPosition)
        val positionValue = findViewById<TextView>(R.id.mostUsedPositionValue)
        val positionSub = findViewById<TextView>(R.id.labelMostUsedPositionSub)
        val sortSub = findViewById<TextView>(R.id.labelSortByUsageSub)

        fun refreshSortSettings() {
            val byUsage = prefs.sortByUsage
            fastRow.alpha = if (byUsage) 0.4f else 1f
            switchFast.isEnabled = !byUsage
            fastSub.text = if (byUsage) getString(R.string.code_turn_off_sort_by_most_used_to_enable)
                else getString(R.string.ui_side_index_to_jump_letters_sorts_a_z)
            positionRow.alpha = if (byUsage) 1f else 0.4f
            positionRow.isClickable = byUsage
            positionValue.text = getString(
                if (prefs.mostUsedPosition == "bottom") R.string.position_bottom
                else R.string.position_top
            )
            positionSub.text = if (byUsage) getString(R.string.code_where_the_most_used_apps_land_in_the_list)
                else getString(R.string.ui_turn_on_sort_by_most_used_to_enable)
            sortSub.text = if (!byUsage) getString(R.string.ui_most_launched_apps_appear_first)
                else if (prefs.mostUsedPosition == "bottom")
                    getString(R.string.code_most_launched_apps_appear_at_the_bottom)
                else getString(R.string.code_most_launched_apps_appear_at_the_top)
        }
        switchSort.isChecked = prefs.sortByUsage
        switchSort.setOnCheckedChangeListener { _, checked ->
            prefs.sortByUsage = checked
            refreshSortSettings()
        }
        switchFast.isChecked = prefs.alphabeticalFastScroll
        switchFast.setOnCheckedChangeListener { _, checked ->
            prefs.alphabeticalFastScroll = checked
        }
        positionRow.setOnClickListener {
            SlateListDialog(
                context = this, title = getString(R.string.ui_show_most_used_at),
                items = listOf(getString(R.string.position_top), getString(R.string.position_bottom)),
                bgColor = prefs.backgroundColor
            ) { index, _ ->
                prefs.mostUsedPosition = if (index == 0) "top" else "bottom"
                refreshSortSettings()
            }.show()
        }
        refreshSortSettings()

        findViewById<MaterialSwitch>(R.id.switchLockOrientation).apply {
            isChecked = prefs.lockOrientation
            setOnCheckedChangeListener { _, checked ->
                prefs.lockOrientation = checked
                requestedOrientation = if (checked) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        findViewById<MaterialSwitch>(R.id.switchHideStatusBar).apply {
            isChecked = prefs.hideStatusBar
            setOnCheckedChangeListener { _, checked -> prefs.hideStatusBar = checked }
        }
        findViewById<View>(R.id.rowDefaultLauncher).setOnClickListener {
            requestDefaultLauncher()
        }
    }

    private fun setupLanguage() {
        val choices = listOf("system", "zh", "en")
        val labels = listOf(
            getString(R.string.language_system),
            getString(R.string.language_chinese),
            getString(R.string.language_english)
        )
        val row = findViewById<View>(R.id.rowLanguage)
        findViewById<TextView>(R.id.languageValue).text =
            labels[choices.indexOf(prefs.language).coerceAtLeast(0)]
        row.setOnClickListener {
            SlateListDialog(
                context = this,
                title = getString(R.string.language),
                items = labels,
                bgColor = prefs.backgroundColor
            ) { index, _ ->
                val selected = choices[index]
                if (selected != prefs.language) {
                    prefs.language = selected
                    row.post { recreate() }
                }
            }.show()
        }
    }

    private fun requestDefaultLauncher() {
        if (isAlreadyDefaultLauncher()) {
            SlateListDialog(
                context = this,
                title = getString(R.string.code_already_the_default_launcher),
                items = listOf(getString(R.string.default_launcher_thanks), getString(R.string.ui_ok)),
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

    // ── App shortcuts ──────────────────────────────────────────────

    private fun setupAppShortcuts() {
        findViewById<View>(R.id.rowAddShortcutToAppList).setOnClickListener {
            openShortcutPicker(ShortcutDestination.APP_LIST)
        }
    }

    private fun openShortcutPicker(destination: ShortcutDestination) {
        val launcherApps = PinnedShortcutStore.launcherApps(this)
        if (launcherApps == null || !PinnedShortcutStore.hasShortcutHostPermissionSafe(launcherApps)) {
            SlateListDialog(
                context = this,
                title = getString(R.string.code_set_slate_as_your_default_launcher),
                items = listOf(
                    getString(R.string.shortcut_host_required),
                    getString(R.string.code_open_launcher_settings)
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
                getString(R.string.ui_unlock_with_fingerprint_or_face_pin_remains_as_f)
            } else {
                getString(R.string.code_no_biometric_enrolled_on_this_device)
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
                    // from under getString(R.string.code_lock_long_press) without that feature's own consent.
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
                            message = getString(R.string.hidden_pin_prompt),
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
                        title = getString(R.string.code_disable_lock),
                        onSuccess = {
                            prefs.hiddenAppsSecurityEnabled = false
                            prefs.biometricEnabled = false
                            // Only if getString(R.string.code_lock_long_press) isn't also keeping this PIN
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
                        Toast.makeText(this, getString(R.string.code_no_biometric_enrolled_on_this_device), Toast.LENGTH_SHORT).show()
                        setBioSilently(false)
                        return@setOnCheckedChangeListener
                    }
                    // Require PIN before enabling biometric. Without this, anyone holding the
                    // unlocked phone could add their own biometric and gain ongoing access.
                    PinFlow.verifyExisting(
                        activity = this,
                        prefs = prefs,
                        pinManager = pinManager,
                        title = getString(R.string.code_enable_biometric),
                        onSuccess = {
                            AuthGate.verifyBiometric(
                                activity = this,
                                title = getString(R.string.code_enable_biometric),
                                subtitle = getString(R.string.code_confirm_biometric_to_enable),
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
                    Toast.makeText(this, getString(R.string.code_pin_changed), Toast.LENGTH_SHORT).show()
                }
            )
        }

        setupKeepHiddenInRecents()
    }

    /**
     * getString(R.string.code_lock_long_press) - PIN-gates the home long-press menu, each app's long-press menu,
     * folder and pinned-shortcut long-press menus (see AppDrawerFragment.showHomeLongPressDialog
     * / showAppMenu / showFolderMenu / showShortcutMenu), and the getString(R.string.code_open_settings) gesture action
     * (see executeGestureAction), using the SAME PIN as getString(R.string.ui_lock_hidden_apps) but never biometric,
     * regardless of [PreferencesManager.biometricEnabled].
     *
     * Independent of setupSecurity()'s master toggle: either can be on, off, or both, and
     * [PinManager.hasPin] is the only thing that ever ties them together. The enable/disable
     * guards here are the exact mirror of setupSecurity()'s own guards - see the comments there
     * for why an unconditional PinFlow.setupNew or an unconditional pinManager.clear() would be
     * wrong once a PIN can be kept alive by either toggle.
     *
     * No consent dialog on enable, unlike getString(R.string.ui_keep_hidden_apps_in_recents) or "Include hidden apps
     * in backups": those toggles WEAKEN an existing protection and this one only adds one,
     * exactly like getString(R.string.ui_lock_hidden_apps) itself, which has no consent dialog either.
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
                    // review) left getString(R.string.ui_change_pin) hidden after enabling this toggle from a cold
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
                            message = getString(R.string.menu_pin_prompt),
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
                        title = getString(R.string.code_disable_long_press_lock),
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
            text = getString(R.string.code_show_in_recents)
            setTextColor(accent)
        }

        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = getString(R.string.recents_consent_body)
            setTextColor(primary)
        }

        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = getString(R.string.recents_consent_note)
            setTextColor(secondary)
        }

        val check = dialog.findViewById<MaterialCheckBox>(R.id.checkUnderstand)
        val confirm = dialog.findViewById<TextView>(R.id.btnContinue)

        // The label is the checkbox's own text rather than a sibling TextView, so TalkBack sees
        // one node with the right role and checked state, and the whole thing is one 48dp
        // target. A separate label plus a row click listener reads as a generic clickable with
        // no checkable state.
        check?.apply {
            text = getString(R.string.code_i_understand_what_this_does)
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
            text = getString(R.string.code_turn_on)
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

    // ── About ─────────────────────────────────────────────────────

    private fun setupAbout() {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "-" }
        findViewById<TextView>(R.id.labelAppVersion)?.apply {
            text = "v$versionName"
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
