package com.tdtchannels

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class TDTChannels : MainAPI() {
    override var mainUrl = "https://play.tdtchannels.com"
    override var name = "TDTChannels"
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/television" to "📺 TV - Todas",
        "$mainUrl/radio" to "📻 Radio - Todas"
    )

    data class ChannelItem(
        val name: String,
        val logo: String?,
        val slug: String,
        val category: String
    )

    private fun cleanHtml(html: String): String {
        return html
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\n", "")
            .replace("\\t", "")
    }

    private fun enc(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun parseChannels(html: String): List<ChannelItem> {
        val clean = cleanHtml(html)
        val out = mutableListOf<ChannelItem>()

        val regex = Regex(
            """"canonicalSlug":"[^"]*","category":"([^"]*)","legacySlug":"([^"]*)","logo":"([^"]*)","name":"([^"]*)","slug":"([^"]*)""""
        )

        regex.findAll(clean).forEach { m ->
            val category = m.groupValues[1].ifBlank { "Otros" }
            val legacySlug = m.groupValues[2]
            val logo = m.groupValues[3]
            val name = m.groupValues[4]
            val slug = m.groupValues[5].ifBlank { legacySlug }

            if (name.isNotBlank() && slug.isNotBlank()) {
                out.add(ChannelItem(name, logo, slug, category))
            }
        }

        return out.distinctBy { it.slug }
    }

    private fun groupTitle(category: String): String {
        return when {
            category.equals("Generalistas", true) -> "🇪🇸 Generalistas"
            category.equals("Informativos", true) -> "📰 Informativos"
            category.equals("Deportivos", true) -> "⚽ Deportivos"
            category.equals("Infantiles", true) -> "🧸 Infantiles"
            category.equals("Eventuales", true) -> "🔴 Eventuales"
            category.equals("Streaming", true) -> "🎮 Streaming"
            category.equals("Musicales", true) -> "🎵 Musicales"
            category.equals("Religiosos", true) -> "⛪ Religiosos"
            category.startsWith("Int. América", true) -> "🌎 Internacional - América"
            category.startsWith("Int. Europa", true) -> "🌍 Internacional - Europa"
            category.startsWith("Int. Asia", true) -> "🌏 Internacional - Asia"
            category.startsWith("Int. África", true) -> "🌍 Internacional - África"
            category.startsWith("Int.", true) -> "🌐 Internacional - Otros"
            category.contains("Deportivos Int.", true) -> "🏆 Deportes Internacionales"
            else -> "🇪🇸 España - $category"
        }
    }

    private fun channelResponse(ch: ChannelItem, base: String): SearchResponse {
        return newMovieSearchResponse(
            ch.name,
            "$base/${enc(ch.slug)}",
            TvType.Live
        ) {
            posterUrl = ch.logo
        }
    }

    private fun extractStream(html: String): String? {
        val clean = cleanHtml(html)

        Regex(
            """"title":"[^"]*","url":"(https?://[^"]+?\.m3u8[^"]*)""""
        ).find(clean)?.groupValues?.getOrNull(1)?.let {
            return it
        }

        Regex(
            """(https?://[^"'\\]+?\.m3u8[^"'\\]*)"""
        ).find(clean)?.groupValues?.getOrNull(1)?.let {
            return it
        }

        return null
    }

    private fun extractTitle(html: String): String {
        val clean = cleanHtml(html)

        return Regex(""""selectedChannel":\{.*?"name":"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""<title>(.*?)\s*\|""")
                .find(clean)
                ?.groupValues
                ?.getOrNull(1)
            ?: "Canal en Vivo"
    }

    private fun extractLogo(html: String): String? {
        val clean = cleanHtml(html)

        return Regex(""""selectedChannel":\{.*?"logo":"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""property="og:image"\s+content="([^"]+)"""")
                .find(clean)
                ?.groupValues
                ?.getOrNull(1)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get(
            request.data,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            )
        ).text

        val channels = parseChannels(html)
        val base = if (request.data.contains("/radio")) "$mainUrl/radio" else "$mainUrl/television"

        val lists = channels
            .groupBy { groupTitle(it.category) }
            .map { group ->
                HomePageList(
                    group.key,
                    group.value.map { channelResponse(it, base) }
                )
            }

        return newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase().trim()
        if (q.length < 2) return emptyList()

        val tvHtml = app.get("$mainUrl/television").text
        val radioHtml = app.get("$mainUrl/radio").text

        val tv = parseChannels(tvHtml).map { it to "$mainUrl/television" }
        val radio = parseChannels(radioHtml).map { it to "$mainUrl/radio" }

        return (tv + radio)
            .filter { it.first.name.lowercase().contains(q) || it.first.category.lowercase().contains(q) }
            .map { pair ->
                channelResponse(pair.first, pair.second)
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            )
        ).text

        val title = extractTitle(html)
        val poster = extractLogo(html)
        val stream = extractStream(html) ?: throw ErrorLoadingException("No se encontró m3u8")

        return newMovieLoadResponse(
            title,
            url,
            TvType.Live,
            stream
        ) {
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = "Transmisión legal vía TDTChannels."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Direct Stream",
                url = data,
                type = INFER_TYPE
            ) {
                referer = mainUrl
                quality = Qualities.Unknown.value
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to mainUrl,
                    "Origin" to mainUrl
                )
            }
        )

        return true
    }
}