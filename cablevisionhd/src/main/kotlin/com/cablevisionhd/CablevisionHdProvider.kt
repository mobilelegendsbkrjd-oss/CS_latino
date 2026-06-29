package com.cablevision

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

class CablevisionHdProvider : MainAPI() {

    override var mainUrl = "https://www.cablevisionhd.com"
    override var name = "CablevisionHd"
    override var lang = "es"

    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "$mainUrl" to "Canales"
    )

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    data class ResolvedLink(
        val url: String,
        val referer: String
    )

    // =====================================================
    // PARSE CHANNELS
    // =====================================================

    private fun parseChannels(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        doc.select("script").forEach { script ->
            val data = script.data().ifBlank { script.html() }

            if (data.contains("homeChannels", true) || data.contains("const channels", true)) {
                try {
                    val blocks = Regex("""`([\s\S]*?)`""")
                        .findAll(data)
                        .map { it.groupValues[1] }
                        .toList()

                    for (block in blocks) {
                        if (block.length <= 100) continue

                        val scriptDoc = Jsoup.parse(block, mainUrl)

                        scriptDoc.select("a").forEach { a ->
                            val link = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
                            val img = a.selectFirst("img")

                            val title = a.attr("title").trim()
                                .ifBlank { img?.attr("alt")?.trim() ?: "" }
                                .ifBlank { a.text().trim() }

                            val rawImg =
                                img?.attr("data-src")?.takeIf { it.isNotBlank() }
                                    ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                                    ?: img?.attr("abs:src")?.takeIf { it.isNotBlank() }
                                    ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                                    ?: ""

                            if (isValidChannel(link, title)) {
                                val finalUrl = fixUrl(link, mainUrl)
                                val poster = if (rawImg.isNotBlank()) fixUrl(rawImg, mainUrl) else ""

                                results.add(
                                    newMovieSearchResponse(
                                        cleanName(title),
                                        finalUrl,
                                        TvType.Live
                                    ) {
                                        this.posterUrl = poster
                                    }
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (results.isEmpty()) {
            doc.select("a:has(img), a").forEach { a ->
                val link = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
                val img = a.selectFirst("img")

                val title = a.attr("title").trim()
                    .ifBlank { img?.attr("alt")?.trim() ?: "" }
                    .ifBlank { a.text().trim() }

                val rawImg =
                    img?.attr("data-src")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("abs:src")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                        ?: ""

                if (isValidChannel(link, title)) {
                    val finalUrl = fixUrl(link, mainUrl)
                    val poster = if (rawImg.isNotBlank()) fixUrl(rawImg, mainUrl) else ""

                    results.add(
                        newMovieSearchResponse(
                            cleanName(title),
                            finalUrl,
                            TvType.Live
                        ) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
        }

        return results.distinctBy { it.url }
    }

    private fun cleanName(name: String): String {
        return name
            .replace("📶", "")
            .replace(Regex("""\bEN\s+VIVO\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|')
    }

    private fun isValidChannel(link: String, title: String): Boolean {
        val cleanLink = link.trim().removeSuffix("/")
        val cleanBase = mainUrl.removeSuffix("/")
        val l = link.lowercase()
        val t = title.lowercase()

        return link.isNotBlank() &&
                title.isNotBlank() &&
                (link.startsWith(mainUrl) || !link.startsWith("http")) &&
                cleanLink != cleanBase &&
                !l.contains("linktre.online") &&
                !l.contains("paypal.com") &&
                !l.contains("/category/") &&
                !l.contains("/tag/") &&
                !l.contains("mailto:") &&
                !l.contains("javascript:") &&
                !t.contains("telegram") &&
                !t.contains("soporte") &&
                !t.contains("donar") &&
                !t.contains("apoya")
    }

    // =====================================================
    // MAIN PAGE
    // =====================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(
            request.data,
            referer = mainUrl,
            headers = buildHeaders(mainUrl)
        ).document

        val channels = parseChannels(doc)

        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Canales",
                    channels
                )
            ),
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val doc = app.get(
                mainUrl,
                referer = mainUrl,
                headers = buildHeaders(mainUrl)
            ).document

            parseChannels(doc)
                .filter { it.name.contains(query, ignoreCase = true) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // =====================================================
    // LOAD
    // =====================================================

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(
            url,
            referer = mainUrl,
            headers = buildHeaders(mainUrl)
        ).document

        val title = cleanName(
            doc.selectFirst("h1, h2, .title, .entry-title")?.text()
                ?: "Canal en Vivo"
        )

        val forbidden = listOf(
            "paypal", "pago", "donar", "pay.png", "qr", "cafecito",
            "mercado", "donate", "buy", "telegram", "whatsapp",
            "facebook", "twitter", "instagram", "share", "ads",
            "banner", "pixel", "button", "btn", "favicon"
        )

        var imgElement =
            doc.select("img.wp-post-image, img.attachment-post-thumbnail")
                .firstOrNull { img ->
                    val src = img.attr("src").lowercase()
                    src.isNotBlank() && forbidden.none { it in src }
                }

        if (imgElement == null) {
            val titleKeywords =
                title.lowercase()
                    .split(" ")
                    .filter { it.length > 3 }

            imgElement =
                doc.select(".entry-content img, .post-content img, article img")
                    .firstOrNull { img ->
                        val alt = img.attr("alt").lowercase()
                        val src = img.attr("src").lowercase()

                        titleKeywords.any { it in alt || it in src } &&
                                src.isNotBlank() &&
                                forbidden.none { it in src }
                    }
        }

        if (imgElement == null) {
            imgElement =
                doc.select(".entry-content img, .card-body img")
                    .firstOrNull { img ->
                        val src = img.attr("src").lowercase()
                        src.isNotBlank() && forbidden.none { it in src }
                    }
        }

        val rawImg =
            imgElement?.attr("abs:src")?.takeIf { it.isNotBlank() }
                ?: imgElement?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: imgElement?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: imgElement?.attr("src")?.takeIf { it.isNotBlank() }
                ?: ""

        val poster =
            if (rawImg.isNotBlank()) fixUrl(rawImg, url) else ""

        return newMovieLoadResponse(
            title,
            url,
            TvType.Live,
            url
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = "Canal de TV por Internet en vivo."
        }
    }

    // =====================================================
    // LOAD LINKS
    // =====================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val servers = getServersFromPage(data)

        var added = false
        var index = 1

        for ((serverUrl, serverReferer) in servers.distinctBy { it.first }) {
            val resolved =
                resolveGenericEmbed(serverUrl, serverReferer)
                    ?: if (isDirectVideo(serverUrl)) ResolvedLink(serverUrl, serverReferer) else null

            if (resolved != null && isDirectVideo(resolved.url)) {
                addLink(
                    source = "$name S$index",
                    linkName = "$name S$index",
                    url = resolved.url,
                    referer = resolved.referer,
                    callback = callback
                )

                added = true
                index++
            } else {
                val ok = loadExtractor(
                    serverUrl,
                    serverReferer,
                    subtitleCallback,
                    callback
                )

                if (ok) {
                    added = true
                    index++
                }
            }
        }

        return added
    }

    private suspend fun getServersFromPage(url: String): List<Pair<String, String>> {
        val servers = mutableListOf<Pair<String, String>>()

        try {
            val doc = app.get(
                url,
                referer = mainUrl,
                headers = buildHeaders(mainUrl)
            ).document

            doc.select("a").forEach { a ->
                val text = a.text().trim()
                val href = a.attr("abs:href").ifBlank { a.attr("href") }.trim()

                if (
                    href.isNotBlank() &&
                    (
                            text.contains("Opción", true) ||
                                    text.contains("Opcion", true) ||
                                    text.contains("Servidor", true) ||
                                    text.contains("Server", true) ||
                                    text.contains("FHD", true) ||
                                    text.contains("HD", true) ||
                                    text.contains("Ver", true) ||
                                    text.contains("Reproducir", true)
                            )
                ) {
                    servers.add(fixUrl(href, url) to url)
                }
            }

            doc.select("iframe").forEach { iframe ->
                val src =
                    iframe.attr("abs:src")
                        .ifBlank { iframe.attr("src") }
                        .ifBlank { iframe.attr("data-src") }
                        .trim()

                if (src.isNotBlank()) {
                    servers.add(fixUrl(src, url) to url)
                }
            }

            if (servers.isEmpty()) {
                servers.add(url to mainUrl)
            }

        } catch (_: Exception) {
            servers.add(url to mainUrl)
        }

        return servers
    }

    private suspend fun resolveGenericEmbed(
        pageUrl: String,
        referer: String?
    ): ResolvedLink? {
        return try {
            val realReferer = referer ?: pageUrl

            val html = app.get(
                pageUrl,
                referer = realReferer,
                headers = buildHeaders(realReferer)
            ).text

            val direct = extractVideoUrl(html)
            if (!direct.isNullOrBlank()) return ResolvedLink(direct, pageUrl)

            val decoded = extractBase64VideoUrl(html)
            if (!decoded.isNullOrBlank()) return ResolvedLink(decoded, pageUrl)

            resolveIframe(html, pageUrl)
                ?: resolveScriptSrc(html, pageUrl)

        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveIframe(
        html: String,
        baseUrl: String
    ): ResolvedLink? {
        val iframes =
            Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
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
                    headers = buildHeaders(baseUrl)
                ).text

                val direct = extractVideoUrl(html2)
                if (!direct.isNullOrBlank()) return ResolvedLink(direct, iframe)

                val decoded = extractBase64VideoUrl(html2)
                if (!decoded.isNullOrBlank()) return ResolvedLink(decoded, iframe)

                val nested = resolveIframe(html2, iframe)
                if (nested != null) return nested

                val script = resolveScriptSrc(html2, iframe)
                if (script != null) return script

            } catch (_: Exception) {
            }
        }

        return null
    }

    private suspend fun resolveScriptSrc(
        html: String,
        baseUrl: String
    ): ResolvedLink? {
        val scripts =
            Regex("""<script[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
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
                    headers = buildHeaders(baseUrl)
                ).text

                val direct = extractVideoUrl(js)
                if (!direct.isNullOrBlank()) return ResolvedLink(direct, script)

                val decoded = extractBase64VideoUrl(js)
                if (!decoded.isNullOrBlank()) return ResolvedLink(decoded, script)

                if (js.contains("eval(function", true)) {
                    val unpacked = JsUnpacker(js).unpack() ?: ""

                    val unpackedDirect = extractVideoUrl(unpacked)
                    if (!unpackedDirect.isNullOrBlank()) return ResolvedLink(unpackedDirect, script)

                    val unpackedDecoded = extractBase64VideoUrl(unpacked)
                    if (!unpackedDecoded.isNullOrBlank()) return ResolvedLink(unpackedDecoded, script)
                }

            } catch (_: Exception) {
            }
        }

        return null
    }

    private suspend fun addLink(
        source: String,
        linkName: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = getOrigin(referer) ?: getOrigin(url) ?: ""

        callback.invoke(
            newExtractorLink(
                source = source,
                name = linkName,
                url = url,
                type = if (url.contains(".m3u8", true))
                    ExtractorLinkType.M3U8
                else
                    ExtractorLinkType.VIDEO
            ) {
                quality = Qualities.Unknown.value
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to origin,
                    "Accept" to "*/*",
                    "Connection" to "keep-alive"
                )
            }
        )
    }

    // =====================================================
    // EXTRACT
    // =====================================================

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
            Regex("""sources\s*:\s*\[[\s\S]*?["']file["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),

            Regex("""src\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""videoUrl\s*=\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""hls\s*=\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        )

        for (regex in patterns) {
            val match = regex.find(clean) ?: continue
            val value =
                match.groupValues.getOrNull(1)?.ifBlank { match.value }
                    ?: match.value

            if (value.startsWith("http", true)) {
                return value
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("&amp;", "&")
                    .trim('"', '\'', ' ', '\n', '\r', '\t')
            }
        }

        val mp4 =
            Regex("""https?://[^"'\s<>]+?\.mp4[^"'\s<>]*""", RegexOption.IGNORE_CASE)
                .find(clean)
                ?.value

        if (!mp4.isNullOrBlank()) return mp4

        val flv =
            Regex("""https?://[^"'\s<>]+?\.flv[^"'\s<>]*""", RegexOption.IGNORE_CASE)
                .find(clean)
                ?.value

        return flv
    }

    private fun extractBase64VideoUrl(text: String): String? {
        val matches =
            Regex("""atob\(["']([^"']+)["']\)""")
                .findAll(text)
                .map { it.groupValues[1] }
                .toList()

        for (enc in matches) {
            try {
                var decoded =
                    String(Base64.decode(enc, Base64.DEFAULT), Charsets.UTF_8)

                repeat(4) {
                    val inner =
                        Regex("""atob\(["']([^"']+)["']\)""")
                            .find(decoded)
                            ?.groupValues
                            ?.getOrNull(1)

                    if (!inner.isNullOrBlank()) {
                        decoded =
                            String(Base64.decode(inner, Base64.DEFAULT), Charsets.UTF_8)
                    } else if (!decoded.startsWith("http")) {
                        try {
                            decoded =
                                String(Base64.decode(decoded, Base64.DEFAULT), Charsets.UTF_8)
                        } catch (_: Exception) {
                        }
                    }
                }

                extractVideoUrl(decoded)?.let { return it }

                val rawUrl =
                    Regex("""https?://[^"'\s<>]+""")
                        .find(decoded)
                        ?.value
                        ?.replace("\\/", "/")
                        ?.replace("\\u0026", "&")
                        ?.replace("&amp;", "&")

                if (
                    !rawUrl.isNullOrBlank() &&
                    isDirectVideo(rawUrl)
                ) {
                    return rawUrl
                }

            } catch (_: Exception) {
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

    // =====================================================
    // HELPERS
    // =====================================================

    private fun buildHeaders(referer: String): Map<String, String> {
        val origin = getOrigin(referer) ?: ""

        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Origin" to origin,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Connection" to "keep-alive"
        )
    }

    private fun fixUrl(url: String, baseUrl: String): String {
        val clean = url.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")

        return try {
            when {
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("http") -> clean
                else -> URI(baseUrl).resolve(clean).toString()
            }
        } catch (_: Exception) {
            when {
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("http") -> clean
                clean.startsWith("/") -> (getOrigin(baseUrl) ?: "") + clean
                else -> (getOrigin(baseUrl) ?: baseUrl).removeSuffix("/") + "/" + clean
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