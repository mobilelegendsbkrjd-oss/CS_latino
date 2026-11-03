package com.gnulahd

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException

private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/** Log único para todo el plugin. En logcat filtra por: GnulaHD */
internal fun gnulaLog(msg: String) {
    Log.d("GnulaHD", msg)
}

// ───────────────────────── Helpers ─────────────────────────

/** Ejecuta el bloque y devuelve null si falla (registra el error; no se traga la cancelación). */
private inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    gnulaLog("attempt ERROR: ${e.javaClass.simpleName}: ${e.message}")
    null
}

private fun hostOf(url: String): String? =
    attempt { URL(url).host.removePrefix("www.") }

private fun baseOf(url: String): String? =
    attempt { URL(url).let { "${it.protocol}://${it.host}" } }

private fun codeOf(url: String): String =
    url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')

private fun b64UrlDecode(s: String): ByteArray? {
    if (s.isBlank()) return null
    return try {
        val fixed = s.replace('-', '+').replace('_', '/')
        val padded = fixed + "=".repeat((4 - fixed.length % 4) % 4)
        Base64.decode(padded, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        gnulaLog("b64 decode ERROR: ${e.message}")
        null
    }
}

private fun aesGcmDecrypt(blob: ByteArray, key: ByteArray, iv: ByteArray): String? = try {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    String(cipher.doFinal(blob), Charsets.UTF_8).removePrefix("\uFEFF")
} catch (e: Exception) {
    // Con key incorrecta GCM lanza AEADBadTagException: es normal al probar candidatas
    gnulaLog("  gcm fail (keyLen=${key.size}): ${e.javaClass.simpleName}")
    null
}

// ───────────────────────── Vidara ─────────────────────────

/**
 * Vidara (vidara.to y sus mirrors). POST /api/stream -> streaming_url (HLS).
 */
open class GnulaVidara(override val mainUrl: String) : ExtractorApi() {
    override val name = "Vidara"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val base = baseOf(url) ?: mainUrl
        // Puede venir como https://vidara.to/CODE?24 -> el ?24 no es parte del código
        val code = url.substringBefore('#').substringAfterLast('/').substringBefore('?')
        gnulaLog("[Vidara] start url=$url base=$base code=$code")
        if (code.isBlank()) {
            gnulaLog("[Vidara] code vacío -> return")
            return
        }

        val text = attempt {
            app.post(
                "$base/api/stream",
                json = mapOf("filecode" to code, "device" to "web"),
                headers = mapOf(
                    "User-Agent" to UA,
                    "Origin" to base,
                    "Referer" to "$base/"
                )
            ).text
        }
        if (text == null) {
            gnulaLog("[Vidara] POST /api/stream falló (ver attempt ERROR arriba) -> return")
            return
        }
        gnulaLog("[Vidara] POST respuesta: ${text.take(300)}")

        val obj = attempt { JSONObject(text) }
        if (obj == null) {
            gnulaLog("[Vidara] respuesta no es JSON -> return")
            return
        }
        val stream = obj.optString("streaming_url")
        if (stream.isBlank()) {
            gnulaLog("[Vidara] sin streaming_url en la respuesta -> return")
            return
        }
        gnulaLog("[Vidara] streaming_url=$stream")

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = stream,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$base/"
                this.headers = mapOf(
                    "User-Agent" to UA,
                    "Origin" to base,
                    "Referer" to "$base/"
                )
            }
        )

        val subs = obj.optJSONArray("subtitles")
        gnulaLog("[Vidara] subtítulos=${subs?.length() ?: 0}")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val file = s.optString("file_path")
                if (file.isBlank()) continue
                subtitleCallback.invoke(newSubtitleFile(s.optString("language", "Sub"), file))
            }
        }
    }
}

// ───────────────────────── Byse / Filemoon ─────────────────────────

/**
 * Byse / Filemoon (nuevo player con playback cifrado en AES-GCM).
 * Prueba primero /api/videos/{code} y, si no trae "playback", el flujo embed/details -> embed/playback.
 * Para armar la key prueba: par según "version" (suma 31), luego las 2 primeras partes, luego todas.
 */
open class GnulaByse(
    override val name: String,
    override val mainUrl: String,
    private val apiHost: String? = null
) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val base = apiHost ?: baseOf(url) ?: mainUrl
        val code = codeOf(url)
        gnulaLog("[$name] start url=$url base=$base code=$code")
        if (code.isBlank()) {
            gnulaLog("[$name] code vacío -> return")
            return
        }

        val playback = fetchPlayback(base, code, url)
        if (playback == null) {
            gnulaLog("[$name] no se obtuvo playback (ni variante A ni B) -> return")
            return
        }
        gnulaLog("[$name] playback keys=${playback.keys().asSequence().toList()}")

        val config = decryptConfig(playback)
        if (config == null) {
            gnulaLog("[$name] no se pudo descifrar el playback -> return")
            return
        }
        gnulaLog("[$name] config descifrado OK, keys=${config.keys().asSequence().toList()}")

        emit(config, base, subtitleCallback, callback)
    }

    private suspend fun fetchPlayback(base: String, code: String, pageUrl: String): JSONObject? {
        val ua = mapOf("User-Agent" to UA)

        // Variante A: el registro del video ya trae el bloque playback
        val a = attempt {
            val txt = app.get("$base/api/videos/$code", referer = pageUrl, headers = ua).text
            gnulaLog("[$name] A: GET $base/api/videos/$code -> ${txt.take(200)}")
            JSONObject(txt).optJSONObject("playback")
        }
        if (a != null) {
            gnulaLog("[$name] A: playback encontrado")
            return a
        }
        gnulaLog("[$name] A: sin playback, probando variante B")

        // Variante B: details -> embed_frame_url -> embed/playback
        return attempt {
            val dTxt = app.get("$base/api/videos/$code/embed/details", headers = ua).text
            gnulaLog("[$name] B: details -> ${dTxt.take(200)}")
            val details = JSONObject(dTxt)
            val frame = details.getString("embed_frame_url")
            val frameBase = baseOf(frame) ?: base
            val frameCode = codeOf(frame)
            val h = ua + mapOf(
                "accept" to "*/*",
                "referer" to frame,
                "x-embed-parent" to pageUrl
            )
            val pTxt = app.get("$frameBase/api/videos/$frameCode/embed/playback", headers = h).text
            gnulaLog("[$name] B: playback -> ${pTxt.take(200)}")
            JSONObject(pTxt).optJSONObject("playback")
        }
    }

    private fun keyCandidates(parts: List<ByteArray>, version: Int): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val pair = listOf(version, 31 - version)
        if (pair.all { it in 1..parts.size }) {
            out += pair.map { parts[it - 1] }.reduce { a, b -> a + b }
        }
        if (parts.size >= 2) out += parts[0] + parts[1]
        out += parts.fold(ByteArray(0)) { acc, p -> acc + p }
        return out.filter { it.size in setOf(16, 24, 32) }.distinctBy { it.toList() }
    }

    private fun decryptConfig(playback: JSONObject): JSONObject? {
        val arr = playback.optJSONArray("key_parts")
        if (arr == null) {
            gnulaLog("[$name] playback sin key_parts")
            return null
        }
        val parts = (0 until arr.length())
            .mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            .mapNotNull { b64UrlDecode(it) }
        val version = playback.optInt("version", 0)
        val keys = keyCandidates(parts, version)
        gnulaLog("[$name] key_parts=${parts.size} sizes=${parts.map { it.size }} version=$version keysCandidatas=${keys.map { it.size }}")
        if (keys.isEmpty()) return null

        for ((ivKey, payloadKey) in listOf("iv" to "payload", "iv2" to "payload2")) {
            val iv = b64UrlDecode(playback.optString(ivKey))
            val blob = b64UrlDecode(playback.optString(payloadKey))
            if (iv == null || blob == null || blob.size <= 16) {
                gnulaLog("[$name] $ivKey/$payloadKey no disponible o inválido")
                continue
            }
            gnulaLog("[$name] probando $ivKey/$payloadKey ivLen=${iv.size} blobLen=${blob.size}")
            for ((idx, key) in keys.withIndex()) {
                val txt = aesGcmDecrypt(blob, key, iv)
                if (txt == null) continue
                val json = attempt { JSONObject(txt) }
                if (json == null) {
                    gnulaLog("[$name] key#$idx descifró pero no es JSON: ${txt.take(100)}")
                    continue
                }
                if (json.has("sources")) {
                    gnulaLog("[$name] OK con $ivKey/$payloadKey key#$idx")
                    return json
                }
                gnulaLog("[$name] key#$idx JSON sin 'sources': ${txt.take(150)}")
            }
        }
        return null
    }

    private suspend fun emit(
        config: JSONObject,
        base: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hdrs = mapOf("User-Agent" to UA, "Origin" to base, "Referer" to "$base/")

        // Subtítulos (si el config los trae; formato best-effort)
        val tracks = config.optJSONArray("tracks")
        gnulaLog("[$name] tracks=${tracks?.length() ?: 0}")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                if (t.optString("kind") == "thumbnails") continue
                val file = t.optString("file").ifBlank { t.optString("url") }
                if (file.isBlank()) continue
                subtitleCallback.invoke(newSubtitleFile(t.optString("label", "Sub"), file))
            }
        }

        val sources = config.optJSONArray("sources")
        if (sources == null) {
            gnulaLog("[$name] config sin 'sources' -> return")
            return
        }
        gnulaLog("[$name] sources=${sources.length()}")

        val seen = mutableSetOf<String>()
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            var stream = s.optString("url")
            if (stream.isBlank()) continue
            if (stream.startsWith("//")) stream = "https:$stream"
            if (!seen.add(stream)) continue

            val mime = s.optString("mime_type")
            val isHls = stream.contains(".m3u8") || mime.contains("mpegurl", ignoreCase = true)
            gnulaLog("[$name] source#$i hls=$isHls mime=$mime label=${s.optString("label")} url=$stream")

            if (isHls) {
                val links = M3u8Helper.generateM3u8(name, stream, "$base/", headers = hdrs)
                gnulaLog("[$name] generateM3u8 -> ${links.size} links")
                links.forEach(callback)
            } else {
                val height = s.optInt("height", 0)
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = stream,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$base/"
                        this.headers = hdrs
                        if (height > 0) this.quality = height
                    }
                )
            }
        }
    }
}

// ───────────────────────── Registro de dominios ─────────────────────────

private val vidaraHosts = listOf(
    "https://vidara.to",
    "https://vidara.so",
    "https://vidavaca.net",
    "https://vidaarax.net",
    "https://vidaarax.com",
    "https://vidaratem.com",
    "https://vidaraw.com",
    "https://vidarax.cc",
    "https://vidaraa.cc",
    "https://odysseusa.cc",
    "https://handfacesnap.cc",
    "https://namefacesnap.cc",
    "https://thebesthosterv.com",
    "https://vidmatrixa.com",
    "https://vidchampions.com",
    "https://antarcticadocs.com",
    "https://nameitweb.com"
)

private val byseHosts = listOf(
    "https://byse.sx",
    "https://byselapuix.com",
    "https://bysekoze.com",
    "https://bysesukior.com",
    "https://bysevepoin.com",
    "https://bysewihe.com",
    "https://bysezejataos.com",
    "https://bysesayeveum.com",
    "https://bysebuho.com",
    "https://byseqekaho.com",
    "https://gn1r5n.org"
)

private val filemoonHosts = listOf(
    "https://filemoon.sx",
    "https://filemoon.to"
)

/** Todos los extractores propios. Se registran en el Plugin y se usan directo en loadLinks. */
val gnulaExtractors: List<ExtractorApi> =
    vidaraHosts.map { GnulaVidara(it) } +
            byseHosts.map { GnulaByse("Byse", it) } +
            filemoonHosts.map { GnulaByse("Filemoon", it) } +
            GnulaByse("Filemoon", "https://filemoon.in", apiHost = "https://filemoon.sx")

/** Devuelve el extractor propio que corresponde al host de la URL, o null. */
fun findGnulaExtractor(url: String): ExtractorApi? {
    val host = hostOf(url) ?: return null
    return gnulaExtractors.firstOrNull { hostOf(it.mainUrl) == host }
}