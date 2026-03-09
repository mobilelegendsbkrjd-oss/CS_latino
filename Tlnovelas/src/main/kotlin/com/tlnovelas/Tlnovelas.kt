package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.google.gson.Gson
import com.lagradost.cloudstream3.utils.JsUnpacker // Asegúrate de importar JsUnpacker

class Tlnovelas : MainAPI() {
    override var mainUrl = "https://ww2.tlnovelas.net"
    override var name = "Tlnovelas"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Últimos Capítulos",
        "gratis/telenovelas/" to "Ver Telenovelas"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/page/$page"
        val document = app.get(url).document
        val home = document.select(".vk-poster, .ani-card, .p-content, .ani-txt")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, home, true)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = selectFirst(".ani-txt, .p-title, .vk-info p")?.text()
            ?: selectFirst("a")?.attr("title") ?: ""
        var href = selectFirst("a")?.attr("href") ?: ""
        val poster = selectFirst("img")?.attr("src")

        if (href.contains("/ver/")) {
            val slug = href.removeSuffix("/").substringAfterLast("/")
                .replace(Regex("(?i)-capitulo-\\d+|-capítulo-\\d+"), "")
            href = "$mainUrl/novela/$slug/"
        }

        return newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar/?q=$query"
        return app.get(url).document.select(".vk-poster, .ani-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val novelaLink = document.selectFirst("a[href*='/novela/']")?.attr("href")
        val finalDoc = if (url.contains("/ver/") && novelaLink != null) app.get(novelaLink).document else document
        
        val title = finalDoc.selectFirst("h1.card-title, .vk-title-main, h1")?.text()
            ?.replace(Regex("(?i)Capitulos de|Ver"), "")?.trim() ?: "Telenovela"
        
        val poster = finalDoc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: finalDoc.selectFirst(".ani-img img")?.attr("src")

        val episodes = finalDoc.select("a[href*='/ver/']").map {
            val epUrl = it.attr("href")
            val epName = it.text().replace(title, "", true)
                .replace(Regex("(?i)Ver|Capitulo|Capítulo"), "").trim()
            newEpisode(epUrl) { name = if (epName.isEmpty()) "Capítulo" else "Capítulo $epName" }
        }.distinctBy { it.data }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = finalDoc.selectFirst(".card-text, .ani-description")?.text()
        }
    }

    private fun decodeVideoUrl(encoded: String): String {
        return try {
            val parts = encoded.split("|")
            if (parts.size == 2) {
                val encodedStr = parts[0]
                val key = parts[1].toInt()
                val decodedChars = mutableListOf<Char>()
                for (i in encodedStr.indices) {
                    val charCode = encodedStr[i].code - key - i
                    decodedChars.add(charCode.toChar())
                }
                URLDecoder.decode(decodedChars.joinToString(""), "UTF-8")
            } else encoded
        } catch (e: Exception) { encoded }
    }

    private suspend fun tryExtractBysejikuar(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val matcher = Regex("""/(e|d)/([a-zA-Z0-9]+)""").find(embedUrl) ?: return false
            val linkType = matcher.groupValues[1]
            val videoId = matcher.groupValues[2]
            val base = embedUrl.substringBefore("/e/").substringBefore("/d/") + "/"
            
            val detailsText = app.get("$base/api/videos/$videoId/embed/details", headers = mapOf("Referer" to referer)).text
            val details = Gson().fromJson(detailsText, DetailsResponse::class.java)
            val embedFrame = details.embed_frame_url ?: return false

            val playbackDomain = if (linkType == "d") base else embedFrame.substringBefore("/api/")
            val playbackText = app.get("$playbackDomain/api/videos/$videoId/embed/playback", headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to embedFrame,
                "X-Embed-Parent" to embedUrl
            )).text
            
            val playback = Gson().fromJson(playbackText, PlaybackResponse::class.java).playback ?: return false
            val decrypted = decryptBysejikuar(playback) ?: return false
            val sources = Gson().fromJson(decrypted, DecryptedPlayback::class.java).sources ?: return false
            
            sources.firstOrNull()?.url?.let { sourceUrl ->
                callback.invoke(ExtractorLink("Bysejikuar", "Bysejikuar", sourceUrl, referer, Qualities.Unknown.value, sourceUrl.contains(".m3u8")))
                true
            } ?: false
        } catch (e: Exception) { false }
    }

    private fun decryptBysejikuar(data: PlaybackData): String? {
        return try {
            val decoder = Base64.getUrlDecoder()
            val iv = decoder.decode(padBase64(data.iv))
            val payload = decoder.decode(padBase64(data.payload))
            val key = decoder.decode(padBase64(data.key_parts[0])) + decoder.decode(padBase64(data.key_parts[1]))
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    private fun padBase64(s: String): String {
        var padded = s
        while (padded.length % 4 != 0) padded += "="
        return padded
    }

    private suspend fun tryExtractLuluVdo(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val docText = app.get(embedUrl, headers = mapOf("Referer" to referer)).text
            val source = Regex("""sources:\s*\[\{file:\s*"([^"]+)"""").find(docText)?.groupValues?.get(1) ?: return false
            
            callback.invoke(ExtractorLink("LuluVdo", "LuluVdo", source, referer, Qualities.Unknown.value, source.contains(".m3u8")))
            
            Regex("""file:\s*"([^"]+)",\s*label:\s*"([^"]+)"""").findAll(docText).forEach {
                val label = it.groupValues[2]
                if (label.lowercase() != "upload captions") subtitleCallback(SubtitleFile(it.groupValues[1], label))
            }
            true
        } catch (e: Exception) { false }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data).text
        val videoLinks = mutableSetOf<String>()

        // Regex para capturar links codificados y normales
        Regex("""e\[(\d+)\]\s*=\s*['"]([^'"]+)['"]""").findAll(response).forEach { match ->
            val decoded = decodeVideoUrl(match.groupValues[2])
            if (decoded.startsWith("http")) videoLinks.add(decoded)
        }

        Regex("""var\s+e\s*=\s*\[(.*?)\]""").find(response)?.groupValues?.get(1)?.split(",")?.forEach {
            val decoded = decodeVideoUrl(it.trim().trim('\'', '"'))
            if (decoded.startsWith("http")) videoLinks.add(decoded)
        }

        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).findAll(response).forEach {
            val link = it.groupValues[1]
            if (!link.contains("google") && !link.contains("adskeeper")) videoLinks.add(link)
        }

        // ===== NUEVAS MEJORAS PARA CAPÍTULOS NUEVOS =====
        
        // Buscar video en scripts JS (nuevo sistema)
        Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)""")
            .find(response)?.groupValues?.get(1)?.let { videoLinks.add(it) }

        Regex("""file\s*:\s*["'](https?://[^"']+)""")
            .findAll(response).forEach {
                videoLinks.add(it.groupValues[1])
            }

        Regex("""window\.location\.href\s*=\s*["']([^"']+)""")
            .find(response)?.groupValues?.get(1)?.let { videoLinks.add(it) }

        // Unpacker JS para código empaquetado (muy importante)
        if (response.contains("eval(function(p,a,c,k,e")) {
            val unpacker = JsUnpacker(response)
            if (unpacker.detect()) {
                val unpacked = unpacker.unpack()
                unpacked?.let {
                    Regex("""file\s*:\s*["'](https?://[^"']+)""")
                        .findAll(it).forEach { m -> videoLinks.add(m.groupValues[1]) }
                }
            }
        }

        // También buscar enlaces directos m3u8 en el texto
        Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").findAll(response).forEach {
            videoLinks.add(it.groupValues[1])
        }

        var success = false
        videoLinks.forEach { link ->
            try {
                if (loadExtractor(link, data, subtitleCallback, callback)) success = true
            } catch (_: Exception) {}
        }

        if (!success) {
            videoLinks.forEach { embed ->
                when {
                    embed.contains("bysejikuar.com") || embed.contains("f75s.com") -> 
                        success = success || tryExtractBysejikuar(embed, data, subtitleCallback, callback)
                    embed.contains("luluvdo.com") -> 
                        success = success || tryExtractLuluVdo(embed, data, subtitleCallback, callback)
                }
            }
        }
        return success || videoLinks.isNotEmpty()
    }

    data class DetailsResponse(val embed_frame_url: String?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class PlaybackData(val iv: String, val payload: String, val key_parts: List<String>)
    data class DecryptedPlayback(val sources: List<DecryptedSource>?)
    data class DecryptedSource(val url: String?)
}
