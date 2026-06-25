package com.kernel.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreCodecTest {
    @Test
    fun `round trips url and gecko state`() {
        val entries = listOf(
            SessionStore.Entry(
                title = "Example",
                url = "https://example.com",
                state = "opaque-gecko-state"
            )
        )

        val decoded = SessionStoreCodec.decode(SessionStoreCodec.encode(entries))

        assertEquals(entries, decoded)
    }

    @Test
    fun `keeps state only tabs for restore fallback`() {
        val entries = listOf(SessionStore.Entry("Restored", "", "state-only"))

        val decoded = SessionStoreCodec.decode(SessionStoreCodec.encode(entries))

        assertEquals(entries, decoded)
    }

    @Test
    fun `drops completely empty tabs`() {
        val encoded = SessionStoreCodec.encode(listOf(SessionStore.Entry("Empty", "", "")))

        assertTrue(SessionStoreCodec.decode(encoded).isEmpty())
    }

    @Test
    fun `ignores corrupt rows and limits tab count`() {
        val entries = (0..75).map {
            SessionStore.Entry("Tab $it", "https://example.com/$it", "state-$it")
        }
        val raw = "bad-row\n${SessionStoreCodec.encode(entries)}"

        val decoded = SessionStoreCodec.decode(raw)

        assertEquals(SessionStoreCodec.MAX_TABS, decoded.size)
        assertEquals("Tab 0", decoded.first().title)
    }
}
