package com.pelisgratishd

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.extractors.FilemoonV2
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Extractores específicos para servidores de PelisGratisHD
class FileMoonlink : FilemoonV2() {
    override var mainUrl = "https://filemoon.link"
    override var name = "FileMoon"
}

class VidHideProExtractor : VidHidePro() {
    override var name = "VidHidePro"
    override var mainUrl = "https://vidhidepro.com"
}

class StreamwishHG : StreamWishExtractor() {
    override val mainUrl = "https://hglink.to"
    override val name = "StreamWish"
}

// Clase de utilidad para cargar extractores con nombres personalizados
suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    loadExtractor(url, referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            callback.invoke(
                newExtractorLink(
                    "$source [${link.source}]",
                    "$source [${link.source}]",
                    link.url,
                ) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}

// Mapa de idiomas (similar al que tenías)
private val languageMap = mapOf(
    "Spanish" to Pair("es", "spa"),
    "English" to Pair("en", "eng"),
    "French" to Pair("fr", "fra"),
    "Portuguese" to Pair("pt", "por"),
    "German" to Pair("de", "deu"),
    "Italian" to Pair("it", "ita"),
    "Japanese" to Pair("ja", "jpn"),
    "Korean" to Pair("ko", "kor"),
    "Chinese" to Pair("zh", "zho"),
    "Russian" to Pair("ru", "rus"),
    "Arabic" to Pair("ar", "ara")
)

fun getLanguage(language: String?): String? {
    language ?: return null
    val normalizedLang = language.substringBefore("-")
    return languageMap.entries.find { 
        it.value.first == normalizedLang || it.value.second == normalizedLang 
    }?.key ?: language
}