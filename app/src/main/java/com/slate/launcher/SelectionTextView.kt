package com.slate.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.TextView

/** Draws a check beside the name, reserving the same room for all rows during selection. */
@SuppressLint("AppCompatCustomView")
class SelectionTextView(context: Context) : TextView(context) {
    private val markerPaint = Paint()
    private var marked = false
    private var markerRoom = 0

    fun setSelectionMarker(selected: Boolean, selecting: Boolean) {
        marked = selected
        // Binding has already restored the row's ordinary padding.
        markerRoom = if (selecting) (textSize * 0.8f).toInt() else 0
        setPaddingRelative(paddingStart + markerRoom, paddingTop, paddingEnd, paddingBottom)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!marked) return
        markerPaint.set(paint)
        markerPaint.color = currentTextColor
        markerPaint.textSize = textSize * 0.65f
        val glyph = if (markerPaint.hasGlyph("\u2713")) "\u2713" else "+"
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            width - paddingRight + markerRoom * 0.15f
        } else paddingLeft - markerRoom * 0.85f
        canvas.drawText(glyph, x, baseline.toFloat(), markerPaint)
    }
}
