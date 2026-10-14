package com.kernel.browser

import org.mozilla.geckoview.GeckoSession.PermissionDelegate

object SitePermissions {
    fun label(permission: Int): String {
        return when (permission) {
            PermissionDelegate.PERMISSION_GEOLOCATION -> "Location"
            PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "Notifications"
            PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> "Persistent storage"
            PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> "Autoplay with sound"
            PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> "Muted autoplay"
            PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> "Protected content (DRM)"
            PermissionDelegate.PERMISSION_STORAGE_ACCESS -> "Cross-site storage access"
            PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS -> "Local network access"
            PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS -> "Local device access"
            PermissionDelegate.PERMISSION_XR -> "Virtual reality"
            PermissionDelegate.PERMISSION_TRACKING -> "Tracking protection exception"
            else -> "Permission"
        }
    }

    fun valueLabel(value: Int): String {
        return when (value) {
            PermissionDelegate.ContentPermission.VALUE_ALLOW -> "Allowed"
            PermissionDelegate.ContentPermission.VALUE_DENY -> "Blocked"
            else -> "Ask"
        }
    }
}
