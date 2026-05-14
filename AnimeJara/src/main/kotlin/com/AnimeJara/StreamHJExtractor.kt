package com.animejara

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

class StreamHJExtractor {

    suspend fun extract(
        url: String,
        referer: String,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc: Document = app.get(url, referer = referer).document

        val elements = doc.select("[onclick*=playVideo]")

        elements.forEach { element ->
            val onclick = element.attr("onclick")

            val videoUrl = Regex("""playVideo\(['"]([^'"]+)['"]\)""")
                .find(onclick)
                ?.groupValues
                ?.get(1)

            if (!videoUrl.isNullOrEmpty()) {
                loadExtractor(videoUrl, url, subtitleCallback, callback)
            }
        }
    }
}