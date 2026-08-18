package ru.srr.passwords.util

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import kotlin.math.abs

/**
 * iOS-style sticky swipe-to-reveal with light Liquid Glass progress callbacks.
 */
class SwipeRevealTouchListener(
    private val target: View,
    private val actionWidthPx: Float,
    private val canSwipe: () -> Boolean,
    private val onOpenChanged: (open: Boolean) -> Unit,
    private val onProgress: (fraction: Float) -> Unit,
    private val onClick: () -> Unit
) : View.OnTouchListener {
    private val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop
    private val settleInterp = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private var downX = 0f
    private var downY = 0f
    private var startTx = 0f
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startTx = target.translationX
                dragging = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!canSwipe()) return false
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging) {
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.1f) {
                        dragging = true
                        target.parent?.requestDisallowInterceptTouchEvent(true)
                    } else {
                        return false
                    }
                }
                val tx = (startTx + dx).coerceIn(-actionWidthPx, 0f)
                target.translationX = tx
                onProgress((-tx / actionWidthPx).coerceIn(0f, 1f))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val open = target.translationX <= -actionWidthPx * 0.35f
                    settle(open)
                    dragging = false
                    target.parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (event.actionMasked == MotionEvent.ACTION_UP && canSwipe()) {
                    if (abs(target.translationX) > 2f) {
                        settle(false)
                        return true
                    }
                    onClick()
                    return true
                }
                if (event.actionMasked == MotionEvent.ACTION_UP && !canSwipe()) {
                    onClick()
                    return true
                }
                return false
            }
        }
        return false
    }

    fun setOpen(open: Boolean, animate: Boolean = false) {
        if (animate) settle(open) else {
            target.animate().cancel()
            target.translationX = if (open) -actionWidthPx else 0f
            onProgress(if (open) 1f else 0f)
        }
    }

    private fun settle(open: Boolean) {
        val end = if (open) -actionWidthPx else 0f
        target.animate().cancel()
        target.animate()
            .translationX(end)
            .setDuration(220L)
            .setInterpolator(settleInterp)
            .setUpdateListener {
                val tx = target.translationX
                onProgress((-tx / actionWidthPx).coerceIn(0f, 1f))
            }
            .withEndAction {
                onProgress(if (open) 1f else 0f)
                onOpenChanged(open)
            }
            .start()
    }
}
