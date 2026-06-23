package com.kernel.browser.extensions

data class AllowedExtension(
    val id: String,
    val slug: String,
    val displayName: String,
    val assetUri: String,
    val version: String,
    val sha256: String,
    val permissionSummary: String,
    val enabledByDefault: Boolean,
    val privateModeByDefault: Boolean = false,
    val defaultPopupPath: String? = null
)

object AllowedExtensions {
    val all = listOf(
        AllowedExtension(
            id = "uBlock0@raymondhill.net",
            slug = "ublock-origin",
            displayName = "uBlock Origin",
            assetUri = "resource://android/assets/extensions/ublock-origin/",
            version = "1.71.0",
            sha256 = "47f788a1fc2c014830b30bb0ef9588615701b98c5265fb19b8cf4ba779849feb",
            permissionSummary = "Blocks network requests and stores filter-list settings. Requires broad host access to filter pages.",
            enabledByDefault = true
        ),
        AllowedExtension(
            id = "{c3c10168-4186-445c-9c5b-63f12b8e2c87}",
            slug = "cookie-editor",
            displayName = "Cookie-Editor",
            assetUri = "resource://android/assets/extensions/cookie-editor/",
            version = "1.13.0",
            sha256 = "3d6fd83a8343dfa5e4461d83c2856264fb74b36b1c165305168d013f4831dbb0",
            permissionSummary = "Reads and edits cookies on sites you visit. Keep disabled unless you need cookie inspection tools.",
            enabledByDefault = false,
            defaultPopupPath = "interface/popup/cookie-list.html"
        )
    )

    fun byId(id: String): AllowedExtension? = all.firstOrNull { it.id == id }
}
