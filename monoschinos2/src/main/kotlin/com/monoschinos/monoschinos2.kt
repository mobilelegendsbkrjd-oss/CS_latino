package com.monoschinos2

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import android.util.Base64
import okhttp3.FormBody

class MonosChinos2 : MainAPI() {
    override var mainUrl = "https://vww.monoschinos2.net"
    override var name = "MonosChinos2"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to mainUrl,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "X-Requested-With" to "XMLHttpRequest",
        "Accept-Language" to "es-419,es;q=0.8",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
    )

    override val mainPage = mainPageOf(
        // Quitamos la categoría general de "Últimos episodios"
        "$mainUrl/animes?tipo=anime&orden=desc&pag=" to "Animes (TV)",
        "$mainUrl/animes?tipo=pelicula&orden=desc&pag=" to "Películas",
        "$mainUrl/animes?tipo=donghua&orden=desc&pag=" to "Donghua",
        "$mainUrl/animes?tipo=especial&orden=desc&pag=" to "Especiales",
        "$mainUrl/animes?tipo=ona&orden=desc&pag=" to "ONA",
        "$mainUrl/animes?tipo=ova&orden=desc&pag=" to "OVA",
        "$mainUrl/animes?tipo=corto&orden=desc&pag=" to "Corto",
        // Nuevas categorías exclusivas Latino
        "$mainUrl/animes?buscar=latino&tipo=anime&orden=desc&pag=" to "Anime Latino",
        "$mainUrl/animes?buscar=latino&tipo=pelicula&orden=desc&pag=" to "Películas Latino"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("pag=")) "${request.data}$page" else request.data
        val doc = app.get(url, headers = requestHeaders).document
        val homeList = mutableListOf<HomePageList>()

        // Lista de animes/películas según la categoría
        val items = doc.select("div#listanime ul li a[href*='/anime/'], div#listanime ul li a[href*='/ver/']").mapNotNull { el ->
            el.toSearchResult()
        }.distinctBy { it.url }

        if (items.isNotEmpty()) {
            homeList.add(HomePageList(request.name, items))
        }

        // Últimos episodios solo si es la home general (opcional, pero lo dejamos por si acaso)
        if (request.data == "$mainUrl/animes") {
            val latestEp = doc.select("section ul.row li.col article a[href*='/ver/']").mapNotNull { el ->
                el.toSearchResult()
            }
            if (latestEp.isNotEmpty()) {
                homeList.add(HomePageList("Últimos episodios", latestEp))
            }
        }

        return HomePageResponse(homeList)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").trim()
        if (href.isBlank()) return null

        val title = selectFirst("h3.title_cap, h2")?.text()?.trim() ?: return null
        val poster = selectFirst("img")?.attr("data-src")?.ifBlank { selectFirst("img")?.attr("src") }

        return newTvSeriesSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = poster?.let { fixUrl(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.trim().isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/animes?buscar=$encoded"
        val doc = app.get(url, headers = requestHeaders).document
        return doc.select("div#listanime ul li a[href*='/anime/']").mapNotNull { el ->
            el.toSearchResult()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = requestHeaders).document

        val title = doc.selectFirst("h1.fs-3.my-3.text-light")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" | ")?.trim()
            ?: "Sin título"

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("img.lazy[data-src]")?.attr("data-src")
            ?: doc.selectFirst("img[src*='/cdn/img/anime/']")?.attr("src")

        val plot = doc.selectFirst(".txt.sp p")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: ""

        val episodes = mutableListOf<Episode>()

        // Caso especial: episodios individuales (cortos, picture dramas, etc.)
        if (url.contains("/ver/") && doc.selectFirst("section.caplist[data-e]") == null) {
            val epNum = url.substringAfterLast("-").replace("episodio-", "").toIntOrNull() ?: 1
            episodes.add(newEpisode(url) {
                name = "Episodio $epNum (Especial / Corto / Picture Drama)"
                season = 1
                episode = epNum
                posterUrl = poster
            })
        } else {
            // Series normales con múltiples episodios
            val episodeCount = doc.selectFirst("div.ep_count")?.text()?.toIntOrNull() ?: 0
            val animeSlug = url.substringAfterLast("/")

            if (episodeCount > 0 && animeSlug.isNotBlank()) {
                for (i in 1..episodeCount) {
                    episodes.add(newEpisode("$mainUrl/ver/$animeSlug-episodio-$i") {
                        name = "Episodio $i"
                        season = 1
                        episode = i
                        posterUrl = poster
                    })
                }
            } else {
                // Fallback: buscar todos los links /ver/ en la página
                doc.select("a[href*='/ver/']").map { it.attr("href") }.distinct().forEach { href ->
                    if (href.isNotBlank()) {
                        val epNumStr = href.substringAfterLast("-").replace("episodio-", "").replace(Regex("[^0-9]"), "")
                        val epNum = epNumStr.toIntOrNull() ?: (episodes.size + 1)
                        episodes.add(newEpisode(fixUrl(href)) {
                            name = "Episodio $epNum"
                            season = 1
                            episode = epNum
                            posterUrl = poster
                        })
                    }
                }
            }
        }

        episodes.sortBy { it.episode }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
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
        var found = false

        val doc = app.get(data, headers = requestHeaders, timeout = 45).document

        val encrypt = doc.selectFirst("ul.nav-tabs.opt, ul.nav-tabs")?.attr("data-encrypt") ?: return false

        val formBody = FormBody.Builder()
            .add("acc", "opt")
            .add("i", encrypt)
            .build()

        val ajaxResponse = app.post(
            "$mainUrl/ajax_pagination",
            headers = requestHeaders,
            requestBody = formBody,
            timeout = 45
        )

        val serverDoc = ajaxResponse.document

        serverDoc.select("button.play-video[data-player]").forEach { btn ->
            val base64 = btn.attr("data-player").trim()
            if (base64.isNotBlank()) {
                try {
                    val decoded = String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8).trim()
                    if (decoded.startsWith("http")) {
                        loadExtractor(decoded, data, subtitleCallback, callback)
                        found = true
                    }
                } catch (_: Throwable) {}
            }
        }

        doc.select("div.ifplay iframe[src], iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").trim()
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        serverDoc.select("div.ifplay iframe[src], iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").trim()
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        doc.select("a.btn-warning[target='_blank'][href]").forEach { a ->
            val downloadUrl = a.attr("abs:href").trim()
            if (downloadUrl.isNotBlank()) {
                loadExtractor(downloadUrl, data, subtitleCallback, callback)
                found = true
            }
        }

        return found
    }

    private fun extractPoster(imgElement: Element?): String? {
        if (imgElement == null) return null

        var src = imgElement.attr("data-src")
        if (src.isBlank()) src = imgElement.attr("src")

        return if (src.isNotBlank() && !src.contains("anime.png") && !src.contains("episode.png") && !src.contains("placeholder")) {
            if (src.startsWith("http")) src else fixUrl(src)
        } else null
    }
}