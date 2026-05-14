package com.cablevision

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class CablevisionHdProvider : MainAPI() {

    override var mainUrl = "https://www.cablevisionhd.com"
    override var name = "CablevisionHd"
    override var lang = "es"

    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "$mainUrl" to "Canales"
    )

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // =====================================================
    // PARSE CHANNELS
    // =====================================================

    private fun parseChannels(doc: Document): List<SearchResponse> {

        val results = mutableListOf<SearchResponse>()

        // =========================================
        // SCRIPT CHANNELS
        // =========================================

        doc.select("script").forEach { script ->

            val data = script.data()

            if (
                data.contains("homeChannels") ||
                data.contains("const channels")
            ) {

                try {

                    val htmlInsideScript =
                        data.substringAfter("`")
                            .substringBeforeLast("`")

                    if (htmlInsideScript.length > 100) {

                        val scriptDoc = Jsoup.parse(htmlInsideScript)

                        scriptDoc.select("a").forEach { a ->

                            val link = a.attr("href")

                            val title = a.text()
                                .trim()
                                .ifEmpty {
                                    a.selectFirst("img")
                                        ?.attr("alt")
                                        ?: ""
                                }

                            val imgElement =
                                a.select("img").firstOrNull { img ->

                                    val src =
                                        img.attr("src").lowercase()

                                    !src.contains("paypal") &&
                                            !src.contains("pago") &&
                                            !src.contains("donar") &&
                                            !src.contains("qr") &&
                                            src.isNotEmpty()
                                } ?: a.selectFirst("img")

                            val rawImg =
                                imgElement?.attr("src") ?: ""

                            val img =
                                if (rawImg.startsWith("http")) rawImg
                                else "$mainUrl/${rawImg.removePrefix("/")}"

                            if (isValidChannel(link, title)) {

                                val finalUrl =
                                    if (link.startsWith("http")) link
                                    else "$mainUrl/${link.removePrefix("/")}"

                                results.add(
                                    newMovieSearchResponse(
                                        title,
                                        finalUrl,
                                        TvType.Live
                                    ) {
                                        this.posterUrl = img
                                    }
                                )
                            }
                        }
                    }

                } catch (_: Exception) {
                }
            }
        }

        // =========================================
        // FALLBACK HTML
        // =========================================

        if (results.isEmpty()) {

            doc.select("a:has(img)").forEach { a ->

                val link =
                    a.attr("abs:href")
                        .ifEmpty { a.attr("href") }

                val imgElement =
                    a.select("img").firstOrNull { img ->

                        val src =
                            img.attr("src").lowercase()

                        !src.contains("paypal") &&
                                !src.contains("donar") &&
                                !src.contains("pago") &&
                                !src.contains("qr") &&
                                src.isNotEmpty()

                    } ?: a.selectFirst("img")

                val title =
                    imgElement?.attr("alt")?.trim()
                        ?: a.text().trim()

                val poster =
                    imgElement?.attr("abs:src")
                        ?: imgElement?.attr("src")
                        ?: ""

                if (isValidChannel(link, title)) {

                    results.add(
                        newMovieSearchResponse(
                            title,
                            link,
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

    // =====================================================
    // VALIDATE CHANNEL
    // =====================================================

    private fun isValidChannel(
        link: String,
        title: String
    ): Boolean {

        val cleanLink =
            link.trim().removeSuffix("/")

        val cleanBase =
            mainUrl.removeSuffix("/")

        return link.isNotEmpty() &&
                title.isNotEmpty() &&
                (link.startsWith(mainUrl) || !link.startsWith("http")) &&
                cleanLink != cleanBase &&
                !link.contains("linktre.online") &&
                !link.contains("paypal.com") &&
                !link.contains("/category/") &&
                !link.contains("/tag/") &&
                !title.contains("Telegram", true) &&
                !title.contains("Soporte", true)
    }

    // =====================================================
    // MAIN PAGE
    // =====================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get(request.data).document

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

    // =====================================================
    // SEARCH
    // =====================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        return try {

            val doc =
                app.get("$mainUrl/?s=$query").document

            parseChannels(doc)

        } catch (_: Exception) {
            emptyList()
        }
    }

    // =====================================================
    // LOAD
    // =====================================================

    override suspend fun load(
        url: String
    ): LoadResponse {

        val doc = app.get(url).document

        val title =
            doc.selectFirst(
                "h1, h2, .title, .entry-title"
            )?.text()
                ?: "Canal en Vivo"

        val forbidden = listOf(
            "paypal",
            "pago",
            "donar",
            "pay.png",
            "qr",
            "cafecito",
            "mercado",
            "donate",
            "buy",
            "telegram",
            "whatsapp",
            "facebook",
            "twitter",
            "instagram",
            "share",
            "ads",
            "banner",
            "pixel",
            "button",
            "btn",
            "favicon"
        )

        var imgElement =
            doc.select(
                "img.wp-post-image, img.attachment-post-thumbnail"
            ).firstOrNull { img ->

                val src =
                    img.attr("src").lowercase()

                forbidden.none { it in src }
            }

        if (imgElement == null) {

            val titleKeywords =
                title.lowercase()
                    .split(" ")
                    .filter { it.length > 3 }

            imgElement =
                doc.select(
                    ".entry-content img, .post-content img, article img"
                ).firstOrNull { img ->

                    val alt =
                        img.attr("alt").lowercase()

                    val src =
                        img.attr("src").lowercase()

                    titleKeywords.any {
                        it in alt || it in src
                    } && forbidden.none { it in src }
                }
        }

        if (imgElement == null) {

            imgElement =
                doc.select(
                    ".entry-content img, .card-body img"
                ).firstOrNull { img ->

                    val src =
                        img.attr("src").lowercase()

                    forbidden.none { it in src } &&
                            src.isNotEmpty()
                }
        }

        val rawImg =
            imgElement?.attr("abs:src")
                ?.ifEmpty {
                    imgElement?.attr("src")
                } ?: ""

        val poster =
            if (rawImg.startsWith("http")) rawImg
            else if (rawImg.isNotEmpty())
                "$mainUrl/${rawImg.removePrefix("/")}"
            else ""

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

        var currentUrl = data
        var currentReferer = mainUrl
        var depth = 0

        val patterns = listOf(

            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),

            Regex("""source\s*:\s*["']([^"']+)["']"""),

            Regex("""file\s*:\s*["']([^"']+)["']"""),

            Regex("""var\s+src\s*=\s*["']([^"']+)["']"""),

            Regex("""["'](https?://[^"']+\.mp4[^"']*)["']"""),

            Regex("""src\s*:\s*["']([^"']+)["']""")
        )

        while (depth < 6) {

            depth++

            try {

                val response = app.get(
                    currentUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to currentReferer,
                        "Origin" to mainUrl
                    )
                )

                val html = response.text
                val document = response.document

                // =========================================
                // DIRECT PATTERNS
                // =========================================

                for (pattern in patterns) {

                    pattern.find(html)?.let { match ->

                        val foundUrl =
                            clean(match.groupValues[1])

                        if (foundUrl.startsWith("http")) {

                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = name,
                                    url = foundUrl,
                                    type = INFER_TYPE
                                ) {
                                    headers = mapOf(
                                        "Referer" to currentUrl,
                                        "User-Agent" to USER_AGENT
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

                document.select("script").forEach { script ->

                    val scriptData = script.data()

                    if (scriptData.contains("eval(function")) {

                        val unpacked =
                            JsUnpacker(scriptData).unpack()
                                ?: ""

                        for (pattern in patterns) {

                            pattern.find(unpacked)?.let { match ->

                                val foundUrl =
                                    clean(match.groupValues[1])

                                if (foundUrl.startsWith("http")) {

                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = name,
                                            url = foundUrl,
                                            type = INFER_TYPE
                                        ) {
                                            headers = mapOf(
                                                "Referer" to currentUrl,
                                                "User-Agent" to USER_AGENT
                                            )
                                        }
                                    )

                                    return true
                                }
                            }
                        }
                    }
                }

                // =========================================
                // BASE64 CASCADE
                // =========================================

                if (
                    html.contains("const decodedURL") ||
                    html.contains("atob(")
                ) {

                    document.select("script").forEach { s ->

                        val dataScript = s.data()

                        if (dataScript.contains("atob(")) {

                            try {

                                val enc =
                                    dataScript.substringAfter("atob(\"")
                                        .substringBefore("\")")

                                var dec =
                                    String(
                                        Base64.decode(
                                            enc,
                                            Base64.DEFAULT
                                        )
                                    )

                                repeat(3) {

                                    if (dec.contains("atob(")) {

                                        val innerEnc =
                                            dec.substringAfter("atob(\"")
                                                .substringBefore("\")")

                                        dec = String(
                                            Base64.decode(
                                                innerEnc,
                                                Base64.DEFAULT
                                            )
                                        )

                                    } else if (!dec.startsWith("http")) {

                                        try {

                                            dec = String(
                                                Base64.decode(
                                                    dec,
                                                    Base64.DEFAULT
                                                )
                                            )

                                        } catch (_: Exception) {
                                        }
                                    }
                                }

                                if (dec.startsWith("http")) {

                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = name,
                                            url = clean(dec),
                                            type = INFER_TYPE
                                        ) {
                                            headers = mapOf(
                                                "Referer" to currentUrl,
                                                "User-Agent" to USER_AGENT
                                            )
                                        }
                                    )

                                    return true
                                }

                            } catch (_: Exception) {
                            }
                        }
                    }
                }

                // =========================================
                // IFRAME FOLLOW
                // =========================================

                val iframes =
                    document.select("iframe")

                val nextIframe =
                    iframes.firstOrNull {
                        it.attr("src").isNotEmpty()
                    }?.attr("src")
                        ?: iframes.firstOrNull {
                            it.attr("data-src").isNotEmpty()
                        }?.attr("data-src")
                        ?: ""

                if (
                    nextIframe.isNotEmpty() &&
                    nextIframe != currentUrl
                ) {

                    currentReferer = currentUrl

                    currentUrl =
                        if (nextIframe.startsWith("http"))
                            nextIframe
                        else
                            "$mainUrl/${nextIframe.removePrefix("/")}"

                } else {
                    break
                }

            } catch (_: Exception) {
                break
            }
        }

        return false
    }

    // =====================================================
    // CLEAN URL
    // =====================================================

    private fun clean(raw: String): String {

        return raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\\"", "")
            .replace("&amp;", "&")
            .trim('"', '\'', ' ')
    }
}
