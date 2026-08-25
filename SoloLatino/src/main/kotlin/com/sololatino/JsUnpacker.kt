package com.sololatino

import android.util.Log
import java.util.regex.Pattern
import kotlin.math.pow

class JsUnpacker(packedJS: String?) {
    private var packedJS: String? = null

    fun detect(): Boolean {
        val js = packedJS ?: return false
        val p = Pattern.compile("eval\\(function\\(p,a,c,k,e,[rd]")
        val m = p.matcher(js.replace(" ", ""))
        return m.find()
    }

    fun unpack(): String? {
        val js = packedJS ?: return null
        try {
            // =========================
            // INTENTO 1: Formato estándar P.A.C.K.E.R.
            // =========================
            var p = Pattern.compile(
                """\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""",
                Pattern.DOTALL
            )
            var m = p.matcher(js)
            if (m.find() && m.groupCount() == 4) {
                return unpackPacker(m)
            }

            // =========================
            // INTENTO 2: Formato con función anónima
            // =========================
            p = Pattern.compile(
                """\}\s*\(function\(p,a,c,k,e,d\)\{(.*?)\}\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""",
                Pattern.DOTALL
            )
            m = p.matcher(js)
            if (m.find() && m.groupCount() == 5) {
                val payload = m.group(2)
                val radixStr = m.group(3)
                val countStr = m.group(4)
                val symtab = m.group(5).split("\\|".toRegex()).toTypedArray()
                return unpackPayload(payload, radixStr, countStr, symtab)
            }

            // =========================
            // INTENTO 3: Buscar directamente var links={...}
            // =========================
            val linksMatch = Regex("""var\s+links\s*=\s*(\{[^}]+\})""").find(js)
            if (linksMatch != null) {
                return linksMatch.value
            }

            // =========================
            // INTENTO 4: Buscar enlaces directamente en el script
            // =========================
            val linkRegex = Regex("""https?://[^\s"'<>]+\.(m3u8|mp4)[^\s"'<>]*""")
            val links = linkRegex.findAll(js).map { it.value }.toList()
            if (links.isNotEmpty()) {
                return links.joinToString("\n")
            }

        } catch (e: Exception) {
            Log.e("JsUnpacker", "unpack: ", e)
        }
        return null
    }

    private fun unpackPacker(m: java.util.regex.Matcher): String? {
        val payload = m.group(1).replace("\\'", "'")
        val radixStr = m.group(2)
        val countStr = m.group(3)
        val symtab = m.group(4).split("\\|".toRegex()).toTypedArray()
        return unpackPayload(payload, radixStr, countStr, symtab)
    }

    private fun unpackPayload(payload: String, radixStr: String, countStr: String, symtab: Array<String>): String? {
        try {
            var radix = 36
            var count = 0
            try {
                radix = radixStr.toInt()
            } catch (_: Exception) {
            }
            try {
                count = countStr.toInt()
            } catch (_: Exception) {
            }
            if (symtab.size != count) {
                // Si no coinciden, intentar igual
                if (symtab.size < count) {
                    // Algunos casos tienen symtab más corto
                }
            }
            val unbase = Unbase(radix)
            val p = Pattern.compile("\\b\\w+\\b")
            val m = p.matcher(payload)
            val decoded = StringBuilder(payload)
            var replaceOffset = 0
            while (m.find()) {
                val word = m.group(0)
                val x = try {
                    unbase.unbase(word)
                } catch (_: Exception) {
                    continue
                }
                var value: String? = null
                if (x < symtab.size && x >= 0) {
                    value = symtab[x]
                }
                if (value != null && value.isNotEmpty()) {
                    decoded.replace(m.start() + replaceOffset, m.end() + replaceOffset, value)
                    replaceOffset += value.length - word.length
                }
            }
            return decoded.toString()
        } catch (e: Exception) {
            Log.e("JsUnpacker", "unpackPayload: ", e)
            return null
        }
    }

    private inner class Unbase(private val radix: Int) {
        private val ALPHABET_62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private val ALPHABET_95 =
            " !\"#$%&\\'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
        private var alphabet: String? = null
        private var dictionary: HashMap<String, Int>? = null

        fun unbase(str: String): Int {
            var ret = 0
            if (alphabet == null) {
                ret = str.toInt(radix)
            } else {
                val tmp = StringBuilder(str).reverse().toString()
                for (i in tmp.indices) {
                    ret += (radix.toDouble().pow(i.toDouble()) * dictionary!![tmp.substring(i, i + 1)]!!).toInt()
                }
            }
            return ret
        }

        init {
            if (radix > 36) {
                when {
                    radix < 62 -> {
                        alphabet = ALPHABET_62.substring(0, radix)
                    }
                    radix in 63..94 -> {
                        alphabet = ALPHABET_95.substring(0, radix)
                    }
                    radix == 62 -> {
                        alphabet = ALPHABET_62
                    }
                    radix == 95 -> {
                        alphabet = ALPHABET_95
                    }
                }
                dictionary = HashMap(95)
                for (i in 0 until alphabet!!.length) {
                    dictionary!![alphabet!!.substring(i, i + 1)] = i
                }
            }
        }
    }

    init {
        this.packedJS = packedJS
    }

    companion object {
        val c = listOf(
            0x63, 0x6f, 0x6d, 0x2e, 0x67, 0x6f, 0x6f, 0x67, 0x6c, 0x65, 0x2e, 0x61,
            0x6e, 0x64, 0x72, 0x6f, 0x69, 0x64, 0x2e, 0x67, 0x6d, 0x73, 0x2e, 0x61,
            0x64, 0x73, 0x2e, 0x4d, 0x6f, 0x62, 0x69, 0x6c, 0x65, 0x41, 0x64, 0x73
        )
        val z = listOf(
            0x63, 0x6f, 0x6d, 0x2e, 0x66, 0x61, 0x63, 0x65, 0x62, 0x6f, 0x6f, 0x6b,
            0x2e, 0x61, 0x64, 0x73, 0x2e, 0x41, 0x64
        )

        fun String.load(): String? {
            return try {
                var load = this
                for (q in c.indices) {
                    if (c[q % 4] > 270) {
                        load += c[q % 3]
                    } else {
                        load += c[q].toChar()
                    }
                }
                Class.forName(load.substring(load.length - c.size, load.length)).name
            } catch (_: Exception) {
                try {
                    var f = c[2].toChar().toString()
                    for (w in z.indices) {
                        f += z[w].toChar()
                    }
                    return Class.forName(f.substring(0b001, f.length)).name
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}