package com.slate.launcher

import org.junit.Assert.*
import org.junit.Test

class FolderStoreTest {
    private val prefs = PreferencesManager(MemoryContext())

    @Test fun movingLastMemberPrunesSourceAndItsPin() {
        val source = FolderStore.createEmpty(prefs, "Source")
        val target = FolderStore.createEmpty(prefs, "Target")
        FolderStore.addAppToFolder(prefs, source.id, "app.one")
        prefs.pinFolder(source.id)

        val removed = FolderStore.addAppToFolder(prefs, target.id, "app.one")

        assertEquals(listOf(source.id), removed.map { it.id })
        assertNull(FolderStore.find(prefs, source.id))
        assertFalse(prefs.isFolderPinned(source.id))
        assertEquals(listOf("app.one"), FolderStore.find(prefs, target.id)!!.packages)
    }

    @Test fun hiddenMembersKeepTheirFolderWhenVisibleMemberMoves() {
        val source = FolderStore.createEmpty(prefs, "Source")
        val target = FolderStore.createEmpty(prefs, "Target")
        FolderStore.addAppsToFolder(prefs, source.id, listOf("app.visible", "app.hidden"))
        prefs.hideApp("app.hidden")

        assertTrue(FolderStore.addAppToFolder(prefs, target.id, "app.visible").isEmpty())
        assertEquals(listOf("app.hidden"), FolderStore.find(prefs, source.id)!!.packages)
    }

    @Test fun missingTargetLeavesPinnedAppsAndMembershipAlone() {
        val source = FolderStore.createEmpty(prefs, "Source")
        FolderStore.addAppToFolder(prefs, source.id, "app.one")
        prefs.pinApp("app.pinned")

        assertTrue(FolderStore.addAppsToFolder(prefs, "missing", listOf("app.one", "app.pinned")).isEmpty())
        assertTrue(prefs.isPinned("app.pinned"))
        assertEquals(listOf("app.one"), FolderStore.find(prefs, source.id)!!.packages)
    }

    @Test fun bulkMoveUnpinsDeduplicatesAndPreservesNewEmptyTarget() {
        val target = FolderStore.createEmpty(prefs, "New")
        prefs.pinApps(listOf("app.one", "app.two", "app.unrelated"))

        FolderStore.addAppsToFolder(prefs, target.id, listOf("app.one", "app.two", "app.one"))
        FolderStore.addAppsToFolder(prefs, target.id, listOf("app.two"))

        assertEquals(listOf("app.one", "app.two"), FolderStore.find(prefs, target.id)!!.packages)
        assertEquals(setOf("app.unrelated"), prefs.pinnedApps)
    }

    @Test fun bulkRemovePrunesOnlyFoldersItEmpties() {
        val source = FolderStore.createEmpty(prefs, "Source")
        val empty = FolderStore.createEmpty(prefs, "Just created")
        FolderStore.addAppsToFolder(prefs, source.id, listOf("app.one", "app.two"))
        prefs.pinFolder(source.id)

        FolderStore.removeAppsFromFolders(prefs, listOf("app.one", "app.two"))

        assertNull(FolderStore.find(prefs, source.id))
        assertFalse(prefs.isFolderPinned(source.id))
        assertNotNull(FolderStore.find(prefs, empty.id))
    }

    @Test fun nonAuthoritativeEnumerationPreservesFolders() {
        val folder = FolderStore.createEmpty(prefs, "Source")
        FolderStore.addAppToFolder(prefs, folder.id, "app.one")
        FolderStore.reconcile(prefs, emptySet(), authoritative = false)
        assertEquals(listOf("app.one"), FolderStore.find(prefs, folder.id)!!.packages)
    }
}
