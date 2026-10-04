package com.slate.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.widget.TextView

/**
 * An app name on the home screen that can show a selection marker in front of itself.
 *
 * The marker is painted, not added to the text, so the name itself is never altered. Showing
 * it widens the padding in front of the name by [markerRoom], and hiding it takes that room
 * back.
 *
 * This extends the platform TextView on purpose. Every home row is one, built in code, and the
 * AppCompat version would change how names are drawn.
 */
@SuppressLint("AppCompatCustomView")
class MarkedTextView(context: Context) : TextView(context) {

    private var glyph: String? = null
    private var fit: SelectionMarker.Fit? = null
    private val markerPaint = Paint()
    private val bounds = Rect()

    /** The width the row grows by, in front of its name, while the marker is shown. */
    val markerRoom: Int get() = fit?.room ?: 0

    /** Whether the marker is shown. Has no effect until [setMarker] has been called. */
    var marked = false
        set(value) {
            if (field == value) return
            field = value
            setPaddingRelative(
                paddingStart + if (value) markerRoom else -markerRoom,
                paddingTop, paddingEnd, paddingBottom
            )
        }

    /** Says what to draw. Call it once, after the text size and typeface are set. */
    fun setMarker(glyph: String, fit: SelectionMarker.Fit) {
        this.glyph = glyph
        this.fit = fit
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!marked) return
        val glyph = glyph ?: return
        val fit = fit ?: return
        val layout = layout ?: return

        // Centred on the capital letters of the first line, so a scaled-down marker sits level
        // with the name instead of dropping to the baseline.
        paint.getTextBounds("H", 0, 1, bounds)
        val middle = baseline + bounds.top / 2f

        markerPaint.set(paint)
        markerPaint.color = currentTextColor
        markerPaint.textSize = paint.textSize * fit.scale
        markerPaint.getTextBounds(glyph, 0, glyph.length, bounds)
        // In front of the first line: to its left, or to its right when the layout is RTL.
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            totalPaddingLeft + layout.getLineRight(0) + fit.gap - bounds.left
        } else {
            totalPaddingLeft + layout.getLineLeft(0) - fit.gap - bounds.right
        }
        canvas.drawText(glyph, x, middle - bounds.exactCenterY(), markerPaint)
    }
}
