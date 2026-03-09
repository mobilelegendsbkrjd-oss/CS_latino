package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.google.gson.Gson

class Tlnovelas : MainAPI() {

    override var mainUrl = "https://ww2.tlnovelas.net"
    override var name = "Tlnovelas"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Últimos Capítulos",
        "gratis/telenovelas/" to "Ver Telenovelas"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val url = if (page <= 1)
            "$mainUrl/${request.data}"
        else
            "$mainUrl/${request.data}/page/$page"

        val document = app.get(url).document

        val home = document
            .select(".vk-poster, .ani-card, .p-content, .ani-txt")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home, true)
    }

    private fun Element.toSearchResult(): SearchResponse {

        val title =
            selectFirst(".ani-txt, .p-title, .vk-info p")?.text()
                ?: selectFirst("a")?.attr("title")
                ?: ""

        var href = selectFirst("a")?.attr("href") ?: ""

        val poster = selectFirst("img")?.attr("src")

        if (href.contains("/ver/")) {

            val slug = href
                .removeSuffix("/")
                .substringAfterLast("/")
                .replace(Regex("(?i)-capitulo-\\d+|-capítulo-\\d+"), "")

            href = "$mainUrl/novela/$slug/"
        }

        return newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val url = "$mainUrl/buscar/?q=$query"

        return app.get(url)
            .document
            .select(".vk-poster, .ani-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document

        val novelaLink = document
            .selectFirst("a[href*='/novela/']")
            ?.attr("href")

        val finalDoc =
            if (url.contains("/ver/") && novelaLink != null)
                app.get(novelaLink).document
            else
                document

        val title =
            finalDoc.selectFirst("h1.card-title, .vk-title-main, h1")
                ?.text()
                ?.replace(Regex("(?i)Capitulos de|Ver"), "")
                ?.trim()
                ?: "Telenovela"

        val poster =
            finalDoc.selectFirst("meta[property='og:image']")
                ?.attr("content")
                ?: finalDoc.selectFirst(".ani-img img")
                    ?.attr("src")

        val episodes = finalDoc
            .select("a[href*='/ver/']")
            .map {

                val epUrl = it.attr("href")

                val epName = it.text()
                    .replace(title, "", true)
                    .replace(Regex("(?i)Ver|Capitulo|Capítulo"), "")
                    .trim()

                newEpisode(epUrl) {
                    name = if (epName.isEmpty()) "Capítulo"
                    else "Capítulo $epName"
                }
            }
            .distinctBy { it.data }
            .reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {

            this.posterUrl = poster

            this.plot =
                finalDoc.selectFirst(".card-text, .ani-description")
                    ?.text()
        }
    }

    private fun decodeVideoUrl(encoded: String): String {

        return try {

            val parts = encoded.split("|")

            if (parts.size == 2) {

                val encodedStr = parts[0]
                val key = parts[1].toInt()

                val decodedChars = mutableListOf<Char>()

                for (i in encodedStr.indices) {

                    val charCode =
                        encodedStr[i].code - key - i

                    decodedChars.add(charCode.toChar())
                }

                URLDecoder.decode(
                    decodedChars.joinToString(""),
                    "UTF-8"
                )

            } else encoded

        } catch (_: Exception) {
            encoded
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val response = app.get(data).text

        val videoLinks = mutableSetOf<String>()

        Regex("""e\[(\d+)\]\s*=\s*['"]([^'"]+)['"]""")
            .findAll(response)
            .forEach {

                val decoded =
                    decodeVideoUrl(it.groupValues[2])

                if (decoded.startsWith("http"))
                    videoLinks.add(decoded)
            }

        Regex("""var\s+e\s*=\s*\[(.*?)\]""")
            .find(response)
            ?.groupValues
            ?.get(1)
            ?.split(",")
            ?.forEach {

                val decoded =
                    decodeVideoUrl(it.trim().trim('\'', '"'))

                if (decoded.startsWith("http"))
                    videoLinks.add(decoded)
            }

        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(response)
            .forEach {

                val link = it.groupValues[1]

                if (!link.contains("google"))
                    videoLinks.add(link)
            }

        Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
            .findAll(response)
            .forEach {

                videoLinks.add(it.groupValues[1])
            }

        var success = false

        videoLinks.forEach { link ->

            try {

                if (UniversalResolver.resolve(
                        link,
                        data,
                        subtitleCallback,
                        callback
                    )
                ) {
                    success = true
                }

            } catch (_: Exception) {}

        }

        return success || videoLinks.isNotEmpty()
    }
}

