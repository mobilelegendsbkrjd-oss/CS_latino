package com.tlnovelas
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.utils.JsUnpacker // Importación correcta

object StreamflixResolver {
    fun extractVideo(script: String): String? {
        Regex("sources:\\s*\\[\\{file:\\s*['\"](.*?)['\"]")
            .find(script)?.groupValues?.get(1)?.let { return it }
        Regex("file:\\s*['\"](https?://.*?)['\"]")
            .find(script)?.groupValues?.get(1)?.let { return it }
        Regex("var\\s*url\\s*=\\s*['\"](.*?)['\"]")
            .find(script)?.groupValues?.get(1)?.let { return it }
        Regex("window\\.location\\.href\\s*=\\s*['\"](.*?)['\"]")
            .find(script)?.groupValues?.get(1)?.let { return it }
        Regex("location\\.replace\\(['\"](.*?)['\"]")
            .find(script)?.groupValues?.get(1)?.let { return it }
        return null
    }
    suspend fun resolve(url: String, referer: String): String? {
        val doc: Document = app.get(url, referer = referer).document
        val scripts = doc.select("script")
        for (s in scripts) {
            val data = s.data()
            val direct = extractVideo(data)
            if (direct != null) return direct
            if (data.contains("eval(function(p,a,c,k,e,d")) {
                val unpacker = JsUnpacker(data) // Esto ahora funcionará
                if (unpacker.detect()) {
                    val unpacked = unpacker.unpack()
                    if (unpacked != null) {
                        val url = extractVideo(unpacked)
                        if (url != null) return url
                    }
                }
            }
        }
        return null
    }
}
