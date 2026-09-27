package com.slate.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.slate.launcher.shortcuts.PinnedShortcut
import com.slate.launcher.shortcuts.PinnedShortcutStore
import com.slate.launcher.shortcuts.ShortcutDestination

class AppRepository(
    private val context: Context,
    private val prefs: PreferencesManager,
    onPackagesChanged: (() -> Unit)? = null
) {

    private var installedSnapshot: Enumeration? = null
    private val packageReceiver = onPackagesChanged?.let { notify ->
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                installedSnapshot = null
                notify()
            }
        }
    }

    init {
        packageReceiver?.let { receiver ->
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }
    }

    fun close() {
        packageReceiver?.let { context.unregisterReceiver(it) }
        installedSnapshot = null
    }

    data class HomeSnapshot(val items: List<HomeItem>, val allApps: List<AppInfo>)

    private data class Enumeration(
        val apps: List<AppInfo>,
        val installedKeys: Set<String>,
        val authoritative: Boolean
    )

    private fun enumerate(): Enumeration {
        val installed = installedSnapshot ?: loadInstalledApps().also { installedSnapshot = it }
        val hidden = prefs.hiddenApps
        val apps = installed.apps.mapNotNull { app ->
            if (app.packageName in hidden) return@mapNotNull null
            val customName = prefs.getAppCustomName(app.packageName)
            if (customName == null) app else app.copy(name = customName)
        }
        return installed.copy(apps = apps)
    }

    private fun loadInstalledApps(): Enumeration {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val pm = context.packageManager
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val installed = activities.mapTo(HashSet()) { it.activityInfo.packageName }
        val apps = activities.mapNotNull { info ->
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName) return@mapNotNull null
            AppInfo(
                name = info.loadLabel(pm).toString(),
                packageName = pkg,
                activityName = info.activityInfo.name
            )
        }
        // An empty package-manager result is not proof that every app was uninstalled.
        return Enumeration(apps, installed, installed.isNotEmpty())
    }

    fun getAllApps(forceAlphabetical: Boolean = false): List<AppInfo> =
        sortApps(enumerate().apps, forceAlphabetical)

    private fun sortApps(apps: List<AppInfo>, forceAlphabetical: Boolean = false): List<AppInfo> {
        val sorted = if (prefs.sortByUsage && !forceAlphabetical) {
            apps.sortedByDescending { prefs.getUsageCount(it.key) }
        } else {
            apps.sortedBy { it.name.lowercase() }
        }
        val pinned = prefs.pinnedApps
        return sorted.sortedByDescending { it.key in pinned }
    }

    fun getHomeItems(folderId: String? = null): List<HomeItem> = getHomeSnapshot(folderId).items

    fun getHomeSnapshot(folderId: String? = null): HomeSnapshot {
        val enumeration = enumerate()
        val folders = FolderStore.reconcile(prefs, enumeration.installedKeys, enumeration.authoritative)
        val shortcuts = PinnedShortcutStore.reconcileInstalled(context, prefs)
        val items = if (folderId == null) {
            buildMainList(enumeration.apps, folders, shortcuts)
        } else {
            val folder = folders.firstOrNull { it.id == folderId }
            if (folder == null) buildMainList(enumeration.apps, folders, shortcuts)
            else {
                val members = enumeration.apps.filter { it.key in folder.packages }
                val sorted = if (prefs.sortByUsage) {
                    members.sortedByDescending { prefs.getUsageCount(it.key) }
                        .let { if (prefs.mostUsedPosition == "bottom") it.reversed() else it }
                } else members.sortedBy { it.name.lowercase() }
                listOf(HomeItem.BackOut) + sorted.map { HomeItem.AppItem(it) }
            }
        }
        return HomeSnapshot(items, sortApps(enumeration.apps))
    }

    private fun buildMainList(apps: List<AppInfo>, folders: List<Folder>, shortcuts: List<PinnedShortcut>): List<HomeItem> {
        val pinned = prefs.pinnedApps
        val pinnedFolders = prefs.pinnedFolders
        val folderKeys = folders.flatMapTo(HashSet()) { it.packages }
        val visibleKeys = apps.mapTo(HashSet()) { it.key }
        val visibleFolders = folders.filter { folder ->
            folder.packages.any { it in visibleKeys }
        }
        fun folderItem(folder: Folder) =
            HomeItem.FolderItem(folder, folder.packages.count { it in visibleKeys })

        val pinnedItems: List<HomeItem> =
            apps.filter { it.key in pinned }.map { HomeItem.AppItem(it) } +
                visibleFolders.filter { it.id in pinnedFolders }.map(::folderItem)
        val remaining: List<HomeItem> =
            apps.filter { it.key !in pinned && it.key !in folderKeys }
                .map { HomeItem.AppItem(it) } +
                visibleFolders.filter { it.id !in pinnedFolders }.map(::folderItem) +
                shortcuts.filter { ShortcutDestination.APP_LIST in it.destinations }
                    .map { HomeItem.ShortcutItem(it) }
        val sortedRemaining = sortSection(remaining)
        return sortSection(pinnedItems) +
            if (prefs.sortByUsage && prefs.mostUsedPosition == "bottom") sortedRemaining.reversed()
            else sortedRemaining
    }

    private fun sortSection(items: List<HomeItem>): List<HomeItem> =
        if (prefs.sortByUsage) {
            items.sortedByDescending { item ->
                when (item) {
                    is HomeItem.AppItem -> prefs.getUsageCount(item.info.key)
                    is HomeItem.FolderItem -> item.folder.packages.sumOf { prefs.getUsageCount(it) }
                    is HomeItem.ShortcutItem, HomeItem.BackOut -> 0
                }
            }
        } else {
            items.sortedBy { item ->
                when (item) {
                    is HomeItem.AppItem -> item.info.name.lowercase()
                    is HomeItem.FolderItem -> item.folder.name.lowercase()
                    is HomeItem.ShortcutItem -> item.shortcut.pinnedLabel.lowercase()
                    HomeItem.BackOut -> ""
                }
            }
        }
}
