package com.verpeliculasonline

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class VerPeliculasOnline : MainAPI() {

    override var mainUrl = "https://verpeliculasonline.org"
    override var name = "VerPeliculasOnline"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "/pelicula/" to "Películas",
        "/genero/terror/" to "Terror",
        "/genero/accion/" to "Acción",
        "/genero/animacion/" to "Animación",
        "/genero/drama/" to "Drama"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = when {
            request.data == "/" && page == 1 -> "$mainUrl/"
            request.data == "/" -> "$mainUrl/page/$page/"
            page == 1 -> "$mainUrl${request.data}"
            else -> "$mainUrl${request.data}page/$page/"
        }

        val doc = app.get(url).document

        val items = doc.select("article.item, article, .item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items, false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val doc = app.get(
            "$mainUrl/?s=${query.trim()}"
        ).document

        return doc.select("article.item, article, .item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: "Sin título"

        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".poster img, img")?.imgAttr()
        )

        val description = doc.selectFirst(
            ".wp-content p, .description p, .entry-content p"
        )?.text()?.trim()

        val year = Regex("""(19|20)\d{2}""")
            .find(doc.text())
            ?.value
            ?.toIntOrNull()

        val tags = doc.select(
            ".sgeneros a, .genres a, .genre a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }

        val episodeNodes = doc.select(
            "ul.episodios li, .se-c .episodios li, .episodes li"
        )

        return if (episodeNodes.isNotEmpty() || url.contains("/serie/")) {

            val episodes = episodeNodes.mapIndexedNotNull { index, el ->

                val href = el.selectFirst("a")?.attr("href")
                    ?: return@mapIndexedNotNull null

                newEpisode(fixUrl(href)) {
                    this.name = el.text().trim()
                    this.episode = index + 1
                    this.season = 1
                }
            }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = description
                this.year = year
                this.tags = tags
            }

        } else {

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = description
                this.year = year
                this.tags = tags
            }
        }
    }

    private fun detectLanguage(text: String): String {

        val t = text.lowercase()

        return when {
            t.contains("latino") -> "LAT"
            t.contains("castellano") -> "CAS"
            t.contains("español") -> "CAS"
            t.contains("espanol") -> "CAS"
            t.contains("subtit") -> "SUB"
            t.contains("sub") -> "SUB"
            t.contains("dual") -> "DUAL"
            else -> "VO"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document
        var found = false

        val finalLinks = mutableListOf<ExtractorLink>()
        val options = doc.select("li.dooplay_player_option")

        for (opt in options) {

            val post = opt.attr("data-post").trim()
            val nume = opt.attr("data-nume").trim()
            val type = opt.attr("data-type").trim()

            if (
                post.isBlank() ||
                nume.isBlank() ||
                type.isBlank()
            ) continue

            val lang = detectLanguage(opt.text())
            val urls = mutableListOf<String>()

            try {
                val json = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = data
                ).parsedSafe<PlayerResponse>()

                json?.embedUrl?.let {
                    urls.add(it)
                }

            } catch (_: Exception) {
            }

            if (urls.isEmpty()) {
                try {
                    val json2 = app.get(
                        "$mainUrl/wp-json/dooplayer/v1/post/$post?type=$type&source=$nume",
                        referer = data
                    ).parsedSafe<PlayerResponse>()

                    json2?.embedUrl?.let {
                        urls.add(it)
                    }

                } catch (_: Exception) {
                }
            }

            for (raw in urls.distinct()) {

                val fixedUrls = buildMirrors(raw)

                for (link in fixedUrls) {

                    if (isBadLink(link)) continue

                    try {

                        val tempLinks =
                            mutableListOf<ExtractorLink>()

                        UniversalHostResolver.resolve(
                            link,
                            data,
                            subtitleCallback
                        ) { ext: ExtractorLink ->
                            tempLinks.add(ext)
                        }

                        for (
                        ext in tempLinks
                            .distinctBy { it.url }
                        ) {

                            finalLinks.add(
                                newExtractorLink(
                                    source = ext.source,
                                    name = "[$lang] ${ext.name}",
                                    url = ext.url,
                                    type = ext.type
                                ) {

                                    quality =
                                        if (ext.quality > 0)
                                            ext.quality
                                        else
                                            getQualityFromName(
                                                ext.name +
                                                        " " +
                                                        ext.url
                                            )

                                    headers = ext.headers
                                    referer = ext.referer
                                }
                            )
                        }

                        if (tempLinks.isNotEmpty()) {
                            found = true
                        }

                    } catch (_: Exception) {
                    }
                }
            }
        }

        if (!found) {

            val frames = doc.select("iframe[src]")

            for (frame in frames) {

                val src = frame.attr("src").trim()

                if (
                    src.startsWith("http") &&
                    !isBadLink(src)
                ) {

                    try {

                        val tempLinks =
                            mutableListOf<ExtractorLink>()

                        UniversalHostResolver.resolve(
                            src,
                            data,
                            subtitleCallback
                        ) { ext: ExtractorLink ->
                            tempLinks.add(ext)
                        }

                        for (
                        ext in tempLinks
                            .distinctBy { it.url }
                        ) {

                            finalLinks.add(
                                newExtractorLink(
                                    source = ext.source,
                                    name = "[VO] ${ext.name}",
                                    url = ext.url,
                                    type = ext.type
                                ) {

                                    quality =
                                        if (ext.quality > 0)
                                            ext.quality
                                        else
                                            getQualityFromName(
                                                ext.name +
                                                        " " +
                                                        ext.url
                                            )

                                    headers = ext.headers
                                    referer = ext.referer
                                }
                            )
                        }

                        if (tempLinks.isNotEmpty()) {
                            found = true
                        }

                    } catch (_: Exception) {
                    }
                }
            }
        }

        val sorted = finalLinks
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<ExtractorLink> {

                    when {
                        it.name.contains("[LAT]", true) -> 4
                        it.name.contains("[CAS]", true) -> 3
                        it.name.contains("[SUB]", true) -> 2
                        it.name.contains("[DUAL]", true) -> 1
                        else -> 0
                    }

                }.thenByDescending {

                    if (it.quality > 0)
                        it.quality
                    else
                        getQualityFromName(
                            it.name + " " + it.url
                        )
                }
            )

        for (item in sorted) {
            callback.invoke(item)
        }

        return found
    }
    private fun buildMirrors(raw: String): List<String> {

        val link = raw
            .replace("\\/", "/")
            .trim()

        val list = mutableListOf(link)

        if (link.contains("opuxa.lat")) {
            list.add(link.replace("opuxa.lat", "waaw.to"))
            list.add(link.replace("opuxa.lat", "netu.tv"))
            list.add(link.replace("opuxa.lat", "hqq.to"))
        }

        return list.distinct()
    }

    private fun isBadLink(url: String): Boolean {

        val blocked = listOf(
            "youtube.com",
            "youtu.be",
            "vimeo.com",
            "facebook.com",
            "instagram.com",
            "twitter.com",
            "trailer",
            "teaser"
        )

        return blocked.any {
            url.contains(it, true)
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val a = selectFirst("a") ?: return null

        val href = a.attr("href").trim()
        if (href.isBlank()) return null

        val title = selectFirst(
            "h1,h2,h3,h4,.title,.data h3"
        )?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(
            selectFirst("img")?.imgAttr()
        )

        val isTv = href.contains("/serie/") ||
                href.contains("/series/")

        return if (isTv) {

            newTvSeriesSearchResponse(
                title,
                fixUrl(href),
                TvType.TvSeries
            ) {
                posterUrl = poster
            }

        } else {

            newMovieSearchResponse(
                title,
                fixUrl(href),
                TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    private fun Element.imgAttr(): String {

        val dataSrc = attr("data-src").trim()
        if (dataSrc.isNotBlank()) return dataSrc

        val src = attr("src").trim()
        if (src.isNotBlank()) return src

        return ""
    }

    data class PlayerResponse(
        @JsonProperty("embed_url")
        val embedUrl: String? = null
    )
}