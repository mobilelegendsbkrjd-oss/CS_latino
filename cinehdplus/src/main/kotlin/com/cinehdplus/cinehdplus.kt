package com.cinehdplus

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class CineHDPlus : MainAPI() {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
    }

    override var mainUrl = "https://cinehdplus.org"
    private val apiUrl = "https://api.cinehdplus.org"

    override var name = "CineHDPlus"
    override val hasMainPage = true
    override var lang = "es"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas" to "Películas",
        "$mainUrl/series" to "Series",
        "$mainUrl/populares" to "Populares"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.contains("/populares") -> {
                val period = when (page % 4) {
                    0 -> "day"
                    1 -> "week"
                    2 -> "month"
                    else -> "year"
                }
                "$mainUrl/populares/?period=$period"
            }

            request.data.contains("/series") ->
                if (page == 1) request.data else "$mainUrl/series/page/$page/"

            request.data.contains("/peliculas") ->
                if (page == 1) request.data else "$mainUrl/peliculas/page/$page/"

            else -> request.data
        }

        val document = app.get(url, headers = pageHeaders()).document
        val results = mutableListOf<SearchResponse>()

        document.select(
            "article, div.group.relative, div.grid div.group, div.grid a, a[href*=/pelicula-], a[href*=/tvshows/], a[href*=/serie-]"
        ).forEach { item ->
            val result = toSearchResult(item) ?: return@forEach
            if (results.none { it.url == result.url }) results.add(result)
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, results.distinctBy { it.url })),
            true
        )
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val a: Element = if (element.tagName() == "a") {
            element
        } else {
            element.selectFirst("a[href*=/pelicula-], a[href*=/tvshows/], a[href*=/serie-]")
                ?: return null
        }

        val link: String = a.attr("href")
        if (link.isBlank()) return null

        val fixedLink: String = fixUrl(link)
        val isMovie = fixedLink.contains("/pelicula-", true)

        val title1: String = element.selectFirst("h3, h2, .title, .entry-title")?.text()?.trim() ?: ""
        val title2: String = a.selectFirst("img")?.attr("alt")?.trim() ?: ""
        val title3: String = element.selectFirst("img")?.attr("alt")?.trim() ?: ""
        val title4: String = a.attr("title").trim()

        val title = cleanTitle(
            title1.ifBlank {
                title2.ifBlank {
                    title3.ifBlank {
                        title4
                    }
                }
            }
        )

        if (title.isBlank()) return null

        val img1: Element? = element.selectFirst("img")
        val img2: Element? = a.selectFirst("img")

        val posterRaw: String? = pickImageFromElement(img1) ?: pickImageFromElement(img2)
        val poster: String? = fixImageUrl(posterRaw)

        return if (isMovie) {
            newMovieSearchResponse(title, fixedLink, TvType.Movie) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, fixedLink, TvType.TvSeries) {
                posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val urls = listOf(
            "$mainUrl/search/$query/",
            "$mainUrl/?s=$query"
        )

        for (url in urls) {
            try {
                val document = app.get(url, headers = pageHeaders()).document
                val results = document.select(
                    "article, div.group.relative, div.grid div.group, div.grid a, a[href*=/pelicula-], a[href*=/tvshows/], a[href*=/serie-]"
                ).mapNotNull { toSearchResult(it) }

                if (results.isNotEmpty()) return results.distinctBy { it.url }
            } catch (_: Exception) {
            }
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = pageHeaders()).document

        val isTv = document.selectFirst("body.tvshows-template-default") != null ||
                url.contains("/tvshows/", true) ||
                url.contains("/serie-", true)

        val titleMeta: String? = document.selectFirst("meta[property=og:title]")?.attr("content")
        val titleText: String? = document.selectFirst("h2.text-xl, h1")?.text()

        val title = cleanTitle(
            (titleText ?: titleMeta ?: "Sin título")
                .substringBefore("|")
        )

        val posterMeta: String? = document.selectFirst("meta[property=og:image]")?.attr("content")
        val posterImgEl: Element? = document.selectFirst("div.aspect-2\\/3 img, img.absolute")
        val posterImg: String? = pickImageFromElement(posterImgEl)
        val posterHistory: String? = document.selectFirst("#rm-post-history")?.attr("data-poster")
        val poster: String? = fixImageUrl(posterImg ?: posterHistory ?: posterMeta)

        val backdropEl: Element? = document.selectFirst(".absolute.inset-0 img, .opacity-20")
        val backdrop: String? = fixImageUrl(pickImageFromElement(backdropEl))

        val plotMeta: String? = document.selectFirst("meta[property=og:description]")?.attr("content")
        val plotText: String? = document.selectFirst("div.prose-custom p")?.text()
        val plot: String? = (plotText ?: plotMeta)?.trim()

        val tags = document.select("a[href*=genero], .details__list li")
            .map { it.text().substringAfter(":").trim() }
            .filter { it.isNotBlank() }

        val year = Regex("""\((\d{4})\)""")
            .find(document.text())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        if (!isTv) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                backgroundPosterUrl = backdrop ?: poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }

        val episodes = mutableListOf<Episode>()

        document.select("div[id^=season-content-], div.season-pane").forEach { seasonBlock ->
            val season = seasonBlock.id()
                .substringAfter("season-content-")
                .toIntOrNull() ?: 1

            seasonBlock.select("a[href*=/episodio-], a.group").forEachIndexed { index, ep ->
                val href = fixUrl(ep.attr("href"))
                if (href.isBlank()) return@forEachIndexed

                val epTitle = cleanTitle(
                    ep.selectFirst("h3")?.text()?.trim()
                        ?: ep.selectFirst("h3 span")?.text()?.trim()
                        ?: "Episodio ${index + 1}"
                )

                val epImgEl: Element? = ep.selectFirst("img")
                val epPoster: String? = fixImageUrl(pickImageFromElement(epImgEl))

                val epNum = Regex("""(?:E|Episodio\s*)(\d+)""", RegexOption.IGNORE_CASE)
                    .find(epTitle)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: (index + 1)

                episodes.add(newEpisode(href) {
                    name = epTitle
                    this.season = season
                    episode = epNum
                    posterUrl = epPoster
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            backgroundPosterUrl = backdrop ?: poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = pageHeaders()).document
        val servers = mutableListOf<Pair<String, String>>()

        document.select("button.player-tab[data-url], li[data-url], div[data-url], a[data-url]").forEach {
            val lang = it.attr("data-lang").ifBlank { it.text().trim() }.ifBlank { "CineHDPlus" }
            val dataUrl = it.attr("data-url")
            if (dataUrl.isNotBlank()) servers.add(lang to dataUrl)
        }

        document.select("iframe[src*='player.php?h=']").forEach {
            servers.add("CineHDPlus" to it.attr("src"))
        }

        Regex("""https?://api\.cinehdplus\.org/ir/player\.php\?h=[^"'\s<>]+""")
            .findAll(document.html())
            .forEach { servers.add("CineHDPlus" to it.value) }

        var found = false

        servers.distinctBy { it.second }.amap { pair ->
            try {
                val rawUrl = pair.second
                val playerUrl = fixPlayerUrl(rawUrl)
                val hash = playerUrl.substringAfter("h=", "").substringBefore("&")

                if (hash.isBlank()) return@amap

                val finalLink = resolveCineHDUrl(hash, playerUrl) ?: return@amap
                val fixedFinal = fixHostsLinks(finalLink)

                Log.d("CineHDPlus", "FINAL LINK: $fixedFinal")

                loadExtractor(fixedFinal, data, subtitleCallback) { link ->
                    callback.invoke(link)
                    found = true
                }
            } catch (e: Exception) {
                Log.e("CineHDPlus", "loadLinks error", e)
            }
        }

        return found
    }

    private suspend fun resolveCineHDUrl(hash: String, playerReferer: String): String? {
        return try {
            val gotoUrl = "$apiUrl/ir/goto.php?h=$hash"

            val step1 = app.get(gotoUrl, headers = headers(playerReferer)).document
            val url1 = step1.selectFirst("input#url, input[name=url]")?.attr("value") ?: return null

            val step2 = app.post(
                "$apiUrl/ir/rd.php",
                data = mapOf("url" to url1),
                headers = headers(gotoUrl)
            ).document

            val url2 = step2.selectFirst("input#url, input[name=url]")?.attr("value") ?: return null

            val step3 = app.post(
                "$apiUrl/ir/redir_ddh.php",
                data = mapOf("url" to url2, "dl" to "0"),
                headers = headers("$apiUrl/ir/rd.php")
            ).document

            val form = step3.selectFirst("form") ?: return null

            val actionRaw = form.attr("action")
            if (actionRaw.isBlank()) return null

            val action = when {
                actionRaw.startsWith("http", true) -> actionRaw
                actionRaw.startsWith("/") -> apiUrl + actionRaw
                else -> "$apiUrl/ir/$actionRaw"
            }

            val vid = form.selectFirst("input#vid, input[name=vid]")?.attr("value") ?: return null
            val hash2 = form.selectFirst("input#hash, input[name=hash]")?.attr("value") ?: return null

            val step4 = app.post(
                action,
                data = mapOf("vid" to vid, "hash" to hash2),
                headers = headers("$apiUrl/ir/redir_ddh.php")
            ).text

            val encoded = Regex("""link\s*=\s*['"]([^'"]+)['"]""")
                .find(step4)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex("""atob\(['"]([^'"]+)['"]\)""")
                    .find(step4)
                    ?.groupValues
                    ?.getOrNull(1)

            if (!encoded.isNullOrBlank()) {
                decodeBase64(encoded).takeIf { it.startsWith("http", true) }
            } else {
                Regex("""https?://[^"'\s<>]+""").find(step4)?.value
            }
        } catch (e: Exception) {
            Log.e("CineHDPlus", "resolveCineHDUrl error", e)
            null
        }
    }

    private fun pickImageFromElement(img: Element?): String? {
        if (img == null) return null

        val dataSrc: String = img.attr("data-src")
        if (dataSrc.isNotBlank() && !dataSrc.startsWith("data:", true)) return dataSrc

        val lazy: String = img.attr("data-lazy-src")
        if (lazy.isNotBlank() && !lazy.startsWith("data:", true)) return lazy

        val original: String = img.attr("data-original")
        if (original.isNotBlank() && !original.startsWith("data:", true)) return original

        val srcset: String = img.attr("srcset")
        if (srcset.isNotBlank() && !srcset.startsWith("data:", true)) {
            return srcset.substringBefore(" ").trim()
        }

        val src: String = img.attr("src")
        if (src.isNotBlank() && !src.startsWith("data:", true)) return src

        return null
    }

    private fun fixImageUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        val clean = raw
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()

        if (clean.startsWith("data:", true)) return null

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http", true) -> clean
            clean.startsWith("/") -> mainUrl.removeSuffix("/") + clean
            else -> fixUrl(clean)
        }
    }

    private fun fixPlayerUrl(url: String): String {
        val clean = url.trim()
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http", true) -> clean
            clean.startsWith("/") -> apiUrl + clean
            else -> "$apiUrl/ir/$clean"
        }
    }

    private fun decodeBase64(str: String): String {
        return try {
            String(Base64.decode(str, Base64.DEFAULT)).trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .replace("Ver ", "", ignoreCase = true)
            .replace("Online HD", "", ignoreCase = true)
            .replace("Online", "", ignoreCase = true)
            .replace(Regex("""\(\d{4}\)"""), "")
            .trim()
    }

    private fun headers(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "es-419,es;q=0.9"
        )
    }

    private fun pageHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to mainUrl,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "es-419,es;q=0.9"
        )
    }

    private fun fixHostsLinks(url: String): String {
        return url
            .replaceFirst("https://hglink.to", "https://streamwish.to")
            .replaceFirst("https://swdyu.com", "https://streamwish.to")
            .replaceFirst("https://cybervynx.com", "https://streamwish.to")
            .replaceFirst("https://dumbalag.com", "https://streamwish.to")
            .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
            .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
            .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
            .replaceFirst("https://filemoon.link", "https://filemoon.sx")
            .replaceFirst("https://sblona.com", "https://watchsb.com")
            .replaceFirst("https://lulu.st", "https://lulustream.com")
            .replaceFirst("https://uqload.io", "https://uqload.com")
            .replaceFirst("https://do7go.com", "https://dood.la")
    }
}