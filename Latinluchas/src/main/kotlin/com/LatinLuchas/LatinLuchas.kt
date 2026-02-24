package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.VidStack

// ===============================
// 🔥 Extractor especial UPNS / UNS / CHERRY / BYZEKOSE
// ===============================
class LatinLuchaUpns : VidStack() {
    override var name = "LatinLucha Server"
    override var mainUrl = "https://latinlucha.upns.online"
}

// ===============================
// 🔥 MAIN API
// ===============================
class LatinLuchas : MainAPI() {

    override var mainUrl = "https://latinluchas.com"
    override var name = "LatinLuchas"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val defaultPoster =
        "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    // ==========================================
    // 🏠 HOMEPAGE
    // ==========================================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        val categories = listOf(
            Pair("En Vivo Hoy", "$mainUrl/en-vivo/"),
            Pair("WWE", "$mainUrl/category/eventos/wwe/"),
            Pair("UFC", "$mainUrl/category/eventos/ufc/"),
            Pair("AEW", "$mainUrl/category/eventos/aew/"),
            Pair("Lucha Libre Mexicana", "$mainUrl/category/eventos/lucha-libre-mexicana/"),
            Pair("Indies", "$mainUrl/category/eventos/indies/")
        )

        val homePages = categories.map { (sectionName, url) ->

            val doc = app.get(url).document

            val items = if (url.contains("/en-vivo/")) {

                // 🔥 Extraemos los enlaces del script JS
                val html = doc.html()
                val regex = Regex("""href="(https://latinluchas\.com/[^"]+)"""")

                regex.findAll(html).map { match ->
                    val link = match.groupValues[1]

                    newAnimeSearchResponse(
                        link.substringAfter("latinluchas.com/")
                            .replace("-", " ")
                            .uppercase(),
                        link,
                        TvType.TvSeries
                    ) {
                        this.posterUrl = defaultPoster
                    }
                }.distinctBy { it.url }

            } else {

                doc.select("article, .post, .elementor-post")
                    .mapNotNull { element ->
                        val title = element.selectFirst("h2, h3, .entry-title")
                            ?.text()?.trim()
                            ?: return@mapNotNull null

                        val href = element.selectFirst("a")
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
            }

            HomePageList(sectionName, items)
        }

        return newHomePageResponse(homePages)
    }

    // ==========================================
    // 📄 LOAD (REPETICIONES + EN VIVO)
    // ==========================================
    override suspend fun load(url: String): LoadResponse? {

        val document = app.get(url).document
        val html = document.html()

        val title =
            document.selectFirst("h1.entry-title")
                ?.text()?.trim()
                ?: "LatinLuchas"

        val poster =
            document.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?: defaultPoster

        val episodes = mutableListOf<Episode>()

        // =====================================
        // 🔥 CASO 1: REPETICIONES (botones OPCION)
        // =====================================
        document.select("a.btn-video").forEach { anchor ->
            val link = anchor.attr("abs:href")
            val name = anchor.text().trim()

            if (!link.contains("descarga", true)) {
                episodes.add(
                    newEpisode(link) {
                        this.name = name
                    }
                )
            }
        }

        // =====================================
        // 🔥 CASO 2: EN VIVO (extraer del script JS)
        // =====================================
        if (episodes.isEmpty()) {

            val regex = Regex("""href="(https://latinluchas\.com/[^"]+)"""")

            regex.findAll(html).forEach { match ->
                val link = match.groupValues[1]

                episodes.add(
                    newEpisode(link) {
                        this.name = link
                            .substringAfter("latinluchas.com/")
                            .replace("-", " ")
                            .uppercase()
                    }
                )
            }
        }

        // =====================================
        // SI NO HAY NADA
        // =====================================
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = "Próximamente"
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
        }
    }

    // ==========================================
    // 🔗 LOAD LINKS (SERVIDORES)
    // ==========================================
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

            when {

                // 🔥 BYZEKOSE / UPNS / UNS / CHERRY
                src.contains("upns", true) ||
                src.contains("uns.wtf", true) ||
                src.contains("cherry", true) ||
                src.contains("byzekose", true) -> {

                    LatinLuchaUpns().getUrl(
                        src,
                        data,
                        subtitleCallback,
                        callback
                    )
                }

                // OK.RU y otros soportados por Cloudstream
                else -> {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}