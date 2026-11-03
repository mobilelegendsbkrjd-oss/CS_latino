package com.gnulahd

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.json.JSONObject
import org.jsoup.nodes.Element
import kotlin.coroutines.cancellation.CancellationException

class GnulaHD : MainAPI() {
    override var mainUrl = "https://ww3.gnulahd.nu"
    override var name = "GnulaHD"
    override var lang = "es"
    override val hasMainPage = true

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/ver/?type=Pelicula&order=latest" to "Últimas Películas",
        "$mainUrl/ver/?type=Serie&order=latest" to "Últimas Series",
        "$mainUrl/ver/?type=Anime&order=latest" to "Últimos Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = headers()).document
        val items = doc.select("a.gnrd-card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.encodeUrl()}"
        val doc = app.get(url, headers = headers()).document
        return doc.select("a.gnrd-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers()).document
        val title = doc.selectFirst("h1.gnrd-fi-title")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("meta[itemprop=\"image\"]")?.attr("content")
        val description = doc.selectFirst("p.gnrd-fi-syn")?.text()

        val episodes = doc.select("a.gnrd-epc").mapNotNull { ep ->
            val epUrl = ep.attr("href").ifBlank { return@mapNotNull null }
            val epNumber = ep.selectFirst("span.gnrd-epc-n")?.text() ?: ""
            val epTitle = ep.selectFirst("span.gnrd-epc-title")?.text() ?: "Episodio"
            val epImage = ep.selectFirst(".gnrd-epc-thumb")?.attr("style")
                ?.let { Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1) }
            val fullTitle = if (epNumber.isNotEmpty()) "$epNumber - $epTitle" else epTitle
            var season = 1
            var episode = 0
            val match = Regex("""(\d+)x(\d+)""").find(epNumber)
            if (match != null) {
                season = match.groupValues[1].toIntOrNull() ?: 1
                episode = match.groupValues[2].toIntOrNull() ?: 0
            }
            newEpisode(epUrl) {
                this.name = fullTitle
                this.season = season
                this.episode = if (episode > 0) episode else null
                this.posterUrl = epImage
            }
        }
        val suggestions = doc.select(".gnrd-similares .gnrd-card").mapNotNull { it.toSearchResult() }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.recommendations = suggestions
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.recommendations = suggestions
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        gnulaLog("=== loadLinks START data=$data ===")
        var anyLink = false
        val pageUrl = data.substringBefore("?")
        val pageHtml = app.get(pageUrl, headers = headers()).text

        // ── FIX 2026-11-03 ────────────────────────────────────────────────────────
        // El sitio fusionó las dos declaraciones en un solo `var` separado por comas:
        //   var _gnrdPid=515492,_gnrdTok="e19f22fd5e8a19fdf5160c1c7c38bcdf";
        // Los regex viejos exigían `var _gnrdTok` y ya no coinciden (el `var` solo
        // aparece delante del primero). Estos nuevos no dependen de dónde esté el `var`.
        val pidMatch = Regex("""_gnrdPid\s*=\s*['"]?(\d+)""").find(pageHtml)
        val tokMatch = Regex("""_gnrdTok\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)
        val pid = pidMatch?.groupValues?.get(1)
        val tok = tokMatch?.groupValues?.get(1)
        gnulaLog("pageUrl=$pageUrl htmlLen=${pageHtml.length} pid=$pid tok=${tok?.take(6)}...")
        if (pid.isNullOrEmpty() || tok.isNullOrEmpty()) {
            gnulaLog("NO pid/tok -> contiene _gnrdPid=${pageHtml.contains("_gnrdPid")} contiene _gnrdTok=${pageHtml.contains("_gnrdTok")}")
            gnulaLog("htmlInicio=${pageHtml.take(300)}")
            return false
        }
        // ──────────────────────────────────────────────────────────────────────────

        val apiUrl = "$mainUrl/wp-json/gnrd/v1/player?id=$pid&t=$tok"
        val apiResponse = app.get(
            apiUrl,
            headers = headers() + mapOf(
                "Referer" to pageUrl,
                "X-Requested-With" to "gnrd",
                "X-Gn-Dev" to "8bdcbc459e3b",
                "X-Gn-Scr" to "1366x768|1366x720|613x633|1|0|0|16|16|24|0|1|hw"
            )
        )
        gnulaLog("API code=${apiResponse.code} body=${apiResponse.text.take(200)}")
        val parsed = apiResponse.parsedSafe<PlayerResponse>()

        val pField = parsed?.p
        if (pField == null) {
            gnulaLog("API sin campo 'p' (403 / token inválido / headers X-Gn-* rechazados)")
            return false
        }
        val langs = decryptPlayerData(pField)
        if (langs == null) {
            gnulaLog("decryptPlayerData devolvió null (cambió la clave XOR o el formato)")
            return false
        }
        gnulaLog("langs=${langs.size} servers=${langs.sumOf { it.servers.size }}")

        for (gl in langs) {
            for (srv in gl.servers) {
                var videoUrl = srv.src.replace("\\/", "/")
                if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"
                if (videoUrl.isBlank() || videoUrl.contains("aviso.mp4")) {
                    gnulaLog("[${gl.label}] saltado (vacío o aviso.mp4): $videoUrl")
                    continue
                }

                val own = findGnulaExtractor(videoUrl)
                gnulaLog("[${gl.label}] server=$videoUrl extractorPropio=${own?.let { it.name + " " + it.mainUrl }}")

                // Se juntan los links aquí y se re-envuelven después con newExtractorLink
                // (es suspend, no se puede llamar dentro del lambda de loadExtractor).
                val found = mutableListOf<ExtractorLink>()

                try {
                    // 1) Extractor propio (Vidara / Byse / Filemoon) llamado directo,
                    //    así ningún extractor de la app le gana el dominio.
                    if (own != null) {
                        own.getUrl(videoUrl, "$mainUrl/", subtitleCallback) { found.add(it) }
                        gnulaLog("[${gl.label}] extractor propio devolvió ${found.size} links")
                    }
                    // 2) Si no hay extractor propio o no devolvió nada → extractores de la app
                    if (found.isEmpty()) {
                        gnulaLog("[${gl.label}] probando loadExtractor de la app para $videoUrl")
                        loadExtractor(videoUrl, "$mainUrl/", subtitleCallback) { found.add(it) }
                        gnulaLog("[${gl.label}] loadExtractor devolvió ${found.size} links")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // un servidor roto no debe tumbar a los demás
                    gnulaLog("[${gl.label}] ERROR en $videoUrl -> ${e.javaClass.simpleName}: ${e.message}")
                }

                found.forEach { link ->
                    gnulaLog("[${gl.label}] LINK source=${link.source} type=${link.type} quality=${link.quality} referer=${link.referer} url=${link.url}")
                    anyLink = true
                    callback.invoke(
                        newExtractorLink(
                            source = "${link.source} - ${gl.label}",
                            name = "${link.name} - ${gl.label}",
                            url = link.url,
                            type = link.type
                        ) {
                            this.referer = link.referer
                            this.quality = link.quality
                            this.headers = link.headers
                            this.extractorData = link.extractorData
                        }
                    )
                }
            }
        }
        gnulaLog("=== loadLinks END anyLink=$anyLink ===")
        return anyLink
    }

    private fun decryptPlayerData(encoded: String): List<GnulaLang>? {
        return try {
            val key = byteArrayOf(103, 78, 55, 100) // "gN7d"
            val raw = Base64.decode(encoded, Base64.DEFAULT)
            val dec = ByteArray(raw.size) { i -> (raw[i].toInt() xor key[i % key.size].toInt()).toByte() }
            val obj = JSONObject(String(dec, Charsets.UTF_8))
            val langsArray = obj.optJSONArray("langs")?.toString() ?: return null
            parseJson<List<GnulaLang>>(langsArray)
        } catch (e: Exception) {
            gnulaLog("decryptPlayerData ERROR: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    data class PlayerResponse(val p: String? = null)
    data class GnulaLang(val label: String, val servers: List<GnulaServer>)
    data class GnulaServer(val src: String)

    private fun headers() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
        "Referer" to mainUrl
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.attr("href").ifBlank { return null }
        val title = selectFirst("span.gnrd-card-title")?.text()?.trim() ?: return null
        val poster = selectFirst(".gnrd-card-art img")?.attr("src")
        val type = when {
            selectFirst("span.gnrd-type-badge")?.text()?.contains("Serie", true) == true -> TvType.TvSeries
            selectFirst("span.gnrd-type-badge")?.text()?.contains("Anime", true) == true -> TvType.Anime
            link.contains("pelicula") -> TvType.Movie
            link.contains("serie") -> TvType.TvSeries
            link.contains("anime") -> TvType.Anime
            else -> TvType.Movie
        }
        return newTvSeriesSearchResponse(title, fixUrl(link), type) { this.posterUrl = poster }
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")
}