package com.sololatino

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlin.text.Regex
import com.sololatino.PlayHydrax

class SoloLatino : MainAPI() {

    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override val hasMainPage = true
    override var lang = "mx"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // =========================
    // JSON REMOTO DE SAGAS
    // =========================
    private val sagasJson =
        "https://raw.githubusercontent.com/mobilelegendsbkrjd-oss/lat_cs_bkrjd/main/sagas.json"

    // =========================
    // DATA CLASSES
    // =========================
    data class SagaMovie(
        val name: String? = null,
        val url: String? = null
    )

    data class SagaItem(
        val title: String? = null,
        val poster: String? = null,
        val description: String? = null,
        val author: String? = null,
        val likes: Int? = null,
        val tags: List<String>? = null,
        val movies: List<SagaMovie>? = null
    )

    // =========================
    // PARSER CENTRAL
    // =========================
    private fun parseCards(
        doc: org.jsoup.nodes.Element
    ): List<SearchResponse> {

        return doc.select(".card, article.card")
            .mapNotNull { card ->

                val a = card.selectFirst("a")
                    ?: return@mapNotNull null

                val link = fixUrl(
                    a.attr("href")
                )

                val name = card.selectFirst(
                    ".card__title, h3, h2"
                )?.text()?.trim()
                    ?: return@mapNotNull null

                val poster = card.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank {
                        it.attr("data-lazy-src").ifBlank {
                            it.attr("src")
                        }
                    }
                }?.replace(
                    Regex("-\\d+x\\d+"),
                    ""
                )

                val type =
                    if (link.contains("/serie/"))
                        TvType.TvSeries
                    else
                        TvType.Movie

                newMovieSearchResponse(
                    name,
                    link,
                    type
                ) {
                    this.posterUrl = poster
                }
            }
    }

    // =========================
    // GET SAGAS
    // =========================
    private suspend fun getSagas():
            List<SearchResponse> {

        return try {

            val json = app.get(
                sagasJson
            ).text

            val parsed =
                AppUtils.tryParseJson<
                        List<SagaItem>
                        >(json)
                    ?: return emptyList()

            parsed.mapIndexedNotNull {
                    index,
                    saga ->

                val title =
                    saga.title
                        ?: return@mapIndexedNotNull null

                val url =
                    "$mainUrl/saga/$index"

                newAnimeSearchResponse(
                    title,
                    url,
                    TvType.TvSeries
                ) {
                    this.posterUrl =
                        saga.poster
                }
            }

        } catch (_: Exception) {
            emptyList()
        }
    }

    // =========================
    // GET SAGA DATA
    // =========================
    private suspend fun getSagaData(
        index: Int
    ): SagaItem? {

        return try {

            val json = app.get(
                sagasJson
            ).text

            val parsed =
                AppUtils.tryParseJson<
                        List<SagaItem>
                        >(json)

            parsed?.getOrNull(index)

        } catch (_: Exception) {
            null
        }
    }

    // =========================
    // MAIN PAGE
    // =========================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get(mainUrl).document

        val lists = mutableListOf<HomePageList>()

        // =========================
        // SAGAS
        // =========================
        val sagas = getSagas()

        if (sagas.isNotEmpty()) {

            lists.add(
                HomePageList(
                    "🔥 Sagas",
                    sagas
                )
            )
        }

        // =========================
        // CATEGORIAS NORMALES
        // =========================
        doc.select("section").forEach { section ->

            val title = section.selectFirst(
                "h2, .section-title"
            )?.text()?.trim()
                ?: return@forEach

            val items = parseCards(section)

            if (items.size < 3) {
                return@forEach
            }

            lists.add(
                HomePageList(
                    title,
                    items
                )
            )
        }

        return newHomePageResponse(lists)
    }

    // =========================
    // SEARCH
    // =========================
    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val results =
            mutableListOf<SearchResponse>()

        // =========================
        // BUSCAR EN SAGAS
        // =========================
        try {

            val sagas = getSagas()

            results.addAll(
                sagas.filter {
                    it.name.contains(
                        query,
                        ignoreCase = true
                    )
                }
            )

        } catch (_: Exception) {}

        // =========================
        // BUSQUEDA NORMAL
        // =========================
        val urls = listOf(
            "$mainUrl/buscar?q=$query",
            "$mainUrl/search?q=$query"
        )

        for (base in urls) {

            for (page in 1..3) {

                try {

                    val url =
                        "$base&page=$page"

                    val doc =
                        app.get(url).document

                    val items =
                        parseCards(doc)

                    if (items.isEmpty()) {
                        break
                    }

                    results.addAll(items)

                } catch (_: Exception) {}
            }
        }

        return results.distinctBy {
            it.url
        }
    }

    // =========================
    // LOAD
    // =========================
    override suspend fun load(
        url: String
    ): LoadResponse {

        // =========================
        // LOAD SAGA
        // =========================
        if (
            url.contains("/saga/")
        ) {

            val index = url
                .substringAfter("/saga/")
                .toIntOrNull()
                ?: throw ErrorLoadingException()

            val saga =
                getSagaData(index)
                    ?: throw ErrorLoadingException()

            val episodes =
                mutableListOf<Episode>()

            saga.movies
                ?.forEachIndexed { i, movie ->

                    val movieUrl =
                        movie.url
                            ?: return@forEachIndexed

                    try {

                        val movieDoc =
                            app.get(movieUrl).document

                        val rawTitle =
                            movieDoc.selectFirst(
                                "meta[property=og:title]"
                            )
                                ?.attr("content")
                                ?.substringBefore("|")
                                ?.trim()

                                ?: movieDoc.selectFirst(
                                    "div.data h1"
                                )?.text()?.trim()

                        val realTitle =
                            rawTitle
                                ?.replace(
                                    Regex("""^Ver\s+""", RegexOption.IGNORE_CASE),
                                    ""
                                )
                                ?.replace(
                                    Regex("""\s+Online Latino.*$""", RegexOption.IGNORE_CASE),
                                    ""
                                )
                                ?.trim()

                                ?: movie.name

                                ?: "Película ${i + 1}"

                        val realPoster =
                            movieDoc.selectFirst(
                                "meta[property=og:image]"
                            )
                                ?.attr("content")

                                ?: movieDoc.selectFirst(
                                    "div.poster img"
                                )?.attr("src")

                                ?: saga.poster

                        val rawDescription =
                            movieDoc.selectFirst(
                                "meta[name=description]"
                            )
                                ?.attr("content")

                                ?: movieDoc.selectFirst(
                                    "div.wp-content"
                                )?.text()?.trim()

                        val realDescription =
                            rawDescription
                                ?.replace(
                                    Regex(
                                        """^Ver\s+.*?\s+en\s+español\s+latino\s+online\.?\s*""",
                                        RegexOption.IGNORE_CASE
                                    ),
                                    ""
                                )
                                ?.trim()

                        episodes.add(
                            newEpisode(movieUrl) {

                                this.name =
                                    realTitle

                                this.description =
                                    realDescription

                                this.posterUrl =
                                    realPoster

                                this.episode =
                                    i + 1

                                this.season = 1
                            }
                        )

                    } catch (_: Exception) {

                        episodes.add(
                            newEpisode(movieUrl) {

                                this.name =
                                    movie.name
                                        ?: "Película ${i + 1}"

                                this.posterUrl =
                                    saga.poster

                                this.episode =
                                    i + 1

                                this.season = 1
                            }
                        )
                    }
                }

            return newTvSeriesLoadResponse(
                saga.title ?: "Saga",
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl =
                    saga.poster

                this.plot =
                    saga.description
            }
        }

        // =========================
        // LOAD NORMAL
        // =========================
        val doc = app.get(url).document

        val title = doc.selectFirst(
            "meta[property=og:title]"
        )
            ?.attr("content")
            ?.substringBefore("|")
            ?.trim()
            ?: "Sin título"

        val poster = doc.selectFirst(
            "meta[property=og:image]"
        )?.attr("content")

        val plot = doc.selectFirst(
            "meta[name=description]"
        )?.attr("content")
            ?: ""

        val isSeries =
            url.contains("/serie/")

        return if (!isSeries) {

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.plot = plot
            }

        } else {

            val episodes =
                mutableListOf<Episode>()

            doc.select("a.ep-item")
                .forEach { ep ->

                    val epUrl = fixUrl(
                        ep.attr("href")
                    )

                    val epNum = ep.selectFirst(
                        ".ep-num"
                    )
                        ?.text()
                        ?.replace("E", "")
                        ?.trim()
                        ?.toIntOrNull()

                    val season = Regex(
                        """temporada-(\d+)"""
                    )
                        .find(epUrl)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: 1

                    val epTitle = ep.selectFirst(
                        "p.text-sm, p.leading-tight"
                    )
                        ?.text()
                        ?.trim()

                    val extra = ep.select(
                        "p.text-xs, p.line-clamp-2"
                    ).map {
                        it.text().trim()
                    }

                    val description =
                        extra.firstOrNull {
                            !it.matches(
                                Regex("""\d{2}/\d{2}/\d{4}""")
                            ) && it.length > 5
                        }

                    val thumb =
                        ep.selectFirst("img")
                            ?.let {
                                it.attr("data-src")
                                    .ifBlank {
                                        it.attr("src")
                                    }
                            }

                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epTitle
                            this.description = description
                            this.episode = epNum
                            this.season = season
                            this.posterUrl =
                                thumb ?: poster
                        }
                    )
                }

            val sorted = episodes.sortedWith(
                compareBy(
                    { it.season },
                    { it.episode }
                )
            )

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                sorted
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // =========================
    // LOAD LINKS
    // =========================
    // =========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc =
            app.get(data).document

        val extracted =
            mutableSetOf<String>()

        // =========================
        // BOTONES
        // =========================
        val servers =
            doc.select("button.server-btn")
                .mapNotNull {

                    val url =
                        it.attr("data-server-url")
                            .trim()

                    if (
                        url.isBlank()
                    ) {
                        return@mapNotNull null
                    }

                    url
                }

        // =========================
        // FALLBACK GLOBAL
        // =========================
        val htmlServers =
            Regex(
                """https?:\/\/[^\s"'<>]+"""
            )
                .findAll(doc.html())
                .map {
                    it.value
                }
                .filter {

                    it.contains(
                        "embed69"
                    ) ||

                            it.contains(
                                "pelisserieshoy"
                            ) ||

                            it.contains(
                                "/f/tt"
                            ) ||

                            it.contains(
                                "playhydrax"
                            )
                }

        val allServers =
            (servers + htmlServers)
                .distinct()

        allServers.forEach { server ->

            try {

                val fixed =
                    fixHostsLinks(server)

                if (
                    !extracted.add(fixed)
                ) {
                    return@forEach
                }

                // =========================
                // EMBED69
                // =========================
                if (
                    fixed.contains("embed69") ||
                    fixed.contains("pelisserieshoy")
                ) {

                    Embed69Extractor.load(
                        fixed,
                        data,
                        subtitleCallback,
                        callback
                    )

                    return@forEach
                }

                // =========================
                // PLAYHYDRAX
                // =========================
                if (
                    fixed.contains("playhydrax")
                ) {

                    PlayHydrax().getUrl(
                        fixed,
                        data,
                        subtitleCallback,
                        callback
                    )

                    return@forEach
                }

                // =========================
                // RESTO
                // =========================
                loadExtractor(
                    fixed,
                    data,
                    subtitleCallback,
                    callback
                )

            } catch (_: Exception) {}
        }

        return extracted.isNotEmpty()
    }

    // =========================
    // GET SERVERS
    // =========================
    private suspend fun getServersFromIframe(
        iframeUrl: String,
        referer: String
    ): List<String> {

        val results =
            mutableListOf<String>()

        try {

            val res = app.get(
                iframeUrl,
                headers = mapOf(
                    "Referer" to referer
                )
            )

            val html = res.text
            val doc = res.document

            Regex(
                """dataLink\s*=\s*(\[.*?\]);"""
            )
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { json ->

                    val parsed =
                        AppUtils.tryParseJson<
                                List<Map<String, Any>>
                                >(json)
                            ?: return@let

                    parsed.forEach { lang ->

                        val embeds =
                            lang["sortedEmbeds"]
                                    as? List<Map<String, Any>>
                                ?: return@forEach

                        embeds.forEach {

                            val enc =
                                it["link"] as? String
                                    ?: return@forEach

                            decodeBase64Link(enc)
                                ?.let { url ->
                                    results.add(url)
                                }
                        }
                    }
                }

            Regex(
                """go_to_playerVast\(\s*['"]([^'"]+)"""
            )
                .findAll(html)
                .mapNotNull {
                    it.groupValues.getOrNull(1)
                }
                .forEach {
                    results.add(it)
                }

            doc.select("iframe")
                .forEach {

                    val src =
                        it.attr("src")

                    if (
                        src.startsWith("http") &&
                        src != iframeUrl
                    ) {

                        results.addAll(
                            getServersFromIframe(
                                src,
                                iframeUrl
                            )
                        )
                    }
                }

        } catch (_: Exception) {}

        return results.distinct()
    }

    // =========================
    // BASE64
    // =========================
    private fun decodeBase64Link(
        enc: String
    ): String? {

        return try {

            val parts =
                enc.split(".")

            if (parts.size != 3) {
                return null
            }

            var payload =
                parts[1]

            val pad =
                payload.length % 4

            if (pad != 0) {
                payload += "=".repeat(
                    4 - pad
                )
            }

            val json = String(
                Base64.decode(
                    payload,
                    Base64.DEFAULT
                )
            )

            Regex(
                "\"link\":\"(.*?)\""
            )
                .find(json)
                ?.groupValues
                ?.getOrNull(1)

        } catch (_: Exception) {
            null
        }
    }

    // =========================
    // FIX HOSTS
    // =========================
    private fun fixHostsLinks(
        url: String
    ): String {

        return url
            .replace(
                "hglink.to",
                "streamwish.to"
            )
            .replace(
                "swdyu.com",
                "streamwish.to"
            )
            .replace(
                "wishembed.com",
                "streamwish.to"
            )

            .replace(
                "vidhide.com",
                "vidhidepro.com"
            )

            .replace(
                "filemoon.link",
                "filemoon.sx"
            )

            .replace(
                "doodstream.com",
                "dood.la"
            )

            .replace(
                "sbfull.com",
                "watchsb.com"
            )

            .replace(
                "uqload.io",
                "uqload.com"
            )

            .replace(
                "voe.sx",
                "voe.unblockit.cat"
            )
    }
}