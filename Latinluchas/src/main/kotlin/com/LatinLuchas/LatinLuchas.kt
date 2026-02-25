package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.VidStack
import org.json.JSONObject

// ===============================
// Bysekoze Extractor integrado
// ===============================
class Bysekoze : ExtractorApi() {

    override val name = "Bysekoze"
    override val mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val headers = mapOf(
            "Referer" to (referer ?: mainUrl),
            "User-Agent" to USER_AGENT
        )

        try {
            val id = Regex("/e/([a-zA-Z0-9]+)")
                .find(url)?.groupValues?.getOrNull(1)

            if (!id.isNullOrEmpty()) {
                val apiUrl = "$mainUrl/api/videos/$id/embed/playback"
                val response = app.get(apiUrl, headers = headers).text
                val json = JSONObject(response)

                if (json.has("sources")) {
                    val sources = json.getJSONArray("sources")

                    for (i in 0 until sources.length()) {
                        val obj = sources.getJSONObject(i)
                        val link = obj.getString("url")

                        callback.invoke(
                            newExtractorLink(
                                name,
                                name,
                                link
                            ) {
                                this.referer = mainUrl
                                this.isM3u8 = link.contains(".m3u8")
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                    return
                }
            }
        } catch (_: Exception) {
        }

        // Fallback antiguo
        try {
            val document = app.get(url, headers = headers).document
            val packedScript = document
                .selectFirst("script:containsData(function(p,a,c,k,e,d))")
                ?.data()
                .orEmpty()

            JsUnpacker(packedScript).unpack()?.let { unpacked ->
                Regex("""sources:\[\{file:"(.*?)"""")
                    .find(unpacked)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { link ->

                        callback.invoke(
                            newExtractorLink(
                                name,
                                name,
                                link
                            ) {
                                this.referer = url
                                this.isM3u8 = link.contains(".m3u8")
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
            }
        } catch (_: Exception) {
        }
    }
}

// ===============================
// MAIN EXTENSION
// ===============================
class LatinLuchas : MainAPI() {

    override val mainUrl = "https://latinluchas.com"
    override val name = "LatinLuchas"
    override val lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val defaultPoster =
        "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        val categories = listOf(
            "WWE" to "$mainUrl/category/eventos/wwe/",
            "UFC" to "$mainUrl/category/eventos/ufc/",
            "AEW" to "$mainUrl/category/eventos/aew/",
            "Lucha Libre Mexicana" to "$mainUrl/category/eventos/lucha-libre-mexicana/",
            "Indies" to "$mainUrl/category/eventos/indies/"
        )

        val home = categories.map { (catName, url) ->

            val doc = app.get(url).document

            val items = doc.select("article, .post, .elementor-post")
                .mapNotNull { element ->

                    val title = element
                        .selectFirst("h2, h3, .entry-title")
                        ?.text()?.trim()
                        ?: return@mapNotNull null

                    val href = element
                        .selectFirst("a")
                        ?.attr("abs:href")
                        ?: return@mapNotNull null

                    val poster = element
                        .selectFirst("img")
                        ?.attr("abs:src")
                        ?: defaultPoster

                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }

            HomePageList(catName, items)
        }

        return newHomePageResponse(home)
    }

    override suspend fun load(url: String): LoadResponse? {

        val document = app.get(url).document

        val title = document
            .selectFirst("h1.entry-title")
            ?.text()?.trim()
            ?: "Evento"

        val poster =
            document.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?: defaultPoster

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            this.posterUrl = poster
            this.plot =
                document.selectFirst("meta[property=og:description]")
                    ?.attr("content")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        document.select("iframe").forEach { iframe ->

            var src = iframe.attr("abs:src")
                .ifBlank { iframe.attr("abs:data-src") }
                .ifBlank { iframe.attr("src") }

            if (src.isBlank()) return@forEach
            if (src.startsWith("//")) src = "https:$src"

            if (src.contains(
                    Regex("facebook|google|ads|twitter|instagram",
                        RegexOption.IGNORE_CASE)
                )
            ) return@forEach

            when {
                src.contains("bysekoze.com") -> {
                    Bysekoze().getUrl(src, data, subtitleCallback, callback)
                }

                src.contains("upns.online") -> {
                    VidStack().getUrl(src, data, subtitleCallback, callback)
                }

                else -> {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}