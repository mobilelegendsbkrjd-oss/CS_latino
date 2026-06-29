package com.tubepelis

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

class TubePelis  : MainAPI() {

    override var mainUrl = "https://www.tubepelis.com"

    override var name = "TubePelis"

    override val hasMainPage = true

    override var lang = "mx"

    override val supportedTypes = setOf(
        TvType.Movie
    )

    override val mainPage = mainPageOf(

        "$mainUrl/" to "🆕 Recientes",
        "$mainUrl/pelicula/ultimas-peliculas/" to "📅 Últimas",
        "$mainUrl/pelicula/peliculas-mas-vistas/" to "🔥 Más vistas",

        "$mainUrl/categoria/accion/" to "💥 Acción",
        "$mainUrl/categoria/animacion-e-infantil/" to "🧸 Animación",
        "$mainUrl/categoria/artes-marciales/" to "🥋 Artes Marciales",
        "$mainUrl/categoria/aventura/" to "🗺️ Aventura",
        "$mainUrl/categoria/belico/" to "🎖️ Bélico",
        "$mainUrl/categoria/ciencia-ficcion/" to "🚀 Ciencia Ficción",
        "$mainUrl/categoria/comedia/" to "😂 Comedia",
        "$mainUrl/categoria/deporte/" to "⚽ Deporte",
        "$mainUrl/categoria/documentales/" to "📚 Documentales",
        "$mainUrl/categoria/drama/" to "🎭 Drama",
        "$mainUrl/categoria/fantasia/" to "🪄 Fantasía",
        "$mainUrl/categoria/intriga/" to "🕵️ Intriga",
        "$mainUrl/categoria/musical/" to "🎵 Musical",
        "$mainUrl/categoria/religiosas/" to "✝️ Religiosas",
        "$mainUrl/categoria/romance/" to "💕 Romance",
        "$mainUrl/categoria/suspenso/" to "😱 Suspenso",
        "$mainUrl/categoria/terror/" to "👻 Terror",
        "$mainUrl/categoria/western/" to "🤠 Western",
        "$mainUrl/pelicula/ultimas-peliculas/fullhd/" to "🎬 Full HD"
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

        val document = app.get(url).document

        val home = document
            .select("li.peli_bx")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            request.name,
            home
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar/?q=$query"

        val document = app.get(url).document

        return document
            .select("li.peli_bx")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title =
            document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.selectFirst("h1")?.text()
                ?: "Sin título"

        val poster =
            document.selectFirst("meta[property=og:image]")?.attr("content")

        val plot =
            document.selectFirst("meta[property=og:description]")?.attr("content")
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

        val players = mutableListOf<String>()

        document.select("iframe").forEach {
            val src = it.attr("data-src")
                .ifBlank { it.attr("src") }
                .trim()

            if (
                src.isNotBlank() &&
                src != "about:blank"
            ) {
                players.add(src)
            }
        }

        println("PLAYERS => $players")

        var found = false

        players.distinct().forEach { player ->
            try {
                println("PLAYER => $player")

                if (player.contains("reproductor.php")) {

                    val vParam = Regex("""[?&]v=([^&]+)""")
                        .find(player)
                        ?.groupValues
                        ?.getOrNull(1)

                    if (vParam != null) {
                        val decodedParam = URLDecoder.decode(vParam, "UTF-8")

                        val decoded = String(
                            Base64.decode(
                                decodedParam,
                                Base64.DEFAULT
                            )
                        ).trim()

                        println("REPRODUCTOR V DECODED => $decoded")

                        found = resolvePlayer(
                            decoded,
                            data,
                            subtitleCallback,
                            callback
                        ) || found

                        return@forEach
                    }

                    val html = app.get(
                        player,
                        headers = mapOf(
                            "Referer" to data,
                            "User-Agent" to USER_AGENT
                        )
                    ).text

                    println("PLAYER HTML => $html")

                    val encoded =
                        Regex("""_0x\s*=\s*"([^"]+)"""")
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
                            ).trim()

                        println("DECODED => $decoded")

                        found = resolvePlayer(
                            decoded,
                            data,
                            subtitleCallback,
                            callback
                        ) || found
                    }

                } else {
                    found = resolvePlayer(
                        player,
                        data,
                        subtitleCallback,
                        callback
                    ) || found
                }

            } catch (e: Exception) {
                println("LOADLINKS ERROR => ${e.message}")
                e.printStackTrace()
            }
        }

        return found
    }

    private suspend fun resolvePlayer(
        player: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        val fixed = player
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()

        if (fixed.isBlank()) return false

        try {
            when {
                FilemoonResolver.isFilemoon(fixed) -> {
                    println("FILEMOON DETECTED => $fixed")

                    val ok = FilemoonResolver.resolve(
                        fixed,
                        referer,
                        subtitleCallback,
                        callback
                    )

                    if (ok) found = true
                }

                fixed.contains("playmogo", true) ||
                        fixed.contains("dood", true) ||
                        fixed.contains("ds2play", true) ||
                        fixed.contains("ds2video", true) ||
                        fixed.contains("d0000d", true) ||
                        fixed.contains("d000d", true) ||
                        fixed.contains("vide0.net", true) ||
                        fixed.contains("myvidplay", true) -> {

                    println("DOOD DETECTED => $fixed")

                    DoodLaExtractor().getUrl(
                        fixed,
                        referer,
                        subtitleCallback
                    ) {
                        found = true
                        callback(it)
                    }
                }

                else -> {
                    println("LOAD EXTRACTOR => $fixed")

                    loadExtractor(
                        fixed,
                        referer,
                        subtitleCallback
                    ) {
                        found = true
                        callback(it)
                    }
                }
            }
        } catch (e: Exception) {
            println("RESOLVE PLAYER ERROR => ${e.message}")

            try {
                loadExtractor(
                    fixed,
                    referer,
                    subtitleCallback
                ) {
                    found = true
                    callback(it)
                }
            } catch (_: Exception) {
            }
        }

        return found
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null

        val href = a.attr("abs:href")

        if (href.isBlank()) {
            return null
        }

        val title =
            a.attr("title")
                .ifBlank {
                    selectFirst("img")?.attr("alt")
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