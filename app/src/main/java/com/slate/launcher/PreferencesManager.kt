package com.slate.launcher

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * A second store for preferences that must never leave this device by ANY route.
     *
     * System backup no longer distinguishes the two stores: `android:allowBackup="false"` plus
     * the exclude-only rules files mean NEITHER [prefs] nor [devicePrefs] is handed to Google
     * cloud backup or to device-to-device transfer. They are equally invisible to the platform.
     *
     * What still separates them is Slate's own export. [BackupManager] serialises [prefs] into
     * the user-facing JSON and never reads [devicePrefs], so putting a preference here keeps it
     * out of a file the user could carry to another device or share by mistake.
     */
    private val devicePrefs: SharedPreferences =
        context.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "slate_prefs"
        private const val DEVICE_PREFS_NAME = "slate_device_prefs"
        private const val KEY_HIDDEN_APPS = "hidden_apps"
        private const val KEY_MAX_FONT_SIZE = "max_font_size"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_BACKGROUND_COLOR = "background_color"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_TEXT_COLOR = "text_color"
        private const val KEY_DOUBLE_TAP_LOCK = "double_tap_lock"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_FONT_WEIGHT = "font_weight"
        private const val KEY_LINE_SPACING = "line_spacing"
        private const val KEY_WORD_SPACING = "word_spacing"
        private const val KEY_HIDE_STATUS_BAR = "hide_status_bar"
        private const val KEY_SORT_BY_USAGE = "sort_by_usage"
        private const val KEY_MOST_USED_POSITION = "most_used_position"
        private const val KEY_TEXT_ALIGNMENT = "text_alignment"
        private const val KEY_LOCK_ORIENTATION = "lock_orientation"
        private const val KEY_SYNC_TO_LOCKSCREEN = "sync_to_lockscreen"
        private const val KEY_PINNED_APPS = "pinned_apps"
        private const val KEY_PINNED_FOLDERS = "pinned_folders"
        private const val KEY_FOLLOW_SYSTEM_THEME = "follow_system_theme"
        private const val KEY_AWAITING_ACCESSIBILITY = "awaiting_accessibility_permission"
        private const val KEY_ALPHA_FAST_SCROLL = "alpha_fast_scroll"
        private const val KEY_HIDDEN_APPS_SECURITY = "hidden_apps_security_enabled"
        /** Stored in slate_device_prefs, not slate_prefs. See [keepHiddenAppsInRecents]. */
        private const val KEY_KEEP_HIDDEN_IN_RECENTS = "keep_hidden_apps_in_recents"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_LOCK_LONG_PRESS_MENUS = "lock_long_press_menus_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_ITERATIONS = "pin_iterations"
        private const val KEY_PIN_FAILED_ATTEMPTS = "pin_failed_attempts"
        private const val KEY_PIN_LOCKOUT_UNTIL = "pin_lockout_until_epoch_ms"
        private const val KEY_PIN_LOCKOUT_UNTIL_ELAPSED = "pin_lockout_until_elapsed_ms"
        private const val KEY_INCLUDE_PRIVATE_IN_BACKUP = "include_private_in_backup"
        private const val KEY_PINNED_SHORTCUTS = "pinned_shortcuts"
        private const val KEY_FOLDERS = "folders_v1"
        private const val KEY_GUIDED_TOUR_STEP = "guided_tour_step_index"
        private const val KEY_GUIDED_TOUR_VERSION_SEEN = "guided_tour_version_seen"
        private const val KEY_FOLDER_STYLE = "folder_style"

        // How folder labels appear on the home screen. Default `chevron` preserves the
        // out-of-the-box behaviour; any unknown stored value also falls back to chevron at
        // render time so renaming or removing a style is safe.
        const val FOLDER_STYLE_CHEVRON = "chevron"     // "Work ›"
        const val FOLDER_STYLE_SLASH = "slash"         // "Work/"
        const val FOLDER_STYLE_BULLET = "bullet"       // "• Work"
        const val FOLDER_STYLE_BRACKETS = "brackets"   // "[Work]"
        const val FOLDER_STYLE_COUNT = "count"         // "Work (5)"
        const val FOLDER_STYLE_PLAIN = "plain"         // "Work"

        const val DEFAULT_FONT_FAMILY = "gf:tex_gyre_adventor_bold"
        const val DEFAULT_FONT_WEIGHT = 400
        const val DEFAULT_LINE_SPACING = 5
        const val DEFAULT_WORD_SPACING = 10

        const val DEFAULT_MAX_FONT_SIZE = 42
        const val DEFAULT_BACKGROUND_COLOR = "#000000"
        const val DEFAULT_TEXT_COLOR = "#808080"

    }

    var hiddenApps: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_HIDDEN_APPS, value).apply()

    var maxFontSize: Int
        get() = prefs.getInt(KEY_MAX_FONT_SIZE, DEFAULT_MAX_FONT_SIZE)
        set(value) = prefs.edit().putInt(KEY_MAX_FONT_SIZE, value).apply()

    var backgroundColor: String
        get() = prefs.getString(KEY_BACKGROUND_COLOR, DEFAULT_BACKGROUND_COLOR) ?: DEFAULT_BACKGROUND_COLOR
        set(value) = prefs.edit().putString(KEY_BACKGROUND_COLOR, value).apply()

    var appTextColor: String
        get() = prefs.getString(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR) ?: DEFAULT_TEXT_COLOR
        set(value) = prefs.edit().putString(KEY_TEXT_COLOR, value).apply()

    var doubleTapToLock: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_TAP_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_DOUBLE_TAP_LOCK, value).apply()

    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, DEFAULT_FONT_FAMILY) ?: DEFAULT_FONT_FAMILY
        set(value) = prefs.edit().putString(KEY_FONT_FAMILY, value).apply()

    var fontWeight: Int
        get() = prefs.getInt(KEY_FONT_WEIGHT, DEFAULT_FONT_WEIGHT)
        set(value) = prefs.edit().putInt(KEY_FONT_WEIGHT, value).apply()

    var lineSpacing: Int
        get() = prefs.getInt(KEY_LINE_SPACING, DEFAULT_LINE_SPACING)
        set(value) = prefs.edit().putInt(KEY_LINE_SPACING, value).apply()

    var wordSpacing: Int
        get() = prefs.getInt(KEY_WORD_SPACING, DEFAULT_WORD_SPACING)
        set(value) = prefs.edit().putInt(KEY_WORD_SPACING, value).apply()

    var hideStatusBar: Boolean
        get() = prefs.getBoolean(KEY_HIDE_STATUS_BAR, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_STATUS_BAR, value).apply()

    var sortByUsage: Boolean
        get() = prefs.getBoolean(KEY_SORT_BY_USAGE, false)
        set(value) = prefs.edit().putBoolean(KEY_SORT_BY_USAGE, value).apply()

    /** "top" or "bottom" - where the most-used apps land within the usage-sorted home list. */
    var mostUsedPosition: String
        get() = prefs.getString(KEY_MOST_USED_POSITION, "top") ?: "top"
        set(value) = prefs.edit().putString(KEY_MOST_USED_POSITION, value).apply()

    /** "left", "center", or "right" */
    var textAlignment: String
        get() = prefs.getString(KEY_TEXT_ALIGNMENT, "center") ?: "center"
        set(value) = prefs.edit().putString(KEY_TEXT_ALIGNMENT, value).apply()

    var lockOrientation: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ORIENTATION, true)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_ORIENTATION, value).apply()

    var syncToLockscreen: Boolean
        get() = prefs.getBoolean(KEY_SYNC_TO_LOCKSCREEN, false)
        set(value) = prefs.edit().putBoolean(KEY_SYNC_TO_LOCKSCREEN, value).apply()

    var pinnedApps: Set<String>
        get() = prefs.getStringSet(KEY_PINNED_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_PINNED_APPS, value).apply()

    fun pinApp(key: String) { pinnedApps = pinnedApps + key }
    fun unpinApp(key: String) { pinnedApps = pinnedApps - key }
    fun isPinned(key: String): Boolean = key in pinnedApps

    /**
     * Folder ids ([Folder.id], a UUID) pinned to the top of the home list. Keyed by id rather
     * than name so a rename never breaks the pin. Ids are stable across a backup round trip -
     * FolderStore serialises and restores them verbatim - so this survives export/import.
     *
     * Entries are pruned by [FolderStore.delete] and [FolderStore.reconcile]; anything that
     * still slips through (a hand-edited backup, say) is simply never matched and stays inert.
     */
    var pinnedFolders: Set<String>
        get() = prefs.getStringSet(KEY_PINNED_FOLDERS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_PINNED_FOLDERS, value).apply()

    fun pinFolder(folderId: String) { pinnedFolders = pinnedFolders + folderId }
    fun unpinFolder(folderId: String) { pinnedFolders = pinnedFolders - folderId }
    fun isFolderPinned(folderId: String): Boolean = folderId in pinnedFolders

    var followSystemTheme: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_SYSTEM_THEME, false)
        set(value) = prefs.edit().putBoolean(KEY_FOLLOW_SYSTEM_THEME, value).apply()

    var awaitingAccessibilityPermission: Boolean
        get() = prefs.getBoolean(KEY_AWAITING_ACCESSIBILITY, false)
        set(value) = prefs.edit().putBoolean(KEY_AWAITING_ACCESSIBILITY, value).apply()

    var alphabeticalFastScroll: Boolean
        get() = prefs.getBoolean(KEY_ALPHA_FAST_SCROLL, false)
        set(value) = prefs.edit().putBoolean(KEY_ALPHA_FAST_SCROLL, value).apply()

    // ── Hidden apps security ───────────────────────────────────────

    /**
     * When true, hidden-app launches skip FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS and so appear in
     * the Recents screen like any other app.
     *
     * The flag is not merely cosmetic: from Android 9 the system trims an excluded task once it
     * falls behind the home task, and trimming finishes the task's activities outright, so the
     * hidden app is destroyed and loses whatever the user had typed. This preference is the only
     * available escape hatch - no public API lets a launcher keep a task out of Recents without
     * that side effect.
     *
     * Lives in [devicePrefs], NOT [prefs], so no backup route can carry it to another device.
     * It is additionally forced back to false by BackupManager.applyNonPrivate, which covers
     * restoring a JSON backup onto the same device. Default false: existing installs are
     * unaffected and the weaker behaviour is never on unless deliberately chosen.
     */
    var keepHiddenAppsInRecents: Boolean
        get() = devicePrefs.getBoolean(KEY_KEEP_HIDDEN_IN_RECENTS, false)
        set(value) = devicePrefs.edit().putBoolean(KEY_KEEP_HIDDEN_IN_RECENTS, value).apply()

    var hiddenAppsSecurityEnabled: Boolean
        get() = prefs.getBoolean(KEY_HIDDEN_APPS_SECURITY, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDDEN_APPS_SECURITY, value).apply()

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()

    /**
     * PIN-gates the home long-press menu (Customize / Hidden Apps / FAQ), each app's long-press
     * menu (Pin, Hide, Rename, Uninstall, ...), the folder and pinned-shortcut long-press menus,
     * and the "Open settings" gesture action - using the SAME PIN as [hiddenAppsSecurityEnabled].
     * There is exactly one PIN in this app (see
     * PinManager); this flag and that one are otherwise independent, either can be on, off, or
     * both, and PinManager.hasPin() is the only thing that ever ties them together.
     *
     * Never uses biometric, regardless of [biometricEnabled] - see AuthGate.authenticatePinOnly,
     * which has no biometric branch in its body at all, a deliberate choice over a parameter
     * that could be silently passed wrong.
     */
    var lockLongPressMenusEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_LONG_PRESS_MENUS, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_LONG_PRESS_MENUS, value).apply()

    /**
     * When false (the default), the JSON backup omits the entire private bundle - hidden-apps
     * list, security flag, biometric flag, and the PIN's PBKDF2 hash / salt / iteration count.
     * This pref is per-device and is NEVER written into a backup file: a restored backup must
     * not carry the source device's privacy preference here, otherwise turning the toggle off
     * on the source device would silently turn back on when restored. Default OFF is the
     * privacy-conservative choice - opt-in only via the Settings → Backup row, which shows a
     * consent dialog when the user turns it on.
     */
    var includePrivateInBackup: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_PRIVATE_IN_BACKUP, false)
        set(value) = prefs.edit().putBoolean(KEY_INCLUDE_PRIVATE_IN_BACKUP, value).apply()

    /** Base64-encoded PBKDF2 hash of the user's PIN. */
    var pinHash: String?
        get() = prefs.getString(KEY_PIN_HASH, null)
        set(value) = prefs.edit().run {
            if (value == null) remove(KEY_PIN_HASH) else putString(KEY_PIN_HASH, value)
        }.apply()

    /** Base64-encoded per-user random salt. */
    var pinSalt: String?
        get() = prefs.getString(KEY_PIN_SALT, null)
        set(value) = prefs.edit().run {
            if (value == null) remove(KEY_PIN_SALT) else putString(KEY_PIN_SALT, value)
        }.apply()

    var pinIterations: Int
        get() = prefs.getInt(KEY_PIN_ITERATIONS, 0)
        set(value) = prefs.edit().putInt(KEY_PIN_ITERATIONS, value).apply()

    var pinFailedAttempts: Int
        get() = prefs.getInt(KEY_PIN_FAILED_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_PIN_FAILED_ATTEMPTS, value).apply()

    var pinLockoutUntilEpochMs: Long
        get() = prefs.getLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_PIN_LOCKOUT_UNTIL, value).apply()

    /** Lockout deadline expressed in SystemClock.elapsedRealtime (monotonic, defeats clock rollback). */
    var pinLockoutUntilElapsedMs: Long
        get() = prefs.getLong(KEY_PIN_LOCKOUT_UNTIL_ELAPSED, 0L)
        set(value) = prefs.edit().putLong(KEY_PIN_LOCKOUT_UNTIL_ELAPSED, value).apply()

    /** Raw JSON for pinned external-app shortcuts. Parsed by PinnedShortcutStore. */
    var pinnedShortcutsJson: String
        get() = prefs.getString(KEY_PINNED_SHORTCUTS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_PINNED_SHORTCUTS, value).apply()

    /** Raw JSON for user-created folders. Parsed by FolderStore. */
    var foldersJson: String
        get() = prefs.getString(KEY_FOLDERS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_FOLDERS, value).apply()

    /**
     * Single-editor commit for folder state. [FolderStore.save] is the only caller. Batching
     * exists because folders_v1 and pinned_folders were previously two independent apply()
     * calls, so a process kill between them could leave a pin referencing a dead folder.
     *
     * Written VALUES are unchanged from the two-write form; only batching changes.
     */
    fun commitFolderState(
        foldersJson: String,
        pinnedFolders: Set<String>? = null
    ) {
        val editor = prefs.edit()
        editor.putString(KEY_FOLDERS, foldersJson)
        pinnedFolders?.let { editor.putStringSet(KEY_PINNED_FOLDERS, it) }
        editor.apply()
    }

    // ── Guided tour ──────────────────────────────────────────────
    // `guidedTourStepIndex` is -1 once the user has completed or skipped the tour, 0+ while
    // mid-tour. Persisted so a process-death mid-tour resumes at the same step.
    var guidedTourStepIndex: Int
        get() = prefs.getInt(KEY_GUIDED_TOUR_STEP, 0)
        set(value) = prefs.edit().putInt(KEY_GUIDED_TOUR_STEP, value).apply()

    // The last CURRENT_TOUR_VERSION the user actually saw. Bumping the in-code constant past
    // this re-triggers the tour on next resume - used to surface meaningful content updates.
    var guidedTourSeenVersion: Int
        get() = prefs.getInt(KEY_GUIDED_TOUR_VERSION_SEEN, 0)
        set(value) = prefs.edit().putInt(KEY_GUIDED_TOUR_VERSION_SEEN, value).apply()

    /** One of the `FOLDER_STYLE_*` constants. Default is chevron - matches current behaviour. */
    var folderStyle: String
        get() = prefs.getString(KEY_FOLDER_STYLE, FOLDER_STYLE_CHEVRON) ?: FOLDER_STYLE_CHEVRON
        set(value) = prefs.edit().putString(KEY_FOLDER_STYLE, value).apply()

    // ── Per-app custom names ──────────────────────────────────────

    fun getAppCustomName(key: String): String? =
        prefs.getString("app_name_$key", null)

    fun setAppCustomName(key: String, name: String) =
        prefs.edit().putString("app_name_$key", name).apply()

    fun clearAppCustomName(key: String) =
        prefs.edit().remove("app_name_$key").apply()

    fun getAllAppCustomNames(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.startsWith("app_name_") }
            .associate { it.key.removePrefix("app_name_") to (it.value as? String ?: "") }

    // ── Per-app text color ─────────────────────────────────────────

    fun getAppTextColor(key: String): String? =
        prefs.getString("app_color_$key", null)

    fun setAppTextColor(key: String, hex: String) =
        prefs.edit().putString("app_color_$key", hex).apply()

    fun clearAppTextColor(key: String) =
        prefs.edit().remove("app_color_$key").apply()

    fun getAllAppColors(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.startsWith("app_color_") }
            .associate { it.key.removePrefix("app_color_") to (it.value as? String ?: "") }

    fun getAllGestureActions(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.startsWith("gesture_") }
            .associate { it.key to (it.value as? String ?: "") }

    fun setGestureActionRaw(key: String, value: String) =
        prefs.edit().putString(key, value).apply()

    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, value).apply()

    /** system follows the device; zh and en are explicit overrides. */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "system")
            ?.takeIf { it == "system" || it == "zh" || it == "en" } ?: "system"
        set(value) = prefs.edit().putString(
            KEY_LANGUAGE,
            value.takeIf { it == "zh" || it == "en" } ?: "system"
        ).apply()

    // ── Gesture actions ───────────────────────────────────────────

    fun getGestureAction(fingers: Int, dir: Direction): GestureAction {
        val saved = prefs.getString(gestureKey(fingers, dir), null)
            ?: return GestureAction.defaultFor(fingers, dir)
        return GestureAction.deserialize(saved)
    }

    fun setGestureAction(fingers: Int, dir: Direction, action: GestureAction) {
        prefs.edit().putString(gestureKey(fingers, dir), action.serialize()).apply()
    }

    private fun gestureKey(fingers: Int, dir: Direction) =
        "gesture_${fingers}_${dir.name.lowercase()}"

    // ── Usage tracking ────────────────────────────────────────────

    fun incrementUsage(appKey: String) {
        val prefKey = "usage_$appKey"
        prefs.edit().putInt(prefKey, prefs.getInt(prefKey, 0) + 1).apply()
    }

    fun getUsageCount(key: String): Int =
        prefs.getInt("usage_$key", 0)

    // ── App visibility ────────────────────────────────────────────

    fun hideApp(key: String) {
        hiddenApps = hiddenApps + key
    }

    fun unhideApp(key: String) {
        hiddenApps = hiddenApps - key
    }
}
