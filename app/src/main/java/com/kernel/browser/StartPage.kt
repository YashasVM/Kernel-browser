package com.kernel.browser

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * Native start page for new tabs and Home. The page is a static HTML document (no scripts) rendered
 * by Gecko from a data: URI so it participates in the normal session/back-stack/thumbnail machinery.
 */
object StartPage {
    const val MAX_TOP_SITES = 8
    const val MAX_BOOKMARKS = 6

    /** Stored in [SessionStore] in place of the (large, stale) data: URI; mapped back on restore. */
    const val SESSION_URL = "kernel:start"

    const val TITLE = "New tab"

    /** Every rendered document starts with this exact text, which makes the data: URI detectable. */
    private const val MARKER = "<!DOCTYPE html><!-- kernel-start-page -->"
    private const val DATA_URI_PREFIX = "data:text/html;charset=utf-8;base64,"

    private val markerBase64: String = run {
        val bytes = MARKER.toByteArray(StandardCharsets.UTF_8)
        // Only whole 3-byte groups encode to a stable base64 prefix regardless of what follows.
        Base64.getEncoder().encodeToString(bytes.copyOf(bytes.size / 3 * 3))
    }

    data class Site(val title: String, val url: String, val host: String)

    fun dataUri(html: String): String {
        return DATA_URI_PREFIX + Base64.getEncoder().encodeToString(html.toByteArray(StandardCharsets.UTF_8))
    }

    fun isStartPageUrl(url: String?): Boolean {
        if (url == null || !url.startsWith("data:", ignoreCase = true)) return false
        val comma = url.indexOf(',')
        if (comma < 0) return false
        return url.regionMatches(comma + 1, markerBase64, 0, markerBase64.length)
    }

    /** The URL to show/store for a tab: empty while the start page is showing. */
    fun displayUrl(url: String?): String = if (isStartPageUrl(url)) "" else url.orEmpty()

    /** Most-visited hosts from (most-recent-first) history, deduped by host, http/https only. */
    fun topSites(history: List<Pair<String, String>>, max: Int = MAX_TOP_SITES): List<Site> {
        data class Bucket(val firstIndex: Int, val title: String, val url: String, val host: String, var count: Int)
        val buckets = linkedMapOf<String, Bucket>()
        history.forEachIndexed { index, (title, url) ->
            val uri = parseWebUri(url) ?: return@forEachIndexed
            val host = displayHost(uri.host)
            val existing = buckets[host]
            if (existing == null) {
                val origin = "${uri.scheme.lowercase(Locale.ROOT)}://${authority(uri)}/"
                buckets[host] = Bucket(index, title, origin, host, 1)
            } else {
                existing.count++
            }
        }
        return buckets.values
            .sortedWith(compareByDescending<Bucket> { it.count }.thenBy { it.firstIndex })
            .take(max)
            .map { Site(siteName(it.host), it.url, it.host) }
    }

    fun render(
        bookmarks: List<Pair<String, String>>,
        topSites: List<Site>,
        searchEngine: SearchEngine
    ): String {
        val form = searchForm(searchEngine.searchUrl)
        val sb = StringBuilder(8192)
        sb.append(MARKER).append('\n')
        sb.append("<html lang=\"en\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">")
        sb.append(
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
                "style-src 'unsafe-inline'; img-src https: http:; form-action https: http:\">"
        )
        sb.append("<meta name=\"referrer\" content=\"no-referrer\">")
        sb.append("<meta name=\"color-scheme\" content=\"dark\">")
        sb.append("<title>").append(TITLE).append("</title>")
        sb.append("<style>").append(CSS).append("</style></head><body><main>")

        sb.append("<h1 class=\"brand\">Kernel</h1>")
        sb.append("<form class=\"search\" method=\"get\" role=\"search\" action=\"")
            .append(escape(form.action)).append("\">")
        form.hidden.forEach { (name, value) ->
            sb.append("<input type=\"hidden\" name=\"").append(escape(name))
                .append("\" value=\"").append(escape(value)).append("\">")
        }
        sb.append("<input class=\"q\" type=\"search\" name=\"").append(escape(form.queryName))
            .append("\" placeholder=\"Search ").append(escape(searchEngine.displayName))
            .append("\" aria-label=\"Search ").append(escape(searchEngine.displayName))
            .append("\" autocomplete=\"off\" autocapitalize=\"off\" enterkeyhint=\"search\">")
        sb.append("<button type=\"submit\" aria-label=\"Search\">Go</button></form>")

        val sites = topSites.filter { safeHref(it.url) != null }.take(MAX_TOP_SITES)
        sb.append("<section><h2>Top sites</h2>")
        if (sites.isEmpty()) {
            sb.append("<p class=\"empty\">Sites you visit often will appear here.</p>")
        } else {
            sb.append("<div class=\"grid\">")
            sites.forEach { site ->
                val href = safeHref(site.url)!!
                val label = site.title.ifBlank { site.host }
                val letter = firstLetter(label)
                sb.append("<a class=\"tile\" href=\"").append(escape(href)).append("\" title=\"")
                    .append(escape(label)).append("\">")
                sb.append("<span class=\"icon\" style=\"background:").append(colorFor(site.host)).append("\">")
                    .append(escape(letter))
                val faviconHost = hostForFavicon(href)
                if (faviconHost.isNotBlank()) {
                    val scheme = if (href.startsWith("http://", ignoreCase = true)) "http" else "https"
                    sb.append("<img src=\"").append(escape("$scheme://$faviconHost/favicon.ico"))
                        .append("\" alt=\"\" width=\"28\" height=\"28\" loading=\"lazy\">")
                }
                sb.append("</span><span class=\"label\">").append(escape(label)).append("</span></a>")
            }
            sb.append("</div>")
        }
        sb.append("</section>")

        val marks = bookmarks.mapNotNull { (title, url) -> safeHref(url)?.let { title to it } }.take(MAX_BOOKMARKS)
        sb.append("<section><h2>Bookmarks</h2>")
        if (marks.isEmpty()) {
            sb.append("<p class=\"empty\">Bookmark pages from the browser menu to see them here.</p>")
        } else {
            sb.append("<ul class=\"list\">")
            marks.forEach { (title, href) ->
                val label = title.ifBlank { href }
                val host = parseWebUri(href)?.host?.let(::displayHost).orEmpty()
                sb.append("<li><a href=\"").append(escape(href)).append("\">")
                sb.append("<span class=\"icon small\" style=\"background:").append(colorFor(host)).append("\">")
                    .append(escape(firstLetter(label))).append("</span>")
                sb.append("<span class=\"text\"><span class=\"title\">").append(escape(label))
                    .append("</span><span class=\"url\">").append(escape(host.ifBlank { href }))
                    .append("</span></span></a></li>")
            }
            sb.append("</ul>")
        }
        sb.append("</section></main></body></html>")
        return sb.toString()
    }

    /** HTML-escapes text for both element content and double/single-quoted attribute values. */
    fun escape(value: String): String {
        val out = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                '`' -> out.append("&#96;")
                else -> if (ch < ' ' && ch != '\t' && ch != '\n') out.append(' ') else out.append(ch)
            }
        }
        return out.toString()
    }

    /** Returns the URL if it is a well-formed http(s) URL with a host, otherwise null. */
    fun safeHref(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.any { it.isWhitespace() || it < ' ' || it == '\u007f' }) return null
        return if (webUrlPattern.containsMatchIn(trimmed)) trimmed else null
    }

    private val webUrlPattern = Regex("^[hH][tT][tT][pP][sS]?://[^/?#@\\\\]+([/?#]|$)")

    internal data class SearchForm(val action: String, val queryName: String, val hidden: List<Pair<String, String>>)

    /** Splits an engine template like `https://host/search?q=%s&x=1` into a GET form. */
    internal fun searchForm(searchUrl: String): SearchForm {
        val action = searchUrl.substringBefore('?')
        val query = searchUrl.substringAfter('?', "")
        var queryName = "q"
        val hidden = mutableListOf<Pair<String, String>>()
        query.split('&').filter { it.isNotEmpty() }.forEach { part ->
            val name = decode(part.substringBefore('='))
            val value = part.substringAfter('=', "")
            if (value == "%s") queryName = name else hidden += name to decode(value)
        }
        return SearchForm(action, queryName, hidden)
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun parseWebUri(url: String): URI? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return uri
    }

    private fun hostForFavicon(href: String): String = parseWebUri(href)?.let(::authority).orEmpty()

    /** host[:port] without any user-info. */
    private fun authority(uri: URI): String =
        uri.host.lowercase(Locale.ROOT) + if (uri.port >= 0) ":${uri.port}" else ""

    private fun displayHost(host: String): String = host.lowercase(Locale.ROOT).removePrefix("www.")

    private fun siteName(host: String): String {
        val labels = host.split('.')
        val name = if (labels.size >= 2) labels[labels.size - 2] else host
        return name.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    private fun firstLetter(label: String): String {
        val ch = label.firstOrNull { it.isLetterOrDigit() } ?: return "?"
        return ch.uppercaseChar().toString()
    }

    private val PALETTE = listOf("#4F7DF3", "#E8615A", "#3FA672", "#B36AE2", "#E0913A", "#2E9DB8", "#D45D9C", "#7D8A99")

    private fun colorFor(key: String): String = PALETTE[(key.hashCode() and 0x7fffffff) % PALETTE.size]

    private val CSS = """
        *{box-sizing:border-box}
        html,body{margin:0;background:#202124;color:#F2F3F5;font-family:system-ui,-apple-system,"Roboto",sans-serif;-webkit-text-size-adjust:100%}
        main{max-width:640px;margin:0 auto;padding:calc(40px + env(safe-area-inset-top)) 16px 140px}
        .brand{margin:8px 0 20px;text-align:center;font-size:30px;font-weight:700;letter-spacing:-.5px;color:#F2F3F5}
        .search{display:flex;align-items:center;background:#2C2D31;border:1px solid #3A3C41;border-radius:26px;padding:4px 4px 4px 18px;margin-bottom:28px}
        .search:focus-within{border-color:#7AB7FF}
        .q{flex:1;min-width:0;background:transparent;border:0;outline:0;color:#F2F3F5;font:inherit;font-size:17px;padding:12px 0}
        .q::placeholder{color:#9AA0A8}
        .search button{border:0;border-radius:22px;background:#7AB7FF;color:#0B1B2E;font:inherit;font-weight:600;font-size:15px;padding:10px 18px;margin-left:8px}
        h2{font-size:15px;font-weight:600;color:#C0C3CA;margin:0 4px 12px;text-transform:none}
        section{margin-bottom:28px}
        .grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px 8px;background:#2C2D31;border-radius:20px;padding:16px 8px}
        .tile{display:flex;flex-direction:column;align-items:center;text-decoration:none;color:#F2F3F5;min-width:0}
        .icon{position:relative;display:flex;align-items:center;justify-content:center;width:52px;height:52px;border-radius:50%;color:#fff;font-weight:700;font-size:21px;overflow:hidden;flex:none}
        .icon img{position:absolute;left:12px;top:12px;width:28px;height:28px;border:0}
        .icon.small{width:36px;height:36px;font-size:16px}
        .label{margin-top:8px;font-size:13px;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        .list{list-style:none;margin:0;padding:4px 0;background:#2C2D31;border-radius:20px}
        .list a{display:flex;align-items:center;gap:14px;padding:10px 16px;text-decoration:none;color:#F2F3F5;min-width:0}
        .list li+li a{border-top:1px solid #36383D}
        .text{display:flex;flex-direction:column;min-width:0}
        .title,.url{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        .title{font-size:16px}
        .url{font-size:13px;color:#9AA0A8;margin-top:2px}
        .empty{margin:0;padding:18px 16px;background:#2C2D31;border-radius:20px;color:#9AA0A8;font-size:14px}
        a:active{opacity:.7}
        a:focus-visible{outline:2px solid #7AB7FF;outline-offset:2px;border-radius:12px}
        @media (orientation:landscape) and (max-height:500px){main{padding-top:calc(16px + env(safe-area-inset-top))}.brand{display:none}.search{margin-bottom:18px}.grid{grid-template-columns:repeat(8,minmax(0,1fr))}}
        @media (min-width:600px){.grid{grid-template-columns:repeat(8,minmax(0,1fr))}}
    """.trimIndent().replace("\n", "")
}
