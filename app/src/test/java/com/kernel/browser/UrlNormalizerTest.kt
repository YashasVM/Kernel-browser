package com.kernel.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlNormalizerTest {
    @Test
    fun `loads absolute urls directly`() {
        assertEquals("https://example.com/path", UrlNormalizer.normalize(" https://example.com/path "))
        assertEquals("about:blank", UrlNormalizer.normalize("about:blank"))
    }

    @Test
    fun `adds https to host-like input`() {
        assertEquals("https://example.com", UrlNormalizer.normalize("example.com"))
        assertEquals("https://localhost:8080", UrlNormalizer.normalize("localhost:8080"))
        assertEquals("https://192.168.0.1/settings", UrlNormalizer.normalize("192.168.0.1/settings"))
    }

    @Test
    fun `searches non-url text with Google`() {
        assertEquals(
            "https://www.google.com/search?q=privacy+browser",
            UrlNormalizer.normalize("privacy browser")
        )
    }
}
