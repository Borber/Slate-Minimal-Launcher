package com.slate.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Observes the whole gesture stream, including touches that begin on an app label. */
class HomeRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    var onTouchObserved: ((MotionEvent) -> Unit)? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        onTouchObserved?.invoke(event)
        return super.dispatchTouchEvent(event)
    }
}

/** Keeps a short list vertically centered as the old ScrollView layout did. */
class CenteredLinearLayoutManager(context: Context) : LinearLayoutManager(context) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        super.onLayoutChildren(recycler, state)
        if (state.isPreLayout || state.itemCount == 0) return

        val first = findViewByPosition(0) ?: return
        val last = findViewByPosition(state.itemCount - 1) ?: return
        val contentHeight = getDecoratedBottom(last) - getDecoratedTop(first)
        val freeSpace = height - paddingTop - paddingBottom - contentHeight
        if (freeSpace > 0) {
            offsetChildrenVertical(paddingTop + freeSpace / 2 - getDecoratedTop(first))
        }
    }
}
