package com.byayzen

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*

class Voe : ExtractorApi() {

    override val name = "Voe"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val doc = app.get(url, referer = referer).document

        val videoUrl = doc.selectFirst("video source")?.attr("src")
            ?: doc.html().substringAfter("file:\"").substringBefore("\"")

        if (videoUrl.isNotEmpty()) {
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    videoUrl,
                    url,
                    Qualities.Unknown.value
                )
            )
        }
    }
}