package com.kernel.browser

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A short message that floats just above the toolbar, with an optional action (Undo, Allow,
 * Switch). Unlike a Toast it sits where the user is already looking and can be acted on.
 */
class KernelSnackbar(
    private val root: FrameLayout,
    /** Distance from the bottom of [root] the message should clear (toolbar + system bar). */
    private val bottomOffset: () -> Int,
    private val maxWidth: () -> Int
) {
    private val context = root.context
    private val handler = Handler(Looper.getMainLooper())
    private val motion = PathInterpolator(0.2f, 0f, 0f, 1f)
    private var current: View? = null
    private val hideRunnable = Runnable { hide() }

    private fun dp(value: Int) = ChromeSheet.dp(context, value)

    fun show(message: String, action: String? = null, durationMs: Long = 0L, onAction: (() -> Unit)? = null) {
        handler.removeCallbacks(hideRunnable)
        current?.let { old ->
            old.animate().cancel()
            root.removeView(old)
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(18), dp(6), dp(if (action != null) 6 else 18), dp(6))
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.kernel_snackbar))
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(20).toFloat()
            addView(TextView(context).apply {
                text = message
                textSize = 14f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(context.getColor(R.color.kernel_text))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (action != null) {
                addView(TextView(context).apply {
                    text = action
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    minWidth = dp(64)
                    minHeight = dp(40)
                    setPadding(dp(12), 0, dp(12), 0)
                    setTextColor(context.getColor(R.color.kernel_accent))
                    background = KernelUi.selectableItemBackground(context)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        hide()
                        onAction?.invoke()
                    }
                })
            }
        }
        val width = maxWidth().coerceAtMost(dp(560))
        root.addView(bar, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = bottomOffset() + dp(10)
        })
        current = bar
        bar.alpha = 0f
        bar.translationY = dp(16).toFloat()
        bar.animate().alpha(1f).translationY(0f).setDuration(220L).setInterpolator(motion).start()
        bar.announceForAccessibility(message)
        bar.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        val duration = if (durationMs > 0) durationMs else if (action != null) ACTION_DURATION_MS else SHORT_DURATION_MS
        handler.postDelayed(hideRunnable, duration)
    }

    /** Moves a visible message when the toolbar shows, hides, or resizes. */
    fun reposition() {
        val bar = current ?: return
        val params = bar.layoutParams as FrameLayout.LayoutParams
        val target = bottomOffset() + dp(10)
        if (params.bottomMargin != target) {
            params.bottomMargin = target
            bar.layoutParams = params
        }
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        val bar = current ?: return
        current = null
        bar.animate().cancel()
        bar.animate().alpha(0f).translationY(dp(12).toFloat()).setDuration(160L).setInterpolator(motion)
            .withEndAction { root.removeView(bar) }
            .start()
    }

    companion object {
        const val SHORT_DURATION_MS = 2_600L
        const val ACTION_DURATION_MS = 5_000L
    }
}
