package com.example.samdapp.data.local.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.samdapp.data.local.AppDatabase
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Generates a random SQLCipher database passphrase and stores it encrypted with a
 * non-exportable Android Keystore AES key. The passphrase itself never touches disk
 * in plaintext; the ciphertext is useless without the Keystore-resident key.
 */
class DatabasePassphraseProvider(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun getOrCreatePassphrase(): ByteArray {
        val storedIv = prefs.getString(KEY_IV, null)
        val storedCiphertext = prefs.getString(KEY_CIPHERTEXT, null)
        if (storedIv != null && storedCiphertext != null) {
            try {
                return decrypt(Base64.decode(storedIv, Base64.NO_WRAP), Base64.decode(storedCiphertext, Base64.NO_WRAP))
            } catch (e: GeneralSecurityException) {
                // Stored ciphertext no longer matches the Keystore key — e.g. this
                // SharedPreferences file was restored from a device-transfer/backup that
                // doesn't carry the non-exportable Keystore key with it. The old database
                // is unreadable without the lost passphrase, so drop both and start clean
                // instead of crashing on every launch.
                prefs.edit().clear().apply()
                deleteDatabaseFiles()
            }
        }

        val passphrase = ByteArray(PASSPHRASE_LENGTH_BYTES)
        SecureRandom().nextBytes(passphrase)
        val (iv, ciphertext) = encrypt(passphrase)
        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
        return passphrase
    }

    private fun getOrCreateSecretKey(): SecretKey {
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun deleteDatabaseFiles() {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            context.getDatabasePath(AppDatabase.DATABASE_NAME + suffix).delete()
        }
    }

    private fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        return cipher.iv to cipher.doFinal(plaintext)
    }

    private fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "samd_db_passphrase_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val PASSPHRASE_LENGTH_BYTES = 32
        const val PREFS_NAME = "samd_db_security_prefs"
        const val KEY_IV = "db_passphrase_iv"
        const val KEY_CIPHERTEXT = "db_passphrase_ciphertext"
    }
}
