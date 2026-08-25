package com.sololatino

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class PlayHydrax : ExtractorApi() {

    override var name = "PlayHydrax"
    override var mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Origin" to "https://playhydrax.com",
            "Referer" to "https://playhydrax.com/"
        )

        val document = app.get(
            url,
            headers = headers
        ).document

        val scripts = document.select("script")
            .joinToString("\n") {
                it.data()
            }

        val encrypted = Regex(
            """const\s+datas\s*=\s*"([^"]*)""""
        )
            .find(scripts)
            ?.groupValues
            ?.getOrNull(1)
            ?: return

        val decrypted = app.post(
            url = "https://enc-dec.app/api/dec-abyss",

            headers = headers,

            requestBody = """
            {
                "text": "$encrypted"
            }
            """.trimIndent()
                .toRequestBody(
                    "application/json"
                        .toMediaType()
                )
        )
            .parsedSafe<AbyssResponse>()
            ?.result
            ?: return

        decrypted.sources
            .filter {
                it.status
            }
            .forEach { source ->

                callback.invoke(
                    newExtractorLink(
                        source = name,

                        name = "$name [${
                            source.codec.uppercase()
                        }]",

                        url = source.url,

                        type = INFER_TYPE
                    ) {

                        this.quality =
                            getQualityFromName(
                                source.type
                            )

                        this.headers = mapOf(
                            "Referer" to
                                    "https://playhydrax.com/"
                        )
                    }
                )
            }
    }

    data class AbyssResponse(
        val status: Long,
        val result: Result,
    )

    data class Result(
        val sources: List<AbyssSource>,
    )

    data class AbyssSource(
        val url: String,
        val size: Long,
        val type: String,
        val codec: String,
        val status: Boolean,
    )
}