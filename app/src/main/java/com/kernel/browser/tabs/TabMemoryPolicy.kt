package com.kernel.browser.tabs

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Framework-free rules for how many tabs keep a live Gecko page and how big tab thumbnails are,
 * so the memory behaviour can be unit tested without Android.
 */
object TabMemoryPolicy {
    /** Background tabs kept loaded during normal use; older ones unload until shown again. */
    const val MAX_LIVE_BACKGROUND_TABS = 5

    // ComponentCallbacks2 trim levels, copied so this file needs no Android classes.
    const val TRIM_MEMORY_RUNNING_MODERATE = 5
    const val TRIM_MEMORY_RUNNING_LOW = 10
    const val TRIM_MEMORY_RUNNING_CRITICAL = 15
    const val TRIM_MEMORY_UI_HIDDEN = 20
    const val TRIM_MEMORY_BACKGROUND = 40
    const val TRIM_MEMORY_MODERATE = 60
    const val TRIM_MEMORY_COMPLETE = 80

    /** Thumbnail height as a multiple of its width; a little taller than a tab card. */
    const val THUMBNAIL_ASPECT = 1.1f

    /** Hard cap on thumbnail width so dense screens don't get huge bitmaps. */
    const val THUMBNAIL_MAX_WIDTH_PX = 480

    data class Candidate(
        val id: Long,
        val lastUsed: Long,
        /** False for tabs already unloaded or not opened yet. */
        val loaded: Boolean,
        /** Playing media or fullscreen: never unloaded. */
        val pinned: Boolean
    )

    data class ThumbnailSize(
        val width: Int,
        val height: Int,
        /** Rows of the source, from the top, that the thumbnail shows. */
        val sourceHeight: Int
    )

    /**
     * Ids of background tabs to unload so at most [keepLoaded] unpinned ones stay live, oldest
     * first. [candidates] must not include the tab being shown.
     */
    fun tabsToUnload(candidates: List<Candidate>, keepLoaded: Int): List<Long> {
        return candidates
            .filter { it.loaded && !it.pinned }
            .sortedByDescending { it.lastUsed }
            .drop(keepLoaded.coerceAtLeast(0))
            .map { it.id }
    }

    /** Background tabs to keep loaded at a trim [level], or null to leave tabs alone. */
    fun backgroundTabsToKeep(level: Int): Int? = when {
        level >= TRIM_MEMORY_MODERATE -> 0
        level >= TRIM_MEMORY_BACKGROUND -> 1
        // UI_HIDDEN just means our UI went away; nothing is short on memory yet.
        level >= TRIM_MEMORY_UI_HIDDEN -> null
        level >= TRIM_MEMORY_RUNNING_CRITICAL -> 0
        level >= TRIM_MEMORY_RUNNING_LOW -> 1
        level >= TRIM_MEMORY_RUNNING_MODERATE -> 3
        else -> null
    }

    /** Whether background tab thumbnails should be dropped at a trim [level]. */
    fun shouldDropThumbnails(level: Int): Boolean =
        level >= TRIM_MEMORY_MODERATE || level == TRIM_MEMORY_RUNNING_CRITICAL

    /**
     * Size of a thumbnail for a [sourceWidth] x [sourceHeight] page capture: the top of the page,
     * scaled to at most [maxWidth] (and [THUMBNAIL_MAX_WIDTH_PX]) wide. Null for an empty capture.
     */
    fun thumbnailSize(sourceWidth: Int, sourceHeight: Int, maxWidth: Int): ThumbnailSize? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || maxWidth <= 0) return null
        val width = min(sourceWidth, min(maxWidth, THUMBNAIL_MAX_WIDTH_PX))
        val cropHeight = min(sourceHeight, max(1, (sourceWidth * THUMBNAIL_ASPECT).roundToInt()))
        val height = max(1, (cropHeight.toLong() * width / sourceWidth).toInt())
        return ThumbnailSize(width, height, cropHeight)
    }
}
