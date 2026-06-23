package com.kernel.browser.extensions

import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksumFormatTest {
    @Test
    fun `sha256 pins are lowercase hex`() {
        val hex = Regex("^[a-f0-9]{64}$")
        AllowedExtensions.all.forEach { extension ->
            assertTrue("${extension.slug} must use lowercase SHA-256", hex.matches(extension.sha256))
        }
    }
}
