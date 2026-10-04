package com.slate.launcher

import android.graphics.Paint
import android.graphics.Rect
import androidx.annotation.StringRes
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How a selected app is marked while several apps are being selected.
 *
 * [STYLES] is the one list of the styles. Everything that needs to know them reads it, so a
 * style is added or removed in a single place.
 *
 * The marker is not part of a row's text. [MarkedTextView] draws it in room that a selected
 * row adds in front of its name, sized by [fit]. Only selected rows add that room, so the usual
 * gap stays between the marker and the name before it, and starting to select leaves the
 * other names where they are.
 */
object SelectionMarker {

    class Style(val key: String, @param:StringRes val label: Int, val glyph: String)

    val STYLES: List<Style> = listOf(
        Style("check", R.string.settings_selection_style_check, "\u2713"),
        Style("dot", R.string.settings_selection_style_dot, "\u25CF"),
        Style("radio", R.string.settings_selection_style_radio, "\u25C9"),
        Style("square", R.string.settings_selection_style_square, "\u25A0"),
    )

    /** Check. Also what [styleFor] resolves an unknown stored key to. */
    const val DEFAULT_KEY = "check"

    private const val FALLBACK_GLYPH = "+"

    /**
     * The marker's width and the clear space between it and its name, as shares of the row's
     * text size. Kept compact so that ticking an app nudges its neighbours, no more. A
     * character that is narrower than this at full size is left at full size.
     */
    private const val WIDTH_EM = 0.45f
    private const val GAP_EM = 0.16f

    fun styleFor(key: String): Style = STYLES.firstOrNull { it.key == key } ?: STYLES.first()

    fun isKnown(key: String): Boolean = STYLES.any { it.key == key }

    /**
     * The character to draw for [style] with [paint]. A plus sign stands in when no font on
     * this device has the style's own character, the fallback fonts included.
     */
    fun glyphFor(style: Style, paint: Paint): String =
        if (paint.hasGlyph(style.glyph)) style.glyph else FALLBACK_GLYPH

    /**
     * How the marker is drawn for one row.
     *
     * @param scale size of the marker against the row's text, 1 being full size
     * @param gap clear width between the marker and the name
     * @param room width a selected row adds in front of its name for the two together
     */
    class Fit(val scale: Float, val gap: Int, val room: Int)

    /** Sizes [glyph] for a row drawn with [paint]. */
    fun fit(glyph: String, paint: Paint): Fit {
        val bounds = Rect()
        paint.getTextBounds(glyph, 0, glyph.length, bounds)
        val natural = bounds.width()
        val gap = max(1, (paint.textSize * GAP_EM).roundToInt())
        if (natural <= 0) return Fit(1f, gap, gap)
        val width = min(natural, (paint.textSize * WIDTH_EM).roundToInt())
        return Fit(width.toFloat() / natural, gap, width + gap)
    }
}
