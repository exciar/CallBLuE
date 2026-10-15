package dev.blue.protocol

import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Session key exchange: the caller sends an RSA-2048 public key (QUERY_AES_KEY), the callee
 * answers with RSA-PKCS1(JSON {"key","iv"}) (NOTIFY_AES_KEY). Messages are then AES-128-GCM.
 */
object Crypto {
    /** XIAOMI reuses the exchanged IV for every message (required to interoperate); SECURE prefixes a fresh nonce. */
    enum class Mode { XIAOMI, SECURE }

    private const val RSA = "RSA/ECB/PKCS1Padding"
    private const val AES = "AES/GCM/NoPadding"
    private const val NONCE = 12
    private val random = SecureRandom()
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    class Channel(key: ByteArray, private val iv: ByteArray, val mode: Mode) {
        private val key = SecretKeySpec(key, "AES")

        /** Shown on both call screens; differs if someone relayed the key exchange. */
        val checkCode: String = MessageDigest.getInstance("SHA-256").digest(key)
            .let { ((it[0].toLong() and 0xFF shl 24) or (it[1].toLong() and 0xFF shl 16) or (it[2].toLong() and 0xFF shl 8) or (it[3].toLong() and 0xFF)) % 10000 }
            .toString().padStart(4, '0')

        fun seal(plain: ByteArray): ByteArray = when (mode) {
            Mode.XIAOMI -> gcm(Cipher.ENCRYPT_MODE, iv, plain)
            Mode.SECURE -> ByteArray(NONCE).also(random::nextBytes).let { it + gcm(Cipher.ENCRYPT_MODE, it, plain) }
        }

        /** Returns null when the data fails authentication. */
        fun open(sealed: ByteArray): ByteArray? = runCatching {
            when (mode) {
                Mode.XIAOMI -> gcm(Cipher.DECRYPT_MODE, iv, sealed)
                Mode.SECURE -> gcm(Cipher.DECRYPT_MODE, sealed.copyOfRange(0, NONCE), sealed.copyOfRange(NONCE, sealed.size))
            }
        }.getOrNull()

        private fun gcm(op: Int, nonce: ByteArray, data: ByteArray): ByteArray =
            Cipher.getInstance(AES).run { init(op, key, GCMParameterSpec(128, nonce)); doFinal(data) }
    }

    class Requester {
        private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()

        /** Unwrapped Base64 of the X.509 SubjectPublicKeyInfo. */
        val publicKey: String = b64e.encodeToString(pair.public.encoded)

        fun accept(notifyPayload: String, mode: Mode): Channel {
            val json = JSONObject(String(Cipher.getInstance(RSA).run { init(Cipher.DECRYPT_MODE, pair.private); doFinal(b64d.decode(notifyPayload)) }))
            return Channel(b64d.decode(json.getString("key")), b64d.decode(json.getString("iv")), mode)
        }
    }

    /** Callee side: returns the NOTIFY_AES_KEY payload and the resulting channel. */
    fun respond(publicKey: String, mode: Mode): Pair<String, Channel> {
        val key = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(NONCE).also(random::nextBytes)
        val json = JSONObject().put("key", b64e.encodeToString(key)).put("iv", b64e.encodeToString(iv)).toString()
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(b64d.decode(publicKey)))
        val sealed = Cipher.getInstance(RSA).run { init(Cipher.ENCRYPT_MODE, pub); doFinal(json.toByteArray()) }
        return b64e.encodeToString(sealed) to Channel(key, iv, mode)
    }
}
