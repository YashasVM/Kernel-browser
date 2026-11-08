package com.kernel.browser

import android.content.SharedPreferences

internal class LatestPreferenceWrite(
    private val preferences: SharedPreferences,
    private val key: String
) {
    fun put(encode: () -> String) {
        val value = try {
            encode()
        } catch (error: RuntimeException) {
            SafeLog.warning("Encoding $key failed", error)
            return
        }
        // apply updates shared memory before returning and schedules disk persistence itself.
        preferences.edit().putString(key, value).apply()
    }

    fun remove() {
        preferences.edit().remove(key).apply()
    }
}
