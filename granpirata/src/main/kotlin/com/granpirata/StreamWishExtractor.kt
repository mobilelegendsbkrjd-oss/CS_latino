package com.granpirata

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class StreamWishExtractor {

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    suspend fun videosFromUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<ExtractorLink> {
        return try {
            // app.get sigue redirects HTTP normales automáticamente.
            // playme.top -> (302) -> host final (hglamioz.com, niramirus.com, etc.)
            val res = app.get(url, referer = referer)
            var doc = res.document
            var workingUrl = res.url // URL final tras redirects

            // Fallback: solo si aterrizamos en la página intermedia de streamwish.to
            // (redirect por JS, no por servidor)
            val scriptElement = doc.selectFirst("body > script[src*=/main.js]")
            if (scriptElement != null) {
                val host = workingUrl.toHttpUrlOrNull()?.host.orEmpty()
                val destination = if (host in RULES_SERVERS) {
                    MAIN_SERVERS.randomOrNull()
                } else {
                    DMCA_SERVERS.randomOrNull()
                } ?: return emptyList()

                val redirectedUrl = workingUrl.toHttpUrlOrNull()
                    ?.newBuilder()
                    ?.host(destination)
                    ?.build()
                    ?.toString() ?: return emptyList()

                val res2 = app.get(redirectedUrl, referer = referer)
                doc = res2.document
                workingUrl = res2.url
            }

            val rawScript = doc.selectFirst("script:containsData(m3u8)")?.data()
                ?: return emptyList()

            val scriptBody = if (rawScript.contains("eval(function(p,a,c")) {
                JsUnpacker(rawScript).unpack() ?: return emptyList()
            } else {
                rawScript
            }

            val masterUrl = M3U8_REGEX.find(scriptBody)?.value ?: return emptyList()

            extractSubtitles(scriptBody).forEach(subtitleCallback)

            listOf(
                newExtractorLink(
                    source = "StreamWish",
                    name = "StreamWish",
                    url = masterUrl
                ) {
                    quality = Qualities.Unknown.value
                    this.referer = workingUrl
                }
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractSubtitles(script: String): List<SubtitleFile> = try {
        val subtitleStr = script
            .substringAfter("tracks")
            .substringAfter("[")
            .substringBefore("]")
        val fixedSubtitleStr = FIX_TRACKS_REGEX.replace(subtitleStr) { match ->
            "\"${match.value}\""
        }

        json.decodeFromString<List<TrackDto>>("[$fixedSubtitleStr]")
            .filter { it.kind.equals("captions", true) }
            .map { SubtitleFile(it.label ?: "Unknown", it.file) }
    } catch (_: SerializationException) {
        emptyList()
    }

    @Serializable
    private data class TrackDto(val file: String, val kind: String, val label: String? = null)

    companion object {
        private val DMCA_SERVERS = arrayOf("playnixes.com", "niramirus.com", "medixiru.com", "hgplaycdn.com", "hglamioz.com")
        private val MAIN_SERVERS = arrayOf("kravaxxa.com", "davioad.com", "haxloppd.com", "tryzendm.com", "dumbalag.com")
        private val RULES_SERVERS = arrayOf("dhcplay.com", "hglink.to", "streamwish.to", "test.hglink.to")

        private val M3U8_REGEX by lazy { Regex("""https[^"]*m3u8[^"]*""") }
        private val FIX_TRACKS_REGEX by lazy { Regex("""(?<!")(file|kind|label)(?!")""") }
    }
}