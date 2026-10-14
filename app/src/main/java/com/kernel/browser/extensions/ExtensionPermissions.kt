package com.kernel.browser.extensions

/**
 * Turns raw WebExtension permission and host-pattern strings into the plain
 * sentences shown in install prompts and the extension details page. API
 * permissions that carry no user-visible risk (storage, alarms, ...) are skipped,
 * matching what Firefox shows.
 */
object ExtensionPermissions {
    private val descriptions = linkedMapOf(
        "bookmarks" to "Read and modify bookmarks",
        "browserSettings" to "Read and modify browser settings",
        "browsingData" to "Clear recent browsing history, cookies, and related data",
        "clipboardRead" to "Get data from the clipboard",
        "clipboardWrite" to "Input data to the clipboard",
        "declarativeNetRequest" to "Block content on any page",
        "declarativeNetRequestFeedback" to "Read your browsing history",
        "devtools" to "Extend developer tools to access your data in open tabs",
        "downloads" to "Download files and read and modify the browser's download history",
        "downloads.open" to "Open files downloaded to your device",
        "find" to "Read the text of all open tabs",
        "geolocation" to "Access your location",
        "history" to "Access browsing history",
        "management" to "Monitor extension usage and manage themes",
        "nativeMessaging" to "Exchange messages with programs other than this browser",
        "notifications" to "Display notifications to you",
        "pkcs11" to "Provide cryptographic authentication services",
        "privacy" to "Read and modify privacy settings",
        "proxy" to "Control browser proxy settings",
        "sessions" to "Access recently closed tabs",
        "tabHide" to "Hide and show browser tabs",
        "tabs" to "Access browser tabs",
        "topSites" to "Access browsing history",
        "webNavigation" to "Access browser activity during navigation"
    )

    private val allSitePatterns = setOf("<all_urls>", "*://*/*", "http://*/*", "https://*/*")

    fun describe(permissions: Collection<String>, origins: Collection<String>): List<String> {
        val lines = mutableListOf<String>()
        val hosts = describeOrigins(origins + permissions.filter(::looksLikeOrigin))
        lines += hosts
        permissions.mapNotNullTo(lines) { descriptions[it] }
        return lines.distinct()
    }

    fun describeOrigins(origins: Collection<String>): List<String> {
        if (origins.any { it in allSitePatterns }) {
            return listOf("Access your data for all websites")
        }
        val domains = linkedSetOf<String>()
        val sites = linkedSetOf<String>()
        origins.forEach { origin ->
            val host = hostOf(origin) ?: return@forEach
            if (host.startsWith("*.")) {
                domains += host.removePrefix("*.")
            } else if (host != "*") {
                sites += host
            }
        }
        return domains.map { "Access your data for sites in the $it domain" } +
            sites.map { "Access your data for $it" }
    }

    private fun looksLikeOrigin(value: String): Boolean {
        return value == "<all_urls>" || value.contains("://")
    }

    private fun hostOf(pattern: String): String? {
        val afterScheme = pattern.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return null
        return afterScheme.substringBefore('/').substringBefore(':').ifBlank { null }
    }
}
