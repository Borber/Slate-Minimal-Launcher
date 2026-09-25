package com.slate.launcher

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import java.io.File

/**
 * Typeface helper shared by the app list and the Settings preview.
 */
object Typography {

    /**
     * Resolve a (fontFamily, fontWeight) pref pair to a [Typeface].
     *
     * Returns `null` only when BOTH inputs are sentinels (empty family AND zero weight) - the
     * signal that the caller wants NO override and should let the theme default apply. A
     * partial override is still meaningful:
     *   - `("", 700)`               → theme default at bold weight (user picked Weight only)
     *   - `("gf:roboto", 0)`        → Roboto at the default 400 weight
     *   - `("gf:roboto", 700)`      → Roboto Bold
     *   - `("", 0)`                 → returns null, caller skips application entirely
     *
     * @param family String pref in one of these forms:
     *               - empty            → use theme default as the base typeface
     *               - `"/abs/path"`    → user-imported font file in app-private storage
     *               - `"gf:<name>"`    → bundled Google downloadable font (R.font.<name>)
     *               - anything else    → system family ("sans-serif", "serif", …)
     * @param weight 0 = no weight override (treated as 400 / regular when the family is set);
     *               otherwise standard 100–900 weight value.
     */
    fun buildTypeface(context: Context, family: String, weight: Int): Typeface? {
        if (family.isEmpty() && weight == 0) return null

        val base: Typeface = when {
            family.isEmpty() -> Typeface.DEFAULT
            family.startsWith("/") ->
                runCatching { Typeface.createFromFile(File(family)) }.getOrNull()
                    ?: Typeface.DEFAULT
            family.startsWith("gf:") -> {
                val resId = googleFontResId(family.removePrefix("gf:"))
                if (resId == 0) Typeface.DEFAULT
                else runCatching { ResourcesCompat.getFont(context, resId) }.getOrNull()
                    ?: Typeface.DEFAULT
            }
            else -> Typeface.create(family, Typeface.NORMAL)
        }

        val effectiveWeight = if (weight == 0) 400 else weight
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, effectiveWeight, false)
        } else {
            Typeface.create(base, if (effectiveWeight >= 700) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /**
     * Map the bundled Google Font key (the part after `"gf:"`) to the R.font resource id.
     * Returns 0 for unknown keys - caller falls back to [Typeface.DEFAULT].
     */
    fun googleFontResId(name: String): Int = when (name) {
        "tex_gyre_adventor_bold" -> R.font.tex_gyre_adventor_bold
        "roboto"                 -> R.font.roboto
        "noto_sans"              -> R.font.noto_sans
        "coming_soon"            -> R.font.coming_soon
        "cutive_mono"            -> R.font.cutive_mono
        else                     -> 0
    }
}
