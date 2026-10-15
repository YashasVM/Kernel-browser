package com.kernel.browser

import android.content.SharedPreferences
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * One low-priority background thread for store writes, so encoding large values (every tab's
 * session state, the history list) never runs on the UI thread.
 */
internal object DiskWriter {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kernel-disk-writer").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    fun execute(task: () -> Unit) {
        executor.execute { task() }
    }
}

/**
 * Writes one preference key on [DiskWriter]. A burst of [put] calls collapses into a single write
 * of the newest value, and a value equal to the last one written is skipped.
 */
internal class LatestPreferenceWrite(
    private val preferences: SharedPreferences,
    private val key: String
) {
    private val generation = AtomicLong()

    /** Only touched on the writer thread. */
    private var lastWritten: String? = null

    /** [encode] runs on the writer thread, so it must only read data nobody mutates any more. */
    fun put(encode: () -> String) {
        val ticket = generation.incrementAndGet()
        DiskWriter.execute {
            // A newer put or remove is queued behind this one; let it win.
            if (generation.get() != ticket) return@execute
            val value = try {
                encode()
            } catch (error: RuntimeException) {
                SafeLog.warning("Encoding $key failed", error)
                return@execute
            }
            if (value == lastWritten) return@execute
            if (preferences.edit().putString(key, value).commit()) lastWritten = value
        }
    }

    fun remove() {
        generation.incrementAndGet()
        DiskWriter.execute {
            lastWritten = null
            preferences.edit().remove(key).commit()
        }
    }
}
