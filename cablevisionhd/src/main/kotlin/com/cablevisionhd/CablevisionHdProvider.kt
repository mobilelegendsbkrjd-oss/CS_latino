package com.cablevision

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.util.regex.Pattern

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
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"

    // ================================
    // BASIC LOAD
    // ================================

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, h2")?.text() ?: "Canal en Vivo"
        val poster = doc.selectFirst("img")?.attr("src") ?: ""

        return newMovieLoadResponse(title, url, TvType.Live, url) {
            this.posterUrl = fixUrl(poster)
            this.backgroundPosterUrl = fixUrl(poster)
            this.plot = "Transmisión en vivo"
        }
    }

    // ================================
    // DEX STYLE LOADLINKS
    // ================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var currentUrl = data
        var referer = mainUrl

        val maxDepth = 6

        repeat(maxDepth) { depth ->

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

            // 1️⃣ DIRECT M3U8
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                .find(html)
                ?.groupValues?.getOrNull(1)
                ?.let { url ->
                    callback.invoke(
                        ExtractorLink(
                            name,
                            name,
                            clean(url),
                            mainUrl,
                            Qualities.getQualityFromName("HD"),
                            isM3u8 = true,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to referer
                            )
                        )
                    )
                    return true
                }

            // 2️⃣ PACKED EVAL
            Regex("""eval\(function\(p,a,c,k,e,[^)]*\).*?\)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(html)
                .forEach { match ->

                    val unpacked = JsUnpacker(match.value).unpack() ?: return@forEach

                    Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                        .find(unpacked)
                        ?.groupValues?.getOrNull(1)
                        ?.let { url ->
                            callback.invoke(
                                ExtractorLink(
                                    name,
                                    name,
                                    clean(url),
                                    mainUrl,
                                    Qualities.getQualityFromName("HD"),
                                    isM3u8 = true
                                )
                            )
                            return true
                        }
                }

            // 3️⃣ BASE64 CASCADE
            Regex("""atob\(["']([^"']+)["']\)""")
                .findAll(html)
                .forEach { match ->
                    try {
                        var encoded = match.groupValues[1]
                        repeat(4) {
                            val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
                            if (decoded.contains(".m3u8")) {
                                callback.invoke(
                                    ExtractorLink(
                                        name,
                                        name,
                                        clean(decoded),
                                        mainUrl,
                                        Qualities.getQualityFromName("HD"),
                                        isM3u8 = true
                                    )
                                )
                                return true
                            }
                            encoded = decoded
                        }
                    } catch (_: Throwable) {}
                }

            // 4️⃣ IFRAME FOLLOW
            val iframe = document.selectFirst("iframe")?.attr("src")
            if (!iframe.isNullOrBlank()) {
                val nextUrl = if (iframe.startsWith("http")) iframe else fixUrl(iframe)
                if (nextUrl != currentUrl) {
                    referer = currentUrl
                    currentUrl = nextUrl
                    return@repeat
                }
            }

            return false
        }

        return false
    }

    private fun clean(raw: String): String {
        return raw.replace("\\/", "/")
            .replace("\\\"", "")
            .trim('"', '\'', ' ')
    }
}