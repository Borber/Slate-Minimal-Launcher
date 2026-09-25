package com.slate.launcher

import android.content.Context

enum class Direction { UP, DOWN, LEFT, RIGHT }

sealed class GestureAction {
    object None : GestureAction()
    object OpenNotifications : GestureAction()
    object LockScreen : GestureAction()
    object OpenSettings : GestureAction()
    object ToggleWifi : GestureAction()
    object ToggleBluetooth : GestureAction()
    object ToggleLocation : GestureAction()
    object OpenCamera : GestureAction()
    /** [key] is a stored app key; legacy work-profile keys remain readable but cannot launch. */
    data class OpenApp(val key: String) : GestureAction()

    fun serialize(): String = when (this) {
        is None              -> "NONE"
        is OpenNotifications -> "OPEN_NOTIFICATIONS"
        is LockScreen        -> "LOCK_SCREEN"
        is OpenSettings      -> "OPEN_SETTINGS"
        is ToggleWifi        -> "TOGGLE_WIFI"
        is ToggleBluetooth   -> "TOGGLE_BLUETOOTH"
        is ToggleLocation    -> "TOGGLE_LOCATION"
        is OpenCamera        -> "OPEN_CAMERA"
        is OpenApp           -> "app:$key"
    }

    companion object {
        /** Actions shown as static menu items (before "Open app…"). */
        val staticActions: List<GestureAction> =
            listOf(None, OpenNotifications, LockScreen, OpenSettings,
                   ToggleWifi, ToggleBluetooth, ToggleLocation, OpenCamera)

        fun deserialize(value: String): GestureAction = when {
            value == "OPEN_NOTIFICATIONS" -> OpenNotifications
            value == "LOCK_SCREEN"        -> LockScreen
            value == "OPEN_SETTINGS"      -> OpenSettings
            value == "SEARCH"             -> None
            value == "TOGGLE_WIFI"        -> ToggleWifi
            value == "TOGGLE_BLUETOOTH"   -> ToggleBluetooth
            value == "TOGGLE_LOCATION"    -> ToggleLocation
            value == "OPEN_CAMERA"        -> OpenCamera
            value.startsWith("app:")      -> OpenApp(value.removePrefix("app:"))
            else                          -> None
        }

        fun defaultFor(fingers: Int, dir: Direction): GestureAction =
            when {
                fingers == 1 && dir == Direction.UP   -> None
                fingers == 1 && dir == Direction.DOWN -> OpenNotifications
                else -> None
            }
    }
}

/** Human-readable label for static actions. App names are resolved at the call site. */
fun GestureAction.label(context: Context): String = context.getString(when (this) {
    is GestureAction.None -> R.string.gesture_none
    is GestureAction.OpenNotifications -> R.string.gesture_notifications
    is GestureAction.LockScreen -> R.string.gesture_lock
    is GestureAction.OpenSettings -> R.string.gesture_settings
    is GestureAction.ToggleWifi -> R.string.gesture_wifi
    is GestureAction.ToggleBluetooth -> R.string.gesture_bluetooth
    is GestureAction.ToggleLocation -> R.string.gesture_location
    is GestureAction.OpenCamera -> R.string.gesture_camera
    is GestureAction.OpenApp -> return key
})
