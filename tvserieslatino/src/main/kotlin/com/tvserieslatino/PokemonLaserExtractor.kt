package com.tvserieslatino

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.json.JSONObject

class PokemonLaserExtractor : ExtractorApi() {

    override val name = "PokemonLaser"
    override val mainUrl = "https://pokemonlaserielatino.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {

        val key = url.substringAfter("/e/")

        val body = JSONObject(
            mapOf(
                "v" to key,
                "secure" to "0",
                "ver" to "4"
            )
        )

        val response = app.post(
            "$mainUrl/player/get_md5.php",
            json = body,
            headers = mapOf(
                "referer" to url,
                "x-requested-with" to "XMLHttpRequest"
            )
        ).text

        val json = JSONObject(response)

        val md5 = json.getString("md5")
        val videoId = json.getString("videoid")

        val m3u8 = "$mainUrl/m3u8/$md5/$videoId/master.txt"

        return M3u8Helper.generateM3u8(
            name,
            m3u8,
            url,
            headers = mapOf("referer" to mainUrl)
        )
    }
}