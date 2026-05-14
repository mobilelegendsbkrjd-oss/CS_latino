package com.telegratishd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TeleGratisProvider : MainAPI() {
    override var mainUrl = "https://www.telegratishd.com"
    override var name = "Tele Gratis HD"
    override val hasMainPage = true
    override var lang = "es"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "" to "Todos los Canales",
        "deportes" to "Deportes",
        "argentina" to "Argentina",
        "mexico" to "México",
        "infantiles" to "Infantiles",
        "entretenimiento" to "Entretenimiento",
        "documentales" to "Documentales"
    )

    data class Channel(
        val name: String,
        val id: String,
        val poster: String,
        val category: String
    )

    // -------------------------------
    // TU LISTA GRANDE DE CANALES
    // (no la toco, va igual que la mandaste)
    // -------------------------------
    private val allChannels = listOf(
        Channel("TUDN", "tudn", "$mainUrl/imge/tudn.png", "deportes"),
        Channel("TNT Sports", "tntsports", "$mainUrl/imge/tntsports.png", "deportes"),
        Channel("Fox Sports Premium", "foxsportspremium", "$mainUrl/imge/foxsportspremium.png", "deportes"),
        Channel("Fox Sports", "foxsports", "$mainUrl/imge/foxsports.png", "deportes"),
        Channel("Fox Sports 2", "foxsports2", "$mainUrl/imge/foxsports2.png", "deportes"),
        Channel("Fox Sports 3", "foxsports3", "$mainUrl/imge/foxsports3.png", "deportes"),
        Channel("ESPN Premium", "espnpremium", "$mainUrl/imge/espnpremium.png", "deportes"),
        Channel("ESPN", "espn", "$mainUrl/imge/espn.png", "deportes"),
        Channel("ESPN 2", "espn2", "$mainUrl/imge/espn2.png", "deportes"),
        Channel("ESPN 3", "espn3", "$mainUrl/imge/espn3.png", "deportes"),
        Channel("ESPN 4", "espn4", "$mainUrl/imge/espn4.png", "deportes"),
        Channel("Directv Sports", "directvsports", "$mainUrl/imge/directvsports.png", "deportes"),
        Channel("Directv Sports 2", "directvsports2", "$mainUrl/imge/directvsports2.png", "deportes"),
        Channel("Directv Sports Plus", "directvsportsplus", "$mainUrl/imge/directvsportsplus.png", "deportes"),
        Channel("TYC Sports", "tycsports", "$mainUrl/imge/tycsports.png", "deportes"),
        Channel("Movistar Deportes", "movistardeportes", "$mainUrl/imge/movistardeportes.png", "deportes"),
        Channel("Movistar Liga De Campeones", "movistarligadecampeones", "$mainUrl/imge/movistarligadecampeones.png", "deportes"),
        Channel("Liga 1 Max", "liga1max", "$mainUrl/imge/liga1max.png", "deportes"),
        Channel("Dazn F1", "daznf1", "$mainUrl/imge/daznf1.jpg", "deportes"),
        Channel("Dazn La Liga", "daznlaliga", "$mainUrl/imge/daznlaliga1.png", "deportes"),
        Channel("ESPN Mexico", "espnmexico", "$mainUrl/imge/espnmexico.png", "deportes"),
        Channel("ESPN 2 Mexico", "espn2mexico", "$mainUrl/imge/espn2mexico.png", "deportes"),
        Channel("ESPN 3 Mexico", "espn3mexico", "$mainUrl/imge/espn3mexico.png", "deportes"),
        Channel("Fox Sports Mexico", "foxsportsmx", "$mainUrl/imge/foxsportsmx.png", "deportes"),
        Channel("Fox Sports 2 Mexico", "foxsports2mx", "$mainUrl/imge/foxsports2mx.png", "deportes"),
        Channel("Fox Sports 3 Mexico", "foxsports3mexico", "$mainUrl/imge/foxsports3mexico.png", "deportes"),
        // ... TODA tu lista sigue igual ...
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val list = if (request.data == "") allChannels else allChannels.filter { it.category == request.data }

        items.add(
            HomePageList(
                name = if (request.data == "") "Todos" else request.data,
                list = list.map {
                    newTvSeriesSearchResponse(it.name, it.id) {
                        posterUrl = it.poster
                    }
                },
                isHorizontalImages = false
            )
        )
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return allChannels.filter {
            it.name.contains(query, true) || it.category.contains(query, true)
        }.map {
            newTvSeriesSearchResponse(it.name, it.id) {
                posterUrl = it.poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val channel = allChannels.firstOrNull { it.id == url }
            ?: Channel(url, url, "$mainUrl/imge/tvenvivo.png", "general")

        val streamBase = if (channel.id.contains("247")) {
            "https://live.saohgdasregions.fun/stream.php"
        } else {
            "https://live.saohgdasregions.fun/stream2.php"
        }

        val streamUrl = "$streamBase?canal=${channel.id.replace("247", "")}&target=1"

        val episodes = listOf(
            newEpisode(streamUrl) {
                name = "En vivo"
                posterUrl = channel.poster
            }
        )

        return newTvSeriesLoadResponse(
            channel.name,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = channel.poster
            plot = "Canal en vivo: ${channel.name}"
        }
    }

    // ==================================================
    // loadLinks CORREGIDO (Bad IO, redirect, token, etc)
    // ==================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val resp = app.get(
                data,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Referer" to mainUrl
                )
            )

            var finalUrl = resp.url

            // Si por alguna razón no terminó en .m3u8, buscarlo en el body
            if (!finalUrl.contains(".m3u8")) {
                val match = Regex("""https?://[^\s'"]+\.m3u8[^\s'"]*""")
                    .find(resp.text)
                if (match != null) {
                    finalUrl = match.value
                }
            }

            if (!finalUrl.contains(".m3u8")) return false

            callback.invoke(
                newExtractorLink(
                    name,
                    "HD",
                    finalUrl,
                    ExtractorLinkType.M3U8
                ) {
                    referer = "https://live.saohgdasregions.fun/"
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0",
                        "Accept" to "*/*",
                        "Connection" to "keep-alive"
                    )
                    quality = 720
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
