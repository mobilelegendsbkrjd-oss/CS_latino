package com.cablevision

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CablevisionHdProvider : MainAPI() {

    override var mainUrl = "https://www.cablevisionhd.com"
    override var name = "CablevisionHd"
    override var lang = "es"

    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Live)

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // =========================================
    // LOAD
    // =========================================

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title = doc.selectFirst(
            "h1, h2, .entry-title, .title"
        )?.text()?.trim() ?: "Canal en Vivo"

        val poster = doc.selectFirst(
            "img.wp-post-image, img.attachment-post-thumbnail, article img, img"
        )?.attr("abs:src")
            ?.ifBlank {
                doc.selectFirst(
                    "img.wp-post-image, img.attachment-post-thumbnail, article img, img"
                )?.attr("src")
            } ?: ""

        return newMovieLoadResponse(
            title,
            url,
            TvType.Live,
            url
        ) {
            this.posterUrl = fixUrlNull(poster)
            this.backgroundPosterUrl = fixUrlNull(poster)
            this.plot = "Transmisión en vivo"
        }
    }

    // =========================================
    // LOAD LINKS
    // =========================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var currentUrl = data
        var referer = mainUrl

        val maxDepth = 6

        val patterns = listOf(

            // DIRECT M3U8
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),

            // DIRECT MP4
            Regex("""["'](https?://[^"']+\.mp4[^"']*)["']"""),

            // PLAYER CONFIGS
            Regex("""source\s*:\s*["']([^"']+)["']"""),
            Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']"""),
            Regex("""file\s*:\s*["']([^"']+)["']"""),

            // VARIABLES
            Regex("""var\s+src\s*=\s*["']([^"']+)["']"""),
            Regex("""src\s*:\s*["']([^"']+)["']"""),

            // JSON
            Regex(""""url"\s*:\s*"([^"]+)""""),
            Regex(""""file"\s*:\s*"([^"]+)"""")
        )

        repeat(maxDepth) {

            try {

                val response = app.get(
                    currentUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "Origin" to mainUrl
                    )
                )

                val html = response.text
                val document = response.document

                // =========================================
                // DIRECT SEARCH
                // =========================================

                for (pattern in patterns) {

                    pattern.find(html)?.let { match ->

                        val found = clean(match.groupValues[1])

                        if (
                            found.startsWith("http") &&
                            (
                                    found.contains(".m3u8") ||
                                            found.contains(".mp4") ||
                                            found.contains("stream") ||
                                            found.contains("playlist")
                                    )
                        ) {

                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = name,
                                    url = found,
                                    type = INFER_TYPE
                                ) {
                                    headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Referer" to referer,
                                        "Origin" to mainUrl
                                    )
                                }
                            )

                            return true
                        }
                    }
                }

                // =========================================
                // PACKED EVAL
                // =========================================

                Regex(
                    """eval\(function\(p,a,c,k,e,[^)]*\).*?\)""",
                    RegexOption.DOT_MATCHES_ALL
                )
                    .findAll(html)
                    .forEach { match ->

                        try {

                            val unpacked =
                                JsUnpacker(match.value).unpack() ?: return@forEach

                            for (pattern in patterns) {

                                pattern.find(unpacked)?.let { result ->

                                    val found = clean(result.groupValues[1])

                                    if (
                                        found.startsWith("http") &&
                                        (
                                                found.contains(".m3u8") ||
                                                        found.contains(".mp4")
                                                )
                                    ) {

                                        callback.invoke(
                                            newExtractorLink(
                                                source = name,
                                                name = name,
                                                url = found,
                                                type = INFER_TYPE
                                            ) {
                                                headers = mapOf(
                                                    "User-Agent" to USER_AGENT,
                                                    "Referer" to referer
                                                )
                                            }
                                        )

                                        return true
                                    }
                                }
                            }

                        } catch (_: Throwable) {
                        }
                    }

                // =========================================
                // BASE64 CASCADE
                // =========================================

                Regex("""atob\(["']([^"']+)["']\)""")
                    .findAll(html)
                    .forEach { match ->

                        try {

                            var encoded = match.groupValues[1]

                            repeat(6) {

                                val decoded = try {
                                    String(Base64.decode(encoded, Base64.DEFAULT))
                                } catch (_: Throwable) {
                                    return@repeat
                                }

                                // DIRECT STREAM
                                if (
                                    decoded.contains(".m3u8") ||
                                    decoded.contains(".mp4")
                                ) {

                                    val stream =
                                        Regex("""https?://[^\s"'\\]+""")
                                            .find(decoded)
                                            ?.value

                                    if (!stream.isNullOrBlank()) {

                                        callback.invoke(
                                            newExtractorLink(
                                                source = name,
                                                name = name,
                                                url = clean(stream),
                                                type = INFER_TYPE
                                            ) {
                                                headers = mapOf(
                                                    "User-Agent" to USER_AGENT,
                                                    "Referer" to referer
                                                )
                                            }
                                        )

                                        return true
                                    }
                                }

                                // NESTED ATOB
                                if (decoded.contains("atob(")) {

                                    val nested =
                                        Regex("""atob\(["']([^"']+)["']\)""")
                                            .find(decoded)
                                            ?.groupValues
                                            ?.getOrNull(1)

                                    if (!nested.isNullOrBlank()) {
                                        encoded = nested
                                        return@repeat
                                    }
                                }

                                encoded = decoded
                            }

                        } catch (_: Throwable) {
                        }
                    }

                // =========================================
                // IFRAME FOLLOW
                // =========================================

                val iframe =
                    document.selectFirst("iframe[src]")?.attr("src")
                        ?: document.selectFirst("iframe[data-src]")?.attr("data-src")

                if (!iframe.isNullOrBlank()) {

                    val nextUrl =
                        if (iframe.startsWith("http")) iframe
                        else fixUrl(iframe)

                    if (
                        nextUrl.isNotBlank() &&
                        nextUrl != currentUrl
                    ) {

                        referer = currentUrl
                        currentUrl = nextUrl

                        return@repeat
                    }
                }

                // =========================================
                // EMBED SEARCH
                // =========================================

                val embed =
                    Regex("""https?://[^\s"'\\]+(?:embed|player|stream)[^\s"'\\]*""")
                        .find(html)
                        ?.value

                if (
                    !embed.isNullOrBlank() &&
                    embed != currentUrl
                ) {

                    referer = currentUrl
                    currentUrl = clean(embed)

                    return@repeat
                }

                return false

            } catch (_: Throwable) {
                return false
            }
        }

        return false
    }

    // =========================================
    // CLEAN URLS
    // =========================================

    private fun clean(raw: String): String {

        return raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\\"", "")
            .replace("&amp;", "&")
            .trim('"', '\'', ' ')
    }
}
