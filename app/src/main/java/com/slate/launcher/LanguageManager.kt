package com.slate.launcher

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/** Resolves the two supported UI languages from the app preference or the device language. */
object LanguageManager {
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
        super.attachBaseContext(LanguageManager.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        if (resources.configuration.locales[0].language != LanguageManager.effectiveLanguage(this)) {
            recreate()
        }
    }
}
