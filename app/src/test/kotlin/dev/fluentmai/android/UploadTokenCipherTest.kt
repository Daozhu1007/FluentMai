package dev.fluentmai.android

import javax.crypto.KeyGenerator
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UploadTokenCipherTest {
    private val key = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }

    @Test
    fun encryptedTokenRoundTripsWithoutPlaintextStorage() {
        val token = "private-import-token-123"
        val payload = UploadTokenCipher.encrypt(token, key)

        assertFalse(payload.contains(token))
        assertEquals(token, UploadTokenCipher.decrypt(payload, key))
    }

    @Test
    fun encryptingSameTokenUsesAUniqueIv() {
        val first = UploadTokenCipher.encrypt("same-token", key)
        val second = UploadTokenCipher.encrypt("same-token", key)

        assertNotEquals(first, second)
    }

    @Test
    fun stillReadsPreviouslySavedV1Payloads() {
        val iv = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val ciphertext = cipher.doFinal("existing-token".toByteArray(Charsets.UTF_8))
        val payload = "v1:${encoder.encodeToString(iv)}:${encoder.encodeToString(ciphertext)}"

        assertEquals("existing-token", UploadTokenCipher.decrypt(payload, key))
    }

    @Test
    fun modifiedCiphertextIsRejected() {
        val payload = UploadTokenCipher.encrypt("token", key)
        val ciphertextStart = payload.lastIndexOf(':') + 1
        val replacement = if (payload[ciphertextStart] == 'A') 'B' else 'A'
        val modified = payload.replaceRange(ciphertextStart, ciphertextStart + 1, replacement.toString())

        assertThrows(Exception::class.java) {
            UploadTokenCipher.decrypt(modified, key)
        }
    }
}
