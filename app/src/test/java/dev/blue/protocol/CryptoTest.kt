package dev.blue.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoTest {

    private val msg = "hello".toByteArray()

    private fun pair(mode: Crypto.Mode): Pair<Crypto.Channel, Crypto.Channel> {
        val requester = Crypto.Requester()
        val (notify, responderChannel) = Crypto.respond(requester.publicKey, mode)
        return requester.accept(notify, mode) to responderChannel
    }

    @Test
    fun keyExchangeGivesBothSidesTheSameChannel() {
        val (a, b) = pair(Crypto.Mode.SECURE)
        assertArrayEquals(msg, b.open(a.seal(msg)))
        assertArrayEquals(msg, a.open(b.seal(msg)))
    }

    @Test
    fun publicKeyIsUnwrappedBase64X509() {
        val key = Crypto.Requester().publicKey
        assertFalse(key.contains('\n'))
        assertEquals(294, Base64.getDecoder().decode(key).size) // RSA-2048 SubjectPublicKeyInfo
    }

    @Test
    fun acceptsKeyJsonWithEscapedSlashes() {
        // Android's org.json writes "/" as "\/"; Base64 keys often contain "/".
        val key = ByteArray(16) { 0xFF.toByte() } // Base64 "/////////////////////w=="
        val iv = ByteArray(12)
        val b64 = Base64.getEncoder()
        val json = """{"key":"${b64.encodeToString(key).replace("/", "\\/")}","iv":"${b64.encodeToString(iv)}"}"""
        val requester = Crypto.Requester()
        val channel = requester.accept(rsaEncrypt(requester.publicKey, json), Crypto.Mode.XIAOMI)
        assertArrayEquals(aesGcm(key, iv, msg), channel.seal(msg))
    }

    private fun rsaEncrypt(publicKeyB64: String, text: String): String {
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, pub) }
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray()))
    }

    @Test
    fun xiaomiModeIsPlainGcmWithTheSharedIv() {
        val (a, _) = pair(Crypto.Mode.XIAOMI)
        assertArrayEquals(a.seal(msg), a.seal(msg)) // same IV every time, as the reference does
        assertEquals(msg.size + 16, a.seal(msg).size)
    }

    @Test
    fun secureModeUsesAFreshNoncePerMessage() {
        val (a, b) = pair(Crypto.Mode.SECURE)
        val c1 = a.seal(msg)
        val c2 = a.seal(msg)
        assertFalse(c1.contentEquals(c2))
        assertEquals(12 + msg.size + 16, c1.size)
        assertArrayEquals(msg, b.open(c2))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val (a, b) = pair(Crypto.Mode.SECURE)
        val c = a.seal(msg).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(b.open(c))
    }

    @Test
    fun checkCodeIsFourDigitsAndMatchesOnBothSides() {
        val (a, b) = pair(Crypto.Mode.SECURE)
        assertEquals(a.checkCode, b.checkCode)
        assertTrue(a.checkCode.matches(Regex("\\d{4}")))
    }

    private fun aesGcm(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            doFinal(plain)
        }
}
