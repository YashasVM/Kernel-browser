package com.kernel.browser

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** "Set as default browser": the browser role on Android 10+, the Default apps screen before. */
object DefaultBrowser {
    const val REQUEST_ROLE = 61

    fun isDefault(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
            }
        }
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = runCatching {
            context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /**
     * Shows the system prompt to make Kernel the default browser, or the Default apps settings
     * screen where the prompt isn't available. Returns false if neither could be opened.
     */
    fun request(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = activity.getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
            ) {
                // The role screen identifies the caller from startActivityForResult, so plain
                // startActivity would be rejected.
                val launched = runCatching {
                    activity.startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER), REQUEST_ROLE)
                }.isSuccess
                if (launched) return true
            }
        }
        return openDefaultAppsSettings(activity)
    }

    fun openDefaultAppsSettings(activity: Activity): Boolean {
        return try {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            true
        } catch (e: ActivityNotFoundException) {
            runCatching { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }.isSuccess
        }
    }
}
