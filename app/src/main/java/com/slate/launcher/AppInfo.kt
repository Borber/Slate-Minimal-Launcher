package com.slate.launcher

/** One launchable app in the current user profile. */
data class AppInfo(
    val name: String,
    val packageName: String,
    val activityName: String
) {
    val key: String get() = packageName
}
