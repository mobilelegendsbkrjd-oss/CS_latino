package com.tioanime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.util.*

class TioAnimeProvider : MainAPI() {

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("OVA") || t.contains("Especial") -> TvType.OVA
                t.contains("Película") -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }
    }

    override var mainUrl = "https://tioanime.com"
    override var name = "TioAnime"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Anime,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val latest = app.get(mainUrl).document.select("ul.episodes li article").mapNotNull {
            val title = it.selectFirst("h3.title")?.text()?.replace(Regex("((\\d+)\$)"), "") ?: return@mapNotNull null
            val poster = it.selectFirst("figure img")?.attr("src")
            val epRegex = Regex("(-(\\d+)\$)")
            val url = it.selectFirst("a")?.attr("href")
                ?.replace(epRegex, "")
                ?.replace("ver/", "anime/") ?: return@mapNotNull null
            val urlepnum = it.selectFirst("a")?.attr("href")
            val epNum = epRegex.find(urlepnum ?: "")?.groupValues?.getOrNull(2)?.toIntOrNull()
            val dubstat =
                if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed

            newAnimeSearchResponse(title, fixUrl(url)) {
                posterUrl = fixUrl(poster ?: "")
                addDubStatus(dubstat, epNum)
            }
        }

        return newHomePageResponse("Últimos episodios", latest)
    }

    data class SearchObject(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("last_id") val lastId: String?,
        @JsonProperty("slug") val slug: String
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "https://tioanime.com/api/search",
            data = mapOf("value" to query)
        ).text

        val json = parseJson<List<SearchObject>>(response)
        return json.map {
            val href = "$mainUrl/anime/${it.slug}"
            val image = "$mainUrl/uploads/portadas/${it.id}.jpg"
            newAnimeSearchResponse(it.title, href) {
                posterUrl = fixUrl(image)
                addDubStatus(
                    if (it.title.contains("Latino") || it.title.contains("Castellano"))
                        DubStatus.Dubbed else DubStatus.Subbed
                )
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val episodes = ArrayList<Episode>()

        val title = doc.selectFirst("h1.Title")?.text() ?: "Sin título"
        val poster = doc.selectFirst("div.thumb img")?.attr("src")
        val description = doc.selectFirst("p.sinopsis")?.text()
        val type = doc.selectFirst("span.anime-type-peli")?.text() ?: ""
        val status = when (doc.selectFirst("div.thumb a.btn.status i")?.text()) {
            "En emision" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val genre = doc.select("p.genres a").map { it.text().trim() }
        val year = doc.selectFirst("span.year")?.text()?.toIntOrNull()

        doc.select("script").forEach { script ->
            if (script.data().contains("var episodes = [")) {
                val data = script.data().substringAfter("var episodes = [").substringBefore("];")
                data.split("],").forEach {
                    it.split(",").forEach { epNum ->
                        val link = url.replace("/anime/", "/ver/") + "-$epNum"
                        episodes.add(
                            newEpisode(link) {
                                name = "Capítulo $epNum"
                                episode = epNum.toIntOrNull()
                            }
                        )
                    }
                }
            }
        }

        return newAnimeLoadResponse(title, url, getType(type)) {
            posterUrl = fixUrl(poster ?: "")
            addEpisodes(DubStatus.Subbed, episodes.reversed())
            showStatus = status
            plot = description
            tags = genre
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("script").forEach { script ->
            if (script.data().contains("var videos =") || script.data().contains("server")) {
                val videos = script.data().replace("\\/", "/")
                fetchUrls(videos).map {
                    it.replace("https://embedsb.com/e/", "https://watchsb.com/e/")
                        .replace("https://ok.ru", "http://ok.ru")
                }.forEach {
                    loadExtractor(it, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
