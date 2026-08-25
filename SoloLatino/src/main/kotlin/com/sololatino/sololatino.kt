package com.sololatino

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SoloLatino : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override val hasMainPage = true
    override var lang = "mx"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie)

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
    private val TAG = "SoloLatino"

    // ====================== DATA CLASSES ======================
    data class Item(
        val video_language: String? = null,
        val sortedEmbeds: List<Embed> = emptyList()
    )

    data class Embed(
        val servername: String? = null,
        val link: String? = null
    )

    data class SagaMovie(
        val name: String? = null,
        val url: String? = null
    )

    data class SagaItem(
        val title: String? = null,
        val poster: String? = null,
        val description: String? = null,
        val movies: List<SagaMovie>? = null
    )

    private val sagasJson = "https://raw.githubusercontent.com/mobilelegendsbkrjd-oss/lat_cs_bkrjd/main/sagas.json"
    private var sagasCache: List<SagaItem>? = null

    // ====================== PARSE CARDS ======================
    private fun parseCards(element: org.jsoup.nodes.Element): List<SearchResponse> {
        return element.select(".card, article.card").mapNotNull { card ->
            val linkElement = card.selectFirst("a") ?: return@mapNotNull null
            val href = linkElement.attr("href")
            val absoluteUrl = fixUrl(href)
            val title = card.selectFirst(".card__title, h3, h2")?.text()?.trim() ?: return@mapNotNull null
            val img = card.selectFirst("img")
            var poster: String? = null

            if (img != null) {
                poster = img.attr("data-src")
                if (poster.isNullOrBlank()) poster = img.attr("data-lazy-src")
                if (poster.isNullOrBlank()) poster = img.attr("src")
                poster = poster?.replace(Regex("-\\d+x\\d+"), "")
            }

            val isMovie = absoluteUrl.contains("/pelicula/")
            val isAnime = absoluteUrl.contains("/anime/") || absoluteUrl.contains("/animes/")

            when {
                isAnime -> {
                    newAnimeSearchResponse(title, absoluteUrl, TvType.Anime) {
                        this.posterUrl = poster
                    }
                }
                isMovie -> {
                    newMovieSearchResponse(title, absoluteUrl, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
                else -> {
                    newTvSeriesSearchResponse(title, absoluteUrl, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                }
            }
        }
    }

    // ====================== RECOMMENDATIONS ======================
    private fun getRecommendations(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val relatedSection = doc.selectFirst("h2:matchesOwn((?i)Relacionadas|Recomendadas|Similares)")
            ?.parent()
            ?: return emptyList()

        val cards = relatedSection.select(".scroll-row .card")
        if (cards.isEmpty()) return emptyList()

        val container = org.jsoup.nodes.Element("div")
        cards.forEach {
            container.appendChild(it.clone())
        }

        return parseCards(container).distinctBy { it.url }
    }

    // ====================== SAGAS ======================
    private suspend fun getSagasRaw(): List<SagaItem> {
        sagasCache?.let { return it }

        return try {
            val arr = JSONArray(app.get(sagasJson).text)
            val out = mutableListOf<SagaItem>()

            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val movies = mutableListOf<SagaMovie>()
                val mArr = o.optJSONArray("movies")

                if (mArr != null) {
                    for (j in 0 until mArr.length()) {
                        val m = mArr.getJSONObject(j)
                        movies.add(
                            SagaMovie(
                                m.optString("name", null),
                                m.optString("url", null)
                            )
                        )
                    }
                }

                out.add(
                    SagaItem(
                        o.optString("title", null),
                        o.optString("poster", null),
                        o.optString("description", null),
                        movies
                    )
                )
            }

            sagasCache = out
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun getSagas(): List<SearchResponse> {
        return getSagasRaw().mapIndexedNotNull { index, saga ->
            val title = saga.title ?: return@mapIndexedNotNull null
            newTvSeriesSearchResponse(title, "$mainUrl/saga/$index", TvType.TvSeries) {
                this.posterUrl = saga.poster
            }
        }
    }

    private suspend fun getSagaData(index: Int): SagaItem? {
        return getSagasRaw().getOrNull(index)
    }

    // ====================== CATEGORY ======================
    private suspend fun getCategory(title: String, url: String): HomePageList {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
            "Referer" to mainUrl
        )

        return try {
            val doc = app.get(url, headers = headers).document
            HomePageList(title, parseCards(doc).distinctBy { it.url })
        } catch (e: Exception) {
            Log.e(name, "$title -> ${e.message}")
            HomePageList(title, emptyList())
        }
    }

    // ====================== MAIN PAGE ======================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = coroutineScope {
        val pageSuffix = if (page == 1) "" else "&page=$page"

        val tasks = listOf(
            async {
                val sagas = getSagas()
                if (sagas.isNotEmpty()) HomePageList("🔥 Sagas", sagas.take(20)) else null
            },
            async { getCategory("🎬 Películas Recientes", "$mainUrl/peliculas?año=0&nota=0&sort=updated$pageSuffix") },
            async { getCategory("📺 Series Recientes", "$mainUrl/series?año=0&nota=0&sort=updated$pageSuffix") },
            async { getCategory("🔴 Netflix", "$mainUrl/red/netflix?año=0&nota=0&orden=recientes$pageSuffix") },
            async { getCategory("🔵 Prime Video", "$mainUrl/red/amazon-prime-video?año=0&nota=0&orden=recientes$pageSuffix") },
            async { getCategory("🍎 Apple TV", "$mainUrl/red/apple-tv?año=0&nota=0&orden=recientes$pageSuffix") },
            async { getCategory("🏰 DisneyPlus", "$mainUrl/red/disney?año=0&nota=0&orden=recientes$pageSuffix") },
            async { getCategory("🟣 HBO Max", "$mainUrl/red/hbo-max?año=0&nota=0&orden=recientes$pageSuffix") },
            async { getCategory("⚫ HBO", "$mainUrl/red/hbo?año=0&nota=0&orden=recientes$pageSuffix") },
            async { getCategory("🟡 AT-X", "$mainUrl/red/at-x?año=0&nota=0&orden=recientes$pageSuffix") },
            async { getCategory("🔵 BS11", "$mainUrl/red/bs11?año=0&nota=0&orden=recientes$pageSuffix") },
            async { getCategory("🗻 Fuji TV", "$mainUrl/red/fuji-tv?año=0&nota=0&orden=recientes$pageSuffix") },
            async { getCategory("🏮 TVTokyo", "$mainUrl/red/tv-tokyo?año=0&nota=0&orden=recientes$pageSuffix") },
            async { getCategory("🗼 TokyoMX", "$mainUrl/red/tokyo-mx?año=0&nota=0&orden=recientes$pageSuffix") },
            async { getCategory("⛩️ Anime", "$mainUrl/animes?año=0&nota=0&sort=updated$pageSuffix") }
        )

        val lists = tasks.awaitAll().filterNotNull()
        newHomePageResponse(lists)
    }

    // ====================== SEARCH ======================
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<SearchResponse>()
        try {
            results.addAll(getSagas().filter { it.name?.contains(query, ignoreCase = true) == true })
        } catch (_: Exception) {}
        val doc = app.get(fixUrl("/buscar?q=$query")).document
        results.addAll(parseCards(doc))
        return results.distinctBy { it.url }
    }

    // ====================== LOAD ======================
    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/saga/")) {
            val index = url.substringAfter("/saga/").toIntOrNull() ?: throw ErrorLoadingException()
            val saga = getSagaData(index) ?: throw ErrorLoadingException()

            val episodes = saga.movies?.mapIndexedNotNull { i, movie ->
                val movieUrl = movie.url ?: return@mapIndexedNotNull null
                try {
                    val doc = app.get(movieUrl).document
                    val realTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("|")?.trim()
                        ?.replace(Regex("^Ver\\s+", RegexOption.IGNORE_CASE), "")
                        ?.replace(Regex("\\s+Online.*$", RegexOption.IGNORE_CASE), "")
                        ?: movie.name ?: "Película ${i + 1}"

                    val realPoster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: saga.poster
                    val realDesc = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

                    newEpisode(movieUrl) {
                        this.name = realTitle
                        this.description = realDesc
                        this.posterUrl = realPoster
                        this.episode = i + 1
                        this.season = 1
                    }
                } catch (_: Exception) {
                    newEpisode(movieUrl) {
                        this.name = movie.name ?: "Película ${i + 1}"
                        this.posterUrl = saga.poster
                        this.episode = i + 1
                        this.season = 1
                    }
                }
            } ?: emptyList()

            return newTvSeriesLoadResponse(saga.title ?: "Saga", url, TvType.TvSeries, episodes) {
                this.posterUrl = saga.poster
                this.plot = saga.description
            }
        }

        val doc = app.get(url).document
        var isAnime = url.contains("/anime/") || url.contains("/animes/")
        val isSeries = url.contains("/serie/")

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("|")?.trim()
            ?: "Sin título"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
        val recommendations = getRecommendations(doc)

        if (isSeries && !isAnime) {
            val hasAnimeBadge = doc.selectFirst(".badge-anime") != null
            val hasAnimeText = doc.select(".detail-field span").any { it.text().equals("anime", ignoreCase = true) }
            val hasAnimeTag = doc.select(".tags a").any { it.text().contains("anime", ignoreCase = true) }
            isAnime = hasAnimeBadge || hasAnimeText || hasAnimeTag
            if (isAnime) Log.d(name, "✅ Detectado anime por badge/tag: $title")
        }

        val episodes = mutableListOf<Episode>()
        doc.select("a.ep-item").forEach { ep ->
            val epUrl = fixUrl(ep.attr("href"))
            val epNum = ep.selectFirst(".ep-num")?.text()?.replace("E", "")?.trim()?.toIntOrNull() ?: 0
            val season = Regex("""temporada-(\d+)""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val epTitle = ep.selectFirst("p.text-sm, p.leading-tight")?.text()?.trim() ?: "Episodio $epNum"

            var thumb: String? = null
            val imgElement = ep.selectFirst("img")
            if (imgElement != null) {
                thumb = imgElement.attr("data-src")
                if (thumb.isNullOrEmpty()) thumb = imgElement.attr("src")
            }

            val description = ep.select("p.text-xs, p.line-clamp-2").map { it.text().trim() }
                .firstOrNull { it.length > 10 && !it.matches(Regex("""\d{2}/\d{2}/\d{4}""")) }

            episodes.add(newEpisode(epUrl) {
                this.name = epTitle
                this.description = description
                this.episode = epNum
                this.season = season
                this.posterUrl = thumb ?: poster
            })
        }

        if (isAnime) {
            if (episodes.size <= 1 && !isSeries) {
                val movieUrl = episodes.firstOrNull()?.data ?: url
                return newMovieLoadResponse(title, url, TvType.AnimeMovie, movieUrl) {
                    this.recommendations = recommendations
                    this.posterUrl = poster
                    this.plot = plot
                }
            }

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                addEpisodes(DubStatus.Dubbed, episodes.sortedWith(compareBy({ it.season }, { it.episode })))
                this.recommendations = recommendations
            }
        }

        if (isSeries) {
            return newTvSeriesLoadResponse(
                title, url, TvType.TvSeries,
                episodes.sortedWith(compareBy({ it.season }, { it.episode }))
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.recommendations = recommendations
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.recommendations = recommendations
        }
    }

    // ====================== LOAD LINKS ======================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "=== INICIANDO LOADLINKS ===")
        Log.d(TAG, "URL: $data")

        val doc = app.get(data).document
        val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""

        val headers = mapOf(
            "Content-Type" to "application/json",
            "X-CSRF-TOKEN" to csrf,
            "Accept" to "application/json",
            "User-Agent" to USER_AGENT
        )

        val playerUrls = mutableListOf<Pair<String, String>>()

        // --- 1. Buscar TODOS los botones de servidor con data-player-token ---
        doc.select("button[data-player-token]").forEach { btn ->
            val token = btn.attr("data-player-token")
            val serverName = btn.text().trim()

            if (token.isNotBlank()) {
                try {
                    val response = app.post(
                        "$mainUrl/api/player-url",
                        headers = headers,
                        data = mapOf("t" to token)
                    ).parsedSafe<PlayerResponse>()

                    response?.let { resolved ->
                        if (resolved.url.isNotBlank()) {
                            val finalUrl = fixHostsLinks(resolved.url)
                            if (finalUrl.isNotBlank()) {
                                Log.d(TAG, "Servidor (token): $serverName -> $finalUrl")
                                playerUrls.add(Pair(finalUrl, serverName))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error con token para $serverName: ${e.message}")
                }
            }
        }

        // --- 2. Buscar botones de servidor con data-server-url ---
        doc.select("button[data-server-url]").forEach { btn ->
            val url = btn.attr("data-server-url")
            val serverName = btn.text().trim()
            if (url.isNotBlank()) {
                val finalUrl = fixHostsLinks(fixUrl(url))
                if (finalUrl.isNotBlank()) {
                    Log.d(TAG, "Servidor (data-server-url): $serverName -> $finalUrl")
                    playerUrls.add(Pair(finalUrl, serverName))
                }
            }
        }

        // --- 3. Buscar TODOS los iframes ---
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                val fixedUrl = fixHostsLinks(fixUrl(src))
                if (fixedUrl.isNotBlank()) {
                    Log.d(TAG, "Iframe: $fixedUrl")
                    playerUrls.add(Pair(fixedUrl, "iframe"))
                }
            }
        }

        // --- 4. Buscar en el JavaScript y enlaces directos ---
        val html = doc.html()

        Regex("""https?://[^\s"'<>]+\.(m3u8|mp4)[^\s"'<>]*""")
            .findAll(html)
            .forEach { match ->
                val url = match.value
                if (url.isNotBlank()) {
                    val finalUrl = fixHostsLinks(url)
                    if (finalUrl.isNotBlank()) {
                        Log.d(TAG, "Enlace directo: $finalUrl")
                        playerUrls.add(Pair(finalUrl, "directo"))
                    }
                }
            }

        // Buscar URLs de reproductores en scripts
        val jsPatterns = listOf(
            Regex("""https://player\.pelisserieshoy\.com/f/[^"'\s]+"""),
            Regex("""https://embed69\.org/f/[^"'\s]+"""),
            Regex("""https://dood\.la/[^"'\s]+"""),
            Regex("""https://streamwish\.to/[^"'\s]+"""),
            Regex("""https://vidhidepro\.com/[^"'\s]+"""),
            Regex("""https://filemoon\.sx/[^"'\s]+"""),
            Regex("""https://playhydrax\.com/[^"'\s]+""")
        )

        jsPatterns.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                val url = match.value
                if (url.isNotBlank()) {
                    val fixedUrl = fixHostsLinks(url)
                    if (fixedUrl.isNotBlank()) {
                        Log.d(TAG, "URL en JS: $fixedUrl")
                        playerUrls.add(Pair(fixedUrl, "js_found"))
                    }
                }
            }
        }

        // --- 5. Si no hay URLs, usar el iframe principal ---
        if (playerUrls.isEmpty()) {
            val iframe = doc.select("#player-frame iframe").firstOrNull()
            if (iframe != null) {
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    val fixedUrl = fixHostsLinks(fixUrl(src))
                    if (fixedUrl.isNotBlank()) {
                        Log.d(TAG, "Usando iframe principal: $fixedUrl")
                        playerUrls.add(Pair(fixedUrl, "main_iframe"))
                    }
                }
            }
        }

        // --- 6. PROCESAR CADA URL ÚNICA ---
        var foundLinks = false
        val uniqueUrls = playerUrls.distinctBy { it.first }

        Log.d(TAG, "Total URLs únicas encontradas: ${uniqueUrls.size}")

        for ((playerUrl, serverName) in uniqueUrls) {
            Log.d(TAG, "Procesando: $playerUrl (Servidor: $serverName)")

            try {
                when {
                    // Xupalace - CON IDIOMA Y SOPORTE PARA TODOS LOS SERVIDORES
                    playerUrl.contains("xupalace.org") || playerUrl.contains("xupalace.com") -> {
                        Log.d(TAG, "Procesando Xupalace: $playerUrl")
                        try {
                            val xupalaceDoc = app.get(playerUrl, referer = data).document
                            val xupalaceHtml = xupalaceDoc.html()

                            Log.d(TAG, "Xupalace HTML length: ${xupalaceHtml.length}")

                            // 🔥 BUSCAR go_to_playerVast CON EL IDIOMA Y NOMBRE DEL SERVIDOR
                            val langMap = mapOf("0" to "LAT", "1" to "ESP", "2" to "SUB")
                            val regex = Regex("""go_to_playerVast\s*\(\s*['"]([^'"]+)['"]\s*,\s*\d+,\s*\d+\)[^<]*<span>([^<]+)</span>[^<]*data-lang="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)

                            val matches = regex.findAll(xupalaceHtml).toList()

                            if (matches.isNotEmpty()) {
                                for (match in matches) {
                                    val rawUrl = match.groupValues[1]
                                    val serverName = match.groupValues[2].trim()
                                    val langCode = match.groupValues[3]
                                    val language = langMap[langCode] ?: "LAT"

                                    var finalUrl = rawUrl
                                    if (rawUrl.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
                                        try {
                                            val decoded = android.util.Base64.decode(rawUrl, android.util.Base64.DEFAULT)
                                            val decodedString = String(decoded, Charsets.UTF_8)
                                            if (decodedString.startsWith("http")) {
                                                finalUrl = decodedString
                                            } else if (decodedString.startsWith("{")) {
                                                val linkMatch = Regex("\"link\"\\s*:\\s*\"([^\"]+)\"").find(decodedString)
                                                finalUrl = linkMatch?.groupValues?.get(1) ?: finalUrl
                                            }
                                        } catch (_: Exception) {}
                                    }

                                    if (finalUrl.startsWith("http") && !finalUrl.contains("xupalace.org")) {
                                        Log.d(TAG, "Xupalace -> $language [$serverName]: $finalUrl")

                                        // ============================================
                                        // CARGAR SEGÚN EL SERVIDOR
                                        // ============================================
                                        val fixedUrl = fixHostsLinks(finalUrl)

                                        when {
                                            // StreamWish
                                            fixedUrl.contains("streamwish") || fixedUrl.contains("hglink") ||
                                                    fixedUrl.contains("swdyu") || fixedUrl.contains("wishembed") -> {
                                                loadExtractorWithLanguage(language, fixedUrl, playerUrl, subtitleCallback, callback)
                                            }
                                            // VidHide / Minochinos - USAR VidHidePro
                                            fixedUrl.contains("vidhide") || fixedUrl.contains("minochinos") ||
                                                    fixedUrl.contains("mivalyo") || fixedUrl.contains("dhtpre") -> {
                                                when {
                                                    fixedUrl.contains("minochinos") -> {
                                                        MinochinosExtractorV2().withLanguage(language).getUrl(fixedUrl, playerUrl, subtitleCallback, callback)
                                                    }
                                                    else -> {
                                                        loadExtractorWithLanguage(language, fixedUrl, playerUrl, subtitleCallback, callback)
                                                    }
                                                }
                                            }
                                            // WaaW
                                            fixedUrl.contains("waaw.to") -> {
                                                loadExtractorWithLanguage(language, fixedUrl, playerUrl, subtitleCallback, callback)
                                            }
                                            // FileMoon - USAR FilemoonV2
                                            fixedUrl.contains("filemoon") || fixedUrl.contains("bysedikamoum") -> {
                                                when {
                                                    fixedUrl.contains("filemoon.to") -> FileMoon2().withLanguage(language).getUrl(fixedUrl, playerUrl, subtitleCallback, callback)
                                                    fixedUrl.contains("filemoon.in") -> FileMoonIn().withLanguage(language).getUrl(fixedUrl, playerUrl, subtitleCallback, callback)
                                                    fixedUrl.contains("filemoon.sx") -> FileMoonSx().withLanguage(language).getUrl(fixedUrl, playerUrl, subtitleCallback, callback)
                                                    fixedUrl.contains("bysedikamoum") -> Bysedikamoum().withLanguage(language).getUrl(fixedUrl, playerUrl, subtitleCallback, callback)
                                                    else -> loadExtractorWithLanguage(language, fixedUrl, playerUrl, subtitleCallback, callback)
                                                }
                                            }
                                            // VOE
                                            fixedUrl.contains("voe") -> {
                                                loadExtractorWithLanguage(language, fixedUrl, playerUrl, subtitleCallback, callback)
                                            }
                                            // Dood - USAR DoodExtractor
                                            fixedUrl.contains("dood") || fixedUrl.contains("dood.la") || fixedUrl.contains("do7go") -> {
                                                DoodExtractor().getUrl(fixedUrl, playerUrl, subtitleCallback, callback)
                                            }
                                            // PlayHydrax - USAR PlayHydrax
                                            fixedUrl.contains("playhydrax") || fixedUrl.contains("abyssplayer") ||
                                                    fixedUrl.contains("player-cdn.com") -> {
                                                PlayHydrax().withLanguage(language).getUrl(fixedUrl, playerUrl, subtitleCallback, callback)
                                            }
                                            // 1fichier (descarga directa)
                                            fixedUrl.contains("1fichier") -> {
                                                loadExtractorWithLanguage(language, fixedUrl, playerUrl, subtitleCallback, callback)
                                            }
                                            // Stape (normalmente es StreamWish)
                                            fixedUrl.contains("stape") || fixedUrl.contains("player-cdn") -> {
                                                loadExtractorWithLanguage(language, fixedUrl, playerUrl, subtitleCallback, callback)
                                            }
                                            // Enlace directo M3U8/MP4
                                            fixedUrl.contains(".m3u8") || fixedUrl.contains(".mp4") -> {
                                                callback.invoke(
                                                    newExtractorLink(
                                                        source = "SoloLatino",
                                                        name = "$language[$serverName]",
                                                        url = fixedUrl,
                                                        type = if (fixedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                                    ) {
                                                        this.referer = playerUrl
                                                    }
                                                )
                                            }
                                            // DEFAULT
                                            else -> {
                                                loadExtractorWithLanguage(language, fixedUrl, playerUrl, subtitleCallback, callback)
                                            }
                                        }
                                    }
                                }
                                foundLinks = true
                            } else {
                                // FALLBACK: Buscar solo las URLs sin idioma
                                Log.d(TAG, "Xupalace: buscando enlaces sin idioma")
                                val regexFallback = Regex("""go_to_playerVast\s*\(\s*['"]([^'"]+)['"]""")
                                val urls = regexFallback.findAll(xupalaceHtml)
                                    .map { it.groupValues[1] }
                                    .filter { it.isNotBlank() && !it.contains("xupalace.org") }
                                    .toList()

                                if (urls.isNotEmpty()) {
                                    for (rawUrl in urls) {
                                        var finalUrl = rawUrl
                                        if (rawUrl.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
                                            try {
                                                val decoded = android.util.Base64.decode(rawUrl, android.util.Base64.DEFAULT)
                                                val decodedString = String(decoded, Charsets.UTF_8)
                                                if (decodedString.startsWith("http")) {
                                                    finalUrl = decodedString
                                                } else if (decodedString.startsWith("{")) {
                                                    val linkMatch = Regex("\"link\"\\s*:\\s*\"([^\"]+)\"").find(decodedString)
                                                    finalUrl = linkMatch?.groupValues?.get(1) ?: finalUrl
                                                }
                                            } catch (_: Exception) {}
                                        }

                                        if (finalUrl.startsWith("http") && !finalUrl.contains("xupalace.org")) {
                                            Log.d(TAG, "Xupalace -> LAT (fallback): $finalUrl")
                                            loadExtractorWithLanguage(
                                                "LAT",
                                                fixHostsLinks(finalUrl),
                                                playerUrl,
                                                subtitleCallback,
                                                callback
                                            )
                                        }
                                    }
                                    foundLinks = true
                                } else {
                                    Log.d(TAG, "Xupalace: no hay enlaces, usando loadExtractor")
                                    loadExtractor(playerUrl, data, subtitleCallback, callback)
                                    foundLinks = true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error en Xupalace: ${e.message}")
                            loadExtractor(playerUrl, data, subtitleCallback, callback)
                            foundLinks = true
                        }
                    }
                    // Embed69
                    playerUrl.contains("embed69.org") || playerUrl.contains("embed69.com") -> {
                        Log.d(TAG, "Usando Embed69Extractor")
                        Embed69Extractor.load(playerUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                    // PlayerPelisSeriesHoy
                    playerUrl.contains("player.pelisserieshoy.com") || playerUrl.contains("pelisserieshoy.com") -> {
                        Log.d(TAG, "Usando PlayerPelisSeriesHoyExtractor")
                        PlayerPelisSeriesHoyExtractor.load(playerUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                    // PlayHydrax
                    playerUrl.contains("playhydrax") || playerUrl.contains("abyssplayer") -> {
                        Log.d(TAG, "Usando PlayHydrax")
                        val lang = when {
                            playerUrl.contains("/lat/") || playerUrl.contains("latino") -> "LAT"
                            playerUrl.contains("/esp/") || playerUrl.contains("espanol") -> "ESP"
                            playerUrl.contains("/sub/") || playerUrl.contains("subtitulado") -> "SUB"
                            else -> "LAT"
                        }
                        PlayHydrax().withLanguage(lang).getUrl(playerUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                    // Dood
                    playerUrl.contains("dood") || playerUrl.contains("dood.la") || playerUrl.contains("do7go") -> {
                        Log.d(TAG, "Usando DoodExtractor")
                        DoodExtractor().getUrl(playerUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                    // F75s
                    playerUrl.contains("f75s") || playerUrl.contains("f75s.com") -> {
                        Log.d(TAG, "Usando F75s")
                        val lang = when {
                            playerUrl.contains("/lat/") || playerUrl.contains("latino") -> "LAT"
                            playerUrl.contains("/esp/") || playerUrl.contains("espanol") -> "ESP"
                            playerUrl.contains("/sub/") || playerUrl.contains("subtitulado") -> "SUB"
                            else -> "LAT"
                        }
                        F75s().withLanguage(lang).getUrl(playerUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                    // Minochinos
                    playerUrl.contains("minochinos") -> {
                        Log.d(TAG, "Usando MinochinosExtractorV2")
                        MinochinosExtractorV2().getUrl(playerUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                    // StreamWish
                    playerUrl.contains("streamwish") || playerUrl.contains("wish") ||
                            playerUrl.contains("hglink") || playerUrl.contains("swdyu") ||
                            playerUrl.contains("cybervynx") || playerUrl.contains("dumbalag") -> {
                        Log.d(TAG, "Cargando StreamWish: $playerUrl")
                        loadExtractor(playerUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                    // VidHide
                    playerUrl.contains("vidhide") || playerUrl.contains("mivalyo") ||
                            playerUrl.contains("dinisglows") || playerUrl.contains("dhtpre") -> {
                        Log.d(TAG, "Cargando VidHide: $playerUrl")
                        loadExtractor(playerUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                    // Filemoon
                    playerUrl.contains("filemoon") || playerUrl.contains("bysedikamoum") -> {
                        Log.d(TAG, "Usando FilemoonV2")
                        val lang = when {
                            playerUrl.contains("/lat/") || playerUrl.contains("latino") -> "LAT"
                            playerUrl.contains("/esp/") || playerUrl.contains("espanol") -> "ESP"
                            playerUrl.contains("/sub/") || playerUrl.contains("subtitulado") -> "SUB"
                            else -> "LAT"
                        }
                        when {
                            playerUrl.contains("filemoon.to") -> FileMoon2().withLanguage(lang).getUrl(playerUrl, data, subtitleCallback, callback)
                            playerUrl.contains("filemoon.in") -> FileMoonIn().withLanguage(lang).getUrl(playerUrl, data, subtitleCallback, callback)
                            playerUrl.contains("filemoon.sx") -> FileMoonSx().withLanguage(lang).getUrl(playerUrl, data, subtitleCallback, callback)
                            playerUrl.contains("bysedikamoum") -> Bysedikamoum().withLanguage(lang).getUrl(playerUrl, data, subtitleCallback, callback)
                            else -> loadExtractor(playerUrl, data, subtitleCallback, callback)
                        }
                        foundLinks = true
                    }
                    // VOE
                    playerUrl.contains("voe") -> {
                        Log.d(TAG, "Cargando VOE: $playerUrl")
                        loadExtractor(playerUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                    // Enlace directo M3U8/MP4
                    playerUrl.contains(".m3u8") || playerUrl.contains(".mp4") -> {
                        Log.d(TAG, "Enlace directo: $playerUrl")
                        callback.invoke(
                            newExtractorLink(
                                source = "SoloLatino",
                                name = if (serverName.isNotBlank() &&
                                    serverName != "directo" &&
                                    serverName != "iframe" &&
                                    serverName != "js_found" &&
                                    serverName != "main_iframe")
                                    serverName else "Directo",
                                url = playerUrl,
                                type = if (playerUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = data
                            }
                        )
                        foundLinks = true
                    }
                    // Fallback genérico
                    else -> {
                        Log.d(TAG, "Fallback genérico para: $playerUrl")
                        loadExtractor(playerUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando $playerUrl: ${e.message}")
            }
        }

        // --- 7. Fallback final ---
        if (!foundLinks) {
            Log.d(TAG, "No se encontraron enlaces, fallback final con: $data")
            loadExtractor(data, data, subtitleCallback, callback)
        }

        Log.d(TAG, "Esperando 3 segundos para que los extractores terminen...")
        delay(3000)
        Log.d(TAG, "Continuando...")

        return true
    }

    // ====================== UTILS ======================
    private fun solvePoW(challenge: String, difficulty: Int, salt: String): ByteArray {
        val prefix = "0".repeat(difficulty)
        var nonce = 0L
        val md = MessageDigest.getInstance("SHA-256")

        while (true) {
            val input = challenge + nonce
            val hashBytes = md.digest(input.toByteArray(Charsets.UTF_8))
            val hashStr = hashBytes.joinToString("") { "%02x".format(it) }

            if (hashStr.startsWith(prefix)) {
                return md.digest((challenge + nonce + salt).toByteArray(Charsets.UTF_8))
            }
            nonce++
        }
    }

    private fun decryptAES(encrypted: String, aesKey: ByteArray): String? {
        return try {
            val decoded = Base64.decode(encrypted, Base64.DEFAULT)
            val iv = decoded.copyOfRange(0, 16)
            val cipherText = decoded.copyOfRange(16, decoded.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey.copyOf(32), "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBase64Link(encryptedLink: String): String? {
        return try {
            val parts = encryptedLink.split(".")
            if (parts.size != 3) return null

            var payload = parts[1]
            if (payload.length % 4 != 0) {
                payload += "=".repeat(4 - payload.length % 4)
            }

            val json = String(Base64.decode(payload, Base64.DEFAULT))
            Regex("\"link\":\"(.*?)\"").find(json)?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }

    private fun fixHostsLinks(url: String): String {
        var fixed = url

        val replacements = mapOf(
            "https://hglink.to" to "https://streamwish.to",
            "https://swdyu.com" to "https://streamwish.to",
            "https://cybervynx.com" to "https://streamwish.to",
            "https://dumbalag.com" to "https://streamwish.to",
            "https://mivalyo.com" to "https://vidhidepro.com",
            "https://dinisglows.com" to "https://vidhidepro.com",
            "https://dhtpre.com" to "https://vidhidepro.com",
            "https://filemoon.link" to "https://filemoon.sx",
            "https://sblona.com" to "https://watchsb.com",
            "https://lulu.st" to "https://lulustream.com",
            "https://uqload.io" to "https://uqload.com",
            "https://do7go.com" to "https://dood.la",
            "https://embed69.com" to "https://embed69.org",
            "https://xupalace.com" to "https://xupalace.org"
        )

        replacements.forEach { (old, new) ->
            fixed = fixed.replace(old, new)
        }

        return fixed
    }
}

// ====================== FUNCIÓN AUXILIAR ======================
private suspend fun loadExtractorWithLanguage(
    language: String,
    url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    loadExtractor(url, referer, subtitleCallback) { link ->
        // Usar CoroutineScope en lugar de GlobalScope
        CoroutineScope(Dispatchers.IO).launch {
            callback.invoke(
                newExtractorLink(
                    source = "$language[${link.source}]",
                    name = "$language[${link.source}]",
                    url = link.url,
                ) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}

// ====================== PLAYER RESPONSE ======================
data class PlayerResponse(
    val url: String = "",
    val type: String = "",
)