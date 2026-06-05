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

class SoloLatino : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

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

    // ====================== PARSE CARDS ======================
    private fun parseCards(element: org.jsoup.nodes.Element): List<SearchResponse> {
        return element.select(".card, article.card").mapNotNull { card ->
            val linkElement = card.selectFirst("a") ?: return@mapNotNull null
            val href = linkElement.attr("href")
            val absoluteUrl = fixUrl(href)
            val title = card.selectFirst(".card__title, h3, h2")?.text()?.trim() ?: return@mapNotNull null
            val poster = card.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("data-lazy-src").ifBlank { it.attr("src") } }
            }?.replace(Regex("-\\d+x\\d+"), "")

            val isMovie = absoluteUrl.contains("/pelicula/")

            if (isMovie) {
                newMovieSearchResponse(title, absoluteUrl, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, absoluteUrl, TvType.TvSeries) { this.posterUrl = poster }
            }
        }
    }

    // ====================== SAGAS ======================
    private suspend fun getSagas(): List<SearchResponse> {
        return try {
            val json = app.get(sagasJson).text
            val parsed = AppUtils.tryParseJson<List<SagaItem>>(json) ?: return emptyList()
            parsed.mapIndexedNotNull { index, saga ->
                val title = saga.title ?: return@mapIndexedNotNull null
                newTvSeriesSearchResponse(title, "$mainUrl/saga/$index", TvType.TvSeries) {
                    this.posterUrl = saga.poster
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun getSagaData(index: Int): SagaItem? {
        return try {
            val json = app.get(sagasJson).text
            val parsed = AppUtils.tryParseJson<List<SagaItem>>(json)
            parsed?.getOrNull(index)
        } catch (_: Exception) { null }
    }

        // ====================== HELPER PARA CATEGORÍAS CON SCROLL INFINITO ======================
private suspend fun getCategoryInfinite(title: String, baseUrl: String): HomePageList {
    val allItems = mutableListOf<SearchResponse>()
    
    // Cargar múltiples páginas hasta tener suficientes items
    var currentPage = 1
    var hasMore = true
    
    while (hasMore && currentPage <= 10) { // Límite de 10 páginas
        try {
            val url = if (currentPage == 1) baseUrl else "$baseUrl&page=$currentPage"
            
            // Añadir headers específicos para evitar 403
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
                "Referer" to mainUrl
            )
            
            val doc = app.get(url, headers = headers).document
            val items = parseCards(doc)
            
            if (items.isEmpty()) {
                hasMore = false
                Log.e(name, "No items found in page $currentPage for $title")
            } else {
                allItems.addAll(items)
                Log.e(name, "Loaded ${items.size} items from page $currentPage for $title (total: ${allItems.size})")
                currentPage++
            }
        } catch (e: Exception) {
            Log.e(name, "Error loading page $currentPage for $title: ${e.message}")
            hasMore = false
        }
    }
    
    // Invertir el orden para que el scroll D-pad funcione correctamente
    return HomePageList(title, allItems.distinctBy { it.url }.reversed())
}

    // ====================== MAIN PAGE ======================
override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val lists = mutableListOf<HomePageList>()

    // Sagas (estático, sin scroll infinito)
    val sagas = getSagas()
    if (sagas.isNotEmpty()) {
        lists.add(HomePageList("🔥 Sagas", sagas.take(20)))
    }

    // Categorías con scroll infinito (carga múltiples páginas automáticamente)
    lists.add(getCategoryInfinite("🎬 Películas Recientes", "$mainUrl/peliculas?año=0&nota=0&sort=updated"))
    lists.add(getCategoryInfinite("📺 Series Recientes", "$mainUrl/series?año=0&nota=0&sort=updated"))
    lists.add(getCategoryInfinite("🌸 Animes", "$mainUrl/animes?año=0&nota=0&sort=updated"))

    // Plataformas con scroll infinito
    lists.add(getCategoryInfinite("🌐 Netflix", "$mainUrl/red/netflix?año=0&nota=0&orden=recientes"))
    lists.add(getCategoryInfinite("📼 Prime Video", "$mainUrl/red/amazon-prime-video?año=0&nota=0&orden=recientes"))
    lists.add(getCategoryInfinite("🍎 Apple TV", "$mainUrl/red/apple-tv?año=0&nota=0&orden=recientes"))
    lists.add(getCategoryInfinite("🐭 Disney+", "$mainUrl/red/disney?año=0&nota=0&orden=recientes"))
    lists.add(getCategoryInfinite("📡 TVTokyo", "$mainUrl/red/tv-tokyo?año=0&nota=0&orden=recientes"))
    lists.add(getCategoryInfinite("📡 Tokyo MX", "$mainUrl/red/tokyo-mx?año=0&nota=0&orden=recientes"))

    return newHomePageResponse(lists)
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
                        ?: movie.name ?: "Película ${i+1}"

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
                        this.name = movie.name ?: "Película ${i+1}"
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
        val isSeries = url.contains("/serie/")
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("|")?.trim() ?: "Sin título"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val episodes = mutableListOf<Episode>()
        doc.select("a.ep-item").forEach { ep ->
            val epUrl = fixUrl(ep.attr("href"))
            val epNum = ep.selectFirst(".ep-num")?.text()?.replace("E", "")?.trim()?.toIntOrNull() ?: 0
            val season = Regex("""temporada-(\d+)""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val epTitle = ep.selectFirst("p.text-sm, p.leading-tight")?.text()?.trim() ?: "Episodio $epNum"
            val thumb = ep.selectFirst("img")?.attr("data-src")?.ifBlank { ep.selectFirst("img")?.attr("src") }
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

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedWith(compareBy({ it.season }, { it.episode }))) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ====================== LOAD LINKS (CORREGIDO) ======================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val serverBtns = doc.select("button.server-btn")

        for (btn in serverBtns) {
            val serverUrl = btn.attr("data-server-url")
            val playerToken = btn.attr("data-player-token")
            val playerId = btn.attr("data-player-id")
            val playerModel = btn.attr("data-player-model")

            if (playerToken.isNotEmpty()) {
                try {
                    val response = app.post("$mainUrl/api/player-url", data = mapOf("t" to playerToken))
                    val resolvedUrl = JSONObject(response.text).optString("url")
                    if (resolvedUrl.isNotEmpty()) {
                        processIframe(resolvedUrl, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.e(name, "Token error: ${e.message}")
                }
            } else if (serverUrl.isNotEmpty()) {
                processIframe(serverUrl, data, subtitleCallback, callback)
            } else if (playerId.isNotEmpty() && playerModel.isNotEmpty()) {
                try {
                    val response = app.get("$mainUrl/api/player-url/$playerModel/$playerId")
                    val resolvedUrl = JSONObject(response.text).optString("url")
                    if (resolvedUrl.isNotEmpty()) {
                        processIframe(resolvedUrl, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.e(name, "Player error: ${e.message}")
                }
            }
        }
        return true
    }

    // ====================== PROCESS IFRAME ======================
    private suspend fun processIframe(
        iframeUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val iframeDoc = app.get(iframeUrl, referer = referer).document
            val html = iframeDoc.html()

            var aesKey: ByteArray? = null

            try {
                val challenge = Regex("""const\s+POW_CHALLENGE\s*=\s*'([^']+)';""").find(html)?.groupValues?.get(1)
                val difficulty = Regex("""const\s+POW_DIFFICULTY\s*=\s*(\d+);""").find(html)?.groupValues?.get(1)?.toIntOrNull()
                val salt = Regex("""const\s+POW_SALT\s*=\s*'([^']+)';""").find(html)?.groupValues?.get(1)
                if (challenge != null && difficulty != null && salt != null) {
                    aesKey = solvePoW(challenge, difficulty, salt)
                }
            } catch (_: Exception) {}

            try {
                val match = Regex("""dataLink = (\[.+?\]);""").find(html)
                if (match != null) {
                    val items = AppUtils.tryParseJson<List<Item>>(match.groupValues[1]) ?: emptyList()
                    for (item in items) {
                        for (embed in item.sortedEmbeds) {
                            if (embed.servername.equals("download", ignoreCase = true)) continue
                            val link = when {
                                embed.link?.contains(".") == true && embed.link.split(".").size == 3 -> decodeBase64Link(embed.link)
                                aesKey != null && embed.link != null -> decryptAES(embed.link, aesKey)
                                else -> null
                            }
                            if (link != null) loadExtractor(link, referer, subtitleCallback, callback)
                        }
                    }
                }
            } catch (_: Exception) {}

            try {
                iframeDoc.select(".ODDIV .OD_1 li[onclick]").forEach { el ->
                    val match = Regex("""go_to_playerVast\(\s*'([^']+)'""").find(el.attr("onclick"))
                    match?.groupValues?.getOrNull(1)?.let { loadExtractor(it.trim(), referer, subtitleCallback, callback) }
                }
            } catch (_: Exception) {}

            iframeDoc.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotEmpty() }?.let {
                loadExtractor(it, referer, subtitleCallback, callback)
            }

            // Extractores premium
            try {
                if (iframeUrl.contains("embed69") || iframeUrl.contains("pelisserieshoy")) {
                    Embed69Extractor.load(iframeUrl, referer, subtitleCallback, callback)
                }
                if (iframeUrl.contains("dood")) {
                    DoodExtractor().getUrl(iframeUrl, referer, subtitleCallback, callback)
                }
                if (iframeUrl.contains("playhydrax") || iframeUrl.contains("abyss")) {
                    PlayHydrax().getUrl(iframeUrl, referer, subtitleCallback, callback)
                }
                XupalaceExtractor().getUrl(iframeUrl, referer, subtitleCallback, callback)
            } catch (_: Exception) {}

            try {
                PlayerPelisSeriesHoyExtractor.load(iframeUrl, referer, subtitleCallback, callback)
            } catch (_: Exception) {}

        } catch (e: Exception) {
            Log.e(name, "processIframe error: ${e.message}")
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
        } catch (_: Exception) { null }
    }

    private fun decodeBase64Link(encryptedLink: String): String? {
        return try {
            val parts = encryptedLink.split(".")
            if (parts.size != 3) return null
            var payload = parts[1]
            if (payload.length % 4 != 0) payload += "=".repeat(4 - payload.length % 4)
            val json = String(Base64.decode(payload, Base64.DEFAULT))
            Regex("\"link\":\"(.*?)\"").find(json)?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }
}