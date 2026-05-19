package com.tubepelis

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.tubepelis.FilemoonResolver
import org.jsoup.nodes.Element

class tubepelis : MainAPI() {

    override var mainUrl = "https://www.tubepelis.com"

    override var name = "TubePelis"

    override val hasMainPage = true

    override var lang = "mx"

    override val supportedTypes = setOf(
        TvType.Movie
    )

    // ============================================================
    // HOME
    // ============================================================

    override val mainPage = mainPageOf(

        "$mainUrl/pelicula/ultimas-peliculas/" to "Últimas",

        "$mainUrl/pelicula/peliculas-mas-vistas/" to "Más vistas",

        "$mainUrl/pelicula/peliculas-mas-votadas/" to "Más valoradas"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url =
            if (page == 1) {
                request.data
            } else {
                "${request.data}?page=$page"
            }

        val document =
            app.get(url).document

        val home = document
            .select("li.peli_bx")
            .mapNotNull {
                it.toSearchResult()
            }

        return newHomePageResponse(
            request.name,
            home
        )
    }

    // ============================================================
    // SEARCH
    // ============================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val url =
            "$mainUrl/buscar/?q=$query"

        val document =
            app.get(url).document

        return document
            .select("li.peli_bx")
            .mapNotNull {
                it.toSearchResult()
            }
    }

    // ============================================================
    // LOAD
    // ============================================================

    override suspend fun load(
        url: String
    ): LoadResponse {

        val document =
            app.get(url).document

        val title =
            document.selectFirst(
                "meta[property=og:title]"
            )?.attr("content")

                ?: document.selectFirst("h1")
                    ?.text()

                ?: "Sin título"

        val poster =
            document.selectFirst(
                "meta[property=og:image]"
            )?.attr("content")

        val plot =
            document.selectFirst(
                "meta[property=og:description]"
            )?.attr("content")

                ?: ""

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {

            this.posterUrl = poster

            this.plot = plot
        }
    }

    // ============================================================
    // LOAD LINKS
    // ============================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(
            data,
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to USER_AGENT
            )
        ).document

        val players =
            mutableListOf<String>()

        // ========================================================
        // IFRAME PLAYERS
        // ========================================================

        document.select("iframe").forEach {

            val src =
                it.attr("src")
                    .ifBlank {
                        it.attr("data-src")
                    }

            if (src.isNotBlank()) {

                players.add(src)
            }
        }

        println("PLAYERS => $players")

        var found = false

        players.distinct().forEach { player ->

            try {

                println("PLAYER => $player")

                // ====================================================
                // reproductor.php
                // ====================================================

                if (
                    player.contains(
                        "reproductor.php"
                    )
                ) {

                    val html = app.get(
                        player,
                        headers = mapOf(
                            "Referer" to data,
                            "User-Agent" to USER_AGENT
                        )
                    ).text

                    println("PLAYER HTML => $html")

                    // ====================================================
                    // BASE64 PLAYER
                    // ====================================================

                    val encoded =
                        Regex(
                            """_0x\s*=\s*"([^"]+)""""
                        )
                            .find(html)
                            ?.groupValues
                            ?.getOrNull(1)

                    if (encoded != null) {

                        println("ENCODED => $encoded")

                        val decoded =
                            String(
                                Base64.decode(
                                    encoded,
                                    Base64.DEFAULT
                                )
                            )

                        println("DECODED => $decoded")

                        // ====================================================
                        // FILEMOON RESOLVER
                        // ====================================================

                        if (
                            FilemoonResolver
                                .isFilemoon(decoded)
                        ) {

                            println(
                                "FILEMOON DETECTED"
                            )

                            val ok =
                                FilemoonResolver.resolve(
                                    decoded,
                                    data,
                                    subtitleCallback,
                                    callback
                                )

                            if (ok) {

                                found = true
                            }

                        } else {

                            println(
                                "LOAD EXTRACTOR => $decoded"
                            )

                            loadExtractor(
                                decoded,
                                data,
                                subtitleCallback
                            ) {

                                found = true

                                callback(it)
                            }
                        }
                    }

                } else {

                    // ====================================================
                    // DIRECT PLAYER
                    // ====================================================

                    if (
                        FilemoonResolver
                            .isFilemoon(player)
                    ) {

                        println(
                            "DIRECT FILEMOON"
                        )

                        val ok =
                            FilemoonResolver.resolve(
                                player,
                                data,
                                subtitleCallback,
                                callback
                            )

                        if (ok) {

                            found = true
                        }

                    } else {

                        println(
                            "DIRECT EXTRACTOR => $player"
                        )

                        loadExtractor(
                            player,
                            data,
                            subtitleCallback
                        ) {

                            found = true

                            callback(it)
                        }
                    }
                }

            } catch (e: Exception) {

                println(
                    "LOADLINKS ERROR => ${e.message}"
                )

                e.printStackTrace()
            }
        }

        return found
    }

    // ============================================================
    // PARSER
    // ============================================================

    private fun Element.toSearchResult(): SearchResponse? {

        val a =
            selectFirst("a")
                ?: return null

        val href =
            a.attr("abs:href")

        if (href.isBlank()) {
            return null
        }

        val title =
            a.attr("title")
                .ifBlank {

                    selectFirst("img")
                        ?.attr("alt")
                }

                ?: return null

        val poster =
            selectFirst("img")
                ?.attr("abs:src")

        return newMovieSearchResponse(
            title,
            href,
            TvType.Movie
        ) {

            this.posterUrl = poster
        }
    }
}