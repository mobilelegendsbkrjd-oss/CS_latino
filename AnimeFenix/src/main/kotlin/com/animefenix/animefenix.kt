package com.animefenix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import kotlin.plus

class Animefenix : MainAPI() {
    override var mainUrl = "https://animefenix2.tv"
    override var name = "Animefenix"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)

    private val afHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
        "Referer" to mainUrl
    )

    override val mainPage = mainPageOf(
        "$mainUrl/directorio/anime?tipo=1&idioma=2&q=&p=" to "Anime Doblado",
        "$mainUrl/directorio/anime?idioma=1&q=&p=" to "Anime Subtitulado",
        "$mainUrl/directorio/anime?tipo=2&idioma=2&q=&p=" to "Películas Doblado",
        "$mainUrl/directorio/anime?tipo=2&idioma=1&q=&p=" to "Películas Subtitulado",
        "$mainUrl/directorio/anime?estreno=2025&p=" to "Estrenos 2025",
        "$mainUrl/directorio/anime?estreno=2026&p=" to "Estrenos 2026"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page, headers = afHeaders).document
        val items = doc.select("li article, .grid-animes li, article, .carousel-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/directorio/anime?q=$query&p=1", headers = afHeaders).document
        return doc.select("li article, .grid-animes li, article, .carousel-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.getImage(): String? {
        val img = selectFirst("img") ?: return null
        var image = img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { img.attr("abs:src") }
        if (image.startsWith("//")) image = "https:$image"
        if (image.startsWith("/")) image = "$mainUrl$image"
        return image.ifBlank { null }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null

        val title = selectFirst("h3, p:not(.gray)")?.text()?.trim()
            ?: a.attr("title").trim()
            ?: return null

        var href = a.attr("href").ifBlank { return null }
        if (href.startsWith("/")) href = "$mainUrl$href"

        return newTvSeriesSearchResponse(title, href, TvType.Anime) {
            posterUrl = getImage()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = afHeaders).document
        val slug = url.substringBefore("?").substringAfterLast("/")

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Animefenix"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("#anime_image")?.attr("src")

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("h2:contains(Sinopsis) + p")?.text()
            ?: doc.selectFirst("p.text-gray-300")?.text()

        val statusText = doc.selectFirst("span.font-semibold, a[href*=estado]")?.text().orEmpty()

        val status = when {
            statusText.contains("emision", true) || statusText.contains("emisión", true) -> ShowStatus.Ongoing
            statusText.contains("finalizado", true) -> ShowStatus.Completed
            else -> null
        }

        val episodes = mutableListOf<Episode>()

        fun parseEpisodesFrom(htmlDoc: org.jsoup.nodes.Document) {
            htmlDoc.select("a.episode-card[href], a[href*=/ver/]").forEach { a ->
                var epUrl = a.attr("abs:href").ifBlank { a.attr("href") }
                if (epUrl.startsWith("/")) epUrl = "$mainUrl$epUrl"
                if (!epUrl.contains("/ver/")) return@forEach

                val epTitle = a.selectFirst(".ep-title")?.text()?.trim()
                    ?: a.attr("title").trim()
                    ?: a.selectFirst("img")?.attr("alt")?.trim()
                    ?: "Capítulo"

                val epNum = Regex("""(?:Capítulo|Capitulo|Episodio|Ep\.?)\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(epTitle)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: Regex("""-(\d+)$""")
                        .find(epUrl.substringBefore("?"))
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                episodes.add(
                    newEpisode(epUrl) {
                        name = epTitle
                        episode = epNum
                        posterUrl = a.selectFirst("img")?.attr("abs:src")
                            ?: a.selectFirst("img")?.attr("src")
                    }
                )
            }
        }

        parseEpisodesFrom(doc)

        val starts = doc.select(".episode-btn[onclick]")
            .mapNotNull {
                Regex("""loadEpisodes\((\d+)""")
                    .find(it.attr("onclick"))
                    ?.groupValues
                    ?.getOrNull(1)
            }
            .ifEmpty { listOf("0") }
            .distinct()

        starts.forEach { start ->
            val epDoc = app.get(
                "$url?id=$slug&load=episodes&start=$start",
                headers = afHeaders + ("X-Requested-With" to "XMLHttpRequest")
            ).document

            parseEpisodesFrom(epDoc)
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.Anime,
            episodes.distinctBy { it.data }.sortedBy { it.episode ?: 0 }
        ) {
            posterUrl = poster
            this.plot = plot
            showStatus = status
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = if (data.startsWith("/")) "$mainUrl$data" else data

        val doc = app.get(
            pageUrl,
            headers = afHeaders + ("Referer" to mainUrl)
        ).document

        val script = doc.selectFirst("script:containsData(var tabsArray)")
            ?.data()
            ?: return false

        val servers = mutableListOf<Pair<String, String>>()

        val names = doc.select(".episode-page__servers-list li a").map {
            it.select("span").last()?.text()?.trim()
                ?: it.attr("title").trim()
                ?: "Servidor"
        }

        val urls = script
            .substringAfter("<iframe")
            .split("src='")
            .drop(1)
            .map {
                it.substringBefore("'")
                    .substringAfter("redirect.php?id=")
                    .trim()
            }

        val count = minOf(urls.size, names.size)

        for (i in 0 until count) {
            val name = names[i].ifBlank { "Servidor" }
            val url = urls[i]
            if (url.startsWith("http")) {
                servers.add(name to url)
            }
        }

        // Links de descarga: en esta peli traen StreamTape y Voex, útiles como fallback.
        doc.select("a[href*=smart.php?url=]").forEach { a ->
            val name = a.text().replace("Descargas", "", true).trim().ifBlank { "Descarga" }
            val url = a.attr("href")
                .substringAfter("smart.php?url=")
                .trim()

            if (url.startsWith("http")) {
                servers.add(name to url)
            }
        }

        fun priority(item: Pair<String, String>): Int {
            val name = item.first.lowercase()
            val url = item.second.lowercase()

            return when {
                name.contains("streamtape") || url.contains("streamtape") -> 1
                name.contains("voe") || name.contains("voex") || url.contains("voe") -> 2
                name.contains("uqload") || url.contains("uqload") -> 3
                name.contains("streamsb") || url.contains("streamsb") || url.contains("viewsb") -> 4
                name.contains("streamhide") || url.contains("ahvsh") -> 5
                name.contains("plus") || url.contains("ironhentai") -> 6
                name.contains("netu") || url.contains("hqq") || url.contains("waaw") -> 99
                else -> 20
            }
        }

        val finalServers = servers
            .distinctBy { it.second }
            .sortedBy { priority(it) }

        var found = false

        finalServers.forEach { (_, rawUrl) ->
            try {
                if (UniversalVideoResolver.resolve(rawUrl, pageUrl, subtitleCallback, callback)) {
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        return found
    }
}