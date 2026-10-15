package com.kernel.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingIntentTest {
    private val google = SearchEngine.GOOGLE

    @Test
    fun `view opens http and https links only`() {
        assertEquals("https://example.com/a?b=1", IncomingIntent.resolve(IncomingIntent.ACTION_VIEW, "https://example.com/a?b=1", null, google))
        assertEquals("HTTP://example.com", IncomingIntent.resolve(IncomingIntent.ACTION_VIEW, "HTTP://example.com", null, google))
        assertNull(IncomingIntent.resolve(IncomingIntent.ACTION_VIEW, "javascript:alert(1)", null, google))
        assertNull(IncomingIntent.resolve(IncomingIntent.ACTION_VIEW, "file:///sdcard/a.html", null, google))
        assertNull(IncomingIntent.resolve(IncomingIntent.ACTION_VIEW, "content://x/y", null, google))
        assertNull(IncomingIntent.resolve(IncomingIntent.ACTION_VIEW, "https://", null, google))
        assertNull(IncomingIntent.resolve(IncomingIntent.ACTION_VIEW, null, null, google))
    }

    @Test
    fun `shared link opens directly`() {
        assertEquals("https://example.com/story", IncomingIntent.resolve(IncomingIntent.ACTION_SEND, null, "  https://example.com/story\n", google))
    }

    @Test
    fun `shared text with an embedded link opens the link`() {
        assertEquals(
            "https://news.example/a/1",
            IncomingIntent.resolve(IncomingIntent.ACTION_SEND, null, "Great read: https://news.example/a/1.", google)
        )
        assertEquals(
            "https://example.com/x",
            IncomingIntent.resolve(IncomingIntent.ACTION_SEND, null, "Title\n(https://example.com/x)", google)
        )
    }

    @Test
    fun `shared plain text is searched`() {
        assertEquals(
            "https://www.google.com/search?q=best+pizza+near+me",
            IncomingIntent.resolve(IncomingIntent.ACTION_SEND, null, "best pizza\nnear me", google)
        )
    }

    @Test
    fun `selected text is searched with the chosen engine`() {
        assertEquals(
            "https://duckduckgo.com/?q=kotlin+coroutines",
            IncomingIntent.resolve(IncomingIntent.ACTION_PROCESS_TEXT, null, "kotlin coroutines", SearchEngine.DUCKDUCKGO)
        )
        assertEquals(
            "https://www.google.com/search?q=hello",
            IncomingIntent.resolve(IncomingIntent.ACTION_WEB_SEARCH, null, "hello", google)
        )
    }

    @Test
    fun `selected text with a link inside is searched rather than opened`() {
        assertEquals(
            "https://www.google.com/search?q=see+https%3A%2F%2Fexample.com",
            IncomingIntent.resolve(IncomingIntent.ACTION_PROCESS_TEXT, null, "see https://example.com", google)
        )
    }

    @Test
    fun `bare domains open as websites`() {
        assertEquals("https://example.com", IncomingIntent.resolve(IncomingIntent.ACTION_WEB_SEARCH, null, "example.com", google))
        assertEquals("https://192.168.1.1:8080", IncomingIntent.resolve(IncomingIntent.ACTION_PROCESS_TEXT, null, "192.168.1.1:8080", google))
    }

    @Test
    fun `non web schemes in text are searched, never loaded`() {
        assertEquals(
            "https://www.google.com/search?q=javascript%3Aalert%281%29",
            IncomingIntent.resolve(IncomingIntent.ACTION_SEND, null, "javascript:alert(1)", google)
        )
        assertEquals(
            "https://www.google.com/search?q=file%3A%2F%2F%2Fetc%2Fhosts",
            IncomingIntent.resolve(IncomingIntent.ACTION_PROCESS_TEXT, null, "file:///etc/hosts", google)
        )
    }

    @Test
    fun `blank text and unknown actions are ignored`() {
        assertNull(IncomingIntent.resolve(IncomingIntent.ACTION_SEND, null, "   ", google))
        assertNull(IncomingIntent.resolve(IncomingIntent.ACTION_SEND, null, null, google))
        assertNull(IncomingIntent.resolve("android.intent.action.MAIN", null, null, google))
        assertNull(IncomingIntent.resolve(null, "https://example.com", null, google))
    }

    @Test
    fun `long text is shortened before searching`() {
        val text = "word ".repeat(300)
        val url = IncomingIntent.resolve(IncomingIntent.ACTION_SEND, null, text, google)!!
        val query = url.substringAfter("q=")
        assertEquals(IncomingIntent.MAX_QUERY_LENGTH, java.net.URLDecoder.decode(query, "UTF-8").length)
    }

    @Test
    fun `custom engines are used for searches`() {
        val engine = SearchEngine(SearchEngine.CUSTOM_ID, "Wiki", "https://en.wikipedia.org/w/index.php?search=%s&title=Special%3ASearch")
        assertEquals(
            "https://en.wikipedia.org/w/index.php?search=100%25+cotton&title=Special%3ASearch",
            IncomingIntent.resolve(IncomingIntent.ACTION_PROCESS_TEXT, null, "100% cotton", engine)
        )
    }
}
