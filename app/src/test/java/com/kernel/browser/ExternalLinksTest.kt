package com.kernel.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalLinksTest {
    @Test
    fun webSchemesStayInTheBrowser() {
        listOf(
            "https://example.com", "http://example.com", "about:blank", "data:text/html,hi",
            "blob:https://x/1", "moz-extension://abc/page.html", "javascript:void(0)", "HTTPS://EXAMPLE.COM"
        ).forEach { assertEquals(it, ExternalLinks.Kind.WEB, ExternalLinks.classify(it)) }
    }

    @Test
    fun wellKnownHandlersOpenDirectly() {
        listOf("tel:+15551234", "mailto:a@b.c", "sms:123", "geo:0,0?q=cafe", "TEL:911")
            .forEach { assertEquals(it, ExternalLinks.Kind.DIRECT, ExternalLinks.classify(it)) }
    }

    @Test
    fun appSchemesNeedConfirmation() {
        listOf("intent://maps#Intent;scheme=https;end", "market://details?id=x", "whatsapp://send", "zoommtg://join")
            .forEach { assertEquals(it, ExternalLinks.Kind.APP, ExternalLinks.classify(it)) }
    }

    @Test
    fun relativeOrMalformedInputIsTreatedAsWeb() {
        assertEquals(ExternalLinks.Kind.WEB, ExternalLinks.classify("example.com/path"))
        assertEquals(ExternalLinks.Kind.WEB, ExternalLinks.classify(":nothing"))
        assertEquals(ExternalLinks.Kind.WEB, ExternalLinks.classify("1abc:foo"))
    }

    @Test
    fun readsWebFallbackUrlFromIntentUri() {
        val uri = "intent://item/1#Intent;scheme=shop;package=com.shop;" +
            "S.browser_fallback_url=https%3A%2F%2Fshop.example%2Fitem%2F1;end"
        assertEquals("https://shop.example/item/1", ExternalLinks.fallbackUrl(uri))
    }

    @Test
    fun ignoresNonWebFallbackUrls() {
        assertNull(ExternalLinks.fallbackUrl("intent://x#Intent;S.browser_fallback_url=javascript%3Aalert(1);end"))
        assertNull(ExternalLinks.fallbackUrl("intent://x#Intent;scheme=a;end"))
        assertNull(ExternalLinks.fallbackUrl("market://details?id=x"))
    }
}
