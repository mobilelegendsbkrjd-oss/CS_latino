package com.phisher98

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class IPTVPlayer : MainAPI() {

    override var lang = "es"
    override var name = "IPTV México"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val baseUrl =
        "https://raw.githubusercontent.com/mobilelegendsbkrjd-oss/lat_cs_bkrjd/main/builds/iptv/"

    private val lists = listOf(
        Triple("24/7", baseUrl + "247.m3u", ""),
        Triple("Cine", baseUrl + "Cine.m3u", ""),
        Triple("Eventos", baseUrl + "Eventos.m3u", ""),
        Triple("Mexico HD", baseUrl + "android(hd).m3u", ""),
        Triple("Mexico SD", baseUrl + "android(sd).m3u", ""),
        Triple("Deportes", baseUrl + "deportes.m3u", ""),
        Triple("Documentales", baseUrl + "documentales.m3u", ""),
        Triple("Entretenimiento", baseUrl + "entretenimiento.m3u", ""),
        Triple("Infantil", baseUrl + "infantil.m3u", ""),
        Triple("Música", baseUrl + "musica.m3u", ""),
        Triple("Noticias", baseUrl + "noticias.m3u", ""),
        Triple("Novelas", baseUrl + "novelas.m3u", "")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val shows = lists.map { (title, _, poster) ->
            newTvSeriesSearchResponse(title, title, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(
            listOf(HomePageList("Listas IPTV", shows, false))
        )
    }

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun load(url: String): LoadResponse {
        val list = lists.find { url == it.first || url.endsWith(it.first) || url.contains(it.first) }
            ?: throw ErrorLoadingException("Lista no encontrada: $url")

        val data = IptvPlaylistParser().parseM3U(app.get(list.second).text)

        val sorted = data.items
            .filter { !it.title.isNullOrBlank() && !it.url.isNullOrBlank() }
            .sortedBy { it.title!!.lowercase() }

        val episodes = sorted.mapIndexed { index, ch ->
            newEpisode(ch.url!!) {
                this.name = ch.title!!
                this.episode = index + 1
                this.season = 1
                this.posterUrl = ch.logo
                this.data = LoadData(ch.url!!, ch.title!!).toJson()
            }
        }

        return newTvSeriesLoadResponse(
            list.first,
            list.first,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = list.third
            this.plot = "Canales en vivo"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ld = parseJson<LoadData>(data)

        // Usamos la estructura que tenías pero definiendo INFER_TYPE 
        // y añadiendo el flag isM3u8 dentro del inicializador para estabilidad
        callback.invoke(
            newExtractorLink(
                this.name,
                ld.title,
                ld.url,
                "" // Referer vacío como pide tu versión
            ) {
                this.quality = Qualities.Unknown.value
                // Al poner esto, CloudStream detecta que es un flujo continuo
                this.isM3u8 = true 
            }
        )
        return true
    }

    data class LoadData(
        val url: String,
        val title: String
    )
}

/* ============== PARSER CON LOGO ============== */

data class Playlist(val items: List<PlaylistItem> = emptyList())

data class PlaylistItem(
    val title: String? = null,
    val url: String? = null,
    val logo: String? = null
)

class IptvPlaylistParser {

    fun parseM3U(content: String): Playlist = parseM3U(content.byteInputStream())

    fun parseM3U(input: java.io.InputStream): Playlist {
        val reader = input.bufferedReader()
        val firstLine = reader.readLine()
        if (firstLine == null || !firstLine.startsWith("#EXTM3U")) throw Exception("M3U inválido")

        val items = mutableListOf<PlaylistItem>()
        var currentTitle: String? = null
        var currentLogo: String? = null

        reader.forEachLine { line ->
            val t = line.trim()
            when {
                t.startsWith("#EXTINF") -> {
                    currentTitle = t.split(",").lastOrNull()?.trim()
                    currentLogo = Regex("""tvg-logo="(.*?)"""").find(t)?.groupValues?.get(1)
                }
                !t.startsWith("#") && t.isNotEmpty() -> {
                    items.add(
                        PlaylistItem(
                            title = currentTitle,
                            url = t,
                            logo = currentLogo
                        )
                    )
                    currentTitle = null
                    currentLogo = null
                }
            }
        }
        return Playlist(items)
    }
}
