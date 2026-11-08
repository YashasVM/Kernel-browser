package com.kernel.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFeatureLogicTest {
    @Test
    fun pipAspectRatioReducesCommonVideoSizes() {
        assertEquals(16 to 9, WebFeatureLogic.pipAspectRatio(1920, 1080))
        assertEquals(4 to 3, WebFeatureLogic.pipAspectRatio(640, 480))
        assertEquals(9 to 16, WebFeatureLogic.pipAspectRatio(1080, 1920))
        assertEquals(1 to 1, WebFeatureLogic.pipAspectRatio(500, 500))
    }

    @Test
    fun pipAspectRatioClampsToAndroidLimits() {
        assertEquals(238 to 100, WebFeatureLogic.pipAspectRatio(3000, 1000))
        assertEquals(100 to 238, WebFeatureLogic.pipAspectRatio(1000, 3000))
        val (w, h) = WebFeatureLogic.pipAspectRatio(2390, 1000)!!
        assertTrue(w.toDouble() / h <= WebFeatureLogic.PIP_MAX_RATIO)
    }

    @Test
    fun pipAspectRatioUnknownForMissingSizes() {
        assertNull(WebFeatureLogic.pipAspectRatio(0, 1080))
        assertNull(WebFeatureLogic.pipAspectRatio(1920, 0))
        assertNull(WebFeatureLogic.pipAspectRatio(-1, -1))
    }

    @Test
    fun pipAspectRatioKeepsTermsSmall() {
        val (w, h) = WebFeatureLogic.pipAspectRatio(1_999_999_999L, 1_000_000_001L)!!
        assertTrue(w in 1..10_000 && h in 1..10_000)
        assertEquals(2.0, w.toDouble() / h, 0.01)
    }

    @Test
    fun mediaTitleFallsBackFromMetadataToPageToHost() {
        assertEquals("Song", WebFeatureLogic.mediaTitle("Song", "Page", "https://www.example.com/a", false))
        assertEquals("Page", WebFeatureLogic.mediaTitle("  ", "Page", "https://www.example.com/a", false))
        assertEquals("example.com", WebFeatureLogic.mediaTitle(null, "", "https://www.example.com/a", false))
        assertEquals("Media", WebFeatureLogic.mediaTitle(null, null, "data:text/html,x", false))
    }

    @Test
    fun privateMediaHidesPageDetails() {
        assertEquals("Private tab", WebFeatureLogic.mediaTitle("Song", "Page", "https://example.com", true))
        assertEquals("Playing media", WebFeatureLogic.mediaSubtitle("Artist", null, "https://example.com", true))
    }

    @Test
    fun mediaSubtitleUsesArtistAndAlbumThenHost() {
        assertEquals("Artist · Album", WebFeatureLogic.mediaSubtitle("Artist", "Album", "https://example.com", false))
        assertEquals("Artist", WebFeatureLogic.mediaSubtitle("Artist", " ", "https://example.com", false))
        assertEquals("example.com", WebFeatureLogic.mediaSubtitle(null, null, "https://www.example.com/x", false))
    }

    @Test
    fun onlyWebPagesCanBePinned() {
        assertTrue(WebFeatureLogic.canPinShortcut("https://example.com/page"))
        assertTrue(WebFeatureLogic.canPinShortcut("HTTP://example.com"))
        assertFalse(WebFeatureLogic.canPinShortcut(""))
        assertFalse(WebFeatureLogic.canPinShortcut(null))
        assertFalse(WebFeatureLogic.canPinShortcut("about:reader?url=x"))
        assertFalse(WebFeatureLogic.canPinShortcut("data:text/html,hi"))
    }

    @Test
    fun shortcutLabelPrefersTitleAndFits() {
        assertEquals("News", WebFeatureLogic.shortcutLabel(" News ", "https://example.com"))
        assertEquals("example.com", WebFeatureLogic.shortcutLabel("", "https://www.example.com"))
        val long = WebFeatureLogic.shortcutLabel("A very long page title that keeps going on", "https://example.com")
        assertTrue(long.length <= 24)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun shortcutLetterComesFromHost() {
        assertEquals("E", WebFeatureLogic.shortcutLetter("Zed", "https://www.example.com"))
        assertEquals("Z", WebFeatureLogic.shortcutLetter("zed", null))
        assertEquals("K", WebFeatureLogic.shortcutLetter("", ""))
    }

    @Test
    fun shortcutIdIsStablePerUrl() {
        assertEquals(WebFeatureLogic.shortcutId("https://a.com"), WebFeatureLogic.shortcutId(" https://a.com "))
        assertFalse(WebFeatureLogic.shortcutId("https://a.com") == WebFeatureLogic.shortcutId("https://b.com"))
    }

    @Test
    fun selectionQueryCollapsesWhitespace() {
        assertEquals("hello world", WebFeatureLogic.selectionQuery("  hello\n\t world "))
        assertNull(WebFeatureLogic.selectionQuery("   "))
        assertNull(WebFeatureLogic.selectionQuery(null))
        assertEquals(1_000, WebFeatureLogic.selectionQuery("a".repeat(5_000))!!.length)
    }

    @Test
    fun searchUrlEncodesQuery() {
        assertEquals(
            "https://duckduckgo.com/?q=a+%26+b",
            WebFeatureLogic.searchUrl("https://duckduckgo.com/?q=%s", "a & b")
        )
    }

    @Test
    fun languageComparisonUsesPrimarySubtag() {
        assertEquals("pt", WebFeatureLogic.primaryLanguage("pt-BR"))
        assertEquals("en", WebFeatureLogic.primaryLanguage("EN_us"))
        assertNull(WebFeatureLogic.primaryLanguage(" "))
        assertTrue(WebFeatureLogic.isSameLanguage("en-GB", "en"))
        assertFalse(WebFeatureLogic.isSameLanguage("fr", "en"))
        assertFalse(WebFeatureLogic.isSameLanguage(null, "en"))
    }
}
