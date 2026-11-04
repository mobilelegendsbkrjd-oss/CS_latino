package com.henaojara

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Henaojara : MainAPI() {
    override var mainUrl = "https://ww1.henaojara.net"
    override var name = "HenaoJara"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)

    private val hjHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
        "Referer" to mainUrl
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Últimos Episodios",
        "$mainUrl/animes?tipo=anime" to "Series",
        "$mainUrl/animes?tipo=pelicula" to "Películas",
        "$mainUrl/animes?estado=en-emision" to "En Emisión"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?")) "${request.data}&p=$page" else "${request.data}?p=$page"
        } else request.data

        val doc = app.get(url, headers = hjHeaders).document

        val items = doc.select(".ul .li, article.li")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(listOf(HomePageList(request.name, items)), items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/animes?buscar=$query", headers = hjHeaders).document
        return doc.select(".ul .li, article.li")
            .mapNotNull { it.toSearchResult() }
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
        val href = a.attr("abs:href").ifBlank { a.attr("href") }
        if (href.isBlank()) return null

        // Solo queremos animes o episodios de este dominio
        if (!href.contains(mainUrl) && !href.startsWith("/")) return null
        if (href.contains("somoskudasai") || href.contains("target=_blank")) return null

        val title = selectFirst("h3.h a")?.text()?.trim()
            ?: a.attr("title")?.trim()
            ?: a.text().trim()
            ?: return null

        val finalUrl = when {
            href.contains("/ver/") -> {
                // Convertir episodio a página del anime
                val slug = href.substringAfter("/ver/")
                    .substringBeforeLast("-")
                    .substringBeforeLast("-") // por si tiene código
                "$mainUrl/anime/$slug"
            }
            href.contains("/anime/") -> href
            else -> href
        }

        return newTvSeriesSearchResponse(title, finalUrl, TvType.Anime) {
            posterUrl = getImage()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = hjHeaders).document

        val slug = doc.selectFirst("[data-sl]")?.attr("data-sl")
            ?: url.substringAfterLast("/").substringBefore("?")

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst(".info-b h1")?.text()?.trim()
            ?: "HenaoJara"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".info-a img")?.attr("abs:src")
            ?: doc.selectFirst(".info-a img")?.attr("data-src")

        val plot = doc.selectFirst(".tx p")?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val statusText = doc.selectFirst("span.e")?.text() ?: ""
        val status = when {
            statusText.contains("emision", ignoreCase = true) -> ShowStatus.Ongoing
            statusText.contains("finalizado", ignoreCase = true) -> ShowStatus.Completed
            else -> null
        }

        val episodes = mutableListOf<Episode>()

        // Leer el array "var eps = [...]"
        val epsScript = doc.selectFirst("script:containsData(var eps =)")?.data() ?: ""
        val epRegex = Regex("""\[\s*"(\d+)"\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*\]""")
        epRegex.findAll(epsScript).forEach { match ->
            val epNum = match.groupValues[1].toIntOrNull() ?: return@forEach
            val code = match.groupValues[3]
            val epUrl = "$mainUrl/ver/$slug-$epNum${if (code.isNotEmpty()) "-$code" else ""}"

            episodes.add(
                newEpisode(epUrl) {
                    name = "Episodio $epNum"
                    episode = epNum
                }
            )
        }

        // Fallback si no encuentra el script
        if (episodes.isEmpty()) {
            doc.select(".eplist li a, .ep li a").forEach { a ->
                val href = a.attr("abs:href")
                val name = a.selectFirst("i")?.text() ?: a.text()
                val epNum = Regex("""(\d+)""").find(name)?.value?.toIntOrNull()
                    ?: Regex("""-(\d+)(?:-|$)""").find(href)?.groupValues?.get(1)?.toIntOrNull()

                if (href.isNotBlank()) {
                    episodes.add(
                        newEpisode(href) {
                            this.name = name
                            this.episode = epNum
                        }
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.sortedBy { it.episode }) {
            this.posterUrl = poster
            this.plot = plot
            this.showStatus = status
        }
    }

    private fun hex2a(hex: String): String {
        val output = StringBuilder()
        var i = 0
        while (i < hex.length - 1) {
            val str = hex.substring(i, i + 2)
            output.append(Integer.parseInt(str, 16).toChar())
            i += 2
        }
        return output.toString()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = if (data.startsWith("/")) "$mainUrl$data" else data
        val doc = app.get(pageUrl, headers = hjHeaders + ("Referer" to mainUrl)).document

        val servers = mutableListOf<Triple<String, String, String>>() // name, url, tipo

        // 1. Servidores por AJAX (./hj)
        val encrypt = doc.selectFirst("ul.opt[data-encrypt]")?.attr("data-encrypt")
        if (!encrypt.isNullOrBlank()) {
            val optHtml = app.post(
                "$mainUrl/hj",
                headers = hjHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to pageUrl,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf("acc" to "opt", "i" to encrypt)
            ).text

            // Capturamos star="1" para VIP
            Regex("""<li[^>]*?(?:star="1")?[^>]*encrypt="([0-9a-fA-F]+)"[^>]*title="([^"]*)"""").findAll(optHtml).forEach { m ->
                val fullMatch = m.value
                val isVip = fullMatch.contains("star=\"1\"")
                val url = hex2a(m.groupValues[1])
                var name = m.groupValues[2]
                    .replace("Opción ", "", true)
                    .replace(Regex("""\s+\d+$"""), "")
                    .trim()
                    .ifBlank { "Servidor" }

                name = when {
                    name.contains("filemoon", true) || name.contains("byse", true) -> "Filemoon"
                    name.contains("dood", true) -> "Doodstream"
                    name.contains("lulu", true) -> "Lulustream"
                    name.contains("voe", true) -> "Voe"
                    name.contains("streamwish", true) || name.contains("dhcplay", true) -> "Streamwish"
                    name.contains("mixdrop", true) -> "Mixdrop"
                    name.contains("mp4upload", true) -> "Mp4upload"
                    name.contains("uqload", true) -> "Uqload"
                    name.contains("vidguard", true) || name.contains("listeamed", true) -> "Vidguard"
                    else -> name
                }

                if (url.startsWith("http")) {
                    servers.add(Triple(name, url, if (isVip) "VIP" else "FREE"))
                }
            }
        }

        // 2. Links de descarga (data-dwn)
        val dwnAttr = doc.selectFirst(".dwn[data-dwn]")?.attr("data-dwn")
        if (!dwnAttr.isNullOrBlank()) {
            // Puede venir como JSON array
            val urls = if (dwnAttr.startsWith("[")) {
                Regex(""""(https?://[^"]+)"""").findAll(dwnAttr).map { it.groupValues[1] }.toList()
            } else {
                dwnAttr.split(",").map { it.trim().trim('"') }
            }

            urls.forEach { raw ->
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
                        url.contains("vidguard") || url.contains("listeamed") -> "Vidguard"
                        else -> "Descarga"
                    }
                    servers.add(Triple(name, url, "FREE"))
                }
            }
        }

        // Prioridad
        fun priority(item: Triple<String, String, String>): Int {
            val (name, url, tipo) = item
            val base = when {
                name.contains("Filemoon", true) || url.contains("filemoon") || url.contains("bysesukior") -> 1
                name.contains("Streamwish", true) || url.contains("streamwish") || url.contains("dhcplay") -> 2
                name.contains("Voe", true) || url.contains("voe") -> 3
                name.contains("Dood", true) || url.contains("dood") -> 4
                name.contains("Lulu", true) || url.contains("lulu") -> 5
                name.contains("Mixdrop", true) || url.contains("mixdrop") -> 6
                name.contains("Mp4upload", true) || url.contains("mp4upload") -> 7
                name.contains("Uqload", true) || url.contains("uqload") -> 8
                else -> 20
            }
            return if (tipo == "VIP") base else base + 100
        }

        val finalServers = servers
            .distinctBy { it.second }
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

                // Usa tu UniversalVideoResolver (el mismo de AnimeFenix)
                if (UniversalVideoResolver.resolve(cleanUrl, pageUrl, subtitleCallback, wrappedCallback)) {
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        return found
    }
}