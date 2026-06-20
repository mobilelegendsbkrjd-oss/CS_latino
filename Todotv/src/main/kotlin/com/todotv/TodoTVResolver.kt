package com.todotv

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder

object TodoTVResolver {

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    data class ResolvedLink(
        val url: String,
        val referer: String
    )

    suspend fun expandServers(source: TodoTV.ChannelSource): List<TodoTV.ChannelSource> {
        return try {
            val html = app.get(
                source.url,
                referer = source.referer ?: source.url,
                headers = buildHeaders(source.referer ?: source.url, cleanUserAgent(source.userAgent))
            ).text

            val doc = Jsoup.parse(html, source.url)
            val servers = mutableListOf<TodoTV.ChannelSource>()

            doc.select("a").forEach { a ->
                val text = a.text().trim()
                val href = a.attr("abs:href").ifBlank { a.attr("href") }.trim()

                if (href.isNotBlank() && isServerText(text) && isValidServerUrl(href)) {
                    servers.add(
                        TodoTV.ChannelSource(
                            name = "${source.name} - ${text.ifBlank { "Servidor" }}",
                            url = fixUrl(href, source.url),
                            referer = source.url,
                            userAgent = source.userAgent ?: DEFAULT_UA,
                            embed = true
                        )
                    )
                }
            }

            if (servers.isEmpty() && doc.select("iframe").isNotEmpty()) {
                servers.add(source)
            }

            servers.distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun resolve(
        channelName: String,
        sourceIndex: Int,
        source: TodoTV.ChannelSource,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = source.url.trim()
        val referer = cleanReferer(source.referer)
        val ua = cleanUserAgent(source.userAgent)

        if (url.isBlank()) return false

        val resolved: ResolvedLink? = when {
            isDirectVideo(url) -> ResolvedLink(url, referer ?: url)
            source.embed -> resolveGenericEmbed(url, referer, ua)
            else -> resolveGenericEmbed(url, referer, ua) ?: ResolvedLink(url, referer ?: url)
        }

        if (resolved != null && (isDirectVideo(resolved.url) || !source.embed)) {
            addLink(
                name = "$channelName - S$sourceIndex",
                source = if (resolved.url == url) "TodoTV Directo $sourceIndex" else "TodoTV Resolver $sourceIndex",
                url = resolved.url,
                referer = resolved.referer,
                ua = ua,
                callback = callback
            )
            return true
        }

        loadExtractor(url, referer ?: url, subtitleCallback, callback)
        return true
    }

    private suspend fun addLink(
        name: String,
        source: String,
        url: String,
        referer: String,
        ua: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = getOrigin(referer) ?: getOrigin(url) ?: ""

        callback.invoke(
            newExtractorLink(
                source = source,
                name = name,
                url = url,
                type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                quality = Qualities.Unknown.value
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to referer,
                    "Origin" to origin,
                    "Accept" to "*/*",
                    "Connection" to "keep-alive"
                )
            }
        )
    }

    private suspend fun resolveGenericEmbed(
        pageUrl: String,
        referer: String?,
        ua: String
    ): ResolvedLink? {
        return try {
            val realReferer = referer ?: pageUrl

            val html = app.get(
                pageUrl,
                referer = realReferer,
                headers = buildHeaders(realReferer, ua)
            ).text

            val direct = extractVideoUrl(html)
            if (!direct.isNullOrBlank()) return ResolvedLink(direct, pageUrl)

            resolveIframe(html, pageUrl, ua)
                ?: resolveScriptSrc(html, pageUrl, ua)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveIframe(
        html: String,
        baseUrl: String,
        ua: String
    ): ResolvedLink? {
        val iframes = Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { fixUrl(it, baseUrl) }
            .filter { it.startsWith("http") }
            .toList()

        for (iframe in iframes) {
            try {
                val html2 = app.get(
                    iframe,
                    referer = baseUrl,
                    headers = buildHeaders(baseUrl, ua)
                ).text

                val direct = extractVideoUrl(html2)
                if (!direct.isNullOrBlank()) return ResolvedLink(direct, iframe)

                val nested = resolveIframe(html2, iframe, ua)
                if (nested != null) return nested

                val script = resolveScriptSrc(html2, iframe, ua)
                if (script != null) return script
            } catch (_: Exception) {
            }
        }

        return null
    }

    private suspend fun resolveScriptSrc(
        html: String,
        baseUrl: String,
        ua: String
    ): ResolvedLink? {
        val scripts = Regex("""<script[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { fixUrl(it, baseUrl) }
            .filter { it.startsWith("http") }
            .filterNot { it.contains("jwplatform", true) }
            .filterNot { it.contains("googletagmanager", true) }
            .filterNot { it.contains("googlesyndication", true) }
            .filterNot { it.contains("doubleclick", true) }
            .filterNot { it.contains("ads", true) }
            .toList()

        for (script in scripts) {
            try {
                val js = app.get(
                    script,
                    referer = baseUrl,
                    headers = buildHeaders(baseUrl, ua)
                ).text

                val direct = extractVideoUrl(js)
                if (!direct.isNullOrBlank()) return ResolvedLink(direct, script)
            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun extractVideoUrl(text: String): String? {
        val clean = text
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val patterns = listOf(
            Regex("""setupPlayer\s*\(\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),

            Regex("""https?://[^"'\s<>]+?\.m3u8[^"'\s<>]*""", RegexOption.IGNORE_CASE),
            Regex("""https?:\\/\\/[^"'\\]+?\.m3u8[^"'\\]*""", RegexOption.IGNORE_CASE),

            Regex("""https?://[^"'\s<>]+?hoca8\.com/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s<>]+?footy\.php[^"'\s<>]*""", RegexOption.IGNORE_CASE),

            Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']file["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""source\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""sources\s*:\s*\[[\s\S]*?file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""src\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),

            Regex("""var\s+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""atob\s*\(\s*["']([^"']+)["']\s*\)""", RegexOption.IGNORE_CASE)
        )

        for (regex in patterns) {
            val match = regex.find(clean) ?: continue
            var value = match.groupValues.getOrNull(1)?.ifBlank { match.value } ?: match.value

            if (!value.startsWith("http", true) && value.length > 20) {
                value = try {
                    String(android.util.Base64.decode(value, android.util.Base64.DEFAULT))
                } catch (_: Exception) {
                    value
                }
            }

            if (value.startsWith("http", true)) {
                return value
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("&amp;", "&")
            }
        }

        return null
    }

    private fun isDirectVideo(url: String): Boolean {
        val clean = url.lowercase()

        return clean.contains(".m3u8") ||
                clean.contains(".mp4") ||
                clean.contains(".flv") ||
                clean.contains("/playlist.m3u8") ||
                clean.contains("index.m3u8") ||
                clean.contains("hoca8.com") ||
                clean.contains("footy.php") ||
                clean.contains("/livetv/") ||
                clean.contains("/play/")
    }

    private fun isServerText(text: String): Boolean {
        val lower = text.lowercase()

        return lower.contains("opción") ||
                lower.contains("opcion") ||
                lower.contains("servidor") ||
                lower.contains("server") ||
                lower.contains("fhd") ||
                lower.contains("hd") ||
                lower.contains("ver") ||
                lower.contains("reproducir")
    }

    private fun isValidServerUrl(url: String): Boolean {
        val lower = url.lowercase()

        return url.isNotBlank() &&
                !lower.contains("paypal") &&
                !lower.contains("telegram") &&
                !lower.contains("whatsapp") &&
                !lower.contains("facebook") &&
                !lower.contains("instagram") &&
                !lower.contains("twitter") &&
                !lower.contains("linktre.online")
    }

    private fun buildHeaders(referer: String, ua: String): Map<String, String> {
        val origin = getOrigin(referer) ?: ""

        return mapOf(
            "User-Agent" to ua,
            "Referer" to referer,
            "Origin" to origin,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Connection" to "keep-alive"
        )
    }

    private fun cleanReferer(input: String?): String? {
        val value = input?.trim()?.ifBlank { null } ?: return null
        if (value == " ") return null
        return value
    }

    private fun cleanUserAgent(input: String?): String {
        if (input.isNullOrBlank()) return DEFAULT_UA

        return try {
            URLDecoder.decode(input.trim(), "UTF-8").trim()
        } catch (_: Exception) {
            input.trim()
        }
    }

    private fun fixUrl(url: String, baseUrl: String): String {
        val clean = url.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http") -> clean
            clean.startsWith("/") -> (getOrigin(baseUrl) ?: "") + clean
            else -> {
                val origin = getOrigin(baseUrl) ?: return clean
                "$origin/$clean"
            }
        }
    }

    private fun getOrigin(url: String): String? {
        return try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            Regex("""https?://[^/]+""").find(url)?.value
        }
    }
}
