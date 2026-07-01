package com.mundodonghua

import com.google.gson.Gson
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class VidHideMundo : VidHidePro() {
    override var name = "VidHide"
    override var mainUrl = "https://vidhidepro.com"
}

class CallistaniseMundo : VidHidePro() {
    override var name = "VidHide"
    override var mainUrl = "https://callistanise.com"
}

object MundoHostResolver {
    private const val MAIN_URL = "https://www.mundodonghua.com"

    suspend fun resolve(
        rawUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = fixHosts(rawUrl)
        val lower = url.lowercase()
        var found = false

        fun emit(link: ExtractorLink) {
            found = true
            println("MD_DEBUG[RESOLVE_EMIT] source=${link.source} name=${link.name} url=${link.url}")
            callback.invoke(link)
        }

        println("MD_DEBUG[RESOLVE_START] raw=$rawUrl fixed=$url referer=$referer")

        try {
            when {
                lower.contains("bysekoze.com") || lower.contains("bysejikuar") || lower.contains("f75s") -> {
                    println("MD_DEBUG[RESOLVE_BRANCH] BYSEKOZE")
                    BysekozeMundo().getUrl(url, referer, subtitleCallback) { emit(it) }
                    if (!found) {
                        println("MD_DEBUG[RESOLVE_BYSEKOZE_FALLBACK_GENERIC]")
                        found = resolveGeneric(url, referer, callback)
                    }
                }

                lower.contains("vidhide") || lower.contains("callistanise") -> {
                    println("MD_DEBUG[RESOLVE_BRANCH] VIDHIDE_CALLISTANISE")
                    try {
                        loadExtractor(url, referer, subtitleCallback) { emit(it) }
                    } catch (e: Exception) {
                        println("MD_DEBUG[RESOLVE_VIDHIDE_LOAD_EXTRACTOR_ERROR] ${e.message}")
                    }

                    if (!found) {
                        println("MD_DEBUG[RESOLVE_VIDHIDE_FALLBACK_GENERIC]")
                        found = resolveGeneric(url, referer, callback)
                    }
                }

                lower.contains("voe.") -> {
                    println("MD_DEBUG[RESOLVE_BRANCH] VOE")
                    try {
                        loadExtractor(url, referer, subtitleCallback) { emit(it) }
                    } catch (e: Exception) {
                        println("MD_DEBUG[RESOLVE_VOE_LOAD_EXTRACTOR_ERROR] ${e.message}")
                    }

                    if (!found) {
                        println("MD_DEBUG[RESOLVE_VOE_FALLBACK_GENERIC]")
                        found = resolveGeneric(url, referer, callback)
                    }
                }

                lower.contains("dailymotion.com/embed/video/") || lower.contains("dailymotion.com/video/") -> {
                    println("MD_DEBUG[RESOLVE_BRANCH] DAILYMOTION")
                    found = resolveDailymotion(url, referer, callback)

                    if (!found) {
                        println("MD_DEBUG[RESOLVE_DAILYMOTION_FALLBACK_LOAD_EXTRACTOR]")
                        try {
                            loadExtractor(url, referer, subtitleCallback) { emit(it) }
                        } catch (e: Exception) {
                            println("MD_DEBUG[RESOLVE_DAILYMOTION_LOAD_EXTRACTOR_ERROR] ${e.message}")
                        }
                    }
                }

                lower.contains(".m3u8") || lower.contains(".mp4") -> {
                    println("MD_DEBUG[RESOLVE_BRANCH] DIRECT_FILE")
                    emitDirect(url, referer, callback, "Direct")
                    found = true
                }

                else -> {
                    println("MD_DEBUG[RESOLVE_BRANCH] GENERIC")
                    found = resolveGeneric(url, referer, callback)

                    if (!found) {
                        println("MD_DEBUG[RESOLVE_GENERIC_FALLBACK_LOAD_EXTRACTOR]")
                        try {
                            loadExtractor(url, referer, subtitleCallback) { emit(it) }
                        } catch (e: Exception) {
                            println("MD_DEBUG[RESOLVE_GENERIC_LOAD_EXTRACTOR_ERROR] ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("MD_DEBUG[RESOLVE_FATAL_ERROR] url=$url error=${e.message}")
        }

        println("MD_DEBUG[RESOLVE_END] url=$url found=$found")
        return found
    }

    suspend fun resolveTamamo(
        slug: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanSlug = slug.trim().trim('"', '\'')
        println("MD_DEBUG[TAMAMO_START] slug=$cleanSlug referer=$referer")

        if (cleanSlug.isBlank()) return false

        val api = "$MAIN_URL/api_donghua.php?slug=$cleanSlug"
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "es-419,es;q=0.9",
            "Origin" to MAIN_URL,
            "Referer" to referer,
            "X-Requested-With" to "XMLHttpRequest"
        )

        val jsonText = try {
            app.get(api, referer = referer, headers = headers).text
        } catch (e: Exception) {
            println("MD_DEBUG[TAMAMO_GET_ERROR] ${e.message}")
            try {
                app.post(api, referer = referer, headers = headers).text
            } catch (e2: Exception) {
                println("MD_DEBUG[TAMAMO_POST_ERROR] ${e2.message}")
                return false
            }
        }

        println("MD_DEBUG[TAMAMO_API] $api")
        println("MD_DEBUG[TAMAMO_RESPONSE] ${jsonText.take(1500)}")

        var found = false

        try {
            val arr = JSONArray(jsonText)

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                println("MD_DEBUG[TAMAMO_OBJ_$i] $obj")

                val sources = obj.optJSONArray("source")
                if (sources != null) {
                    println("MD_DEBUG[TAMAMO_SOURCES_COUNT] ${sources.length()}")
                    for (s in 0 until sources.length()) {
                        val src = sources.optJSONObject(s) ?: continue
                        var file = src.optString("file")
                        val label = src.optString("label").ifBlank { "Protea" }
                        if (file.startsWith("//")) file = "https:$file"

                        println("MD_DEBUG[TAMAMO_SOURCE] label=$label file=$file")

                        if (file.isNotBlank()) {
                            emitDirect(file, referer, callback, "Protea $label")
                            found = true
                        }
                    }
                }

                var file = obj.optString("file")
                if (file.startsWith("//")) file = "https:$file"

                if (file.isNotBlank()) {
                    println("MD_DEBUG[TAMAMO_FILE] $file")
                    found = resolve(file, referer, subtitleCallback, callback) || found
                }

                val key = obj.optString("url").ifBlank { obj.optString("key") }

                if (key.isNotBlank()) {
                    println("MD_DEBUG[TAMAMO_KEY] $key")

                    val players = listOf(
                        "https://www.mdnemonicplayer.xyz/nemonicplayer/dmplayer.php?key=$key",
                        "https://www.mdplayer.xyz/nemonicplayer/dmplayer.php?key=$key"
                    )

                    for (player in players) {
                        println("MD_DEBUG[TAMAMO_PLAYER] $player")
                        found = resolveDmPlayer(player, referer, callback) || found
                    }

                    val decoded = runCatching {
                        String(Base64.getDecoder().decode(key), Charsets.UTF_8)
                    }.getOrNull()

                    println("MD_DEBUG[TAMAMO_KEY_DECODED] $decoded")

                    if (!decoded.isNullOrBlank()) {
                        val dm = if (decoded.startsWith("http", true)) decoded else "https://www.dailymotion.com/embed/video/$decoded"
                        found = resolve(dm, referer, subtitleCallback, callback) || found
                    }
                }
            }
        } catch (e: Exception) {
            println("MD_DEBUG[TAMAMO_PARSE_ERROR] ${e.message}")
        }

        println("MD_DEBUG[TAMAMO_END] found=$found")
        return found
    }

    private suspend fun resolveDmPlayer(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("MD_DEBUG[DMPLAYER_START] $url")

            val html = app.get(
                url,
                referer = MAIN_URL,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept-Language" to "es-419,es;q=0.9",
                    "Referer" to "$MAIN_URL/"
                )
            ).text
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("\\\"", "\"")

            println("MD_DEBUG[DMPLAYER_HTML_LEN] ${html.length}")
            println("MD_DEBUG[DMPLAYER_HTML_PREVIEW] ${html.take(800)}")

            val videoId = listOf(
                Regex("""video-id=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1),
                Regex("""video\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1),
                Regex("""["']?video["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1),
                Regex("""video[-_]?id["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty()

            println("MD_DEBUG[DMPLAYER_VIDEO_ID] $videoId")

            if (videoId.isNotBlank()) {
                resolveDailymotion("https://www.dailymotion.com/video/$videoId", url, callback)
            } else {
                false
            }
        } catch (e: Exception) {
            println("MD_DEBUG[DMPLAYER_ERROR] ${e.message}")
            false
        }
    }

    private suspend fun resolveDailymotion(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("MD_DEBUG[DAILYMOTION_START] url=$url referer=$referer")

            val videoId = Regex("""(?:video|embed/video)/([a-zA-Z0-9]+)""")
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?: return false

            println("MD_DEBUG[DAILYMOTION_ID] $videoId")

            val metadataUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val jsonText = app.get(
                metadataUrl,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "application/json,text/plain,*/*"
                )
            ).text

            println("MD_DEBUG[DAILYMOTION_METADATA] $metadataUrl")
            println("MD_DEBUG[DAILYMOTION_RESPONSE] ${jsonText.take(1200)}")

            val json = JSONObject(jsonText)
            val qualities = json.optJSONObject("qualities") ?: return false

            var emitted = false
            val qualityNames = qualities.keys()

            while (qualityNames.hasNext()) {
                val qName = qualityNames.next()
                val arr = qualities.optJSONArray(qName) ?: continue

                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val link = obj.optString("url")
                    val type = obj.optString("type")

                    println("MD_DEBUG[DAILYMOTION_QUALITY] q=$qName type=$type url=$link")

                    if (link.isNotBlank()) {
                        emitDirect(link, url, callback, "Dailymotion $qName")
                        emitted = true
                    }
                }
            }

            println("MD_DEBUG[DAILYMOTION_END] emitted=$emitted")
            emitted
        } catch (e: Exception) {
            println("MD_DEBUG[DAILYMOTION_ERROR] ${e.message}")
            false
        }
    }

    private suspend fun resolveGeneric(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("MD_DEBUG[GENERIC_START] url=$url referer=$referer")

            val text = app.get(
                url,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Referer" to referer
                )
            ).text

            println("MD_DEBUG[GENERIC_HTML_LEN] ${text.length}")
            println("MD_DEBUG[GENERIC_HTML_PREVIEW] ${text.take(600)}")

            var searchText = text
                .replace("\\/", "/")
                .replace("&amp;", "&")

            if (searchText.contains("eval(function(p,a,c,k,e", true)) {
                JsUnpacker(searchText).unpack()?.let {
                    println("MD_DEBUG[GENERIC_UNPACK_OK] len=${it.length}")
                    searchText = it
                }
            }

            val found = linkedSetOf<String>()

            Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                .findAll(searchText)
                .forEach { found.add(it.value.replace("\\/", "/")) }

            Regex("""file\s*[:=]\s*["']([^"']+)""", RegexOption.IGNORE_CASE)
                .findAll(searchText)
                .forEach { found.add(it.groupValues[1].replace("\\/", "/")) }

            Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)""", RegexOption.IGNORE_CASE)
                .findAll(searchText)
                .forEach { found.add(it.groupValues[1].replace("\\/", "/")) }

            println("MD_DEBUG[GENERIC_FOUND_COUNT] ${found.size}")

            var success = false

            found.forEach { link ->
                println("MD_DEBUG[GENERIC_LINK] $link")
                if (link.startsWith("http")) {
                    emitDirect(link, url, callback, "Generic")
                    success = true
                }
            }

            println("MD_DEBUG[GENERIC_END] success=$success")
            success
        } catch (e: Exception) {
            println("MD_DEBUG[GENERIC_ERROR] ${e.message}")
            false
        }
    }

    private suspend fun emitDirect(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        name: String
    ) {
        println("MD_DEBUG[EMIT_DIRECT] name=$name url=$url referer=$referer")

        callback.invoke(
            newExtractorLink(
                source = "MundoDonghua",
                name = name,
                url = url,
                type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(url).takeIf { it > 0 } ?: Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                )
            }
        )
    }

    private fun fixHosts(url: String): String {
        var fixed = url.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")

        if (fixed.startsWith("//")) fixed = "https:$fixed"

        return fixed
            .replace("vidhidefast.com", "vidhidepro.com")
            .replace("vidhide.com", "vidhidepro.com")
            .replace("mivalyo.com", "vidhidepro.com")
            .replace("dinisglows.com", "vidhidepro.com")
            .replace("dhtpre.com", "vidhidepro.com")
            .replace("bysejikuar.com", "bysekoze.com")
            .replace("filemoon.link", "filemoon.sx")
            .replace("filemoon.lat", "filemoon.sx")
            .replace("hglink.to", "streamwish.to")
            .replace("swdyu.com", "streamwish.to")
            .replace("wishembed.com", "streamwish.to")
    }

    fun isVideoHost(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("bysekoze") ||
                lower.contains("bysejikuar") ||
                lower.contains("f75s") ||
                lower.contains("voe.") ||
                lower.contains("vidhide") ||
                lower.contains("callistanise") ||
                lower.contains("filemoon") ||
                lower.contains("fmoon") ||
                lower.contains("dailymotion") ||
                lower.contains("streamwish") ||
                lower.contains("watchsb") ||
                lower.contains(".m3u8") ||
                lower.contains(".mp4")
    }

    fun extractUrls(text: String): List<String> {
        return Regex(
            """https?://[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]+""",
            RegexOption.IGNORE_CASE
        ).findAll(text)
            .map { it.value.trim().trim('"', '\'', '<', '>', ')', ';') }
            .filter { it.startsWith("http", true) }
            .distinct()
            .toList()
    }
}

class BysekozeMundo : ExtractorApi() {
    override var name = "Bysekoze"
    override var mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = url.replace("\\/", "/").replace("&amp;", "&")
        val safeReferer = referer ?: "https://www.mundodonghua.com/"

        println("MD_DEBUG[BYSEKOZE_START] fixed=$fixed referer=$safeReferer")

        if (simplePlayback(fixed, safeReferer, callback)) {
            println("MD_DEBUG[BYSEKOZE_SIMPLE_OK]")
            return
        }

        println("MD_DEBUG[BYSEKOZE_SIMPLE_FAIL]")

        if (encryptedPlayback(fixed, safeReferer, callback)) {
            println("MD_DEBUG[BYSEKOZE_ENCRYPTED_OK]")
            return
        }

        println("MD_DEBUG[BYSEKOZE_ENCRYPTED_FAIL]")
        println("MD_DEBUG[BYSEKOZE_GENERIC_FALLBACK]")

        genericBysekoze(fixed, safeReferer, callback)
    }

    private suspend fun simplePlayback(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val id = Regex("""/(?:e|d)/([A-Za-z0-9]+)""")
                .find(embedUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?: return false

            val api = "$mainUrl/api/videos/$id/embed/playback"
            println("MD_DEBUG[BYSEKOZE_SIMPLE_API] $api")

            val text = app.get(
                api,
                headers = mapOf(
                    "Referer" to embedUrl,
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json,text/plain,*/*",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text

            println("MD_DEBUG[BYSEKOZE_SIMPLE_RESPONSE] ${text.take(1200)}")

            val json = JSONObject(text)
            val sources = json.optJSONArray("sources") ?: return false

            var ok = false

            for (i in 0 until sources.length()) {
                val link = sources.getJSONObject(i).optString("url")
                println("MD_DEBUG[BYSEKOZE_SIMPLE_LINK] $link")
                if (link.isBlank()) continue

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link,
                        type = if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                    }
                )

                ok = true
            }

            ok
        } catch (e: Exception) {
            println("MD_DEBUG[BYSEKOZE_SIMPLE_ERROR] ${e.message}")
            false
        }
    }

    private suspend fun encryptedPlayback(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val id = Regex("""/(?:e|d)/([A-Za-z0-9]+)""")
                .find(embedUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?: return false

            val domain = Regex("""^https?://[^/]+""")
                .find(embedUrl)
                ?.value
                ?: mainUrl

            println("MD_DEBUG[BYSEKOZE_ENCRYPTED_DOMAIN] $domain id=$id")

            val embedRes = app.get(
                embedUrl,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )

            val cookies = embedRes.cookies
            val viewerId = cookies["byse_viewer_id"] ?: ""
            val deviceId = cookies["byse_device_id"] ?: ""

            println("MD_DEBUG[BYSEKOZE_COOKIES] viewer=$viewerId device=$deviceId")

            val detailsUrl = "$domain/api/videos/$id/embed/details"
            val detailsText = app.get(
                detailsUrl,
                headers = mapOf(
                    "Referer" to embedUrl,
                    "Origin" to domain,
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json,text/plain,*/*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-Embed-Origin" to "www.mundodonghua.com",
                    "X-Embed-Referer" to referer
                )
            ).text

            println("MD_DEBUG[BYSEKOZE_DETAILS_URL] $detailsUrl")
            println("MD_DEBUG[BYSEKOZE_DETAILS_RESPONSE] ${detailsText.take(1200)}")

            val details = Gson().fromJson(detailsText, DetailsResponse::class.java)
            val embedFrame = details.embed_frame_url ?: embedUrl

            val playbackBase = if (embedFrame.contains("f75s.com")) "https://f75s.com" else domain

            val cookie = buildString {
                if (viewerId.isNotEmpty()) append("byse_viewer_id=$viewerId; ")
                if (deviceId.isNotEmpty()) append("byse_device_id=$deviceId")
            }.trimEnd(';', ' ')

            val playbackUrl = "$playbackBase/api/videos/$id/embed/playback"
            val playbackText = app.get(
                playbackUrl,
                headers = mapOf(
                    "Referer" to embedFrame,
                    "Origin" to playbackBase,
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json,text/plain,*/*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-Embed-Origin" to "www.mundodonghua.com",
                    "X-Embed-Parent" to embedUrl,
                    "X-Embed-Referer" to referer,
                    "Cookie" to cookie
                )
            ).text

            println("MD_DEBUG[BYSEKOZE_PLAYBACK_URL] $playbackUrl")
            println("MD_DEBUG[BYSEKOZE_PLAYBACK_RESPONSE] ${playbackText.take(1200)}")

            val playback = Gson()
                .fromJson(playbackText, PlaybackResponse::class.java)
                .playback
                ?: return false

            val decrypted = decryptPlayback(playback) ?: return false

            println("MD_DEBUG[BYSEKOZE_DECRYPTED] ${decrypted.take(1200)}")

            val sources = Gson()
                .fromJson(decrypted, DecryptedPlayback::class.java)
                .sources
                ?: return false

            var ok = false

            sources.forEach { src ->
                src.url?.let { link ->
                    println("MD_DEBUG[BYSEKOZE_ENCRYPTED_LINK] $link")
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = link,
                            type = if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = embedFrame
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    ok = true
                }
            }

            ok
        } catch (e: Exception) {
            println("MD_DEBUG[BYSEKOZE_ENCRYPTED_ERROR] ${e.message}")
            false
        }
    }

    private suspend fun genericBysekoze(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(
                embedUrl,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                )
            ).text
                .replace("\\/", "/")
                .replace("&amp;", "&")

            println("MD_DEBUG[BYSEKOZE_GENERIC_HTML_LEN] ${html.length}")
            println("MD_DEBUG[BYSEKOZE_GENERIC_HTML_PREVIEW] ${html.take(900)}")

            val links = Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.value }
                .distinct()
                .toList()

            println("MD_DEBUG[BYSEKOZE_GENERIC_LINKS] $links")

            var ok = false

            links.forEach { link ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link,
                        type = if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                ok = true
            }

            ok
        } catch (e: Exception) {
            println("MD_DEBUG[BYSEKOZE_GENERIC_ERROR] ${e.message}")
            false
        }
    }

    private fun decryptPlayback(data: PlaybackData): String? {
        return try {
            val decoder = Base64.getUrlDecoder()

            val iv = decoder.decode(pad(data.iv))
            val payload = decoder.decode(pad(data.payload))
            val key = decoder.decode(pad(data.key_parts[0])) + decoder.decode(pad(data.key_parts[1]))

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, iv)
            )

            String(cipher.doFinal(payload))
        } catch (e: Exception) {
            println("MD_DEBUG[BYSEKOZE_DECRYPT_ERROR] ${e.message}")
            null
        }
    }

    private fun pad(s: String): String {
        var str = s
        while (str.length % 4 != 0) str += "="
        return str
    }

    data class DetailsResponse(val embed_frame_url: String?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class PlaybackData(val iv: String, val payload: String, val key_parts: List<String>)
    data class DecryptedPlayback(val sources: List<DecryptedSource>?)
    data class DecryptedSource(val url: String?)
}

open class MDNemonicPlayerExtractor : ExtractorApi() {
    override var name = "MDNemonicPlayer"
    override var mainUrl = "https://www.mdnemonicplayer.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        MundoHostResolver.resolve(url, referer ?: "https://www.mundodonghua.com/", subtitleCallback, callback)
    }
}

class MDPlayerExtractor : MDNemonicPlayerExtractor() {
    override var name = "MDPlayer"
    override var mainUrl = "https://www.mdplayer.xyz"
}
