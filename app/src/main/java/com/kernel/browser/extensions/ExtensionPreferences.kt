package com.kernel.browser.extensions

import android.content.Context

class ExtensionPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("extensions", Context.MODE_PRIVATE)

    fun isEnabled(extension: AllowedExtension): Boolean {
        return prefs.getBoolean("${extension.id}.enabled", extension.enabledByDefault)
    }

    fun setEnabled(extension: AllowedExtension, enabled: Boolean) {
        prefs.edit().putBoolean("${extension.id}.enabled", enabled).apply()
    }

    fun isAllowedInPrivate(extension: AllowedExtension): Boolean {
        return prefs.getBoolean("${extension.id}.private", extension.privateModeByDefault)
    }

    fun setAllowedInPrivate(extension: AllowedExtension, allowed: Boolean) {
        prefs.edit().putBoolean("${extension.id}.private", allowed).apply()
    }
}
