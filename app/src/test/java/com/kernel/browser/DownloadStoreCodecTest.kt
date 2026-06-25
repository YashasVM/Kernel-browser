package com.kernel.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class DownloadStoreCodecTest {
    @Test
    fun `round trips rich download entries`() {
        val entries = listOf(
            DownloadStore.Entry(
                id = 42L,
                title = "Report",
                url = "https://example.com/report.pdf",
                fileName = "report.pdf"
            )
        )

        val decoded = DownloadStoreCodec.decode(DownloadStoreCodec.encode(entries))

        assertEquals(entries, decoded)
    }

    @Test
    fun `decodes legacy title and url rows`() {
        val raw = "${encodeField("Old file")}\t${encodeField("https://example.com/file")}"

        val decoded = DownloadStoreCodec.decode(raw)

        assertEquals(DownloadStore.Entry(-1L, "Old file", "https://example.com/file", "Old file"), decoded.single())
    }

    @Test
    fun `drops corrupt and blank url rows`() {
        val blankUrl = listOf("1", "Title", "", "file.txt").joinToString("\t", transform = ::encodeField)
        val decoded = DownloadStoreCodec.decode("not\tenough\tfields\n$blankUrl")

        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `limits download history`() {
        val entries = (0..150).map {
            DownloadStore.Entry(it.toLong(), "File $it", "https://example.com/$it", "$it.bin")
        }

        val decoded = DownloadStoreCodec.decode(DownloadStoreCodec.encode(entries))

        assertEquals(DownloadStoreCodec.MAX_ENTRIES, decoded.size)
    }

    private fun encodeField(value: String): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }
}
