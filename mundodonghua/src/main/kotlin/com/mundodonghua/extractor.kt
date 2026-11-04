package com.mundodonghua

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Extractores propios de MundoDonghua.
 * Son autocontenidos (solo usan APIs públicas de CloudStream), así que no dependen
 * de qué versión de la app tengas ni de que los extractores estén registrados.
 * Se llaman directo desde MundoDonghua.loadLinks().
 */

// ───────────────────────── Helpers ─────────────────────────

private val ROOT_REGEX = Regex("""^(https?://[^/?#]+)""", RegexOption.IGNORE_CASE)
private val M3U8_REGEX = Regex("""["']((?:https?:)?/[^"'\s]*?m3u8[^"'\s]*)["']""")
private val MP4_REGEX = Regex("""["']((?:https?:)?//[^"'\s]+?\.mp4[^"'\s]*)["']""")

internal fun mdRoot(url: String, fallback: String): String =
    ROOT_REGEX.find(url)?.groupValues?.get(1) ?: fallback

internal fun mdClean(text: String): String =
    text.replace("\\/", "/").replace("&amp;", "&").trim()

internal fun mdAbsolute(base: String, url: String): String {
    if (url.startsWith("http", true)) return url
    if (url.startsWith("//")) return "https:$url"
    return try {
        URI(base).resolve(url).toString()
    } catch (e: Exception) {
        url
    }
}

// Acepta base64 normal o url-safe, con o sin padding.
internal fun mdDecodeB64(text: String): ByteArray? {
    return try {
        var s = text.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        while (s.length % 4 != 0) s += "="
        Base64.decode(s, Base64.DEFAULT)
    } catch (e: Exception) {
        null
    }
}

internal fun mdAesGcm(blob: ByteArray, key: ByteArray, iv: ByteArray): String? {
    return try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(blob), Charsets.UTF_8)
    } catch (e: Exception) {
        println("MD_DEBUG[AES_ERR] ${e.message}")
        null
    }
}

internal suspend fun mdFetch(url: String, ref: String?): String? {
    return try {
        app.get(
            url,
            referer = ref,
            headers = mapOf("User-Agent" to USER_AGENT),
            timeout = 20
        ).text
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("MD_DEBUG[FETCH_ERR] $url -> ${e.message}")
        null
    }
}

internal fun mdQuality(label: String?): Int =
    Regex("""(\d{3,4})""").find(label ?: "")?.value?.toIntOrNull() ?: Qualities.Unknown.value

internal suspend fun mdEmitStream(
    name: String,
    url: String,
    ref: String,
    hdrs: Map<String, String>,
    label: String?,
    callback: (ExtractorLink) -> Unit
): Boolean {
    if (url.isBlank()) return false
    if (url.contains("m3u8", true)) {
        val links = generateM3u8(name, url, referer = ref, headers = hdrs)
        links.forEach(callback)
        return links.isNotEmpty()
    }
    val quality = mdQuality(label)
    callback(
        newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
            this.referer = ref
            this.quality = quality
            this.headers = hdrs
        }
    )
    return true
}

// Busca streams (m3u8 / mp4) en una página de player, desempacando el JS si hace falta.
internal suspend fun mdEmitFromPlayerPage(
    name: String,
    pageUrl: String,
    page: String,
    ref: String,
    hdrs: Map<String, String>,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val texts = mutableListOf(page.replace("\\/", "/"))
    if (!getPacked(page).isNullOrEmpty()) {
        val unpacked = try {
            getAndUnpack(page)
        } catch (e: Exception) {
            null
        }
        if (!unpacked.isNullOrBlank()) texts.add(unpacked.replace("\\/", "/"))
    }

    var produced = false
    val seen = mutableSetOf<String>()

    for (text in texts) {
        for (m in M3U8_REGEX.findAll(text)) {
            if (seen.size >= 3) break
            val stream = mdAbsolute(pageUrl, mdClean(m.groupValues[1]))
            if (!seen.add(stream)) continue
            produced = mdEmitStream(name, stream, ref, hdrs, null, callback) || produced
        }
    }

    if (!produced) {
        for (text in texts) {
            val mp4 = MP4_REGEX.find(text)?.groupValues?.get(1) ?: continue
            val stream = mdAbsolute(pageUrl, mdClean(mp4))
            produced = mdEmitStream(name, stream, ref, hdrs, null, callback) || produced
            if (produced) break
        }
    }
    return produced
}

// ───────────────────────── Byse / Filemoon (bysekoze.com, etc.) ─────────────────────────

open class MdByse : ExtractorApi() {
    override val name = "Byse"
    override val mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val base = mdRoot(url, mainUrl)
        val code = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        if (code.isEmpty()) return

        val body = try {
            app.get(
                "$base/api/videos/$code",
                referer = url,
                headers = mapOf("User-Agent" to USER_AGENT),
                timeout = 20
            ).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("MD_DEBUG[BYSE] fallo el record de $code: ${e.message}")
            return
        }

        val playback = try {
            JSONObject(body).optJSONObject("playback")
        } catch (e: Exception) {
            null
        }
        if (playback == null) {
            println("MD_DEBUG[BYSE] $code sin bloque playback")
            return
        }

        val partsArr = playback.optJSONArray("key_parts")
        val parts = (0 until (partsArr?.length() ?: 0))
            .mapNotNull { partsArr?.optString(it)?.takeIf { s -> s.isNotEmpty() } }
        val key = chosen(parts, playback.optInt("version", 0))
            .mapNotNull { mdDecodeB64(it) }
            .fold(ByteArray(0)) { acc, part -> acc + part }
        val iv = mdDecodeB64(playback.optString("iv", ""))
        val blob = mdDecodeB64(playback.optString("payload", ""))

        if (key.size !in KEY_SIZES || iv == null || blob == null || blob.size <= TAG_BYTES) {
            println("MD_DEBUG[BYSE] $code: el bloque playback no tiene la forma esperada")
            return
        }

        val config = mdAesGcm(blob, key, iv) ?: return
        emit(config, base, subtitleCallback, callback)
    }

    private suspend fun emit(
        config: String,
        base: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cfg = try {
            JSONObject(config)
        } catch (e: Exception) {
            println("MD_DEBUG[BYSE] config no es JSON")
            return
        }
        val playbackHeaders = mapOf("User-Agent" to USER_AGENT, "Origin" to base, "Referer" to "$base/")

        cfg.optJSONArray("tracks")?.let { tracks ->
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                if (t.optString("kind").equals("thumbnails", true)) continue
                val file = t.optString("file").ifBlank { t.optString("url") }
                if (file.isBlank()) continue
                val lang = t.optString("label").ifBlank { t.optString("language").ifBlank { "Unknown" } }
                try {
                    subtitleCallback(SubtitleFile(lang, mdAbsolute(base, mdClean(file))))
                } catch (e: Exception) {
                    println("MD_DEBUG[BYSE] subtitulo fallo: ${e.message}")
                }
            }
        }

        val sources = cfg.optJSONArray("sources")
        if (sources == null) {
            println("MD_DEBUG[BYSE] config sin sources")
            return
        }
        var produced = false
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val stream = s.optString("url")
            if (stream.isBlank()) continue
            val label = s.optString("label").ifBlank { s.optString("height") }
            produced = mdEmitStream(name, mdClean(stream), "$base/", playbackHeaders, label, callback) || produced
        }
        if (!produced) println("MD_DEBUG[BYSE] config leida pero sin streams")
    }

    private fun chosen(parts: List<String>, version: Int): List<String> {
        val pair = listOf(version, PAIR_SUM - version)
        if (pair.any { it < 1 || it > parts.size }) return parts
        return pair.map { parts[it - 1] }
    }

    private companion object {
        const val PAIR_SUM = 31
        const val TAG_BYTES = 16
        val KEY_SIZES = setOf(16, 24, 32)
    }
}

// ───────────────────────── Voe (voe.sx) ─────────────────────────

open class MdVoe : ExtractorApi() {
    override val name = "Voe"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer ?: mainUrl
        var current = url
        var page = mdFetch(current, pageReferer) ?: return

        val hop = REDIRECT.find(page)?.groupValues?.get(1)
        if (hop != null && hop != current) {
            current = hop
            page = mdFetch(current, pageReferer) ?: return
        }

        val headers = playbackHeaders(current)
        val config = configIn(page)
        if (config == null) {
            if (!mdEmitFromPlayerPage(name, current, page, current, headers, callback)) {
                println("MD_DEBUG[VOE] sin config ni streams en $url")
            }
            return
        }

        val tree = try {
            JSONObject(config)
        } catch (e: Exception) {
            null
        }

        tree?.optJSONArray("captions")?.let { caps ->
            for (i in 0 until caps.length()) {
                val track = caps.optJSONObject(i) ?: continue
                val file = track.optString("file").ifBlank { track.optString("url") }
                if (file.isBlank()) continue
                val label = track.optString("label").ifBlank { track.optString("language") }
                val lang = SubtitleHelper.fromTagToEnglishLanguageName(label) ?: label.ifBlank { "Unknown" }
                try {
                    subtitleCallback(SubtitleFile(lang, mdAbsolute(current, mdClean(file))))
                } catch (e: Exception) {
                    println("MD_DEBUG[VOE] subtitulo fallo: ${e.message}")
                }
            }
        }

        val hls = tree?.optString("source").orEmpty()
        val direct = tree?.optString("direct_access_url").orEmpty()
        var produced = false
        if (hls.isNotBlank()) produced = mdEmitStream(name, mdClean(hls), current, headers, null, callback)
        if (!produced && direct.isNotBlank()) produced = mdEmitStream(name, mdClean(direct), current, headers, null, callback)
        if (!produced) println("MD_DEBUG[VOE] config sin stream en $url")
    }

    private fun playbackHeaders(pageUrl: String): Map<String, String> =
        mapOf("User-Agent" to USER_AGENT, "Referer" to pageUrl, "Origin" to mdRoot(pageUrl, mainUrl))

    private fun configIn(page: String): String? {
        val packed = PAYLOAD.find(page)?.groupValues?.get(1)?.trim() ?: return null
        var text = packed
        for (junk in JUNK) text = text.replace(junk, "")
        text = rotate(text)
        text = text.filter { it in BASE64_ALPHABET }

        val once = mdDecodeB64(text)?.toString(Charsets.UTF_8) ?: return null
        val shifted = String(CharArray(once.length) { (once[it].code - SHIFT).toChar() })
        val decoded = mdDecodeB64(shifted.reversed())?.toString(Charsets.UTF_8) ?: return null
        return decoded.takeIf { it.trimStart().startsWith("{") }
    }

    private fun rotate(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when {
                    c in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                    c in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                    else -> c
                }
            )
        }
    }

    private companion object {
        const val SHIFT = 3
        val JUNK = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
        const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        val PAYLOAD = Regex(
            """<script[^>]+application/json[^>]*>\s*\[\s*"([^"]+)"\s*]\s*</script>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val REDIRECT = Regex("""window\.location\.href\s*=\s*['"](https?://[^'"]+)['"]""")
    }
}


// ───────────────────────── VidHidePro (vidhidepro.com, filelions, etc.) ─────────────────────────

open class MdVidHidePro : ExtractorApi() {
    override val name = "VidHidePro"
    override val mainUrl = "https://vidhidepro.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embed = embedUrl(url)
        val root = mdRoot(embed, mainUrl)
        val headers = mapOf(
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to root,
            "User-Agent" to USER_AGENT
        )
        val page = mdFetch(embed, referer ?: "$root/") ?: return
        if (!mdEmitFromPlayerPage(name, embed, page, "$root/", headers, callback)) {
            println("MD_DEBUG[VIDHIDE] sin streams en $embed")
        }
    }

    private fun embedUrl(url: String): String = when {
        url.contains("/d/") -> url.replace("/d/", "/v/")
        url.contains("/download/") -> url.replace("/download/", "/v/")
        url.contains("/file/") -> url.replace("/file/", "/v/")
        else -> url.replace("/f/", "/v/")
    }
}

// ───────────────────────── StreamWish (embedwish.com, hlswish, etc.) ─────────────────────────

open class MdStreamWish : ExtractorApi() {
    override val name = "StreamWish"
    override val mainUrl = "https://embedwish.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        for (candidate in candidates(url)) {
            val root = mdRoot(candidate, mainUrl)
            val pageReferer = if (candidate == url) referer ?: "$root/" else "$root/"
            val headers = mapOf("Origin" to root, "User-Agent" to USER_AGENT)
            val page = mdFetch(candidate, pageReferer) ?: continue

            if (mdEmitFromPlayerPage(name, candidate, page, "$root/", headers, callback)) return

            val embed = embedUrl(candidate, page, root) ?: continue
            val embedPage = mdFetch(embed, pageReferer) ?: continue
            if (mdEmitFromPlayerPage(name, embed, embedPage, "$root/", headers, callback)) return
        }
        println("MD_DEBUG[WISH] sin streams en $url")
    }

    private fun candidates(url: String): List<String> {
        val id = fileId(url) ?: return listOf(url)
        val here = mdRoot(url, mainUrl)
        return listOf(url) + READABLE.filter { it != here }.map { "$it/e/$id" }
    }

    private fun embedUrl(url: String, page: String, root: String): String? {
        IFRAME.find(page)?.groupValues?.get(1)?.let { return mdAbsolute(root, mdClean(it)) }
        val id = url.trimEnd('/').substringAfterLast('/')
        if (id.isBlank() || url.contains("/e/")) return null
        return "$root/e/$id"
    }

    private fun fileId(url: String): String? {
        val path = url.substringAfter("://", "").substringAfter('/', "")
            .substringBefore('?').substringBefore('#')
        val last = path.trimEnd('/').substringAfterLast('/').substringBefore('.')
        return last.takeIf { it.length >= 6 && it.all(Char::isLetterOrDigit) }
    }

    private companion object {
        val IFRAME = Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val READABLE = listOf("https://hlswish.com", "https://flaswish.com", "https://embedwish.com")
    }
}