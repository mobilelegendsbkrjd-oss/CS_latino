package com.tlnovelas

import android.graphics.BitmapFactory
import android.util.Base64 as AndroidBase64
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.max

object UniversalResolver {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    private val jsonType = "application/json; charset=utf-8".toMediaTypeOrNull()

    suspend fun resolve(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            when {
                url.contains("hqq.to", true) ||
                        url.contains("waaw.to", true) ||
                        url.contains("netu.tv", true) -> {
                    extractHqq(url, referer, callback) ||
                            loadExtractor(url, referer, subtitleCallback, callback)
                }

                url.contains("bysejikuar", true) ||
                        url.contains("f75s", true) -> {
                    loadExtractor(url, referer, subtitleCallback, callback) ||
                            extractBysejikuar(url, referer, callback)
                }

                url.contains("dooodster", true) ||
                        url.contains("dood.", true) ||
                        url.contains("doodstream", true) -> {
                    loadExtractor(url, referer, subtitleCallback, callback)
                }

                else -> {
                    loadExtractor(url, referer, subtitleCallback, callback) ||
                            tryResolveGeneric(url, referer, callback)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getBase(url: String): String {
        val protocol = url.substringBefore("://")
        val host = url.substringAfter("://").substringBefore("/")
        return "$protocol://$host"
    }

    private fun extractJsVar(html: String, name: String, default: String = ""): String {
        return Regex("""(?:var\s+)?${Regex.escape(name)}\s*=\s*['"]([^'"]*)['"]""")
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: default
    }

    private fun extractLiteral(html: String, pattern: String, default: String = ""): String {
        return Regex(pattern, RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: default
    }

    private fun randomSha1(): String {
        val chars = "0123456789abcdef"
        return (1..40).map { chars.random() }.joinToString("")
    }

    private fun decodeObfLink(value: String?): String {
        if (value.isNullOrBlank() || value == "#") return ""
        if (value.contains(".")) return value

        val clean = value.drop(1)
        val out = StringBuilder()

        var i = 0
        while (i + 3 <= clean.length) {
            val chunk = clean.substring(i, i + 3)
            val code = chunk.toIntOrNull(16) ?: return ""
            out.append(code.toChar())
            i += 3
        }

        return out.toString()
    }

    private fun normalizeStreamUrl(obf: String): String {
        var url = obf.replace("\\/", "/").trim()

        if (url.startsWith("//")) url = "https:$url"
        else if (!url.startsWith("http")) url = "https:$url"

        if (!url.contains(".mp4.m3u8")) {
            url += ".mp4.m3u8"
        }

        return url
    }

    private fun encodeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "")
            .replace("\r", "")
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun findPlayButtonPoint(dataImage: String): Pair<Int, Int>? {
        return try {
            val clean = dataImage
                .substringAfter("base64,", dataImage)
                .replace("\\/", "/")

            val bytes = AndroidBase64.decode(clean, AndroidBase64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            val w = bmp.width
            val h = bmp.height

            var bestX = w / 2
            var bestY = h / 2
            var bestScore = -1

            val startX = w / 5
            val endX = w * 4 / 5
            val startY = h / 5
            val endY = h * 4 / 5

            for (y in startY until endY step 2) {
                for (x in startX until endX step 2) {
                    val p = bmp.getPixel(x, y)

                    val r = (p shr 16) and 255
                    val g = (p shr 8) and 255
                    val b = p and 255

                    val lum = (r + g + b) / 3
                    val centerBonus = 300 - (abs(x - w / 2) + abs(y - h / 2))
                    val score = lum + max(0, centerBonus)

                    if (lum > 135 && score > bestScore) {
                        bestScore = score
                        bestX = x
                        bestY = y
                    }
                }
            }

            bestX to bestY
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun extractHqq(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val realEmbed = if (embedUrl.contains("/f/")) {
                embedUrl.replace("/f/", "/e/")
            } else {
                embedUrl
            }

            val base = getBase(realEmbed)

            val pageRes = app.get(
                realEmbed,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to referer,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )

            val html = pageRes.text
            val cookies = pageRes.cookies

            val mediaId = realEmbed.substringAfterLast("/").substringBefore("?")
            val videoKey = extractLiteral(html, """['"]videokey['"]\s*:\s*['"]([^'"]+)['"]""", mediaId)
                .ifBlank { extractJsVar(html, "videokey", mediaId) }
                .ifBlank { extractJsVar(html, "videokeyorig", mediaId) }

            val videoId = extractLiteral(html, """['"]videoid['"]\s*:\s*['"]([^'"]+)['"]""")
                .ifBlank { extractJsVar(html, "videoid") }

            val adbn = extractJsVar(html, "adbn")
            val secure = extractJsVar(html, "secure", "0")
            val htoken = extractJsVar(html, "htoken")
            val gtr = extractJsVar(html, "gtr")
            val embedFrm = extractJsVar(html, "embedfrm", "0")

            if (videoKey.isBlank() || videoId.isBlank() || adbn.isBlank()) {
                return false
            }

            val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

            val ajaxHeaders = mapOf(
                "User-Agent" to UA,
                "Referer" to realEmbed,
                "Origin" to base,
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/json",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Cookie" to cookieHeader
            )

            val imageBody =
                """{"videoid":"${encodeJson(videoId)}","videokey":"${encodeJson(videoKey)}","width":407,"height":400}"""

            val imageText = app.post(
                "$base/player/get_player_image.php",
                referer = realEmbed,
                headers = ajaxHeaders,
                requestBody = imageBody.toRequestBody(jsonType)
            ).text

            val imageJson = JsonParser.parseString(imageText).asJsonObject

            if (imageJson.has("try_again") && imageJson["try_again"].asString == "1") {
                return false
            }

            val imageBase64 = imageJson.get("image")?.asString
            val hashImage = imageJson.get("hash_image")?.asString ?: return false
            val encodedHash = urlEncode(hashImage)

            val autoPoint = imageBase64?.let { findPlayButtonPoint(it) }

            val coords = linkedSetOf<Pair<Int, Int>>()

            if (autoPoint != null) {
                coords.add(autoPoint)
                coords.add(autoPoint.first + 4 to autoPoint.second)
                coords.add(autoPoint.first - 4 to autoPoint.second)
                coords.add(autoPoint.first to autoPoint.second + 4)
                coords.add(autoPoint.first to autoPoint.second - 4)
            }

            coords.add(203 to 200)
            coords.add(203 to 220)
            coords.add(203 to 180)
            coords.add(190 to 200)
            coords.add(215 to 200)
            coords.add(478 to 114)
            coords.add(841 to 179)
            coords.add(169 to 332)

            for ((clickX, clickY) in coords) {
                val md5Body = """
                    {
                      "htoken":"${encodeJson(htoken)}",
                      "sh":"${randomSha1()}",
                      "ver":"4",
                      "secure":"${encodeJson(secure)}",
                      "adb":"${encodeJson(adbn)}",
                      "v":"${encodeJson(videoKey)}",
                      "token":"",
                      "gt":"${encodeJson(gtr)}",
                      "embed_from":"${encodeJson(embedFrm)}",
                      "wasmcheck":1,
                      "adscore":"",
                      "click_hash":"${encodeJson(encodedHash)}",
                      "clickx":$clickX,
                      "clicky":$clickY
                    }
                """.trimIndent()

                val md5Text = app.post(
                    "$base/player/get_md5.php",
                    referer = realEmbed,
                    headers = ajaxHeaders,
                    requestBody = md5Body.toRequestBody(jsonType)
                ).text

                val md5Json = JsonParser.parseString(md5Text).asJsonObject

                if (
                    (md5Json.has("try_again") && md5Json["try_again"].asString == "1") ||
                    (md5Json.has("need_captcha") && md5Json["need_captcha"].asString == "1") ||
                    (md5Json.has("wrong_recaptcha") && md5Json["wrong_recaptcha"].asString == "1")
                ) {
                    continue
                }

                val rawUrl = md5Json.get("html5_file")?.asString
                    ?: md5Json.get("link")?.asString

                val streamUrl = if (!rawUrl.isNullOrBlank()) {
                    normalizeStreamUrl(rawUrl)
                } else {
                    val obf = md5Json.get("obf_link")?.asString ?: continue
                    val decoded = decodeObfLink(obf)
                    if (decoded.isBlank()) continue
                    normalizeStreamUrl(decoded)
                }

                callback.invoke(
                    newExtractorLink("HQQ", "HQQ", streamUrl) {
                        this.referer = base
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.M3U8
                    }
                )

                return true
            }

            false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun tryResolveGeneric(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val text = app.get(
                url,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Accept" to "*/*",
                    "Referer" to referer
                )
            ).text

            var searchText = text

            if (text.contains("eval(function(p,a,c,k,e")) {
                val unpacker = JsUnpacker(text)
                if (unpacker.detect()) {
                    unpacker.unpack()?.let { searchText = it }
                }
            }

            val found = linkedSetOf<String>()

            Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                .findAll(searchText)
                .forEach { found.add(it.groupValues[1].replace("\\/", "/")) }

            Regex("""file\s*:\s*["']([^"']+)""")
                .findAll(searchText)
                .forEach { found.add(it.groupValues[1].replace("\\/", "/")) }

            Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)""")
                .findAll(searchText)
                .forEach { found.add(it.groupValues[1].replace("\\/", "/")) }

            var success = false

            found.forEach { videoUrl ->
                if (videoUrl.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink("Generic", "Generic", videoUrl) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                            this.type =
                                if (videoUrl.contains(".m3u8"))
                                    ExtractorLinkType.M3U8
                                else
                                    ExtractorLinkType.VIDEO
                        }
                    )
                    success = true
                }
            }

            success
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractBysejikuar(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val id = Regex("""/(?:e|d)/([A-Za-z0-9]+)""")
                .find(embedUrl)
                ?.groupValues
                ?.get(1)
                ?: return false

            val base = getBase(embedUrl)

            val embedRes = app.get(
                embedUrl,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to referer,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )

            val cookies = embedRes.cookies
            val viewerId = cookies["byse_viewer_id"] ?: ""
            val deviceId = cookies["byse_device_id"] ?: ""

            val detailsText = app.get(
                "$base/api/videos/$id/embed/details",
                headers = mapOf(
                    "Referer" to embedUrl,
                    "Origin" to base,
                    "User-Agent" to UA,
                    "Accept" to "application/json,text/plain,*/*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-Embed-Origin" to "ww2.tlnovelas.net",
                    "X-Embed-Referer" to referer
                )
            ).text

            val details = Gson().fromJson(detailsText, DetailsResponse::class.java)
            val embedFrame = details.embed_frame_url ?: embedUrl

            try {
                app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to UA))
                app.get(embedFrame, referer = embedUrl, headers = mapOf("User-Agent" to UA))
            } catch (_: Exception) {}

            val playbackBase =
                if (embedFrame.contains("f75s.com")) "https://f75s.com"
                else base

            val cookie = buildString {
                if (viewerId.isNotEmpty()) append("byse_viewer_id=$viewerId; ")
                if (deviceId.isNotEmpty()) append("byse_device_id=$deviceId")
            }.trimEnd(';', ' ')

            val playbackText = app.get(
                "$playbackBase/api/videos/$id/embed/playback",
                headers = mapOf(
                    "Referer" to embedFrame,
                    "Origin" to playbackBase,
                    "User-Agent" to UA,
                    "Accept" to "application/json,text/plain,*/*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-Embed-Origin" to "ww2.tlnovelas.net",
                    "X-Embed-Parent" to embedUrl,
                    "X-Embed-Referer" to referer,
                    "Cookie" to cookie
                )
            ).text

            val playback = Gson()
                .fromJson(playbackText, PlaybackResponse::class.java)
                .playback
                ?: return false

            val decrypted = decryptPlayback(playback) ?: return false

            val sources = Gson()
                .fromJson(decrypted, DecryptedPlayback::class.java)
                .sources
                ?: return false

            var success = false

            sources.forEach { src ->
                src.url?.let { videoUrl ->
                    callback.invoke(
                        newExtractorLink("Bysejikuar", "Bysejikuar", videoUrl) {
                            this.referer = embedFrame
                            this.quality = Qualities.Unknown.value
                            this.type =
                                if (videoUrl.contains(".m3u8"))
                                    ExtractorLinkType.M3U8
                                else
                                    ExtractorLinkType.VIDEO
                        }
                    )
                    success = true
                }
            }

            success
        } catch (_: Exception) {
            false
        }
    }

    private fun decryptPlayback(data: PlaybackData): String? {
        return try {
            val decoder = Base64.getUrlDecoder()

            val iv = decoder.decode(pad(data.iv))
            val payload = decoder.decode(pad(data.payload))

            val key =
                decoder.decode(pad(data.key_parts[0])) +
                        decoder.decode(pad(data.key_parts[1]))

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, iv)
            )

            String(cipher.doFinal(payload))
        } catch (_: Exception) {
            null
        }
    }

    private fun pad(s: String): String {
        var str = s
        while (str.length % 4 != 0) str += "="
        return str
    }

    data class DetailsResponse(
        val embed_frame_url: String?
    )

    data class PlaybackResponse(
        val playback: PlaybackData?
    )

    data class PlaybackData(
        val iv: String,
        val payload: String,
        val key_parts: List<String>
    )

    data class DecryptedPlayback(
        val sources: List<DecryptedSource>?
    )

    data class DecryptedSource(
        val url: String?
    )
}