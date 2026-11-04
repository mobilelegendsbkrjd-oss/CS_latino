package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.VidStack
import org.json.JSONObject

// ===============================
// Bysekoze Extractor integrado
// ===============================
class Bysekoze : ExtractorApi() {

    override var name = "Bysekoze"
    override var mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    companion object {
        private val ID_REGEX = Regex("""/(?:e|v|embed)/([a-zA-Z0-9_-]+)""")
        private val PACKED_LINK_REGEX = Regex("""(?:file|url)\s*:\s*["']([^"']+)["']""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val refUrl = referer ?: url
        val headers = mapOf(
            "Referer" to refUrl,
            "User-Agent" to USER_AGENT
        )

        try {
            val id = ID_REGEX.find(url)?.groupValues?.getOrNull(1)

            if (!id.isNullOrEmpty()) {
                val apiUrl = "$mainUrl/api/videos/$id/embed/playback"
                val responseText = app.get(apiUrl, headers = headers).text
                val json = JSONObject(responseText)

                val sources = json.optJSONArray("sources")
                if (sources != null && sources.length() > 0) {
                    for (i in 0 until sources.length()) {
                        val obj = sources.optJSONObject(i) ?: continue
                        val link = obj.optString("url").ifBlank { obj.optString("file") }
                        if (link.isBlank()) continue

                        if (link.contains(".m3u8", ignoreCase = true)) {
                            M3u8Helper.generateM3u8(
                                name,
                                link,
                                refUrl,
                                headers = headers
                            ).forEach(callback)
                        } else {
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    name,
                                    link,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = refUrl
                                    this.quality = Qualities.Unknown.value
                                    this.headers = headers
                                }
                            )
                        }
                    }
                    return
                }
            }
        } catch (_: Exception) {
        }

        // fallback antiguo (packer)
        try {
            val document = app.get(url, headers = headers).document
            val packed = document
                .selectFirst("script:containsData(function(p,a,c,k,e,d))")
                ?.data()
                .orEmpty()

            if (packed.isNotBlank()) {
                JsUnpacker(packed).unpack()?.let { unpacked ->
                    PACKED_LINK_REGEX.find(unpacked)?.groupValues?.getOrNull(1)?.let { link ->
                        if (link.contains(".m3u8", ignoreCase = true)) {
                            M3u8Helper.generateM3u8(
                                name,
                                link,
                                refUrl,
                                headers = headers
                            ).forEach(callback)
                        } else {
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    name,
                                    link,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = refUrl
                                    this.quality = Qualities.Unknown.value
                                    this.headers = headers
                                }
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}

// ===============================
// MAIN
// ===============================
class LatinLuchas : MainAPI() {

    override var mainUrl = "https://latinlucha.es"
    override var name = "LatinLuchas"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Live)

    private val defaultPoster =
        "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        val home = mutableListOf<HomePageList>()

        // --- 1. Canales en Vivo (solo en la primera página) ---
        if (page == 1) {
            try {
                val doc = app.get(mainUrl).document
                val liveChannels = doc.select("div.accordion-content a")
                    .mapNotNull { element ->
                        val title = element.text().trim()
                        val href = element.attr("abs:href")
                        if (title.contains("CANAL", ignoreCase = true)) {
                            newMovieSearchResponse(title, href, TvType.Live) {
                                this.posterUrl = defaultPoster
                            }
                        } else null
                    }
                if (liveChannels.isNotEmpty()) {
                    home.add(HomePageList("En Vivo", liveChannels))
                }
            } catch (_: Exception) {}
        }

        // --- 2. Categorías de eventos (paginadas) ---
        val categories = listOf(
            "WWE" to "$mainUrl/category/eventos/wwe/",
            "UFC" to "$mainUrl/category/eventos/ufc/",
            "AEW" to "$mainUrl/category/eventos/aew/",
            "Lucha Libre Mexicana" to "$mainUrl/category/eventos/lucha-libre-mexicana/",
            "Indies" to "$mainUrl/category/eventos/indies/"
        )

        categories.forEach { (catName, baseUrl) ->
            try {
                val pageUrl = if (page == 1) baseUrl else "${baseUrl}page/$page/"
                val doc = app.get(pageUrl).document

                // Selector correcto para la página de categoría
                val items = doc.select("article.post")
                    .mapNotNull { element ->
                        val title = element.selectFirst("h2.entry-title a")?.text()?.trim()
                            ?: return@mapNotNull null
                        val href = element.selectFirst("h2.entry-title a")?.attr("abs:href")
                            ?: return@mapNotNull null
                        val poster = element.selectFirst("img.wp-post-image")?.attr("abs:src")
                            ?: defaultPoster

                        newMovieSearchResponse(title, href, TvType.Movie) {
                            this.posterUrl = poster
                        }
                    }

                if (items.isNotEmpty()) {
                    home.add(HomePageList(catName, items))
                }
            } catch (_: Exception) {
                // Si una categoría falla, continuamos con las demás
            }
        }

        return if (home.isNotEmpty()) newHomePageResponse(home) else null
    }

    override suspend fun load(url: String): LoadResponse? {

        val document = app.get(url).document

        val title = document
            .selectFirst("h1.entry-title")
            ?.text()?.trim()
            ?: "Evento"

        val poster =
            document.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?: defaultPoster

        val plot = document
            .selectFirst("meta[property=og:description]")
            ?.attr("content")

        val type = if (url.contains("canal")) TvType.Live else TvType.Movie

        return newMovieLoadResponse(
            title,
            url,
            type,
            url
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        // 1. Buscar enlaces de botones (a.btn-video) que apuntan a tv.latinlucha.es
        val videoLinks = document.select("a.btn-video")
            .mapNotNull { it.attr("abs:href").takeIf { href -> href.isNotBlank() } }

        // 2. Procesar cada enlace de video encontrado
        videoLinks.forEach { videoUrl ->
            try {
                // Seguir el enlace a la página de TV (ej: tv.latinlucha.es/tv/triplemierda18jul26-1/)
                val videoDoc = app.get(videoUrl).document

                // Buscar iframes reales dentro de la página de TV
                videoDoc.select("iframe").forEach { iframe ->
                    val src = iframe.attr("abs:src")
                        .ifBlank { iframe.attr("abs:data-src") }
                        .ifBlank { iframe.attr("src") }
                        .let { if (it.startsWith("//")) "https:$it" else it }

                    if (src.isBlank()) return@forEach

                    // Filtrar publicidad y redes sociales
                    if (src.contains(
                            Regex("facebook|google|ads|twitter|instagram|doubleclick|profitablerate",
                                RegexOption.IGNORE_CASE)
                        )
                    ) return@forEach

                    when {
                        // Opción 1: ok.ru → Odnoklassniki (lo maneja loadExtractor automáticamente)
                        src.contains("ok.ru") -> {
                            loadExtractor(src, videoUrl, subtitleCallback, callback)
                        }

                        // Opción 2: upns.online → VidStack
                        src.contains("upns.online") -> {
                            VidStack().getUrl(src, videoUrl, subtitleCallback, callback)
                        }

                        // bysekoze (por si acaso)
                        src.contains("bysekoze") -> {
                            Bysekoze().getUrl(src, videoUrl, subtitleCallback, callback)
                        }

                        // Enlaces directos a video
                        src.contains(".m3u8", ignoreCase = true) -> {
                            M3u8Helper.generateM3u8(
                                "LatinLuchas",
                                src,
                                videoUrl
                            ).forEach(callback)
                        }

                        src.contains(".mp4", ignoreCase = true) -> {
                            callback.invoke(
                                newExtractorLink(
                                    "LatinLuchas",
                                    "LatinLuchas",
                                    src,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = videoUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }

                        // Cualquier otro iframe → extractor genérico
                        else -> {
                            loadExtractor(src, videoUrl, subtitleCallback, callback)
                        }
                    }
                }
            } catch (_: Exception) {
                // Si falla un enlace, continuamos con el siguiente
            }
        }

        // 3. También buscar iframes directamente en la página del evento (por si acaso)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("abs:src")
                .ifBlank { iframe.attr("abs:data-src") }
                .ifBlank { iframe.attr("src") }
                .let { if (it.startsWith("//")) "https:$it" else it }

            if (src.isBlank()) return@forEach

            if (src.contains(
                    Regex("facebook|google|ads|twitter|instagram|doubleclick|profitablerate",
                        RegexOption.IGNORE_CASE)
                )
            ) return@forEach

            when {
                src.contains("ok.ru") -> {
                    loadExtractor(src, data, subtitleCallback, callback)
                }

                src.contains("upns.online") -> {
                    VidStack().getUrl(src, data, subtitleCallback, callback)
                }

                src.contains("bysekoze") -> {
                    Bysekoze().getUrl(src, data, subtitleCallback, callback)
                }

                else -> {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}