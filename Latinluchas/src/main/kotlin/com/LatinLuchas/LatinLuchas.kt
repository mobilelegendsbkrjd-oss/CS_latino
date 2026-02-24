package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.VidStack

// ===============================
// Extractor específico para Upns
// ===============================
class LatinLuchaUpns : VidStack() {
    override var name = "LatinLucha Upns"
    override var mainUrl = "https://latinlucha.upns.online"
}

// ===============================
// MAIN API
// ===============================
class LatinLuchas : MainAPI() {

    override var mainUrl = "https://latinluchas.com"
    override var name = "LatinLuchas"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val defaultPoster =
        "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    // ===============================
    // HOMEPAGE (categorías reales)
    // ===============================
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

        val homePages = categories.map { (sectionName, sectionUrl) ->
            val doc = app.get(sectionUrl).document

            val items = doc.select("article, .post, .elementor-post")
                .mapNotNull { element ->
                    val title = element
                        .selectFirst("h2, h3, .entry-title")
                        ?.text()
                        ?.trim()
                        ?: return@mapNotNull null

                    val href = element
                        .selectFirst("a")
                        ?.attr("abs:href")
                        ?: return@mapNotNull null

                    val poster =
                        element.selectFirst("img")?.attr("abs:src")
                            ?: element.selectFirst("img")?.attr("abs:data-src")
                            ?: defaultPoster

                    newAnimeSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                }

            HomePageList(sectionName, items)
        }

        return newHomePageResponse(homePages)
    }

    // ===============================
    // LOAD (evento / repetición / en vivo)
    // ===============================
    override suspend fun load(url: String): LoadResponse? {

        val document = app.get(url).document

        val title =
            document.selectFirst("h1.entry-title")
                ?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    ?.substringBefore(" - LATINLUCHAS")
                ?: "Evento"

        val poster =
            document.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?: defaultPoster

        val plot =
            document.selectFirst("meta[property=og:description]")
                ?.attr("content")

        val episodes = mutableListOf<Episode>()

        // ============================
        // 🟢 REPETICIONES (btn-video)
        // ============================
        document.select("a.btn-video").forEach { anchor ->
            val name = anchor.text().trim()
            val link = anchor.attr("abs:href")

            if (link.isNotBlank() && !name.contains("DESCARGA", true)) {
                episodes.add(
                    newEpisode(link) {
                        this.name = name
                    }
                )
            }
        }

        // ============================
        // 🟡 EN VIVO (button-es / en)
        // ============================
        if (episodes.isEmpty()) {
            document.select("a.button-es, a.button-en").forEach { anchor ->
                val name = anchor.text().trim()
                val link = anchor.attr("abs:href")

                if (link.isNotBlank()) {
                    episodes.add(
                        newEpisode(link) {
                            this.name = name
                        }
                    )
                }
            }
        }

        // ============================
        // 🔴 CANAL DIRECTO
        // ============================
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = "VER CANAL"
                }
            )
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes.distinctBy { it.data }
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ===============================
    // LOAD LINKS (reproductor real)
    // ===============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        var found = false

        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("abs:src")
                .ifBlank { iframe.attr("abs:data-src") }
                .ifBlank { iframe.attr("src") }

            if (src.isBlank()) return@forEach

            if (src.startsWith("//"))
                src = "https:$src"

            when {
                src.contains("upns.online") -> {
                    LatinLuchaUpns().getUrl(src, data, subtitleCallback, callback)
                    found = true
                }

                else -> {
                    if (loadExtractor(src, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }
        }

        return found
    }
}