package dev.fluentmai.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class UploadTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var divingFishToken: String
        get() = read(DIVING_FISH_TOKEN_KEY)
        set(value) = write(DIVING_FISH_TOKEN_KEY, value)

    var lxnsToken: String
        get() = read(LXNS_TOKEN_KEY)
        set(value) = write(LXNS_TOKEN_KEY, value)

    @Synchronized
    private fun read(preferenceKey: String): String {
        val payload = preferences.getString(preferenceKey, null) ?: return ""
        return runCatching { UploadTokenCipher.decrypt(payload, getOrCreateSecretKey()) }
            .getOrDefault("")
    }

    @Synchronized
    private fun write(preferenceKey: String, value: String) {
        if (value.isEmpty()) {
            preferences.edit().remove(preferenceKey).apply()
            return
        }
        val payload = UploadTokenCipher.encrypt(value, getOrCreateSecretKey())
        preferences.edit().putString(preferenceKey, payload).apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "fluentmai_encrypted_upload_tokens"
        const val DIVING_FISH_TOKEN_KEY = "diving_fish_import_token"
        const val LXNS_TOKEN_KEY = "lxns_user_token"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "fluentmai.upload-token-key.v1"
    }
}

internal object UploadTokenCipher {
    private const val PAYLOAD_VERSION = "v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    fun encrypt(value: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            // Android Keystore requires the provider to generate the encryption IV.
            init(Cipher.ENCRYPT_MODE, key)
        }
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return listOf(PAYLOAD_VERSION, encode(cipher.iv), encode(ciphertext)).joinToString(":")
    }

    fun decrypt(payload: String, key: SecretKey): String {
        val parts = payload.split(':')
        require(parts.size == 3 && parts[0] == PAYLOAD_VERSION) { "Unsupported token payload" }
        val iv = decode(parts[1])
        require(iv.size == IV_LENGTH_BYTES) { "Invalid token payload IV" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        }
        return cipher.doFinal(decode(parts[2])).toString(Charsets.UTF_8)
    }

    private fun encode(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}
