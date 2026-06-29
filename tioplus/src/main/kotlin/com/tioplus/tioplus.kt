package com.tioplus

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class TioPlus : MainAPI() {
    override var mainUrl = "https://tioplus.app"
    override var name = "TioPlus"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas?page=" to "🎬 Películas",
        "$mainUrl/series?page=" to "📺 Series",
        "$mainUrl/animes?page=" to "🍥 Animes",
        "$mainUrl/doramas?page=" to "🌸 Doramas"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(request.data + page, referer = mainUrl).document

        val items = doc.select("article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = items.size == 24
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/search/$q", referer = mainUrl).document

        return doc.select("article")
            .mapNotNull { it.toSearchResult(searchAll = true) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixOldHost(url)
        val doc = app.get(fixedUrl, referer = mainUrl).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Sin título"

        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img[data-src], img")?.imgAttr()
        )

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".description, .synopsis, p")?.text()

        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(doc.text())
            ?.value
            ?.toIntOrNull()

        val isMovie = fixedUrl.contains("/pelicula/")

        if (isMovie) {
            return newMovieLoadResponse(
                title,
                fixedUrl,
                TvType.Movie,
                fixedUrl
            ) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        var episodes = parseAllEpisodes(doc.html(), fixedUrl)

        if (episodes.isEmpty()) {
            episodes = parseEpisodesFallback(doc.html(), fixedUrl)
        }

        return newTvSeriesLoadResponse(
            title,
            fixedUrl,
            if (fixedUrl.contains("/anime", true) || fixedUrl.contains("/animes", true)) {
                TvType.Anime
            } else {
                TvType.TvSeries
            },
            episodes
        ) {
            posterUrl = poster
            backgroundPosterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedData = fixOldHost(data)

        val html = app.get(fixedData, referer = mainUrl).text
            .replace("\n", "")
            .replace("\r", "")
            .replace("\t", "")
            .replace("&nbsp;", "")

        val lang = when {
            html.contains(">Español Latino<", true) -> "Lat"
            html.contains(">Castellano<", true) -> "Esp"
            html.contains(">Subtitulado<", true) -> "Vose"
            else -> "?"
        }

        val bloque = Regex(
            """<div class=["']bg-tabs["']>(.*?)</div>\s*</div>\s*</div>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.getOrNull(1) ?: html

        val matches = Regex(
            """data-server=["']([^"']+)["'].*?<span>(.*?)</span>""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(bloque)
            .map { it.groupValues[1] to it.groupValues[2].cleanTitle() }
            .toList()

        var found = false

        for (pair in matches) {
            val opt = pair.first
            val srv = pair.second

            try {
                val encoded = Base64.encodeToString(
                    opt.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )

                val playerUrl = "$mainUrl/player/$encoded"

                var playerHtml = app.get(playerUrl, referer = fixedData).text

                if (playerHtml.contains("Estas saturando la red se te dará un bloqueo temporal", true)) {
                    playerHtml = app.get(playerUrl, referer = fixedData).text
                }

                var realUrl = Regex("""(?i)Location\.href\s*=\s*['"]([^'"]+)['"]""")
                    .find(playerHtml)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: continue

                if (realUrl.contains("up.asdasd", true)) {
                    val path = Regex(""".site(.*?)$""")
                        .find(realUrl)
                        ?.groupValues
                        ?.getOrNull(1)

                    if (!path.isNullOrBlank()) {
                        realUrl = "https://netu.to$path"
                    }
                }

                if (realUrl.startsWith("/")) {
                    realUrl = mainUrl + realUrl
                }

                if (!realUrl.contains("http")) continue

                val tempLinks = mutableListOf<ExtractorLink>()

                loadExtractor(realUrl, fixedData, subtitleCallback) { link ->
                    tempLinks.add(link)
                }

                for (link in tempLinks) {
                    found = true

                    callback.invoke(
                        newExtractorLink(
                            source = link.source,
                            name = "[$lang] ${srv.ifBlank { link.name }}",
                            url = link.url,
                            type = link.type
                        ) {
                            quality = if (link.quality > 0) {
                                link.quality
                            } else {
                                getQualityFromName(link.name + " " + link.url)
                            }

                            referer = link.referer
                            headers = link.headers
                        }
                    )
                }

            } catch (_: Exception) {
            }
        }

        return found
    }

    private fun parseAllEpisodes(rawHtml: String, baseUrl: String): List<Episode> {
        val html = rawHtml
            .replace("\\/", "/")
            .replace("\n", "")
            .replace("\r", "")
            .replace("\t", "")

        val seasonBlock = Regex(
            """<ul id=["']seasonAll["']>(.*?)</ul>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.getOrNull(1) ?: ""

        val seasons = Regex("""<li class.*?>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(seasonBlock)
            .map {
                it.groupValues[1]
                    .replace("Temporada", "", true)
                    .replace("TEMPORADA", "", true)
                    .trim()
            }
            .filter { it.isNotBlank() }
            .toList()
            .ifEmpty { listOf("1") }

        val episodes = mutableListOf<Episode>()

        for (season in seasons) {
            val block = Regex(""""$season".*?]\.""", RegexOption.DOT_MATCHES_ALL)
                .find(html)
                ?.value
                ?: continue

            Regex(
                """"title":\s*"(.*?)".*?"image":\s*"(.*?)".*?"episode":\s*(\d+)""",
                RegexOption.DOT_MATCHES_ALL
            ).findAll(block).forEach { m ->
                val title = m.groupValues[1].decodeUnicodeTitle()
                val img = "https://image.tmdb.org/t/p/w500" +
                        m.groupValues[2].replace("\\/", "/")
                val ep = m.groupValues[3].toIntOrNull()

                episodes.add(
                    newEpisode("$baseUrl/season/$season/episode/${m.groupValues[3]}") {
                        name = "${season}x${m.groupValues[3]} $title"
                        this.season = season.toIntOrNull()
                        episode = ep
                        posterUrl = img
                    }
                )
            }
        }

        return episodes.distinctBy { it.data }
    }

    private fun parseEpisodesFallback(rawHtml: String, baseUrl: String): List<Episode> {
        val html = rawHtml
            .replace("\\/", "/")
            .replace("\n", "")
            .replace("\r", "")
            .replace("\t", "")

        val episodes = mutableListOf<Episode>()

        Regex("""/season/(\d+)/episode/(\d+)""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { m ->
                val season = m.groupValues[1].toIntOrNull()
                val episode = m.groupValues[2].toIntOrNull()

                if (season != null && episode != null) {
                    val epUrl = "$baseUrl/season/$season/episode/$episode"

                    episodes.add(
                        newEpisode(epUrl) {
                            name = "${season}x$episode"
                            this.season = season
                            this.episode = episode
                        }
                    )
                }
            }

        return episodes.distinctBy { it.data }
    }

    private fun Element.toSearchResult(searchAll: Boolean = false): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null

        val href = fixOldHost(a.attr("abs:href").ifBlank { a.attr("href") })
        if (href.isBlank()) return null

        val img = selectFirst("img")

        val title = img?.attr("alt")
            ?.replace("&#039;", "'")
            ?.cleanTitle()
            ?: return null

        if (title.contains("PREMIUM", true)) return null

        val poster = fixUrlNull(img.imgAttr())
        val isMovie = href.contains("/pelicula/")
        val suffix = if (searchAll) {
            if (isMovie) " Película" else " Serie"
        } else {
            ""
        }

        return if (isMovie) {
            newMovieSearchResponse(
                title + suffix,
                fixUrl(href),
                TvType.Movie
            ) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(
                title + suffix,
                fixUrl(href),
                TvType.TvSeries
            ) {
                posterUrl = poster
            }
        }
    }

    private fun Element.imgAttr(): String {
        return attr("data-src")
            .ifBlank { attr("src") }
            .replace("\\/", "/")
            .trim()
    }

    private fun fixOldHost(url: String): String {
        return url
            .replace("https://ww3.pelisplus.to/", "$mainUrl/")
            .replace("https://ww3.tioplus.net/", "$mainUrl/")
    }

    private fun String.cleanTitle(): String {
        return this
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&#038;", "&")
            .replace("&#8217;", "'")
            .trim()
    }

    private fun String.decodeUnicodeTitle(): String {
        return this
            .replace("\\u00e1", "á")
            .replace("\\u00c1", "Á")
            .replace("\\u00e9", "é")
            .replace("\\u00ed", "í")
            .replace("\\u00f3", "ó")
            .replace("\\u00fa", "ú")
            .replace("\\u00f1", "ñ")
            .replace("\\u00bf", "¿")
            .replace("\\u00a1", "¡")
            .replace("\\u00ba", "º")
            .replace("\\u00c9", "É")
            .replace("\\/", "/")
            .cleanTitle()
    }
}