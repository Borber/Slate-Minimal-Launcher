package com.slate.launcher

import android.content.Context

enum class Direction { UP, DOWN, LEFT, RIGHT }

sealed class GestureAction {
    object None : GestureAction()
    object OpenNotifications : GestureAction()
    object LockScreen : GestureAction()
    object OpenSettings : GestureAction()
    object Search : GestureAction()
    object ToggleWifi : GestureAction()
    object ToggleBluetooth : GestureAction()
    object ToggleLocation : GestureAction()
    object OpenCamera : GestureAction()
    /**
     * [key] is preference-space (an AppKey), not a bare package name. Readers that hand it to
     * the OS must unwrap it with AppKey.packageOf once work profiles land in Stage 2; in Stage 0
     * the two are identical, so the serialised form is byte-compatible with existing bindings.
     */
    data class OpenApp(val key: String) : GestureAction()

    fun serialize(): String = when (this) {
        is None              -> "NONE"
        is OpenNotifications -> "OPEN_NOTIFICATIONS"
        is LockScreen        -> "LOCK_SCREEN"
        is OpenSettings      -> "OPEN_SETTINGS"
        is Search            -> "SEARCH"
        is ToggleWifi        -> "TOGGLE_WIFI"
        is ToggleBluetooth   -> "TOGGLE_BLUETOOTH"
        is ToggleLocation    -> "TOGGLE_LOCATION"
        is OpenCamera        -> "OPEN_CAMERA"
        is OpenApp           -> "app:$key"
    }

    companion object {
        /** Actions shown as static menu items (before "Open app…"). */
        val staticActions: List<GestureAction> =
            listOf(None, OpenNotifications, LockScreen, OpenSettings, Search,
                   ToggleWifi, ToggleBluetooth, ToggleLocation, OpenCamera)

        fun deserialize(value: String): GestureAction = when {
            value == "OPEN_NOTIFICATIONS" -> OpenNotifications
            value == "LOCK_SCREEN"        -> LockScreen
            value == "OPEN_SETTINGS"      -> OpenSettings
            value == "SEARCH"             -> Search
            value == "TOGGLE_WIFI"        -> ToggleWifi
            value == "TOGGLE_BLUETOOTH"   -> ToggleBluetooth
            value == "TOGGLE_LOCATION"    -> ToggleLocation
            value == "OPEN_CAMERA"        -> OpenCamera
            value.startsWith("app:")      -> OpenApp(value.removePrefix("app:"))
            else                          -> None
        }

        fun defaultFor(fingers: Int, dir: Direction): GestureAction =
            when {
                fingers == 1 && dir == Direction.UP   -> Search
                fingers == 1 && dir == Direction.DOWN -> OpenNotifications
                else -> None
            }
    }
}

/** Human-readable label for static actions. App names are resolved at the call site. */
fun GestureAction.staticLabel(context: Context): String =
    when (this) {
        is GestureAction.None              -> context.getString(R.string.gesture_none)
        is GestureAction.OpenNotifications -> context.getString(R.string.gesture_open_notifications)
        is GestureAction.LockScreen        -> context.getString(R.string.gesture_lock_screen)
        is GestureAction.OpenSettings      -> context.getString(R.string.gesture_open_settings)
        is GestureAction.Search            -> context.getString(R.string.gesture_search_apps)
        is GestureAction.ToggleWifi        -> context.getString(R.string.gesture_toggle_wifi)
        is GestureAction.ToggleBluetooth   -> context.getString(R.string.gesture_toggle_bluetooth)
        is GestureAction.ToggleLocation    -> context.getString(R.string.gesture_toggle_location)
        is GestureAction.OpenCamera        -> context.getString(R.string.gesture_open_camera)
        is GestureAction.OpenApp           -> key          // resolved to app name in UI
    }
