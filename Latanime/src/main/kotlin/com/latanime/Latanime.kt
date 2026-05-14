package com.latanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Latanime : MainAPI() {
    override var mainUrl = "https://latanime.org"
    override var name = "Latanime"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    // =========================
    // HOME
    // =========================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Usa mainUrl + paginación básica (ajusta si el sitio usa ?page= o /page/)
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document  // o .documentLarge si prefieres timeout mayor

        val items = document.select("article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            listOf(
                HomePageList("Últimos Animes", items)
            )
        )
    }

    // =========================
    // SEARCH
    // =========================
    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return emptyList()

        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article").mapNotNull { it.toSearchResult() }
    }

    // =========================
    // LOAD
    // =========================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() ?: "Sin título"
        val poster = document.selectFirst("img")?.attr("abs:src")  // abs: para URL completa
        val plot = document.selectFirst("p")?.text() ?: ""

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            this.posterUrl = poster
            this.plot = plot
            // Puedes agregar más: year, tags, etc. si los parseas
        }
    }

    // =========================
    // EXTENSION FUNCTION
    // =========================
    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = selectFirst("h2")?.text() ?: return null
        val href = selectFirst("a")?.attr("abs:href") ?: return null  // abs:href completo
        val poster = selectFirst("img")?.attr("abs:src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }
}
