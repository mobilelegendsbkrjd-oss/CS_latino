package com.sololatino

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SoloLatino : MainAPI() {

    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override val hasMainPage = true
    override var lang = "es"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // =========================
    // PARSER CENTRAL
    // =========================
    private fun parseCards(doc: org.jsoup.nodes.Element): List<SearchResponse> {
        return doc.select(".card, article.card").mapNotNull { card ->

            val a = card.selectFirst("a") ?: return@mapNotNull null
            val link = fixUrl(a.attr("href"))

            val name = card.selectFirst(".card__title, h3, h2")?.text()
                ?: return@mapNotNull null

            val poster = card.selectFirst("img")?.let {
                it.attr("data-src").ifBlank {
                    it.attr("data-lazy-src").ifBlank {
                        it.attr("src")
                    }
                }
            }?.replace(Regex("-\\d+x\\d+"), "")

            val type = if (link.contains("/serie/"))
                TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(name, link, type) {
                this.posterUrl = poster
            }
        }
    }

    // =========================
    // MAIN PAGE
    // =========================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val doc = app.get(mainUrl).document
        val lists = mutableListOf<HomePageList>()

        doc.select("section").forEach { section ->

            val title = section.selectFirst("h2, .section-title")?.text() ?: return@forEach

            val items = parseCards(section)

            if (items.size < 3) return@forEach

            lists.add(HomePageList(title, items))
        }

        return newHomePageResponse(lists)
    }

    // =========================
    // SEARCH (CON FALLBACK)
    // =========================
    override suspend fun search(query: String): List<SearchResponse> {

        val results = mutableListOf<SearchResponse>()

        val urls = listOf(
            "$mainUrl/buscar?q=$query",
            "$mainUrl/search?q=$query"
        )

        for (base in urls) {
            for (page in 1..3) {
                try {
                    val url = "$base&page=$page"
                    val doc = app.get(url).document
                    val items = parseCards(doc)

                    if (items.isEmpty()) break
                    results.addAll(items)
                } catch (_: Exception) {}
            }
        }

        return results.distinctBy { it.url }
    }

    // =========================
    // LOAD (METADATA PRO)
    // =========================
    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title = doc.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.substringBefore("|")
            ?.trim() ?: "Sin título"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""

        val isSeries = url.contains("/serie/")

        return if (!isSeries) {

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }

        } else {

            val episodes = mutableListOf<Episode>()

            doc.select("a.ep-item").forEach { ep ->

                val epUrl = fixUrl(ep.attr("href"))

                val epNum = ep.selectFirst(".ep-num")
                    ?.text()
                    ?.replace("E", "")
                    ?.trim()
                    ?.toIntOrNull()

                val season = Regex("""temporada-(\d+)""")
                    .find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

                val epTitle = ep.selectFirst("p.text-sm, p.leading-tight")
                    ?.text()
                    ?.trim()

                val extra = ep.select("p.text-xs, p.line-clamp-2")
                    .map { it.text().trim() }

                val description = extra.firstOrNull {
                    !it.matches(Regex("""\d{2}/\d{2}/\d{4}""")) &&
                            it.length > 5
                }

                val thumb = ep.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank {
                        it.attr("src")
                    }
                }

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epTitle
                        this.description = description
                        this.episode = epNum
                        this.season = season
                        this.posterUrl = thumb ?: poster
                    }
                )
            }

            val sorted = episodes.sortedWith(compareBy({ it.season }, { it.episode }))

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // =========================
    // LINKS (ULTRA ROBUSTO)
    // =========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        val servers = doc.select("button.server-btn")
            .mapNotNull { it.attr("data-server-url") }

        val extracted = mutableSetOf<String>()

        for (server in servers) {

            val fixedServer = fixHostsLinks(server)

            if (fixedServer.contains("xupalace")) {
                XupalaceExtractor().getUrl(
                    fixedServer,
                    data,
                    subtitleCallback,
                    callback
                )
                continue
            }

            val links = getServersFromIframe(server, data)

            links.forEach { link ->
                val fixed = fixHostsLinks(link)

                val refererFinal = if (
                    link.contains("vidhide") ||
                    link.contains("filemoon") ||
                    link.contains("voe")
                ) server else data

                if (extracted.add(fixed)) {
                    loadExtractor(fixed, refererFinal, subtitleCallback, callback)
                }
            }
        }

        return extracted.isNotEmpty()
    }

    private suspend fun getServersFromIframe(
        iframeUrl: String,
        referer: String
    ): List<String> {

        val results = mutableListOf<String>()

        try {
            val res = app.get(iframeUrl, headers = mapOf("Referer" to referer))
            val html = res.text
            val doc = res.document

            Regex("""dataLink\s*=\s*(\[.*?\]);""")
                .find(html)?.groupValues?.getOrNull(1)?.let { json ->

                    val parsed = AppUtils.tryParseJson<List<Map<String, Any>>>(json)
                        ?: return@let

                    parsed.forEach { lang ->
                        val embeds = lang["sortedEmbeds"] as? List<Map<String, Any>>
                            ?: return@forEach

                        embeds.forEach {
                            val enc = it["link"] as? String ?: return@forEach
                            decodeBase64Link(enc)?.let { results.add(it) }
                        }
                    }
                }

            Regex("""go_to_playerVast\(\s*['"]([^'"]+)""")
                .findAll(html)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .forEach { results.add(it) }

            doc.select("iframe").forEach {
                val src = it.attr("src")
                if (src.startsWith("http") && src != iframeUrl) {
                    results.addAll(getServersFromIframe(src, iframeUrl))
                }
            }

        } catch (_: Exception) {}

        return results.distinct()
    }

    private fun decodeBase64Link(enc: String): String? {
        return try {
            val parts = enc.split(".")
            if (parts.size != 3) return null

            var payload = parts[1]
            val pad = payload.length % 4
            if (pad != 0) payload += "=".repeat(4 - pad)

            val json = String(Base64.decode(payload, Base64.DEFAULT))

            Regex("\"link\":\"(.*?)\"")
                .find(json)?.groupValues?.getOrNull(1)

        } catch (_: Exception) {
            null
        }
    }

    private fun fixHostsLinks(url: String): String {
        return url
            .replace("hglink.to", "streamwish.to")
            .replace("swdyu.com", "streamwish.to")
            .replace("wishembed.com", "streamwish.to")

            .replace("vidhide.com", "vidhidepro.com")

            .replace("filemoon.link", "filemoon.sx")

            .replace("doodstream.com", "dood.la")

            .replace("sbfull.com", "watchsb.com")

            .replace("uqload.io", "uqload.com")

            .replace("voe.sx", "voe.unblockit.cat")
    }
}