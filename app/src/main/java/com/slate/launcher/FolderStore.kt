package com.slate.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persistence and invariant enforcement for [Folder] objects. The whole library is stored as a
 * single JSON array in [PreferencesManager.foldersJson] - atomic writes, simple schema. The
 * format is intentionally trivial; no migration story needed for additive future fields.
 *
 * Invariants enforced by every mutating call:
 *   - An app (by AppKey) appears in at most one folder.
 *   - Folders are pruned when empty (or when their last visible app leaves).
 *   - Pinning an app removes it from any folder it was in.
 *   - [PreferencesManager.pinnedFolders] never references a folder that no longer exists.
 *
 * Hiding an app does NOT alter folder membership - the app is simply filtered at render time so
 * unhiding restores the previous home layout cleanly.
 *
 * [reconcile]'s input must be the full launcher-visible key set, including hidden apps.
 */
object FolderStore {

    fun all(prefs: PreferencesManager): List<Folder> = runCatching {
        val arr = JSONArray(prefs.foldersJson)
        (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun find(prefs: PreferencesManager, id: String): Folder? =
        all(prefs).firstOrNull { it.id == id }

    fun folderContaining(prefs: PreferencesManager, key: String): Folder? =
        all(prefs).firstOrNull { key in it.packages }

    /** Every AppKey currently inside any folder - cheap O(1) lookup helper. */
    fun keysInAnyFolder(prefs: PreferencesManager): Set<String> =
        all(prefs).flatMap { it.packages }.toSet()

    /** Create a new empty folder with the given (trimmed, non-empty) name. */
    fun createEmpty(prefs: PreferencesManager, name: String): Folder {
        val folder = Folder(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            packages = mutableListOf()
        )
        save(prefs, all(prefs) + folder)
        return folder
    }

    /** Adds [key] to [folderId]; removes it from any other folder it was in. */
    fun addAppToFolder(prefs: PreferencesManager, folderId: String, key: String) {
        // Pinned apps cannot live in folders - enforce by unpinning first.
        if (prefs.isPinned(key)) prefs.unpinApp(key)
        val list = all(prefs).toMutableList()
        list.forEachIndexed { i, f ->
            if (f.id != folderId) {
                if (key in f.packages) {
                    f.packages.remove(key)
                    list[i] = f
                }
            }
        }
        val target = list.firstOrNull { it.id == folderId } ?: return
        if (key !in target.packages) target.packages.add(key)
        save(prefs, list)
    }

    /**
     * Removes [key] from whatever folder contains it (if any). Prunes if empty.
     * Returns the folder that was pruned, or null.
     */
    fun removeAppFromFolder(prefs: PreferencesManager, key: String): Folder? {
        val list = all(prefs).toMutableList()
        var changed = false
        var pruned: Folder? = null
        val iter = list.iterator()
        while (iter.hasNext()) {
            val f = iter.next()
            if (key in f.packages) {
                f.packages.remove(key)
                changed = true
                if (f.packages.isEmpty()) {
                    pruned = f
                    iter.remove()  // prune now-empty folder
                }
            }
        }
        if (changed) save(prefs, list)
        return pruned
    }

    fun rename(prefs: PreferencesManager, folderId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val list = all(prefs)
        val target = list.firstOrNull { it.id == folderId } ?: return
        target.name = trimmed
        save(prefs, list)
    }

    fun setColor(prefs: PreferencesManager, folderId: String, hex: String?) {
        val list = all(prefs)
        val target = list.firstOrNull { it.id == folderId } ?: return
        target.color = hex
        save(prefs, list)
    }

    fun delete(prefs: PreferencesManager, folderId: String) {
        save(prefs, all(prefs).filterNot { it.id == folderId })
    }

    /**
     * Drop any package from folders that no longer corresponds to an installed app. Prunes the
     * folder ONLY if removal actually emptied a previously non-empty folder (i.e. all of its
     * apps were uninstalled). Freshly-created empty folders are preserved so the user can add
     * apps to them via the "Move to folder" flow - otherwise a stray rebuild between
     * createEmpty() and addAppToFolder() would silently nuke the new folder.
     */
    /** Reconcile current-profile apps while preserving legacy work-profile keys in stored folders. */
    fun reconcile(prefs: PreferencesManager, installedKeys: Set<String>, authoritative: Boolean): List<Folder> {
        val list = all(prefs).toMutableList()
        if (!authoritative) return list
        var changed = false
        val iter = list.iterator()
        while (iter.hasNext()) {
            val f = iter.next()
            val before = f.packages.size
            f.packages.removeAll { AppKey.serialOf(it) == null && it !in installedKeys }
            if (f.packages.size != before) {
                changed = true
                // Only auto-prune folders that BECAME empty here (had members before this call).
                // Folders that started empty are user-just-created and must persist.
                if (f.packages.isEmpty() && before > 0) iter.remove()
            }
        }
        if (changed) save(prefs, list)
        return list
    }


    private fun save(prefs: PreferencesManager, folders: List<Folder>) {
        val arr = JSONArray()
        folders.forEach { arr.put(it.toJson()) }

        // Drop pins for folders that no longer exist. Centralised here rather than at each
        // deletion site because folders die in three separate places - delete(), reconcile()'s
        // uninstall prune, and removeAppFromFolder()'s now-empty prune - and every one of them
        // funnels through save(), so this cannot be forgotten when a fourth is added. The guard
        // keeps the common case (nothing pinned, or nothing removed) free of a redundant write.
        // Both keys now land in ONE editor and ONE apply(), so a process kill can no longer
        // leave folders_v1 written and pinned_folders referencing a folder that just died.
        val liveIds = folders.mapTo(HashSet()) { it.id }
        val pinned = prefs.pinnedFolders
        val prunedPins =
            if (pinned.any { it !in liveIds }) pinned.filterTo(HashSet()) { it in liveIds }
            else null

        prefs.commitFolderState(
            foldersJson = arr.toString(),
            pinnedFolders = prunedPins
        )
    }

    private fun Folder.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("packages", JSONArray().also { arr -> packages.forEach { arr.put(it) } })
        color?.let { put("color", it) }
        profileSerial?.let { put("profileSerial", it) }
    }

    private fun fromJson(obj: JSONObject): Folder? {
        val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: return null
        val pkgsArr = obj.optJSONArray("packages") ?: JSONArray()
        val packages = (0 until pkgsArr.length()).mapTo(mutableListOf()) { pkgsArr.getString(it) }
        val color = obj.optString("color").takeIf { it.isNotEmpty() }
        // has() is mandatory: optLong returns 0 for an absent key, and 0 is a valid user
        // serial, so a bare optLong would make every pre-existing folder claim serial 0.
        val profileSerial = if (obj.has("profileSerial")) obj.optLong("profileSerial") else null
        return Folder(id, name, packages, color, profileSerial)
    }
}
