package com.kernel.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressDisplayTest {
    private val google = SearchEngine.GOOGLE

    @Test
    fun showsHostWithoutWww() {
        assertEquals("example.com", AddressDisplay.format("https://www.example.com/a/b?c=d#e", google))
        assertEquals("news.ycombinator.com", AddressDisplay.format("https://news.ycombinator.com/item?id=1", google))
        assertEquals("localhost:8000", AddressDisplay.format("http://localhost:8000/x", google))
    }

    @Test
    fun showsSearchTermsOnResultsPages() {
        assertEquals("kotlin coroutines", AddressDisplay.format("https://www.google.com/search?q=kotlin+coroutines&hl=en", google))
        assertEquals("a&b", AddressDisplay.format("https://duckduckgo.com/?q=a%26b", SearchEngine.DUCKDUCKGO))
        assertEquals("search.brave.com", AddressDisplay.format("https://search.brave.com/images", SearchEngine.BRAVE))
    }

    @Test
    fun otherEnginesResultsShowTheHost() {
        assertEquals("bing.com", AddressDisplay.format("https://www.bing.com/search?q=hello", google))
        assertNull(AddressDisplay.searchTerms("https://www.google.com/maps?q=cafe", google))
    }

    @Test
    fun readerViewShowsTheArticleHost() {
        assertEquals(
            "blog.example.org",
            AddressDisplay.format("about:reader?url=https%3A%2F%2Fblog.example.org%2Fpost", google)
        )
    }

    @Test
    fun nonWebUrlsAreShownAsIs() {
        assertEquals("", AddressDisplay.format("", google))
        assertEquals("about:blank", AddressDisplay.format("about:blank", google))
        assertEquals("moz-extension://abc/options.html", AddressDisplay.format("moz-extension://abc/options.html", google))
    }
}
