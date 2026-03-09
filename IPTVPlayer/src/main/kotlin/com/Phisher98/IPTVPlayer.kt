package com.phisher98

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.io.InputStream

class IPTVPlayer : MainAPI() {
    override var lang = "es"
    override var name = "IPTV México"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val baseUrl = "https://raw.githubusercontent.com/mobilelegendsbkrjd-oss/lat_cs_bkrjd/main/builds/iptv/"

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
            newTvSeriesSearchResponse(title, title, TvType.Live) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(listOf(HomePageList("Listas IPTV", shows, false)))
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
                this.posterUrl = ch.attributes["tvg-logo"] ?: ""
                this.data = LoadData(
                    url = ch.url!!,
                    title = ch.title!!,
                    key = ch.attributes["key"] ?: "",
                    kid = ch.attributes["keyid"] ?: ""
                ).toJson()
            }
        }

        return newTvSeriesLoadResponse(list.first, list.first, TvType.Live, episodes) {
            this.posterUrl = list.third
            this.plot = "Canales en vivo: ${list.first}"
        }
    }

    data class LoadData(
        val url: String,
        val title: String,
        val key: String,
        val kid: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ld = parseJson<LoadData>(data)
        
        // Esta es la sintaxis exacta del segundo código que sí compila.
        // Solo 3 parámetros obligatorios y el resto en el bloque { }
        callback.invoke(
            newExtractorLink(
                this.name,
                ld.title,
                ld.url
            ) {
                this.quality = Qualities.Unknown.value
                // Usamos la propiedad del bloque para evitar errores de constructor
                this.type = ExtractorLinkType.M3U8
            }
        )
        return true
    }
}

/* ============== TU PARSER ORIGINAL ============== */

data class Playlist(val items: List<PlaylistItem> = emptyList())
data class PlaylistItem(
    val title: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val url: String? = null
)

class IptvPlaylistParser {
    fun parseM3U(content: String): Playlist = parseM3U(content.byteInputStream())
    fun parseM3U(input: InputStream): Playlist {
        val reader = input.bufferedReader()
        val firstLine = reader.readLine() ?: throw Exception("M3U vacío")
        if (!firstLine.startsWith("#EXTM3U")) throw Exception("M3U inválido")
        
        val items = mutableListOf<PlaylistItem>()
        var currentTitle: String? = null
        var currentAttributes: Map<String, String> = emptyMap()

        reader.forEachLine { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEachLine
            when {
                t.startsWith("#EXTINF") -> {
                    currentTitle = t.split(",").lastOrNull()?.trim()
                    currentAttributes = t.substringAfter("#EXTINF:").substringBefore(",")
                        .split(" ")
                        .mapNotNull {
                            val p = it.split("=")
                            if (p.size == 2) p[0] to p[1].replace("\"", "") else null
                        }.toMap()
                }
                !t.startsWith("#") -> {
                    items.add(PlaylistItem(currentTitle, currentAttributes, t))
                    currentTitle = null
                    currentAttributes = emptyMap()
                }
            }
        }
        return Playlist(items)
    }
}
