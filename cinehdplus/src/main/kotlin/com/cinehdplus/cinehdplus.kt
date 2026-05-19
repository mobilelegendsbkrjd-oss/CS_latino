package com.cinehdplus

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class CineHDPlus : MainAPI() {

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
    }

    override var mainUrl = "https://cinehdplus.org"
    private val apiUrl = "https://api.cinehdplus.org"

    override var name = "CineHDPlus"
    override val hasMainPage = true
    override var lang = "MX"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Inicio",
        "$mainUrl/peliculas" to "Películas",
        "$mainUrl/series" to "Series",
        "$mainUrl/populares" to "Populares"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = when {

            // POPULARES
            request.data.contains("/populares") -> {

                val period = when (page % 4) {
                    0 -> "day"
                    1 -> "week"
                    2 -> "month"
                    else -> "year"
                }

                "$mainUrl/populares/?period=$period"
            }

            // SERIES
            request.data.contains("/series") -> {

                if (page == 1) {
                    request.data
                } else {
                    "$mainUrl/series/page/$page/"
                }
            }

            // PELICULAS
            request.data.contains("/peliculas") -> {

                if (page == 1) {
                    request.data
                } else {
                    "$mainUrl/peliculas/page/$page/"
                }
            }

            // INICIO
            page == 1 -> {
                request.data
            }

            else -> {
                "$mainUrl/page/$page/"
            }
        }

        Log.d(
            "CineHDPlus",
            "MAINPAGE URL = $url"
        )

        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            )
        ).document

        val results = mutableListOf<SearchResponse>()

        // HERO
        document.select(".slider-slide").forEach { slide ->

            val link =
                slide.selectFirst(
                    "a[href*=/pelicula-], a[href*=/tvshows/]"
                )?.attr("href")
                    ?: return@forEach

            val title =
                slide.selectFirst("h1, h2")
                    ?.text()
                    ?.substringBefore("(")
                    ?.trim()
                    ?: return@forEach

            val poster =
                fixUrlNull(
                    slide.selectFirst("img")
                        ?.attr("src")
                )

            val fixedLink =
                fixUrl(link)

            val item =
                if (fixedLink.contains("/pelicula-")) {

                    newMovieSearchResponse(
                        title,
                        fixedLink,
                        TvType.Movie
                    ) {
                        this.posterUrl = poster
                    }

                } else {

                    newTvSeriesSearchResponse(
                        title,
                        fixedLink,
                        TvType.TvSeries
                    ) {
                        this.posterUrl = poster
                    }
                }

            if (
                results.none {
                    it.url == item.url
                }
            ) {
                results.add(item)
            }
        }

        // GRID REAL NUEVO
        document.select(
            "div.group.relative"
        ).forEach { item ->

            val result =
                toSearchResult(item)
                    ?: return@forEach

            if (
                results.none {
                    it.url == result.url
                }
            ) {
                results.add(result)
            }
        }

        Log.d(
            "CineHDPlus",
            "TOTAL RESULTS = ${results.size}"
        )

        return newHomePageResponse(
            request.name,
            results
        )
    }

    private fun toSearchResult(
        element: Element
    ): SearchResponse? {

        val link =
            element.selectFirst(
                "a[href*=/pelicula-], a[href*=/tvshows/]"
            )?.attr("href")
                ?: return null

        val fixedLink =
            fixUrl(link)

        val title =
            element.selectFirst("h3")
                ?.text()
                ?.trim()
                ?: return null

        val poster =
            fixUrlNull(
                element.selectFirst("img")
                    ?.attr("src")
            )

        return if (
            fixedLink.contains("/pelicula-")
        ) {

            newMovieSearchResponse(
                title,
                fixedLink,
                TvType.Movie
            ) {
                this.posterUrl = poster
            }

        } else {

            newTvSeriesSearchResponse(
                title,
                fixedLink,
                TvType.TvSeries
            ) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val document = app.get(
            "$mainUrl/search/$query/",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            )
        ).document

        return document.select(
            "div.group.relative"
        ).mapNotNull {
            toSearchResult(it)
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            )
        ).document

        val isTv =
            document.selectFirst(
                "body.tvshows-template-default"
            ) != null

        val title =
            document.selectFirst(
                "h2.text-xl, h1, meta[property=og:title]"
            )?.let {

                when {

                    it.tagName() == "meta" -> {
                        it.attr("content")
                            .substringBefore("(")
                            .replace("Ver ", "")
                            .replace(" Online HD", "")
                            .trim()
                    }

                    else -> {
                        it.ownText()
                            .ifBlank { it.text() }
                            .substringBefore("(")
                            .trim()
                    }
                }

            } ?: "Sin título"

        val poster =
            fixUrlNull(

                document.selectFirst(
                    """
                div.aspect-2\\/3 img,
                meta[property=og:image],
                #rm-post-history
                """.trimIndent()
                )?.let {

                    when {

                        it.tagName() == "meta" -> {
                            it.attr("content")
                        }

                        it.id() == "rm-post-history" -> {
                            it.attr("data-poster")
                        }

                        else -> {
                            it.attr("src")
                        }
                    }
                }
            )

        val backdrop =
            fixUrlNull(
                document.selectFirst(
                    ".absolute.inset-0 img"
                )?.attr("src")
            )

        val plot =
            document.selectFirst(
                "div.prose-custom p"
            )?.text()?.trim()

        val tags =
            document.select(
                "a[href*=genero]"
            ).map {
                it.text().trim()
            }

        val year =
            Regex("\\((\\d{4})\\)")
                .find(document.text())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        if (!isTv) {

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {

                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }

        val episodes = mutableListOf<Episode>()

        document.select(
            "div[id^=season-content-]"
        ).forEach { seasonBlock ->

            val season =
                seasonBlock.id()
                    .substringAfter(
                        "season-content-"
                    )
                    .toIntOrNull()
                    ?: 1

            seasonBlock.select(
                "a[href*=/episodio-]"
            ).forEach { ep ->

                val href =
                    fixUrl(
                        ep.attr("href")
                    )

                val epTitle =
                    ep.selectFirst("h3")
                        ?.text()
                        ?.trim()
                        ?: "Episodio"

                val epPoster =
                    fixUrlNull(
                        ep.selectFirst("img")
                            ?.attr("src")
                    )

                val epNum =
                    Regex("""E(\d+)""")
                        .find(epTitle)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                episodes.add(
                    newEpisode(href) {
                        this.name = epTitle
                        this.season = season
                        this.episode = epNum
                        this.posterUrl = epPoster
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {

            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    private fun rot13(
        input: String
    ): String {

        val result = StringBuilder()

        input.forEach { c ->

            when {

                c in 'a'..'z' -> {
                    result.append(
                        'a' + (c - 'a' + 13) % 26
                    )
                }

                c in 'A'..'Z' -> {
                    result.append(
                        'A' + (c - 'A' + 13) % 26
                    )
                }

                else -> {
                    result.append(c)
                }
            }
        }

        return result.toString()
    }

    private fun decodeBase64(
        str: String
    ): String {

        return try {

            String(
                Base64.decode(
                    str,
                    Base64.DEFAULT
                )
            )

        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun resolveCineHDUrl(
        hash: String,
        referer: String
    ): String? {

        return try {

            val step1 = app.get(
                "$apiUrl/ir/goto.php?h=$hash",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                )
            ).document

            val form1Value =
                step1.selectFirst(
                    "input[name=url]"
                )?.attr("value")
                    ?: return null

            val step2 = app.post(
                "$apiUrl/ir/rd.php",
                data = mapOf(
                    "url" to form1Value
                ),
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$apiUrl/ir/goto.php?h=$hash"
                )
            ).document

            val form2Value =
                step2.selectFirst(
                    "input[name=url]"
                )?.attr("value")
                    ?: return null

            val dl =
                step2.selectFirst(
                    "input[name=dl]"
                )?.attr("value")
                    ?: "0"

            val action =
                step2.selectFirst("form")
                    ?.attr("action")
                    ?: "redir_ddh.php"

            val step3 = app.post(
                "$apiUrl/ir/$action",
                data = mapOf(
                    "url" to form2Value,
                    "dl" to dl
                ),
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$apiUrl/ir/go_ddh.php"
                )
            ).document

            val encodedVid =
                step3.selectFirst(
                    "input[name=vid]"
                )?.attr("value")
                    ?: return null

            val decoded =
                decodeBase64(encodedVid)

            rot13(decoded)

        } catch (e: Exception) {

            Log.e(
                "CineHDPlus",
                "resolve error",
                e
            )

            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(
            data,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            )
        ).document

        val servers = document.select(
            ".player-tab[data-url], li[data-url]"
        )

        Log.d(
            "CineHDPlus",
            "Servidores encontrados: ${servers.size}"
        )

        servers.apmap { server ->

            try {

                val dataUrl =
                    server.attr("data-url")

                if (
                    dataUrl.isBlank() ||
                    !dataUrl.contains("player.php?h=")
                ) {
                    return@apmap
                }

                val fixedUrl =
                    if (dataUrl.startsWith("//")) {
                        "https:$dataUrl"
                    } else {
                        dataUrl
                    }

                val uri =
                    URI(fixedUrl)

                val hash =
                    uri.query
                        ?.split("&")
                        ?.firstOrNull {
                            it.startsWith("h=")
                        }
                        ?.substringAfter("h=")
                        ?: return@apmap

                val finalPlayer =
                    resolveCineHDUrl(
                        hash,
                        fixedUrl
                    ) ?: return@apmap

                Log.d(
                    "CineHDPlus",
                    "Player final: $finalPlayer"
                )

                loadExtractor(
                    finalPlayer,
                    data,
                    subtitleCallback,
                    callback
                )

            } catch (e: Exception) {

                Log.e(
                    "CineHDPlus",
                    "loadLinks error",
                    e
                )
            }
        }

        return true
    }
}