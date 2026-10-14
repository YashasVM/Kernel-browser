package com.kernel.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class StartPageTest {
    private val google = SearchEngine.GOOGLE

    @Test
    fun `escape neutralizes markup and attribute breakouts`() {
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;", StartPage.escape("<script>alert(1)</script>"))
        assertEquals("&quot; onmouseover=&quot;alert(1)", StartPage.escape("\" onmouseover=\"alert(1)"))
        assertEquals("&#39; onfocus=&#39;x", StartPage.escape("' onfocus='x"))
        assertEquals("a &amp;amp; b", StartPage.escape("a &amp; b"))
        assertEquals("plain text", StartPage.escape("plain text"))
    }

    @Test
    fun `render escapes bookmark titles and urls`() {
        val html = StartPage.render(
            bookmarks = listOf(
                "\" onmouseover=\"alert(1)" to "https://example.com/?a=1&b=\"x\"",
                "<img src=x onerror=alert(1)>" to "https://evil.example/<script>"
            ),
            topSites = emptyList(),
            searchEngine = google
        )
        assertFalse(html.contains("\" onmouseover="))
        assertFalse(html.contains("<img src=x"))
        assertFalse(html.contains("<script"))
        assertTrue(html.contains("&quot; onmouseover=&quot;alert(1)"))
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
    }

    @Test
    fun `render only links http and https urls`() {
        val html = StartPage.render(
            bookmarks = listOf(
                "js" to "javascript:alert(1)",
                "js upper" to " JavaScript:alert(1)",
                "data" to "data:text/html,<b>x</b>",
                "file" to "file:///sdcard/x.html",
                "ok" to "https://ok.example/path"
            ),
            topSites = listOf(
                StartPage.Site("Bad", "javascript:alert(2)", "bad"),
                StartPage.Site("Good", "http://good.example/", "good.example")
            ),
            searchEngine = google
        )
        assertFalse(html.contains("javascript:", ignoreCase = true))
        assertFalse(html.contains("href=\"data:"))
        assertFalse(html.contains("file:"))
        assertTrue(html.contains("href=\"https://ok.example/path\""))
        assertTrue(html.contains("href=\"http://good.example/\""))
        val hrefs = Regex("href=\"([^\"]*)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(2, hrefs.size)
        assertTrue(hrefs.all { it.startsWith("http://") || it.startsWith("https://") })
    }

    @Test
    fun `render shows empty states without lists`() {
        val html = StartPage.render(emptyList(), emptyList(), google)
        assertTrue(html.contains("Top sites"))
        assertTrue(html.contains("Bookmarks"))
        assertTrue(html.contains("class=\"empty\""))
        assertFalse(html.contains("class=\"grid\""))
        assertFalse(html.contains("class=\"list\""))
        assertFalse(html.contains("<script"))
    }

    @Test
    fun `render caps bookmarks and top sites`() {
        val bookmarks = (1..10).map { "B$it" to "https://b$it.example/" }
        val sites = (1..12).map { StartPage.Site("S$it", "https://s$it.example/", "s$it.example") }
        val html = StartPage.render(bookmarks, sites, google)
        assertEquals(StartPage.MAX_BOOKMARKS, Regex("<li>").findAll(html).count())
        assertEquals(StartPage.MAX_TOP_SITES, Regex("class=\"tile\"").findAll(html).count())
    }

    @Test
    fun `search form targets the selected engine`() {
        val html = StartPage.render(emptyList(), emptyList(), SearchEngine.DUCKDUCKGO)
        assertTrue(html.contains("action=\"https://duckduckgo.com/\""))
        assertTrue(html.contains("name=\"q\""))
        assertTrue(html.contains("Search DuckDuckGo"))

        val form = StartPage.searchForm("https://example.com/s?client=k&query=%s")
        assertEquals("https://example.com/s", form.action)
        assertEquals("query", form.queryName)
        assertEquals(listOf("client" to "k"), form.hidden)
    }

    @Test
    fun `data uri round trips and is detected as the start page`() {
        val html = StartPage.render(emptyList(), emptyList(), google)
        val uri = StartPage.dataUri(html)
        assertTrue(uri.startsWith("data:text/html;charset=utf-8;base64,"))
        val decoded = String(Base64.getDecoder().decode(uri.substringAfter(',')), StandardCharsets.UTF_8)
        assertEquals(html, decoded)
        assertTrue(StartPage.isStartPageUrl(uri))
        assertTrue(StartPage.isStartPageUrl(uri.replace("charset=utf-8", "charset=UTF-8")))
        assertEquals("", StartPage.displayUrl(uri))

        val other = "data:text/html;base64," + Base64.getEncoder().encodeToString("<p>hi</p>".toByteArray())
        assertFalse(StartPage.isStartPageUrl(other))
        assertFalse(StartPage.isStartPageUrl("https://www.google.com"))
        assertFalse(StartPage.isStartPageUrl(null))
        assertEquals("https://a.example/", StartPage.displayUrl("https://a.example/"))
    }

    @Test
    fun `top sites dedupe by host and rank by frequency`() {
        val history = listOf(
            "News 3" to "https://news.example/c",
            "Mail" to "https://www.mail.example/inbox",
            "News 2" to "https://news.example/b",
            "Mail again" to "https://mail.example/other",
            "News 1" to "https://news.example/a",
            "Bad" to "javascript:alert(1)",
            "Once" to "http://once.example:8080/x"
        )
        val sites = StartPage.topSites(history)
        assertEquals(listOf("news.example", "mail.example", "once.example"), sites.map { it.host })
        assertEquals("https://news.example/", sites[0].url)
        assertEquals("https://www.mail.example/", sites[1].url)
        assertEquals("http://once.example:8080/", sites[2].url)
        assertEquals("News", sites[0].title)

        val many = (1..20).map { "T" to "https://site$it.example/" }
        assertEquals(StartPage.MAX_TOP_SITES, StartPage.topSites(many).size)
    }

    @Test
    fun `safe href rejects non web urls`() {
        assertNull(StartPage.safeHref("javascript:alert(1)"))
        assertNull(StartPage.safeHref("https://exa mple.com"))
        assertNull(StartPage.safeHref("https:///nohost"))
        assertEquals("https://example.com", StartPage.safeHref("https://example.com"))
    }

    @Test
    fun `top site favicons are css backgrounds so a missing icon shows the letter`() {
        val html = StartPage.render(
            bookmarks = emptyList(),
            topSites = StartPage.topSites(listOf("Example" to "https://www.example.com/page", "Local" to "http://10.0.2.2:8000/")),
            searchEngine = SearchEngine.GOOGLE
        )

        assertFalse(html.contains("<img"))
        assertTrue(html.contains("background-image:url(&quot;https://www.example.com/favicon.ico&quot;)"))
        assertTrue(html.contains(">10.0.2.2:8000<") || html.contains(">10.0.2.2<"))
    }
}
