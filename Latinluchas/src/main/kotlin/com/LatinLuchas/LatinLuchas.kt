package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.FilemoonV2
import com.lagradost.cloudstream3.extractors.VidStack

// =========================
// Extractor UPNS
// =========================
class LatinLuchaUpns : VidStack() {
    override var name = "LatinLucha Server"
    override var mainUrl = "https://latinlucha.online"
}

// =========================
// Extractor BYSEKOZE (Filemoon backend)
// =========================
class Bysekoze : FilemoonV2() {
    override var name = "Bysekoze"
    override var mainUrl = "https://bysekoze.com"
}

// =========================
// MAIN API
// =========================
class LatinLuchas : MainAPI() {

    override var mainUrl = "https://latinluchas.com"
    override var name = "LatinLuchas"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val defaultPoster =
        "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    // =========================
    // HOMEPAGE
    // =========================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        val categories = listOf(
            Pair("WWE", "$mainUrl/category/eventos/wwe/"),
            Pair("UFC", "$mainUrl/category/eventos/ufc/"),
            Pair("AEW", "$mainUrl/category/eventos/aew/"),
            Pair("Lucha Libre Mexicana", "$mainUrl/category/eventos/lucha-libre-mexicana/"),
            Pair("Indies", "$mainUrl/category/eventos/indies/")
        )

        val homePages = categories.map { (title, url) ->
            val doc = app.get(url).document

            val items = doc.select("article, .post, .elementor-post")
                .mapNotNull { element ->

                    val name = element
                        .selectFirst("h2, h3, .entry-title")
                        ?.text()
                        ?.trim()
                        ?: return@mapNotNull null

                    val href = element
                        .selectFirst("a")
                        ?.attr("abs:href")
                        ?: return@mapNotNull null

                    val poster = element
                        .selectFirst("img")
                        ?.attr("abs:src")
                        ?: defaultPoster

                    newAnimeSearchResponse(name, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                }

            HomePageList(title, items)
        }

        return newHomePageResponse(homePages)
    }

    // =========================
    // LOAD EVENT
    // =========================
    override suspend fun load(url: String): LoadResponse? {

        val document = app.get(url).document

        val title = document
            .selectFirst("h1.entry-title")
            ?.text()
            ?.trim()
            ?: "Evento"

        val poster = document
            .selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?: defaultPoster

        val plot = document
            .selectFirst("meta[property='og:description']")
            ?.attr("content")

        val episodes = document
            .select("a.btn-video")
            .mapNotNull { anchor ->

                val name = anchor.text().trim()
                val link = anchor.attr("abs:href")

                if (link.isBlank() || name.contains("DESCARGA", true))
                    null
                else
                    newEpisode(link) {
                        this.name = name
                    }
            }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // =========================
    // LOAD LINKS
    // =========================
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
                    Regex("facebook|google|ads|twitter|instagram", RegexOption.IGNORE_CASE)
                )
            ) return@forEach

            when {

                // BYSEKOZE (Filemoon backend)
                src.contains("bysekoze.com") ->
                    Bysekoze().getUrl(src, data, subtitleCallback, callback)

                // UPNS
                src.contains("upns.online") || src.contains("latinlucha.online") ->
                    LatinLuchaUpns().getUrl(src, data, subtitleCallback, callback)

                // Otros (OK.ru, etc)
                else ->
                    loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }
}