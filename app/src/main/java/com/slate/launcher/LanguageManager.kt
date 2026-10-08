package com.slate.launcher

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/** Resolves the two supported UI languages from the app preference or the device language. */
object LanguageManager {
    fun applySystemLanguage(context: Context, language: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        val tags = when (language) {
            "zh" -> "zh-Hans"
            "en" -> "en"
            else -> ""
        }
        val locales = LocaleList.forLanguageTags(tags)
        if (manager.applicationLocales != locales) manager.applicationLocales = locales
    }

    /** Migrate once, then let the system's per-app setting be the source of truth. */
    fun syncSystemLanguage(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        val prefs = PreferencesManager(context)
        if (!prefs.systemLanguageMigrated) {
            if (manager.applicationLocales.isEmpty) applySystemLanguage(context, prefs.language)
            prefs.systemLanguageMigrated = true
        }
        val language = manager.applicationLocales[0]?.language
            ?.takeIf { it == "zh" || it == "en" } ?: "system"
        if (prefs.language != language) prefs.language = language
    }

    fun effectiveLanguage(context: Context): String = when (PreferencesManager(context).language) {
        "zh" -> "zh"
        "en" -> "en"
        else -> if (Resources.getSystem().configuration.locales[0].language == "zh") "zh" else "en"
    }

    fun wrap(context: Context): Context {
        val locale = if (effectiveLanguage(context) == "zh") Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config)
    }
}

/** Ensures every activity and its dialogs use the same resolved resources. */
open class LocalizedActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        LanguageManager.syncSystemLanguage(newBase)
        super.attachBaseContext(LanguageManager.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        LanguageManager.syncSystemLanguage(this)
        if (resources.configuration.locales[0].language != LanguageManager.effectiveLanguage(this)) {
            recreate()
        }
    }
}
