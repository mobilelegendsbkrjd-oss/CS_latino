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

class SoloLatino : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override val hasMainPage = true
    override var lang = "mx"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie)

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
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
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
        val doc = app.get(data).document
        val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""

        val headers = mapOf(
            "Content-Type" to "application/json",
            "X-CSRF-TOKEN" to csrf,
            "Accept" to "application/json",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
        )

        val serverButtons = doc.select("button.server-btn, .options li, .dropdown-menu li a")

        serverButtons.forEach { btn ->
            val token = btn.attr("data-player-token").ifBlank { btn.attr("data-token") }
            val directHref = btn.attr("href").let { if (it.isNotBlank() && it != "#") fixUrl(it) else null }

            if (token.isBlank() && directHref != null) {
                val fixedDirect = fixHostsLinks(directHref)
                when {
                    fixedDirect.contains("embed69.org") -> processEmbed69(fixedDirect, data, subtitleCallback, callback)
                    fixedDirect.contains("xupalace.org") -> processXupalace(fixedDirect, data, subtitleCallback, callback)
                    fixedDirect.contains("playerpelis") -> processPlayerPelis(fixedDirect, data, subtitleCallback, callback)
                    else -> loadExtractor(fixedDirect, data, subtitleCallback, callback)
                }
                return@forEach
            }

            if (token.isBlank()) return@forEach

            try {
                val response = app.post(
                    "$mainUrl/api/player-url",
                    headers = headers,
                    data = mapOf("t" to token)
                ).parsedSafe<PlayerResponse>()

                response?.let { resolved ->
                    var url = resolved.url
                    if (url.isBlank()) return@let

                    url = fixHostsLinks(url)

                    when {
                        resolved.type == "mp4" || url.endsWith(".mp4") -> {
                            callback.invoke(
                                newExtractorLink("SoloLatino", "SoloLatino", url, ExtractorLinkType.VIDEO) {
                                    this.referer = data
                                }
                            )
                        }
                        url.contains(".m3u8") -> {
                            callback.invoke(
                                newExtractorLink("SoloLatino", "SoloLatino", url, ExtractorLinkType.M3U8) {
                                    this.referer = data
                                }
                            )
                        }
                        url.contains("embed69.org") -> {
                            processEmbed69(url, data, subtitleCallback, callback)
                        }
                        url.contains("xupalace.org") -> {
                            processXupalace(url, data, subtitleCallback, callback)
                        }
                        url.contains("playerpelis") -> {
                            processPlayerPelis(url, data, subtitleCallback, callback)
                        }
                        else -> {
                            loadExtractor(url, data, subtitleCallback, callback)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(name, "Error procesando token: ${e.message}")
            }
        }

        doc.select("iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (iframeSrc.isNotBlank()) {
                val fixedIframeUrl = fixHostsLinks(fixUrl(iframeSrc))
                when {
                    fixedIframeUrl.contains("embed69.org") -> processEmbed69(fixedIframeUrl, data, subtitleCallback, callback)
                    fixedIframeUrl.contains("xupalace.org") -> processXupalace(fixedIframeUrl, data, subtitleCallback, callback)
                    fixedIframeUrl.contains("playerpelis") -> processPlayerPelis(fixedIframeUrl, data, subtitleCallback, callback)
                    else -> loadExtractor(fixedIframeUrl, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }

    // ====================== PROCESAR EMBED69 ======================
    private suspend fun processEmbed69(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, referer = referer).text

            val challenge = Regex("""const\s+POW_CHALLENGE\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            val difficulty = Regex("""const\s+POW_DIFFICULTY\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            val salt = Regex("""const\s+POW_SALT\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)

            var aesKey: ByteArray? = null
            if (challenge != null && difficulty != null && salt != null) {
                aesKey = solvePoW(challenge, difficulty, salt)
            }

            val dataLinkMatch = Regex("""dataLink\s*=\s*(\[.+?\]);""").find(html)
            if (dataLinkMatch != null) {
                val arr = JSONArray(dataLinkMatch.groupValues[1])

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val lang = obj.optString("video_language", "LAT").ifBlank { "LAT" }
                    val embedsArr = obj.optJSONArray("sortedEmbeds") ?: continue

                    for (j in 0 until embedsArr.length()) {
                        val e = embedsArr.getJSONObject(j)
                        val servername = e.optString("servername", "")
                        val linkEnc = e.optString("link", null) ?: continue

                        if (servername.equals("download", ignoreCase = true)) continue

                        val link = when {
                            linkEnc.split(".").size == 3 -> decodeBase64Link(linkEnc)
                            aesKey != null -> decryptAES(linkEnc, aesKey)
                            else -> null
                        }

                        if (!link.isNullOrBlank()) {
                            loadSourceNameExtractor(
                                lang,
                                fixHostsLinks(link),
                                referer,
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            } else {
                loadExtractor(url, referer, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e(name, "processEmbed69 error: ${e.message}")
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    // ====================== PROCESAR XUPALACE ======================
    private suspend fun processXupalace(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, referer = referer).text
            val regex = """(go_to_player|go_to_playerVast)\(['"]([^'"]+)['"]""".toRegex()

            regex.findAll(html).forEach { match ->
                val videoUrl = match.groupValues[2]
                if (videoUrl.isNotBlank()) {
                    loadExtractor(fixHostsLinks(videoUrl), referer, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e(name, "processXupalace error: ${e.message}")
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    // ====================== PROCESAR PLAYERPELIS ======================
    private suspend fun processPlayerPelis(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, referer = referer).text

            // Extracción genérica de iframes, scripts o urls incrustadas comunes en playerpelis
            val regexUrls = Regex("""https?://[^\s"'<>]+""")
            regexUrls.findAll(html).forEach { match ->
                val foundUrl = match.value
                if (foundUrl.contains("streamwish") || foundUrl.contains("vidhide") || foundUrl.contains("filemoon") || foundUrl.contains(".m3u8") || foundUrl.contains(".mp4")) {
                    loadExtractor(fixHostsLinks(foundUrl), url, subtitleCallback, callback)
                }
            }

            // Fallback estándar si contiene reproductores internos
            loadExtractor(url, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.e(name, "processPlayerPelis error: ${e.message}")
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    // ====================== UTILS ======================
    private fun solvePoW(challenge: String, difficulty: Int, salt: String): ByteArray {
        val prefix = "0".repeat(difficulty)
        var nonce = 0
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
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
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
        return url
            .replace("https://hglink.to", "https://streamwish.to")
            .replace("https://swdyu.com", "https://streamwish.to")
            .replace("https://cybervynx.com", "https://streamwish.to")
            .replace("https://dumbalag.com", "https://streamwish.to")
            .replace("https://mivalyo.com", "https://vidhidepro.com")
            .replace("https://dinisglows.com", "https://vidhidepro.com")
            .replace("https://dhtpre.com", "https://vidhidepro.com")
            .replace("https://filemoon.link", "https://filemoon.sx")
            .replace("https://sblona.com", "https://watchsb.com")
            .replace("https://lulu.st", "https://lulustream.com")
            .replace("https://uqload.io", "https://uqload.com")
            .replace("https://do7go.com", "https://dood.la")
    }
}

// ====================== PLAYER RESPONSE ======================
data class PlayerResponse(
    val url: String = "",
    val type: String = "",
)