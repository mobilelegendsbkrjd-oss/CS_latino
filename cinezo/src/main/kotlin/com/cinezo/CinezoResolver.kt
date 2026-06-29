package com.cinezo

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CinezoResolver {
    data class SubtitleTrack(val name: String, val url: String)

    data class StreamResult(
        val url: String,
        val headers: Map<String, String>,
        val subtitles: List<SubtitleTrack>
    )

    private data class Server(
        val name: String,
        val movie: String,
        val tv: String
    )

    private val servers = listOf(
        Server("4k", "https://leavemealone.tulnex.com/4k/movie/{id}", "https://leavemealone.tulnex.com/4k/tv/{id}/{season}/{episode}"),
        Server("Cline", "https://leavemealone.tulnex.com/cline/movie/{id}", "https://leavemealone.tulnex.com/cline/tv/{id}/{season}/{episode}"),
        Server("Fabric", "https://leavemealone.tulnex.com/fabric/movie/{id}", "https://leavemealone.tulnex.com/fabric/tv/{id}/{season}/{episode}"),
        Server("Flax", "https://leavemealone.tulnex.com/flax/movie/{id}", "https://leavemealone.tulnex.com/flax/tv/{id}/{season}/{episode}"),
        Server("Flix", "https://leavemealone.tulnex.com/flix/movie/{id}", "https://leavemealone.tulnex.com/flix/tv/{id}/{season}/{episode}"),
        Server("Fucklink", "https://leavemealone.tulnex.com/Fucklink/movie/{id}", "https://leavemealone.tulnex.com/Fucklink/tv/{id}/{season}/{episode}"),
        Server("Hi", "https://leavemealone.tulnex.com/hi/movie/{id}", "https://leavemealone.tulnex.com/hi/tv/{id}/{season}/{episode}"),
        Server("Kilo", "https://leavemealone.tulnex.com/kilo/movie/{id}", "https://leavemealone.tulnex.com/kilo/tv/{id}/{season}/{episode}"),
        Server("Lux", "https://leavemealone.tulnex.com/lux/movie/{id}", ""),
        Server("Max", "https://leavemealone.tulnex.com/max/movie/{id}", "https://leavemealone.tulnex.com/max/tv/{id}/{season}/{episode}"),
        Server("Mom", "https://leavemealone.tulnex.com/mom/movie/{id}", "https://leavemealone.tulnex.com/mom/tv/{id}/{season}/{episode}"),
        Server("Netamp4", "https://leavemealone.tulnex.com/netamp4/movie/{id}", "https://leavemealone.tulnex.com/netamp4/tv/{id}/{season}/{episode}"),
        Server("NgFlix", "https://leavemealone.tulnex.com/Ngflix/movie/{id}", "https://leavemealone.tulnex.com/Ngflix/tv/{id}/{season}/{episode}"),
        Server("Orion", "https://leavemealone.tulnex.com/orion/movie/{id}", "https://leavemealone.tulnex.com/orion/tv/{id}/{season}/{episode}"),
        Server("Rido", "https://leavemealone.tulnex.com/rido/movie/{id}", "https://leavemealone.tulnex.com/rido/tv/{id}/{season}/{episode}"),
        Server("Tulnex1", "https://leavemealone.tulnex.com/tulnex1/movie/{id}", "https://leavemealone.tulnex.com/tulnex1/tv/{id}/{season}/{episode}"),
        Server("Youtube", "https://leavemealone.tulnex.com/youtube/movie/{id}", "https://leavemealone.tulnex.com/youtube/tv/{id}/{season}/{episode}"),
        Server("Zebra", "https://leavemealone.tulnex.com/zebra/movie/{id}", "https://leavemealone.tulnex.com/zebra/tv/{id}/{season}/{episode}")
    )

    suspend fun getStream(
        tmdbId: Int,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null
    ): StreamResult? {
        val direct111 = if (mediaType == "movie") {
            resolve111Movies("https://111movies.net/movie/$tmdbId?autoplay=1")
        } else {
            resolve111Movies("https://111movies.net/tv/$tmdbId/${season ?: 1}/${episode ?: 1}?autoplay=1&autoNext=1")
        }

        if (direct111 != null && direct111.url.startsWith("http")) {
            println("CINEZO 111MOVIES OK => ${direct111.url}")
            return direct111
        } else {
            println("CINEZO 111MOVIES FAIL => fallback Tulnex")
        }

        val apiHeaders = mapOf(
            "user-agent" to USER_AGENT,
            "referer" to "https://player.cinezo.live/embed/"
        )

        for (server in servers.shuffled()) {
            val apiUrl = if (mediaType == "movie") {
                server.movie.replace("{id}", tmdbId.toString())
            } else {
                if (server.tv.isBlank()) continue
                server.tv
                    .replace("{id}", tmdbId.toString())
                    .replace("{season}", (season ?: 1).toString())
                    .replace("{episode}", (episode ?: 1).toString())
            }

            try {
                val response = app.get(apiUrl, headers = apiHeaders).text
                println("CINEZO SERVER URL => $apiUrl")
                println("CINEZO SERVER RESPONSE => $response")

                val json = JSONObject(response)
                val parsed = parseResponse(json) ?: continue
                var final = parsed

                if (final.url.contains("111movies.", true)) {
                    final = resolve111Movies(final.url) ?: final
                }

                if (final.url.startsWith("http")) {
                    println("CINEZO OK => ${server.name} => ${final.url}")
                    return final
                }
            } catch (e: Exception) {
                println("CINEZO FAIL => ${server.name} => ${e.message}")
            }
        }

        return null
    }

    private fun parseResponse(json: JSONObject): StreamResult? {
        if (json.optBoolean("success") == false && json.has("error")) return null

        if (json.has("sources") && !json.has("payload")) {
            return parseSources(json)
        }

        if (json.has("data") && !json.has("payload")) {
            return parseNestedData(json)
        }

        val payload = json.optString("payload")
        if (payload.isBlank()) return null

        val decoded = decodePayload(payload)
        return parseStreamResult(decoded)
    }

    private fun parseSources(json: JSONObject): StreamResult? {
        val sources = json.optJSONArray("sources") ?: return null
        if (sources.length() == 0) return null

        val first = sources.opt(0)
        val url: String
        val headers: Map<String, String>

        if (first is JSONObject) {
            url = first.optString("url")
                .ifBlank { first.optString("file") }
                .ifBlank { first.optString("stream") }

            headers = first.optJSONObject("headers")?.toMapString() ?: emptyMap()
        } else {
            url = first.toString()
            headers = emptyMap()
        }

        if (url.isBlank()) return null

        val unwrapped = unwrapProxy(url, headers)
        return StreamResult(
            unwrapped.first,
            unwrapped.second,
            subsToTracks(json.optJSONArray("subtitles"))
        )
    }

    private fun parseNestedData(json: JSONObject): StreamResult? {
        var inner = json.opt("data")
        if (inner is JSONObject && inner.has("data")) inner = inner.opt("data")
        if (inner !is JSONObject) return null

        val stream = inner.optJSONObject("stream") ?: return null
        val playlist = stream.optString("playlist")
            .ifBlank { stream.optString("url") }
            .ifBlank { stream.optString("file") }

        if (playlist.isBlank()) return null

        val unwrapped = unwrapProxy(playlist, emptyMap())
        return StreamResult(
            unwrapped.first,
            unwrapped.second,
            subsToTracks(stream.optJSONArray("captions"))
        )
    }

    private fun parseStreamResult(raw: String): StreamResult? {
        val cleanRaw = raw.trim().trim('"')
        val obj = runCatching { JSONObject(cleanRaw) }.getOrNull()

        if (obj == null) {
            val unwrapped = unwrapProxy(cleanRaw, emptyMap())
            return StreamResult(unwrapped.first, unwrapped.second, emptyList())
        }

        if (obj.has("streams")) {
            val streams = obj.optJSONArray("streams") ?: return null
            if (streams.length() == 0) return null

            val first = streams.opt(0)
            val url: String
            val headers: Map<String, String>

            if (first is JSONObject) {
                url = first.optString("url")
                    .ifBlank { first.optString("stream") }
                    .ifBlank { first.optString("file") }

                headers = first.optJSONObject("headers")?.toMapString() ?: emptyMap()
            } else {
                url = first.toString()
                headers = emptyMap()
            }

            if (url.isBlank()) return null

            val unwrapped = unwrapProxy(url, headers)
            return StreamResult(
                unwrapped.first,
                unwrapped.second,
                subsToTracks(obj.optJSONArray("subtitles"))
            )
        }

        if (obj.has("sources")) {
            return parseSources(obj)
        }

        val url = obj.optString("url")
            .ifBlank { obj.optString("stream") }
            .ifBlank { obj.optString("file") }

        if (url.isBlank()) return null

        val headers = obj.optJSONObject("headers")?.toMapString() ?: emptyMap()
        val unwrapped = unwrapProxy(url, headers)

        return StreamResult(
            unwrapped.first,
            unwrapped.second,
            subsToTracks(obj.optJSONArray("subtitles"))
        )
    }

    suspend fun resolve111Movies(inputUrl: String): StreamResult? {
        val uri = URI(inputUrl)
        val base = "${uri.scheme}://${uri.host}/"

        val headers = mapOf(
            "Referer" to base,
            "User-Agent" to USER_AGENT,
            "Content-Type" to "application/x-shockwave-flash",
            "X-Csrf-Token" to "lOky1FfH4K8k7nlP1rymCoe3q2smDW8T"
        )

        try {
            val page = app.get(inputUrl, headers = headers).text
            println("111MOVIES PAGE URL => $inputUrl")

            val rawData = extractNextData(page)
                ?: extractLegacyData(page)
                ?: return null

            println("111MOVIES RAW DATA => $rawData")

            val key = hexToBytes("55eb57c5e52d3ae19f899e702cb539084adf606b06cc44382c21e48a82215d8a")
            val iv = hexToBytes("324d1fae84bafaba643f236ee116de27")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

            val encryptedHex = cipher
                .doFinal(rawData.toByteArray(Charsets.UTF_8))
                .toHex()

            val xorKey = hexToBytes("dd69ce")

            val xorResult = buildString {
                encryptedHex.forEachIndexed { index, char ->
                    append((char.code xor (xorKey[index % xorKey.size].toInt() and 0xff)).toChar())
                }
            }

            val encodedFinal = customEncode(xorResult)

            val staticPath =
                "9816ad6837c78fcc2e0944fe2e6398b8a525d43f1afea28f9d5347b35cd53128/" +
                        "c1a5e2db/" +
                        "APA91iMgb2ifswAU727_OpyUBk45sDi2ciUVYVGZXVUXlYUrIshxfIIWC7WwfK3Rug52O7fWefpKiXKVeVPB-I4gl5GeF6Wj-MeAmJpzWiKkZMhg5kDvEv0fRguit6YtNIAHOpF47joyVLBgqzKlw98WhN6eQiF_QvG8Mmq3j2tpbtfSw0oAU-o/" +
                        "2db11c71f014bd4128f1a3ec314796da7e09b87e/" +
                        "tor/c6779436-9455-57ed-8527-73ad249a83db"

            val srUrl = "$base$staticPath/$encodedFinal/sr"
            val srText = app.post(srUrl, headers = headers).text
            println("111MOVIES SR URL => $srUrl")
            println("111MOVIES SR RESPONSE => $srText")

            val arr = JSONArray(srText)
            if (arr.length() == 0) return null

            val server = arr.optJSONObject(0)?.optString("data") ?: return null
            if (server.isBlank()) return null

            val streamUrl = "$base$staticPath/$server"
            val streamText = app.post(streamUrl, headers = headers).text
            println("111MOVIES STREAM URL => $streamUrl")
            println("111MOVIES STREAM RESPONSE => $streamText")

            val streamJson = JSONObject(streamText)
            val videoUrl = streamJson.optString("url")
                .ifBlank { streamJson.optString("file") }
                .ifBlank { streamJson.optString("stream") }

            if (videoUrl.isBlank()) return null

            return StreamResult(
                videoUrl,
                mapOf(
                    "Referer" to base,
                    "User-Agent" to USER_AGENT
                ),
                emptyList()
            )
        } catch (e: Exception) {
            println("111MOVIES FAIL => ${e.message}")
            return null
        }
    }

    private fun extractNextData(page: String): String? {
        val nextData = Regex(
            """<script id=["']__NEXT_DATA__["'] type=["']application/json["']>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(page)?.groupValues?.getOrNull(1) ?: return null

        return runCatching {
            JSONObject(nextData)
                .optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optString("data")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun extractLegacyData(page: String): String? {
        return Regex("""\{\\"data\\":\\"(.*?)\\"""")
            .find(page)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""\{"data":"(.*?)"""")
                .find(page)
                ?.groupValues
                ?.getOrNull(1)
    }

    private fun decodePayload(payload: String): String {
        val sep = payload.indexOf("|")
        val dataB64 = if (sep >= 0) payload.substring(sep + 1) else payload
        val l3 = b64(dataB64).toString(Charsets.UTF_8)

        val parts = l3.split(".")
        if (parts.size < 3) return l3

        val iv = b64(parts[0])
        val salt = b64(parts[1])
        val cipherText = b64(parts[2])

        val aesKey = pbkdf2(
            "Sn00pD0g#L3_AES_S3cur3K3y@2026\$sex",
            salt,
            100000,
            32,
            "PBKDF2WithHmacSHA512"
        )

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))

        val intermediate = cipher.doFinal(cipherText).toString(Charsets.UTF_8)
        val binary = b64(intermediate).toString(Charsets.UTF_8)

        val hex = binary.split(" ")
            .filter { it.isNotBlank() }
            .joinToString("") { Integer.parseInt(it, 2).toChar().toString() }

        val xorKey = pbkdf2(
            "Sn00pD0g#L1_X0R_M4st3rK3y!2026sexx",
            "xK9!mR2@pL5#nQ8sex".toByteArray(),
            50000,
            32,
            "PBKDF2WithHmacSHA256"
        )

        val raw = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val finalBytes = ByteArray(raw.size) { i ->
            (raw[i].toInt() xor xorKey[i % xorKey.size].toInt()).toByte()
        }

        return finalBytes.toString(Charsets.UTF_8)
    }

    private fun customEncode(input: String): String {
        val src = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        val dst = "zF-NXZYgxKqj7nbuGoI_SDfkQ9y3VcJrRBip6tadPwv0MWLehT5Um4As2l8C1HEO"

        val b64 = Base64.getEncoder()
            .encodeToString(input.toByteArray(Charsets.UTF_8))
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")

        return buildString {
            for (c in b64) {
                val idx = src.indexOf(c)
                append(if (idx >= 0) dst[idx] else c)
            }
        }
    }

    private fun pbkdf2(
        password: String,
        salt: ByteArray,
        iterations: Int,
        length: Int,
        algo: String
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, length * 8)
        return SecretKeyFactory.getInstance(algo).generateSecret(spec).encoded
    }

    private fun b64(value: String): ByteArray {
        var v = value.trim()
        val pad = 4 - v.length % 4
        if (pad < 4) v += "=".repeat(pad)
        return Base64.getDecoder().decode(v)
    }

    private fun unwrapProxy(
        url: String,
        headers: Map<String, String>
    ): Pair<String, Map<String, String>> {
        return try {
            val uri = URI(url)
            val query = uri.rawQuery ?: return url to headers

            val params = query.split("&").associate {
                val p = it.split("=", limit = 2)
                p[0] to if (p.size > 1) URLDecoder.decode(p[1], "UTF-8") else ""
            }

            val real = params["url"] ?: return url to headers

            val newHeaders = params["headers"]?.let {
                runCatching { JSONObject(it).toMapString() }.getOrNull()
            } ?: headers

            real to newHeaders
        } catch (_: Exception) {
            url to headers
        }
    }

    private fun subsToTracks(arr: JSONArray?): List<SubtitleTrack> {
        if (arr == null) return emptyList()

        val out = mutableListOf<SubtitleTrack>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val url = obj.optString("url")
                .ifBlank { obj.optString("file") }

            if (url.isBlank()) continue

            out.add(
                SubtitleTrack(
                    obj.optString("display")
                        .ifBlank { obj.optString("name") }
                        .ifBlank { obj.optString("language") }
                        .ifBlank { "Subtitle" },
                    url
                )
            )
        }

        return out
    }

    private fun JSONObject.toMapString(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        keys().forEach { key ->
            val value = optString(key)
            if (value.isNotBlank()) map[key] = value
        }
        return map
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
