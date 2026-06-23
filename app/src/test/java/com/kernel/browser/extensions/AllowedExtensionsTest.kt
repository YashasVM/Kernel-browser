package com.kernel.browser.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowedExtensionsTest {
    @Test
    fun `uBlock is enabled by default and Cookie-Editor is opt-in`() {
        val ublock = AllowedExtensions.byId("uBlock0@raymondhill.net")
        val cookieEditor = AllowedExtensions.byId("{c3c10168-4186-445c-9c5b-63f12b8e2c87}")

        assertNotNull(ublock)
        assertNotNull(cookieEditor)
        assertTrue(ublock!!.enabledByDefault)
        assertTrue(!cookieEditor!!.enabledByDefault)
    }

    @Test
    fun `metadata contains asset uri and sha256 pins`() {
        AllowedExtensions.all.forEach { extension ->
            assertEquals(64, extension.sha256.length)
            assertTrue(extension.assetUri.startsWith("resource://android/assets/extensions/"))
            assertTrue(extension.assetUri.endsWith("${extension.slug}/"))
        }
    }
}
