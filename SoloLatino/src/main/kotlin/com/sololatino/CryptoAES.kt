package com.sololatino

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoAES {

    fun decrypt(data: String, key: String): String? {
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            val secretKey = SecretKeySpec(key.toByteArray(), "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decoded = Base64.decode(data, Base64.DEFAULT)
            String(cipher.doFinal(decoded))
        } catch (e: Exception) {
            null
        }
    }

    fun decryptCbcIV(data: String, key: String): String? {
        return try {
            val decoded = Base64.decode(data, Base64.DEFAULT)

            val iv = decoded.copyOfRange(0, 16)
            val content = decoded.copyOfRange(16, decoded.size)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(key.toByteArray(), "AES")
            val ivSpec = IvParameterSpec(iv)

            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            String(cipher.doFinal(content))
        } catch (e: Exception) {
            null
        }
    }
}