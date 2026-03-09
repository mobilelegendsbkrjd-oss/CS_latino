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
import com.google.gson.JsonElement

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

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".ani-txt, .p-title, .vk-info p")?.text()
            ?: selectFirst("a")?.attr("title") ?: return null
        var href = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.attr("src")

        if (href.contains("/ver/")) {
            val slug = href.removeSuffix("/").substringAfterLast("/")
                .replace(Regex("(?i)-capitulo-\\d+|-capítulo-\\d+"), "")
            href = "$mainUrl/novela/$slug/"
        }

        return newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
            this.posterUrl = poster
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
            newEpisode(epUrl) {
                name = if (epName.isEmpty()) "Capítulo" else "Capítulo $epName"
            }
        }.distinctBy { it.data }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = finalDoc.selectFirst(".card-text, .ani-description")?.text()
        }
    }

    private fun decodeVideoUrl(encoded: String): String {
        try {
            // Caso 1: Formato viejo "encoded|key" (shift char codes) – para directos
            val parts = encoded.split("|")
            if (parts.size == 2) {
                val encodedStr = parts[0]
                val key = parts[1].toIntOrNull() ?: return encoded
                val decodedChars = mutableListOf<Char>()
                for (i in encodedStr.indices) {
                    val charCode = encodedStr[i].code - key - i
                    decodedChars.add(charCode.toChar())
                }
                val decodedString = decodedChars.joinToString("")
                return URLDecoder.decode(decodedString, "UTF-8")
            }

            // Caso 2: Base64 simple o JSON encriptado
            try {
                val padded = padBase64(encoded)
                val decodedBytes = Base64.getUrlDecoder().decode(padded)
                val decodedStr = String(decodedBytes, Charsets.UTF_8)
                if (decodedStr.startsWith("http")) return decodedStr

                // Intento de parsear como JSON con iv/payload/key_parts
                val json = Gson().fromJson(decodedStr, JsonElement::class.java).asJsonObject
                if (json.has("iv") && json.has("payload") && json.has("key_parts")) {
                    val playbackData = PlaybackData(
                        json.get("iv").asString,
                        json.get("payload").asString,
                        json.get("key_parts").asJsonArray.map { it.asString }
                    )
                    return decryptBysejikuar(playbackData) ?: encoded
                }
            } catch (_: Exception) {}

            return encoded
        } catch (e: Exception) {
            return encoded
        }
    }

    private fun padBase64(s: String): String {
        var padded = s
        while (padded.length % 4 != 0) padded += "="
        return padded
    }

    private fun decryptBysejikuar(data: PlaybackData): String? {
        try {
            val decoder = Base64.getUrlDecoder()
            val iv = decoder.decode(padBase64(data.iv))
            val payload = decoder.decode(padBase64(data.payload))
            val p1 = decoder.decode(padBase64(data.key_parts[0]))
            val p2 = decoder.decode(padBase64(data.key_parts[1]))
            val key = p1 + p2

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            val secretKey = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            return String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
    }

    private suspend fun tryExtractBysejikuar(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val matcher = Regex("""/(e|d)/([a-zA-Z0-9]+)""").find(embedUrl) ?: return false
            val linkType = matcher.groupValues[1]
            val videoId = matcher.groupValues[2]
            val base = embedUrl.substringBefore("/e/").substringBefore("/d/") + "/"
            val detailsUrl = "$base/api/videos/$videoId/embed/details"
            val detailsText = app.get(detailsUrl, headers = mapOf("Referer" to referer)).text
            val details = Gson().fromJson(detailsText, DetailsResponse::class.java)
            val embedFrame = details.embed_frame_url ?: return false

            val headers = mutableMapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to embedFrame,
                "X-Embed-Parent" to embedUrl
            )

            val playbackDomain = if (linkType == "d") base else embedFrame.substringBefore("/api/")
            val playbackUrl = "$playbackDomain/api/videos/$videoId/embed/playback"
            val playbackText = app.get(playbackUrl, headers = headers).text
            val playback = Gson().fromJson(playbackText, PlaybackResponse::class.java).playback ?: return false
            val decrypted = decryptBysejikuar(playback) ?: return false
            val sources = Gson().fromJson(decrypted, DecryptedPlayback::class.java).sources ?: return false
            if (sources.isEmpty()) return false

            val sourceUrl = sources[0].url ?: return false
            callback.invoke(
                newExtractorLink(
                    source = "Bysejikuar",
                    name = "Bysejikuar",
                    url = sourceUrl,
                    referer = referer,
                    quality = 0,                    // 0 = Unknown, puedes cambiar a 720 o 1080 si sabes
                    isM3u8 = sourceUrl.contains(".m3u8")
                )
            )
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private suspend fun tryExtractLuluVdo(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val docText = app.get(embedUrl, headers = mapOf("Referer" to referer)).text
            val sourceMatch = Regex("sources:\\s*\\[\\{file:\\s*\"(.*?)\"").find(docText)
            val source = sourceMatch?.groupValues?.get(1) ?: return false

            callback.invoke(
                newExtractorLink(
                    source = "LuluVdo",
                    name = "LuluVdo",
                    url = source,
                    referer = referer,
                    quality = 0,                    // 0 = Unknown
                    isM3u8 = source.contains(".m3u8")
                )
            )

            val tracksPart = Regex("tracks:\\s*\\[(.*?)\\]").find(docText)?.groupValues?.get(1) ?: ""
            Regex("file:\\s*\"(.*?)\",\\s*label:\\s*\"(.*?)\"").findAll(tracksPart).forEach {
                val file = it.groupValues[1]
                val label = it.groupValues[2]
                if (label.lowercase() != "upload captions") {
                    subtitleCallback(SubtitleFile(file, label))
                }
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data).text
        val videoLinks = mutableSetOf<String>()

        // Extrae TODOS los strings posibles de e[] (incluso ofuscados)
        val allEncoded = mutableSetOf<String>()
        Regex("""e\[\d+\]\s*=\s*['"]([^'"]+)['"]""").findAll(response).forEach {
            allEncoded.add(it.groupValues[1])
        }
        Regex("""var\s+e\s*=\s*\[([^\]]+)\]""").findAll(response).forEach { match ->
            match.groupValues[1].split(",")
                .map { it.trim().trim('\'', '"') }
                .filter { it.isNotEmpty() }
                .forEach { allEncoded.add(it) }
        }
        // Regex extra para posibles Base64 largos ofuscados
        Regex("""['"]([A-Za-z0-9+/=]{20,})['"]""").findAll(response).forEach {
            allEncoded.add(it.groupValues[1])
        }

        // Decodifica y filtra URLs válidas
        videoLinks.addAll(allEncoded.map { decodeVideoUrl(it) }.filter { it.startsWith("http") })

        val regexVideFunc = Regex("""v_ideo\(([^)]+)\)""")
        regexVideFunc.findAll(response).forEach { match ->
            val param = match.groupValues[1]
            val arrayIndex = Regex("""e\[(\d+)\]""").find(param)?.groupValues?.get(1)?.toIntOrNull()
            if (arrayIndex != null) {
                val arrayRegex = Regex("""e\[$arrayIndex\]\s*=\s*['"]([^'"]+)['"]""")
                arrayRegex.find(response)?.let { arrayMatch ->
                    val encodedUrl = arrayMatch.groupValues[1]
                    val decodedUrl = decodeVideoUrl(encodedUrl)
                    if (decodedUrl.startsWith("http")) videoLinks.add(decodedUrl)
                }
            }
        }

        val decodedPatterns = listOf(
            Regex("""https?://[^"'\s<>]+\.(mp4|m3u8|mkv|avi|mov|flv|wmv|webm)[^"'\s<>]*""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s<>]+/video/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s<>]+/embed/[^"'\s<>]+""", RegexOption.IGNORE_CASE)
        )
        decodedPatterns.forEach { pattern ->
            pattern.findAll(response).forEach { match ->
                val url = match.value
                if (!url.contains("google") && !url.contains("adskeeper") && !url.contains("googletagmanager")) {
                    videoLinks.add(url)
                }
            }
        }

        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).findAll(response).forEach {
            val link = it.groupValues[1]
            if (!link.contains("google") && !link.contains("adskeeper")) {
                videoLinks.add(link)
            }
        }

        var success = false
        videoLinks.forEach { link ->
            try {
                if (loadExtractor(link, data, subtitleCallback, callback)) success = true
            } catch (_: Exception) {}
        }

        // FALLBACK para embeds específicos
        if (!success) {
            val embeds = mutableListOf<String>()
            Regex("""['"](https?://[^'"]+)['"]""").findAll(response).forEach {
                val url = it.groupValues[1]
                if (url.contains("bysejikuar") || url.contains("luluvdo") || url.contains("dood")) {
                    embeds.add(url)
                }
            }
            embeds.distinct().forEach { embed ->
                try {
                    when {
                        embed.contains("bysejikuar.com") || embed.contains("f75s.com") ->
                            success = success || tryExtractBysejikuar(embed, data, subtitleCallback, callback)
                        embed.contains("luluvdo.com") ->
                            success = success || tryExtractLuluVdo(embed, data, subtitleCallback, callback)
                        else ->
                            success = success || loadExtractor(embed, data, subtitleCallback, callback)
                    }
                } catch (_: Exception) {}
            }
        }

        return success || videoLinks.isNotEmpty()
    }

    // Modelos para bysejikuar
    data class DetailsResponse(val embed_frame_url: String?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class PlaybackData(val iv: String, val payload: String, val key_parts: List<String>)
    data class DecryptedPlayback(val sources: List<DecryptedSource>?)
    data class DecryptedSource(val url: String?)
}
