package com.animefenix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

class Animefenix : MainAPI() {
    override var mainUrl = "https://ww1.animefenix.vip"
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
        "$mainUrl/" to "Últimos episodios",
        "$mainUrl/animes?estado=emision" to "En emisión",
        "$mainUrl/animes?estado=finalizado" to "Finalizados",
        "$mainUrl/animes?tipo=tv" to "Series TV",
        "$mainUrl/animes?tipo=pelicula" to "Películas",
        "$mainUrl/animes?ano=2026" to "Estrenos 2026",
        "$mainUrl/animes?ano=2025" to "Estrenos 2025"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}${if (request.data.contains("?")) "&" else "?"}p=$page" else request.data
        val doc = app.get(url, headers = afHeaders).document

        val items = doc.select("article.li, .ul .li, .grid-animes li, .carousel-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/animes?buscar=${query.replace(" ", "+")}", headers = afHeaders).document
        return doc.select("article.li, .ul .li")
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
        val title = selectFirst("h3 a, h3, .h a")?.text()?.trim()
            ?: a.attr("title").trim()
            ?: return null

        var href = a.attr("abs:href").ifBlank { a.attr("href") }
        if (href.startsWith("/")) href = "$mainUrl$href"
        // Limpiar URLs de episodios para que apunten a la serie
        if (href.contains("/ver/")) {
            val parts = href.substringAfter("/ver/").split("-")
            if (parts.size >= 2) {
                val slug = parts.dropLast(2).joinToString("-")
                val code = parts.last()
                href = "$mainUrl/$slug-$code"
            }
        }

        return newTvSeriesSearchResponse(title, href, TvType.Anime) {
            posterUrl = getImage()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = afHeaders).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Animefenix"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".inf-a img, #anime_image")?.attr("data-src")
            ?: doc.selectFirst(".inf-a img")?.attr("src")

        val plot = doc.selectFirst(".tx p, .inf-b .tx p")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val statusText = doc.selectFirst(".inf-a span, .lu li:contains(Estado)")?.text().orEmpty()
        val status = when {
            statusText.contains("emision", true) || statusText.contains("emisión", true) -> ShowStatus.Ongoing
            statusText.contains("finalizado", true) -> ShowStatus.Completed
            else -> null
        }

        val episodes = mutableListOf<Episode>()

        // Método principal: script var eps = [["4","tPGs"], ...]
        val epsScript = doc.select("script").map { it.data() }.firstOrNull { it.contains("var eps =") }
        val slug = doc.selectFirst("[data-sl]")?.attr("data-sl")
            ?: url.substringAfterLast("/").substringBeforeLast("-")

        if (epsScript != null) {
            val epsMatch = Regex("""var\s+eps\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
                .find(epsScript)?.groupValues?.getOrNull(1)

            if (epsMatch != null) {
                val pairs = Regex("""\["(\d+)","([^"]+)"\]""").findAll(epsMatch)
                pairs.forEach { match ->
                    val epNum = match.groupValues[1].toIntOrNull()
                    val code = match.groupValues[2]
                    val epUrl = "$mainUrl/ver/$slug-$epNum-$code"
                    episodes.add(
                        newEpisode(epUrl) {
                            name = "Episodio $epNum"
                            episode = epNum
                        }
                    )
                }
            }
        }

        // Fallback: links directos
        if (episodes.isEmpty()) {
            doc.select("#eplist a[href*=/ver/], a[href*=/ver/]").forEach { a ->
                var epUrl = a.attr("abs:href").ifBlank { a.attr("href") }
                if (epUrl.startsWith("/")) epUrl = "$mainUrl$epUrl"
                val epNum = Regex("""-(\d+)-[a-zA-Z0-9]+$""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""(?:Episodio|Capítulo|Capitulo)\s*(\d+)""", RegexOption.IGNORE_CASE)
                        .find(a.text())?.groupValues?.getOrNull(1)?.toIntOrNull()

                episodes.add(
                    newEpisode(epUrl) {
                        name = a.text().trim().ifBlank { "Episodio $epNum" }
                        episode = epNum
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.Anime,
            episodes.distinctBy { it.data }.sortedBy { it.episode ?: 0 }
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.showStatus = status
        }
    }

    private fun hex2a(hex: String): String {
        return try {
            hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        } catch (_: Exception) {
            ""
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = if (data.startsWith("/")) "$mainUrl$data" else data
        val doc = app.get(pageUrl, headers = afHeaders + ("Referer" to mainUrl)).document

        val servers = mutableListOf<Triple<String, String, String>>() // name, url, tipo (VIP/FREE)

        // 1. Servidores del AJAX (los que tienen star="1" son VIP)
        val encrypt = doc.selectFirst("ul.opt[data-encrypt]")?.attr("data-encrypt")
        if (!encrypt.isNullOrBlank()) {
            val optHtml = app.post(
                "$mainUrl/fnx",
                headers = afHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to pageUrl,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf("acc" to "opt", "i" to encrypt)
            ).text

            // Capturamos si tiene star="1"
            Regex("""<li[^>]*?(?:star="1")?[^>]*encrypt="([0-9a-fA-F]+)"[^>]*title="([^"]*)"""").findAll(optHtml).forEach { m ->
                val fullMatch = m.value
                val isVip = fullMatch.contains("star=\"1\"")
                val url = hex2a(m.groupValues[1])
                var name = m.groupValues[2]
                    .replace("Opción ", "", true)
                    .replace(Regex("""\s+\d+$"""), "")
                    .trim()
                    .ifBlank { "Servidor" }

                // Limpiamos nombres feos
                name = when {
                    name.contains("filemoon", true) -> "Filemoon"
                    name.contains("byse", true) -> "Byse"
                    name.contains("dood", true) -> "Doodstream"
                    name.contains("lulu", true) -> "Lulustream"
                    name.contains("voe", true) -> "Voe"
                    name.contains("uqload", true) -> "Uqload"
                    name.contains("mp4upload", true) -> "Mp4upload"
                    name.contains("mixdrop", true) -> "Mixdrop"
                    else -> name
                }

                if (url.startsWith("http")) {
                    val tipo = if (isVip) "VIP" else "FREE"
                    servers.add(Triple(name, url, tipo))
                }
            }
        }

        // 2. Links de descarga (siempre FREE)
        val dwn = doc.selectFirst("ul.dw[data-dwn]")?.attr("data-dwn")
        if (!dwn.isNullOrBlank()) {
            dwn.split(",").forEach { raw ->
                val url = raw.trim()
                if (url.startsWith("http")) {
                    val name = when {
                        url.contains("filemoon") || url.contains("bysesukior") -> "Filemoon"
                        url.contains("dood") -> "Doodstream"
                        url.contains("streamwish") || url.contains("dhcplay") -> "Streamwish"
                        url.contains("voe") -> "Voe"
                        url.contains("mixdrop") -> "Mixdrop"
                        url.contains("mp4upload") -> "Mp4upload"
                        url.contains("uqload") -> "Uqload"
                        url.contains("lulu") -> "Lulustream"
                        else -> "Descarga"
                    }
                    servers.add(Triple(name, url, "FREE"))
                }
            }
        }

        // Prioridad: VIP primero, luego por servidor
        fun priority(item: Triple<String, String, String>): Int {
            val (name, url, tipo) = item
            val base = when {
                name.contains("Filemoon", true) || url.contains("filemoon") || url.contains("bysesukior") -> 1
                name.contains("Streamwish", true) || url.contains("streamwish") || url.contains("dhcplay") -> 2
                name.contains("Voe", true) || url.contains("voe") -> 3
                name.contains("Dood", true) || url.contains("dood") -> 4
                name.contains("Mixdrop", true) || url.contains("mixdrop") -> 5
                name.contains("Mp4upload", true) || url.contains("mp4upload") -> 6
                name.contains("Uqload", true) || url.contains("uqload") -> 7
                name.contains("Lulu", true) || url.contains("lulu") -> 8
                else -> 20
            }
            return if (tipo == "VIP") base else base + 100
        }

        val finalServers = servers
            .distinctBy { it.second } // evitamos duplicados por URL
            .sortedBy { priority(it) }

        var found = false

        for ((name, rawUrl, tipo) in finalServers) {
            try {
                val cleanUrl = when {
                    rawUrl.contains("bysesukior.com/d/") -> rawUrl.replace("/d/", "/e/")
                    rawUrl.contains("doodstream.com/d/") -> rawUrl.replace("/d/", "/e/")
                    rawUrl.contains("dhcplay.com/f/") -> rawUrl.replace("/f/", "/e/")
                    else -> rawUrl
                }

                val displayName = "[$tipo] $name"

                val wrappedCallback: (ExtractorLink) -> Unit = { link ->
                    callback.invoke(
                        ExtractorLink(
                            source = link.source,
                            name = displayName,
                            url = link.url,
                            referer = link.referer,
                            quality = link.quality,
                            type = link.type,
                            headers = link.headers,
                            extractorData = link.extractorData
                        )
                    )
                }

                if (UniversalVideoResolver.resolve(cleanUrl, pageUrl, subtitleCallback, wrappedCallback)) {
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        return found
    }
}