package com.granpirata

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.*

class GranPirata : MainAPI() {
    override var mainUrl = "https://granpirata.com"
    override var name = "Gran Pirata"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document

        val movies = doc.select("article.item.movies").mapNotNull { item ->
            val title = item.selectFirst("h3 a")?.text() ?: return@mapNotNull null
            val url = fixUrl(item.selectFirst("a")?.attr("href") ?: "")
            val poster = fixUrl(item.selectFirst("img")?.attr("src") ?: "")
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        }

        val series = doc.select("article.item.tvshows").mapNotNull { item ->
            val title = item.selectFirst("h3 a")?.text() ?: return@mapNotNull null
            val url = fixUrl(item.selectFirst("a")?.attr("href") ?: "")
            val poster = fixUrl(item.selectFirst("img")?.attr("src") ?: "")
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        }

        return newHomePageResponse(listOf(
            HomePageList("Películas", movies),
            HomePageList("Series", series)
        ))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article.item").mapNotNull { item ->
            val title = item.selectFirst("h3 a")?.text() ?: return@mapNotNull null
            val url = fixUrl(item.selectFirst("a")?.attr("href") ?: "")
            val poster = fixUrl(item.selectFirst("img")?.attr("src") ?: "")
            val type = if (url.contains("/series/")) TvType.TvSeries else TvType.Movie

            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, url, type) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, url, type) { this.posterUrl = poster }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: doc.title()
        val poster = fixUrl(doc.selectFirst("meta[property=og:image]")?.attr("content") ?: "")
        val plot = doc.selectFirst(".wp-content p")?.text()

        val episodes = doc.select("ul.episodios li").map { li ->
            val epUrl = fixUrl(li.selectFirst("a")?.attr("href") ?: "")
            val epName = li.selectFirst(".episodiotitle a")?.text() ?: ""
            val num = li.selectFirst(".numerando")?.text()?.split(" - ") ?: listOf("1", "1")

            newEpisode(epUrl) {
                this.name = epName
                this.season = num.getOrNull(0)?.toIntOrNull() ?: 1
                this.episode = num.getOrNull(1)?.toIntOrNull() ?: 1
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val postId = doc.selectFirst("input[name=comment_post_ID]")?.attr("value") ?: return false
        val isTv = data.contains("/episodios/") || data.contains("/temporadas/")

        // Hay varios reproductores por página (NETU, STREAMWISH, etc.) con su propio "nume"
        val options = doc.select("li.dooplay_player_option")
        if (options.isEmpty()) return false

        for (option in options) {
            val nume = option.attr("data-nume")
            val serverTitle = option.selectFirst(".title")?.text()?.trim().orEmpty()

            // Por ahora solo procesamos STREAMWISH. Agregar más "if" aquí para otros servidores.
            if (!serverTitle.contains("STREAMWISH", ignoreCase = true)) continue

            try {
                val ajax = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to postId,
                        "nume" to nume,
                        "type" to if (isTv) "tv" else "movie"
                    ),
                    referer = data
                ).parsed<Map<String, String>>()

                val path = ajax["embed_url"] ?: continue
                val playUrl = fixUrl(path)
                val finalUrl = followPlayRedirect(playUrl)

                StreamWishExtractor().videosFromUrl(finalUrl, data, subtitleCallback)
                    .forEach { video -> callback(video) }
            } catch (_: Exception) {}
        }

        return true
    }

    // /play/?h=XXXX no da el embed directo: devuelve una página "Anonimizador" que
    // inyecta un <form> con un input h (distinto al original) y lo postea a r.php,
    // el cual responde con un redirect al host final (streamwish.to, playme.top, etc.)
    private suspend fun followPlayRedirect(playUrl: String): String {
        return try {
            val rawHtml = app.get(playUrl, referer = mainUrl).text

            // Buscar h de varias formas posibles
            val hValue = sequenceOf(
                Regex("""name="h"\s+value="([^"]+)"""),           // forma principal
                Regex("""h["']\s*:\s*["']([^"']+)"""),           // posible objeto JS
                Regex("""value=["']([A-Za-z0-9+/=]{60,})["']""") // base64 largo
            ).firstNotNullOfOrNull { regex ->
                regex.find(rawHtml)?.groupValues?.get(1)
            } ?: return playUrl

            val resp = app.post(
                "$mainUrl/play/r.php",
                referer = playUrl,
                allowRedirects = false,
                data = mapOf("h" to hValue),
                headers = mapOf("User-Agent" to "Mozilla/5.0")
            )

            resp.headers["location"] ?: resp.headers["Location"] ?: resp.url
        } catch (e: Exception) {
            playUrl
        }
    }
}