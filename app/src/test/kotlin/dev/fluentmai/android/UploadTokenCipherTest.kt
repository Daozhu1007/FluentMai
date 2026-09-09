package dev.fluentmai.android

import javax.crypto.KeyGenerator
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
