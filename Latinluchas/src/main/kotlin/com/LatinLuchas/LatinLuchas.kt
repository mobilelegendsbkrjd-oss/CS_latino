// Latinluchas/src/main/kotlin/com/latinluchas/LatinLuchas.kt

package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class LatinLuchas : MainAPI() {
    override val name = "TV LatinLuchas"
    override val mainUrl = "https://tv.latinluchas.com/tv"
    override val hasMainPage = true
    override val lang = "es"
    override val supportedTypes = setOf(TvType.Live)

    private val defaultPoster = "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse { list(emptyList()) }

        val document = app.get(mainUrl).document

        val home = document.select("article, .elementor-post, .post, a[href*='/tv/coli']").mapNotNull { element ->
            val href = element.attr("abs:href").takeIf { it.contains("/tv/coli") } ?: return@mapNotNull null
            val title = element.selectFirst("h2, h3, .entry-title, a")?.text()?.trim() ?: "Evento sin título"

            newSearchResponse(title, href, TvType.Live)
        }.distinctBy { it.url }

        return newHomePageResponse {
            name = "Eventos y Repeticiones"
            list(home)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.title().substringBefore(" - TV LatinLuchas").trim()
            .ifBlank { "Evento en vivo" }

        val plot = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst(".elementor-widget-container p, .elementor-text-editor")?.text()
            ?: "Repetición o transmisión en vivo - TV LatinLuchas"

        return newLiveStreamLoadResponse(title) {
            this.url = url
            apiName = name
            type = TvType.Live
            posterUrl = defaultPoster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundAny = false

        document.select("iframe[src]").forEach { iframe ->
            var src = iframe.attr("abs:src").trim()
            if (src.isBlank()) return@forEach
            if (src.startsWith("//")) src = "https:$src"

            // Delegamos todo a loadExtractor (como en SoloLatino y Tlnovelas)
            if (loadExtractor(src, data, subtitleCallback, callback)) {
                foundAny = true
            }
        }

        return foundAny
    }
}