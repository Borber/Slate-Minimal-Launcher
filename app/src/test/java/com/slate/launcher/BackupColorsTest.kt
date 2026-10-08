package com.slate.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupColorsTest {
    private val prefs = PreferencesManager(MemoryContext())
    private val backup = BackupManager(prefs)

    @Test fun colorsRoundTripWithoutPrivateSettings() {
        prefs.folderTextColor = "#123456"
        prefs.customColors = listOf("#123456", "#ABCDEF")
        prefs.hiddenApps = setOf("app.private")
        val json = backup.toJson()
        val restored = PreferencesManager(MemoryContext())
        val manager = BackupManager(restored)
        manager.applyNonPrivate(manager.parse(json))

        assertEquals("#123456", restored.folderTextColor)
        assertEquals(listOf("#123456", "#ABCDEF"), restored.customColors)
        assertFalse(JSONObject(json).has("hiddenApps"))
        assertTrue(restored.hiddenApps.isEmpty())
    }

    @Test fun importingOldBackupRestoresFollowAppColorAndKeepsRecentColors() {
        prefs.folderTextColor = "#123456"
        prefs.customColors = listOf("#ABCDEF")
        backup.applyNonPrivate(backup.parse("""{"version":1}"""))
        assertNull(prefs.folderTextColor)
        assertEquals(listOf("#ABCDEF"), prefs.customColors)
    }

    @Test fun handEditedColorsAreValidatedAndDeduplicated() {
        backup.applyNonPrivate(backup.parse("""{
            "version":1,
            "folderTextColor":"invalid",
            "customColors":["invalid","#000000","#ABCDEF","#abcdef",42,"#123456"]
        }"""))
        assertNull(prefs.folderTextColor)
        assertEquals(listOf("#ABCDEF", "#123456"), prefs.customColors)
    }

    @Test fun rememberedColorsPromoteExistingColorAndLimitHistory() {
        val saved = listOf("#123456", "#234567", "#345678", "#456789", "#56789A")
        assertEquals(
            listOf("#345678", "#123456", "#234567", "#456789", "#56789A"),
            ColorPickerDialog.withRemembered(saved, "#345678")
        )
        assertEquals(
            listOf("#ABCDEF", "#123456", "#234567", "#345678", "#456789"),
            ColorPickerDialog.withRemembered(saved, "#ABCDEF")
        )
        assertEquals(saved, ColorPickerDialog.withRemembered(saved, "#000000"))
    }

    @Test fun importingColorSettingsPreservesLegacyWorkFolderAndPrivatePreferences() {
        prefs.foldersJson = """[{"id":"work","name":"Work","packages":["app.work@11"],"profileSerial":11}]"""
        prefs.pinFolder("work")
        prefs.hiddenApps = setOf("app.private")
        prefs.hiddenAppsSecurityEnabled = true
        backup.applyNonPrivate(backup.parse("""{"version":1,"folders":[],"customColors":[]}"""))

        assertNotNull(FolderStore.find(prefs, "work"))
        assertTrue(prefs.isFolderPinned("work"))
        assertEquals(setOf("app.private"), prefs.hiddenApps)
        assertTrue(prefs.hiddenAppsSecurityEnabled)
    }
}
