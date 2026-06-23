package com.kernel.browser

import android.util.Log

object SafeLog {
    private const val TAG = "KernelBrowser"

    fun status(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable == null) {
                Log.d(TAG, message)
            } else {
                Log.d(TAG, message, throwable)
            }
        }
    }

    fun warning(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.w(TAG, message)
        } else {
            Log.w(TAG, message, throwable)
        }
    }
}
