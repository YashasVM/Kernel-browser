package com.kernel.browser.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionPermissionsTest {
    @Test
    fun allUrlsCollapsesToSingleLine() {
        val lines = ExtensionPermissions.describe(
            permissions = listOf("storage", "tabs", "<all_urls>"),
            origins = listOf("https://example.com/*")
        )

        assertEquals(listOf("Access your data for all websites", "Access browser tabs"), lines)
    }

    @Test
    fun wildcardAndExactHostsAreDescribedSeparately() {
        val lines = ExtensionPermissions.describeOrigins(
            listOf("*://*.youtube.com/*", "https://github.com/*", "https://github.com/login")
        )

        assertEquals(
            listOf(
                "Access your data for sites in the youtube.com domain",
                "Access your data for github.com"
            ),
            lines
        )
    }

    @Test
    fun silentPermissionsAreSkipped() {
        val lines = ExtensionPermissions.describe(
            permissions = listOf("storage", "alarms", "webRequest", "unlimitedStorage"),
            origins = emptyList()
        )

        assertTrue(lines.isEmpty())
    }
}
