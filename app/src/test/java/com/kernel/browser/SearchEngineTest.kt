package com.kernel.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {
    @Test
    fun `stored ids still resolve to the same engines`() {
        assertEquals(SearchEngine.GOOGLE, SearchEngine.byId("google"))
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.byId("duckduckgo"))
        assertEquals(SearchEngine.BRAVE, SearchEngine.byId("brave"))
        assertEquals(SearchEngine.BING, SearchEngine.byId("bing"))
        assertEquals(SearchEngine.GOOGLE, SearchEngine.byId("unknown"))
        assertEquals(SearchEngine.GOOGLE, SearchEngine.byId(SearchEngine.CUSTOM_ID))
    }

    @Test
    fun `every built in engine offers suggestions in the OpenSearch format`() {
        SearchEngine.all.forEach { engine ->
            val url = engine.suggestionsUrl
            assertNotNull(engine.displayName, url)
            assertTrue(url!!.startsWith("https://"))
            assertTrue(url.contains("%s"))
            // MainActivity fills these in with String.format, so no other % may appear.
            assertEquals(1, Regex("%").findAll(url).count())
        }
    }

    @Test
    fun `search url encodes the query`() {
        assertEquals("https://www.google.com/search?q=a+%26+b", SearchEngine.GOOGLE.searchUrlFor("a & b"))
        assertEquals("https://duckduckgo.com/?q=caf%C3%A9", SearchEngine.DUCKDUCKGO.searchUrlFor("café"))
    }

    @Test
    fun `custom template accepts a query parameter`() {
        val result = CustomSearchEngines.validate("  My   Wiki ", " https://en.wikipedia.org/w/index.php?search=%s&ns0=1 ")
        val engine = (result as CustomSearchEngines.Result.Valid).engine
        assertEquals("My Wiki", engine.displayName)
        assertEquals(SearchEngine.CUSTOM_ID, engine.id)
        assertTrue(engine.isCustom)
        assertNull(engine.suggestionsUrl)
        assertEquals("https://en.wikipedia.org/w/index.php?search=%s&ns0=1", engine.searchUrl)
        assertEquals("https://en.wikipedia.org/w/index.php?search=red+fox&ns0=1", engine.searchUrlFor("red fox"))
    }

    @Test
    fun `custom template converts other browsers placeholders`() {
        val engine = CustomSearchEngines.create("Ex", "https://example.com/find?q={searchTerms}")
        assertEquals("https://example.com/find?q=%s", engine?.searchUrl)
    }

    @Test
    fun `custom template keeps other percent escapes intact`() {
        val engine = CustomSearchEngines.create("Ex", "https://example.com/s?q=%s&cat=a%20b")!!
        assertEquals("https://example.com/s?q=x&cat=a%20b", engine.searchUrlFor("x"))
    }

    @Test
    fun `custom template is rejected when unusable`() {
        fun invalid(name: String, url: String) =
            CustomSearchEngines.validate(name, url) is CustomSearchEngines.Result.Invalid

        assertTrue(invalid("", "https://example.com/?q=%s"))
        assertTrue(invalid("x".repeat(CustomSearchEngines.MAX_NAME_LENGTH + 1), "https://example.com/?q=%s"))
        assertTrue(invalid("Ex", ""))
        assertTrue(invalid("Ex", "https://example.com/search"))
        assertTrue(invalid("Ex", "https://example.com/search/%s"))
        assertTrue(invalid("Ex", "https://example.com/?q=pre%s"))
        assertTrue(invalid("Ex", "javascript:alert(%s)"))
        assertTrue(invalid("Ex", "ftp://example.com/?q=%s"))
        assertTrue(invalid("Ex", "https://exa mple.com/?q=%s"))
        assertTrue(invalid("Ex", "https:///?q=%s"))
        assertNull(CustomSearchEngines.create("", ""))
    }
}
