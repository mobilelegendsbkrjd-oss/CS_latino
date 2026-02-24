package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.FilemoonV2
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.api.Log
import okhttp3.FormBody
import org.json.JSONObject

class LatinLuchaUpns : VidStack() {
    override var name = "LatinLucha Upns"
    override var mainUrl = "https://latinlucha.upns.online"
}

class LatinLuchas : MainAPI() {

    override var mainUrl = "https://latinluchas.com"
    override var name = "LatinLuchas"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val defaultPoster =
        "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    // =========================
    // HOMEPAGE
    // =========================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        val categories = listOf(
            Pair("WWE", "$mainUrl/category/eventos/wwe/"),
            Pair("UFC", "$mainUrl/category/eventos/ufc/"),
            Pair("AEW", "$mainUrl/category/eventos/aew/"),
            Pair("Lucha Libre Mexicana", "$mainUrl/category/eventos/lucha-libre-mexicana/"),
            Pair("Indies", "$mainUrl/category/eventos/indies/")
        )

        val homePages = categories.map { (sectionName, url) ->

            val doc = app.get(url).document

            val items = doc.select("article, .post, .elementor-post")
                .mapNotNull { element ->
                    val title = element.selectFirst("h2, h3, .entry-title")
                        ?.text()?.trim() ?: return@mapNotNull null

                    val href = element.selectFirst("a")
                        ?.attr("abs:href") ?: return@mapNotNull null

                    val poster = element.selectFirst("img")
                        ?.attr("abs:src")
                        ?.takeIf { it.isNotBlank() }
                        ?: element.selectFirst("img")
                            ?.attr("abs:data-src")
                        ?: defaultPoster

                    newAnimeSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                }

            HomePageList(sectionName, items)
        }

        return newHomePageResponse(homePages)
    }

    // =========================
    // LOAD EVENTO
    // =========================
    override suspend fun load(url: String): LoadResponse? {

        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")
            ?.text()?.trim() ?: "Evento"

        val poster = document.selectFirst("meta[property='og:image']")
            ?.attr("content") ?: defaultPoster

        // SOLO BOTONES REALES DE VIDEO
        val episodes = document
            .select("a.btn-video")
            .mapNotNull { anchor ->

                val name = anchor.text().trim()
                val link = anchor.attr("abs:href")

                if (link.isBlank() ||
                    link.contains("descargar", true)
                ) return@mapNotNull null

                newEpisode(link) {
                    this.name = name
                }
            }
            .distinctBy { it.data }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster
            this.plot = document
                .selectFirst("meta[property='og:description']")
                ?.attr("content")
        }
    }

    // =========================
    // LOAD LINKS (NO TOCAR)
    // =========================
    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    val document = app.get(data).document

    document.select("iframe").forEach { iframe ->

        var src = iframe.attr("abs:src")
            .ifBlank { iframe.attr("abs:data-src") }
            .ifBlank { iframe.attr("src") }

        if (src.isBlank()) return@forEach

        if (src.startsWith("//"))
            src = "https:$src"

        when {

            // UPNS
            src.contains("upns.online") ->
                LatinLuchaUpns()
                    .getUrl(src, data, subtitleCallback, callback)

            // ONION UNS (Byzekose)
            src.contains("uns.wtf") ->
                Movierulzups()
                    .getUrl(src, data, subtitleCallback, callback)

            // CHERRY
            src.contains("cherry.upns") ->
                CherryUpns()
                    .getUrl(src, data, subtitleCallback, callback)

            else ->
                loadExtractor(src, data, subtitleCallback, callback)
        }
    }

    return true
}
// ===============================
// SERVIDORES EXTRA INTEGRADOS
// ===============================

class FMHD : Filesim() {
    override val name = "FMHD"
    override var mainUrl = "https://fmhd.bar/"
    override val requiresReferer = true
}

class Playonion : Filesim() {
    override val mainUrl = "https://playonion.sbs"
}

class Luluvdo : StreamWishExtractor() {
    override val mainUrl = "https://luluvdo.com"
}

class Lulust : StreamWishExtractor() {
    override val mainUrl = "https://lulu.st"
}

class Movierulz : FilemoonV2() {
    override var name = "Movierulz"
    override var mainUrl = "https://movierulz2025.bar"
}

class Movierulzups : VidStack() {
    override var name = "Movierulz"
    override var mainUrl = "https://onion.uns.wtf"
}

class CherryUpns : VidStack() {
    override var name = "Movierulz"
    override var mainUrl = "https://cherry.upns.online"
}
}