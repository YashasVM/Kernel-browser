package com.kernel.browser

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController
import kotlin.math.abs

/**
 * GeckoView that recognizes pull-to-refresh. Every touch still goes to Gecko unchanged; a
 * downward drag only counts as a pull when Gecko reports, for that touch, that the page is
 * already at the top, did not consume the touch itself (maps, carousels), and can overscroll.
 */
class PullToRefreshGeckoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GeckoView(context, attrs) {

    interface Listener {
        /** [distance] is the damped pull distance in px, 0 when the pull ends. */
        fun onPull(distance: Float, armed: Boolean)

        fun onRefresh()
    }

    var listener: Listener? = null
    var pullEnabled = true

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val threshold = ChromeSheet.dp(context, 72).toFloat()
    private var eligible = false
    private var downX = 0f
    private var downY = 0f
    private var pulling = false
    private var distance = 0f
    private var gestureId = 0

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val id = ++gestureId
                eligible = false
                pulling = false
                distance = 0f
                downX = event.x
                downY = event.y
                if (!pullEnabled) return super.onTouchEvent(event)
                // The verdict arrives asynchronously; later moves of this gesture use it.
                onTouchEventForDetailResult(event).accept({ detail ->
                    if (detail == null || id != gestureId) return@accept
                    eligible = detail.handledResult() != PanZoomController.INPUT_RESULT_HANDLED_CONTENT &&
                        detail.scrollableDirections() and PanZoomController.SCROLLABLE_FLAG_TOP == 0 &&
                        detail.overscrollDirections() and PanZoomController.OVERSCROLL_FLAG_VERTICAL != 0
                }, { })
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> endPull(trigger = false)
            MotionEvent.ACTION_MOVE -> if (eligible && event.pointerCount == 1) {
                val dy = event.y - downY
                val dx = event.x - downX
                if (!pulling && dy > touchSlop && dy > abs(dx) * 1.5f) pulling = true
                if (pulling) {
                    val wasArmed = distance >= threshold
                    // Resistance grows with distance so the indicator never runs away.
                    distance = (dy - touchSlop).coerceAtLeast(0f).let { it * 0.5f }.coerceAtMost(threshold * 1.6f)
                    val armed = distance >= threshold
                    if (armed && !wasArmed) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    listener?.onPull(distance, armed)
                }
            }
            MotionEvent.ACTION_UP -> endPull(trigger = true)
            MotionEvent.ACTION_CANCEL -> endPull(trigger = false)
        }
        return super.onTouchEvent(event)
    }

    private fun endPull(trigger: Boolean) {
        val armed = pulling && distance >= threshold
        if (pulling) listener?.onPull(0f, false)
        pulling = false
        eligible = false
        distance = 0f
        if (trigger && armed) listener?.onRefresh()
    }
}
