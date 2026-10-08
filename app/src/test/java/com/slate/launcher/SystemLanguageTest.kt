package com.slate.launcher

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SystemLanguageTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun existingChinesePreferenceMigratesOnceAndSystemChangesThenWin() {
        val prefs = PreferencesManager(context)
        context.getSharedPreferences("slate_prefs", Context.MODE_PRIVATE).edit()
            .putString("language", "zh").commit()
        val manager = context.getSystemService(LocaleManager::class.java)

        LanguageManager.syncSystemLanguage(context)
        assertEquals("zh-Hans", manager.applicationLocales.toLanguageTags())
        assertTrue(prefs.systemLanguageMigrated)

        manager.applicationLocales = LocaleList.forLanguageTags("en")
        LanguageManager.syncSystemLanguage(context)
        assertEquals("en", prefs.language)

        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        LanguageManager.syncSystemLanguage(context)
        assertEquals("system", prefs.language)
    }

    @Test fun existingSystemLanguageWinsOverLegacyPreference() {
        val prefs = PreferencesManager(context)
        context.getSharedPreferences("slate_prefs", Context.MODE_PRIVATE).edit()
            .putString("language", "zh").commit()
        val manager = context.getSystemService(LocaleManager::class.java)
        manager.applicationLocales = LocaleList.forLanguageTags("en")
        LanguageManager.syncSystemLanguage(context)
        assertEquals("en", prefs.language)
        assertEquals("en", manager.applicationLocales.toLanguageTags())
    }

    @Test fun importingLanguageUpdatesSystemSetting() {
        val prefs = PreferencesManager(context)
        val backup = BackupManager(prefs)
        backup.applyNonPrivate(backup.parse("""{"version":1,"language":"zh"}"""))
        assertEquals("zh-Hans", context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags())
    }
}
