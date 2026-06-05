package com.latanime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlin.random.Random

// =========================
// ZILLA
// =========================
open class Zilla : ExtractorApi() {

    override var name = "HLS"

    override var mainUrl =
        "https://player.zilla-networks.com"

    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {

        val id =
            url.substringAfterLast("/")

        val m3u8 =
            "$mainUrl/m3u8/$id"

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer =
                    referer ?: mainUrl

                this.quality =
                    Qualities.P1080.value
            }
        )
    }
}

// =========================
// VIDSTACK
// =========================
class Animeav1upn : VidStack() {
    override var mainUrl =
        "https://animeav1.uns.bio"
}

class CoflixUPN : VidStack() {
    override var mainUrl =
        "https://coflix.upn.one"
}

// =========================
// STREAMWISH
// =========================
class WishOnly : StreamWishExtractor() {
    override var mainUrl =
        "https://wishonly.site"
}

// =========================
// VIDHIDE
// =========================
class VidHidePlus : VidhideExtractor() {
    override var mainUrl =
        "https://vidhideplus.com"
}

class Mivalyo : VidhideExtractor() {
    override var mainUrl =
        "https://mivalyo.com"
}

// =========================
// FILEMOON
// =========================
class FileMoonSx : Filesim() {

    override val mainUrl =
        "https://filemoon.sx"

    override val name =
        "FileMoon"
}

// =========================
// UQLOAD
// =========================
class Uqload : ExtractorApi() {

    override val name = "Uqload"

    override val mainUrl =
        "https://uqload.cx"

    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val html = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0"
            )
        ).text

        val videoUrl = Regex(
            """sources\s*:\s*\[\s*["']([^"']+)"""
        ).find(html)
            ?.groupValues
            ?.getOrNull(1)

        if (videoUrl != null) {

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = INFER_TYPE
                ) {
                    this.referer =
                        referer ?: mainUrl

                    this.quality =
                        Qualities.P1080.value
                }
            )
        }
    }
}

// =========================
// YOURUPLOAD
// =========================
open class YourUpload : ExtractorApi() {

    override val name = "YourUpload"

    override val mainUrl =
        "https://www.yourupload.com"

    override val requiresReferer = false

    private val fileRegex =
        Regex(
            """file\s*:\s*['"]([^'"]+)"""
        )

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink> {

        val sources =
            mutableListOf<ExtractorLink>()

        val html =
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0"
                )
            ).text

        val quality =
            Regex("""\d{3,4}p""")
                .find(html)
                ?.groupValues
                ?.getOrNull(0)

        val videoUrl =
            fileRegex.find(html)
                ?.groupValues
                ?.getOrNull(1)

        if (
            !videoUrl.isNullOrBlank()
        ) {

            sources.add(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl
                ) {
                    this.referer =
                        referer ?: mainUrl

                    this.quality =
                        getQualityFromName(
                            quality
                        )
                }
            )
        }

        return sources
    }
}

// =========================
// MP4UPLOAD
// =========================
open class Mp4Upload : ExtractorApi() {

    override var name = "Mp4Upload"

    override var mainUrl =
        "https://www.mp4upload.com"

    override val requiresReferer = true

    // Viejo formato:
    // player.src("https://...")
    private val oldRegex =
        Regex("""player\.src\(["']([^"']+)""")

    // Nuevo formato:
    // src: "https://..."
    private val newRegex =
        Regex("""src\s*:\s*["']([^"']+)""")

    // Fallback genérico mp4
    private val mp4Regex =
        Regex("""https?:\/\/[^"'\\ ]+\.mp4[^"'\\ ]*""")

    private val idRegex =
        Regex("""mp4upload\.com/(embed-|)([A-Za-z0-9]+)""")

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {

        val realUrl =
            idRegex.find(url)
                ?.groupValues
                ?.getOrNull(2)
                ?.let {
                    "$mainUrl/embed-$it.html"
                } ?: url

        val response = app.get(
            realUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0"
            ),
            referer = referer ?: mainUrl
        )

        val unpacked =
            getAndUnpack(response.text)

        val quality =
            Regex("""(\d{3,4})p""")
                .find(unpacked)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: Qualities.Unknown.value

        val videoUrl =
            oldRegex.find(unpacked)
                ?.groupValues
                ?.getOrNull(1)

                ?: newRegex.find(unpacked)
                    ?.groupValues
                    ?.getOrNull(1)

                ?: mp4Regex.find(unpacked)
                    ?.value

        if (videoUrl.isNullOrBlank()) {
            return null
        }

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = INFER_TYPE
            ) {
                this.referer =
                    realUrl

                this.quality =
                    quality
            }
        )
    }
}

// =========================
// STREAMSB
// =========================
open class StreamSB : ExtractorApi() {

    override var name = "StreamSB"

    override var mainUrl =
        "https://watchsb.com"

    override val requiresReferer = false

    private val alphabet =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val id = Regex(
            "(embed-[a-zA-Z\\d_-]+|/e/[a-zA-Z\\d_-]+)"
        ).find(url)
            ?.value
            ?.replace("embed-", "")
            ?.replace("/e/", "")
            ?: return

        val master =
            "$mainUrl/375664356a494546326c4b797c7c6e756577776778623171737/${encodeId(id)}"

        val headers = mapOf(
            "watchsb" to "sbstream"
        )

        val mapped = app.get(
            master.lowercase(),
            headers = headers,
            referer = url
        ).parsedSafe<Main>()

        val stream =
            mapped?.streamData?.file
                ?: return

        M3u8Helper.generateM3u8(
            name,
            stream,
            url,
            headers = headers
        ).forEach(callback)

        mapped.streamData.subs?.forEach { sub ->

            val file =
                sub.file ?: return@forEach

            subtitleCallback.invoke(
                newSubtitleFile(
                    sub.label ?: "Sub",
                    file
                )
            )
        }
    }

    private fun encodeId(
        id: String
    ): String {

        val code =
            "${createHashTable()}||$id||${createHashTable()}||streamsb"

        return code.toCharArray()
            .joinToString("") {
                it.code.toString(16)
            }
    }

    private fun createHashTable():
        String {

        return buildString {

            repeat(12) {

                append(
                    alphabet.random()
                )
            }
        }
    }

    data class Subs(
        @JsonProperty("file")
        val file: String? = null,

        @JsonProperty("label")
        val label: String? = null
    )

    data class StreamData(

        @JsonProperty("file")
        val file: String,

        @JsonProperty("subs")
        val subs: ArrayList<Subs>? =
            arrayListOf()
    )

    data class Main(

        @JsonProperty("stream_data")
        val streamData: StreamData
    )
}

// =========================
// STREAMSB MIRRORS
// =========================
class Waaw : StreamSB() {
    override var mainUrl =
        "https://waaw.to"
}

class Sblona : StreamSB() {
    override var mainUrl =
        "https://sblona.com"
}

class Lvturbo : StreamSB() {
    override var mainUrl =
        "https://lvturbo.com"
}

class Sbrapid : StreamSB() {
    override var mainUrl =
        "https://sbrapid.com"
}

class Sbface : StreamSB() {
    override var mainUrl =
        "https://sbface.com"
}

class Sbsonic : StreamSB() {
    override var mainUrl =
        "https://sbsonic.com"
}

class Sbasian : StreamSB() {
    override var mainUrl =
        "https://sbasian.pro"
}

class Sbnet : StreamSB() {
    override var mainUrl =
        "https://sbnet.one"
}

class Sbspeed : StreamSB() {
    override var mainUrl =
        "https://sbspeed.com"
}

class Streamsss : StreamSB() {
    override var mainUrl =
        "https://streamsss.net"
}

class Sbflix : StreamSB() {
    override var mainUrl =
        "https://sbflix.xyz"
}

class Sbthe : StreamSB() {
    override var mainUrl =
        "https://sbthe.com"
}

class Ssbstream : StreamSB() {
    override var mainUrl =
        "https://ssbstream.net"
}

class SBfull : StreamSB() {
    override var mainUrl =
        "https://sbfull.com"
}