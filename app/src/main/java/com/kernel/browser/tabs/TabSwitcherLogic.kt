package com.kernel.browser.tabs

import kotlin.math.abs

/**
 * Framework-free helpers for the tab switcher so the layout and gesture math
 * can be unit tested without Android.
 */
object TabSwitcherLogic {
    /** Fraction of the card width a drag must pass before release closes the tab. */
    const val DISMISS_FRACTION = 0.35f

    /** A fast fling closes the tab even if it has not travelled [DISMISS_FRACTION]. */
    const val FLING_DISMISS_DP_PER_SECOND = 1100f

    private val hostPattern = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^/?#@]*@)?(\\[[^\\]]+\\]|[^/?#:]+)")

    /**
     * Short host for a card subtitle: "https://www.example.com/a?b" -> "example.com".
     * Non-hierarchical URLs (about:, data:) are returned as-is, blank stays blank.
     */
    fun displayHost(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        val host = hostPattern.find(trimmed)?.groupValues?.get(1)
            ?.lowercase()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
        if (host != null) return host
        return if (trimmed.startsWith("data:", ignoreCase = true)) "data:" else trimmed
    }

    /** Title for a card, falling back to the host and finally a generic label. */
    fun displayTitle(title: String, url: String, isPrivate: Boolean): String {
        return title.trim().ifEmpty {
            displayHost(url).ifEmpty { if (isPrivate) "Private tab" else "New tab" }
        }
    }

    /** Two columns on phones, more on wide screens. */
    fun columnsFor(widthDp: Int): Int = when {
        widthDp >= 900 -> 4
        widthDp >= 600 -> 3
        else -> 2
    }

    /** Whether releasing a drag of [dx] px on a card [width] px wide should close it. */
    fun shouldDismiss(dx: Float, width: Int, velocityX: Float = 0f, flingThreshold: Float = Float.MAX_VALUE): Boolean {
        if (width <= 0) return false
        if (abs(dx) >= width * DISMISS_FRACTION) return true
        // A fling only counts when it goes the same way as the drag.
        return abs(velocityX) >= flingThreshold && dx != 0f && (velocityX > 0) == (dx > 0)
    }

    /** Card opacity while dragged: fades to 0.25 at a full card-width of travel. */
    fun dragAlpha(dx: Float, width: Int): Float {
        if (width <= 0) return 1f
        val progress = (abs(dx) / width).coerceIn(0f, 1f)
        return 1f - progress * 0.75f
    }

    /** Whether a move is a deliberate horizontal swipe rather than a scroll. */
    fun isHorizontalDrag(dx: Float, dy: Float, touchSlop: Int): Boolean {
        return abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f
    }
}
