package com.slate.launcher.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.slate.launcher.R

private fun batteryReceiver(context: Context, onChanged: () -> Unit): WidgetSubscription {
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_BATTERY_CHANGED)
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
    }
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = onChanged()
    }
    // RECEIVER_EXPORTED required on API 34+ for system-originated broadcasts; ContextCompat
    // routes correctly on older APIs.
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    return object : WidgetSubscription {
        override fun close() {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}

/** Tries to deep-link to the battery settings page; falls back silently if unsupported. */
private fun openBatterySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

object BatteryPercentWidget : QuickWidget() {
    override val id = "battery"
    override fun displayName(context: Context) = context.getString(R.string.widget_battery_name)
    override fun renderLabel(context: Context): WidgetLabel {
        val mgr = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = mgr.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val text =
            if (pct in 0..100) context.getString(R.string.widget_battery_value, pct)
            else context.getString(R.string.widget_battery_unknown)
        return WidgetLabel(text, active = true)
    }
    override fun onTap(context: Context) = openBatterySettings(context)
    override fun startObserving(context: Context, onChanged: () -> Unit) =
        batteryReceiver(context, onChanged)
}

object ChargingWidget : QuickWidget() {
    override val id = "charging"
    override fun displayName(context: Context) = context.getString(R.string.widget_charging_name)
    override fun renderLabel(context: Context): WidgetLabel {
        val mgr = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val charging = mgr.isCharging
        return WidgetLabel(context.getString(R.string.widget_charging), active = charging)
    }
    override fun onTap(context: Context) = openBatterySettings(context)
    override fun startObserving(context: Context, onChanged: () -> Unit) =
        batteryReceiver(context, onChanged)
}

object BatteryTempWidget : QuickWidget() {
    override val id = "battery_temp"
    override fun displayName(context: Context) =
        context.getString(R.string.widget_battery_temp_name)
    override fun renderLabel(context: Context): WidgetLabel {
        // EXTRA_TEMPERATURE is reported in tenths of a degree Celsius via the sticky broadcast.
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenthsC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (tenthsC < 0) return WidgetLabel(
            context.getString(R.string.widget_battery_temp_unknown), active = false
        )
        val celsius = tenthsC / 10
        return WidgetLabel(
            context.getString(R.string.widget_battery_temp_value, celsius), active = true
        )
    }
    override fun startObserving(context: Context, onChanged: () -> Unit) =
        batteryReceiver(context, onChanged)
}

object TimeToFullWidget : QuickWidget() {
    override val id = "time_to_full"
    override fun displayName(context: Context) =
        context.getString(R.string.widget_time_to_full_name)
    // Surfaced in the widget picker so users on devices where BatteryManager doesn't expose a
    // charge-time estimate (common on Xiaomi / Samsung / various MediaTek-based OEMs) know to
    // expect "-" instead of treating it as a bug. The strip itself stays minimal - this hint
    // lives only at opt-in time.
    override fun pickerNote(context: Context) =
        context.getString(R.string.widget_time_to_full_note)

    // computeChargeTimeRemaining is API 28+ (Android 9). On older releases the widget is hidden
    // entirely via the catalog's isAvailable filter rather than rendering a permanent "-".
    override fun isAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    override fun renderLabel(context: Context): WidgetLabel {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return WidgetLabel(
                context.getString(R.string.widget_time_to_full_unknown), active = false
            )
        }
        val mgr = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        if (!mgr.isCharging) {
            return WidgetLabel(
                context.getString(R.string.widget_time_to_full_unknown), active = false
            )
        }
        // -1 = OEM didn't wire up an estimate. 0 = at-or-near-full (trickle / topping). Both
        // collapse to "-" because there's no meaningful number to surface; the user can read
        // the actual charge level from the Battery % widget next to this one.
        val ms = mgr.computeChargeTimeRemaining()
        if (ms <= 0L) {
            return WidgetLabel(
                context.getString(R.string.widget_time_to_full_unknown), active = false
            )
        }
        val totalMinutes = ms / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val text =
            if (hours > 0) context.getString(R.string.widget_duration_hours_minutes, hours, minutes)
            else context.getString(R.string.widget_duration_minutes, minutes)
        return WidgetLabel(
            context.getString(R.string.widget_time_to_full_value, text), active = true
        )
    }

    override fun onTap(context: Context) = openBatterySettings(context)
    override fun startObserving(context: Context, onChanged: () -> Unit) =
        batteryReceiver(context, onChanged)
}

object UptimeWidget : QuickWidget() {
    override val id = "uptime"
    override fun displayName(context: Context) = context.getString(R.string.widget_uptime_name)
    override fun renderLabel(context: Context): WidgetLabel {
        val ms = SystemClock.elapsedRealtime()
        val totalMinutes = ms / 60_000L
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        val text = when {
            days > 0 -> context.getString(R.string.widget_duration_days_hours, days, hours)
            hours > 0 -> context.getString(R.string.widget_duration_hours_minutes, hours, minutes)
            else -> context.getString(R.string.widget_duration_minutes, minutes)
        }
        return WidgetLabel(context.getString(R.string.widget_uptime_value, text), active = true)
    }
    // No broadcast - strip will refresh on resume; close-enough granularity for an uptime label.
}
