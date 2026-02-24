package com.sololatino

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class SoloLatino : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    private val sagasJsonUrl = "https://raw.githubusercontent.com/mobilelegendsbkrjd-oss/lat_cs_bkrjd/main/ListasSL.json"

    @Suppress("DEPRECATION")
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = coroutineScope {
        val sections = listOf(
            "🔥 Sagas" to sagasJsonUrl,
            "🎥 Películas" to "$mainUrl/peliculas",
            "📺 Series" to "$mainUrl/series",
            "🌸 Animes" to "$mainUrl/animes",
            "🦁 Cartoons" to "$mainUrl/genre_series/toons",
            "💕 Doramas" to "$mainUrl/genre_series/kdramas/",
            "🎬 Netflix" to "$mainUrl/network/netflix/",
            "🟠 Amazon" to "$mainUrl/network/amazon/",
            "🐭 Disney+" to "$mainUrl/network/disney/",
            "🟣 HBO Max" to "$mainUrl/network/hbo-max/",
            "🍎 Apple TV" to "$mainUrl/network/apple-tv/",
            "🟢 Hulu" to "$mainUrl/network/hulu/"
        )

        val items = ArrayList<HomePageList>()

        // Carga por lotes de 3 para mantener velocidad sin bloqueos del servidor
        sections.chunked(3).forEach { batch ->
            val batchItems = batch.map { (name, url) ->
                async {
                    try {
                        val tvType = if (name == "🎥 Películas") TvType.Movie else TvType.TvSeries

                        val home = if (name == "🔥 Sagas") {
                            try {
                                val jsonText = app.get(url, timeout = 20).text.trim()
                                val sagas = mutableListOf<SearchResponse>()
                                val cleanJson = jsonText.removePrefix("[").removeSuffix("]").trim()
                                
                                if (cleanJson.isNotEmpty()) {
                                    val objetos = cleanJson.split("},").map { it.trim() + "}" }
                                    objetos.forEach { objStr ->
                                        try {
                                            val titleMatch = Regex(""""title"\s*:\s*"([^"]*)"""").find(objStr)
                                            val urlMatch = Regex(""""url"\s*:\s*"([^"]*)"""").find(objStr)
                                            val posterMatch = Regex(""""poster"\s*:\s*"([^"]*)"""").find(objStr)

                                            val title = titleMatch?.groupValues?.get(1) ?: return@forEach
                                            val link = urlMatch?.groupValues?.get(1) ?: return@forEach
                                            val poster = posterMatch?.groupValues?.get(1)

                                            sagas.add(newTvSeriesSearchResponse(title, link, tvType) { this.posterUrl = poster })
                                        } catch (e: Exception) {}
                                    }
                                }
                                sagas
                            } catch (e: Exception) { emptyList() }
                        } else {
                            val finalUrl = if (page > 1) "$url/page/$page/" else url
                            val doc = app.get(finalUrl, timeout = 30).document
                            doc.select("div.items article.item").mapNotNull {
                                val title = it.selectFirst("a div.data h3")?.text() ?: return@mapNotNull null
                                val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                                val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset")
                                newTvSeriesSearchResponse(title, link, tvType, true) { this.posterUrl = img }
                            }
                        }

                        if (home.isNotEmpty()) HomePageList(name, home) else null
                    } catch (e: Exception) { null }
                }
            }.awaitAll().filterNotNull()
            
            items.addAll(batchItems)
        }
        return@coroutineScope newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        return try {
            val doc = app.get(url).document
            doc.select("div.items article.item").mapNotNull {
                val title = it.selectFirst("a div.data h3")?.text() ?: return@mapNotNull null
                val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset")
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) { this.posterUrl = img }
            }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        if (url.contains("/listas/") && doc.selectFirst("div.infoCard") != null) {
            val title = doc.selectFirst("div.infoCard h1")?.text()?.trim() ?: "Saga"
            val description = doc.selectFirst("div.infoCard article p")?.text()?.trim() ?: ""
            
            val sagaItems = doc.select("div#archive-content article.item").mapNotNull { it ->
                val epurl = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epTitle = it.selectFirst("h3")?.text()?.trim() ?: ""
                val epYearText = it.selectFirst(".data p")?.text()?.trim() ?: ""
                var realimg: String? = it.selectFirst("div.poster img")?.attr("data-srcset")
                if (realimg.isNullOrBlank()) realimg = it.selectFirst("img")?.attr("data-src")

                val yearMatch = Regex("""(\d{4})""").find(epYearText)
                val epYear = yearMatch?.value?.toIntOrNull() ?: 0
                val isMovie = epurl.contains("/peliculas/") || epurl.contains("/episodios/")

                EpisodeData(epurl, epTitle, realimg, epYear, isMovie)
            }

            val sortedItems = sagaItems.sortedBy { it.year }
            val episodes = sortedItems.filter { it.isMovie }.mapIndexed { index, data ->
                newEpisode(data.url) {
                    name = data.title + if (data.year > 0) " (${data.year})" else ""
                    posterUrl = data.poster
                    season = 1
                    episode = index + 1
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = episodes.firstOrNull()?.posterUrl
                plot = description
            }
        }

        val tvType = if (url.contains("peliculas")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div.data h1")?.text() ?: ""
        val poster = doc.selectFirst("div.poster img")?.attr("data-src") ?: ""
        val backimage = doc.selectFirst(".wallpaper")?.attr("style")?.substringAfter("url(")?.substringBefore(");")
        val description = doc.selectFirst("div.wp-content")?.text() ?: ""

        val episodes = if (tvType == TvType.TvSeries) {
            doc.select("div#seasons div.se-c").flatMap { season ->
                season.select("ul.episodios li").mapNotNull {
                    val epurl = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val epTitle = it.selectFirst("div.episodiotitle div.epst")?.text() ?: ""
                    newEpisode(epurl) {
                        name = epTitle
                        posterUrl = it.selectFirst("div.imagen img")?.attr("data-src")
                    }
                }
            }
        } else emptyList()

        return when (tvType) {
            TvType.TvSeries -> newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backimage ?: poster
                this.plot = description
            }
            TvType.Movie -> newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backimage ?: poster
                this.plot = description
            }
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        try {
            val doc = app.get(data).document
            val iframeSrc = doc.selectFirst("iframe")?.attr("src") ?: return@coroutineScope false
            
            if (iframeSrc.startsWith("https://embed69.org/")) {
                Embed69Extractor.load(iframeSrc, data, subtitleCallback, callback)
            } else if (iframeSrc.startsWith("https://xupalace.org/video")) {
                val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
                val html = app.get(iframeSrc).text
                regex.findAll(html).map { it.groupValues.get(2) }.toList().map { link ->
                    async { loadExtractor(fixHostsLinks(link), data, subtitleCallback, callback) }
                }.awaitAll()
            } else {
                loadExtractor(fixHostsLinks(iframeSrc), data, subtitleCallback, callback)
            }
        } catch (e: Exception) {}
        return@coroutineScope true
    }

    private fun fixHostsLinks(url: String): String {
        return url.replaceFirst("https://hglink.to", "https://streamwish.to")
            .replaceFirst("https://swdyu.com", "https://streamwish.to")
            .replaceFirst("https://filemoon.link", "https://filemoon.sx")
            .replaceFirst("https://do7go.com", "https://dood.la")
            // ... (puedes añadir más si los necesitas)
    }

    private data class EpisodeData(val url: String, val title: String, val poster: String?, val year: Int, val isMovie: Boolean)
}