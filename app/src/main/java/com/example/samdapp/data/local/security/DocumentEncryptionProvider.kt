package com.example.samdapp.data.local.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** [sizeBytes] and [sha256] are measured over the PLAINTEXT as it streamed through, in the same
 *  pass as encryption — no second read of the file. */
data class DocumentEncryptResult(val sizeBytes: Long, val sha256: String)

/** Thrown by [DocumentEncryptionProvider.encryptToFile] when the source stream exceeds [maxBytes]
 *  — enforced DURING streaming, because a content provider can lie about a claimed length. The
 *  partially-written destination file is deleted before this is thrown. */
class DocumentTooLargeException(val maxBytes: Long) : Exception("Document exceeds the $maxBytes byte cap")

/** Thrown by [DocumentEncryptionProvider.decryptToStream] on a corrupt or tampered file (GCM's
 *  auth tag fails) or a file too short to hold a 12-byte IV. Never produces garbage output —
 *  GCM authentication fails closed. */
class DocumentDecryptionFailedException(cause: Throwable? = null) : Exception("Document could not be decrypted", cause)

/**
 * H-18, Build 3a. AES-256-GCM at rest for consultation-document bytes, under a Keystore key
 * SEPARATE from [DatabasePassphraseProvider]'s — a document-key rotation or loss must not take
 * the SQLCipher database with it. Key generation is IDENTICAL to
 * [DatabasePassphraseProvider.getOrCreateSecretKey]: same [KeyGenParameterSpec] shape, same
 * `BLOCK_MODE_GCM`/`ENCRYPTION_PADDING_NONE`/256-bit/non-exportable — only the alias differs.
 *
 * Per-file random IV, stored as the first 12 bytes of the ciphertext file (same convention
 * [DatabasePassphraseProvider] uses for its own stored ciphertext, just inline in the file rather
 * than a separate SharedPreferences field). GCM is authenticated: a corrupted or tampered file
 * fails to decrypt with [DocumentDecryptionFailedException] rather than silently producing
 * garbage that could be rendered as a clinical document.
 */
@Singleton
class DocumentEncryptionProvider @Inject constructor() {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Encrypts [input] to [destFile], writing the 12-byte IV first then the ciphertext.
     *  [maxBytes] is enforced while streaming, not just checked up front. On any failure
     *  (including [DocumentTooLargeException]), [destFile] is deleted — never left partially
     *  written. */
    fun encryptToFile(input: InputStream, destFile: File, maxBytes: Long): DocumentEncryptResult =
        encryptToFile(destFile, maxBytes) { plaintextSink -> input.copyTo(plaintextSink) }

    /**
     * Push-mode counterpart of the [InputStream] overload above, for producers that write rather
     * than being read: [android.graphics.pdf.PdfDocument.writeTo] takes an [OutputStream] and
     * offers no [InputStream], so the assembled PDF of Build 3b can only reach the cipher this
     * way. [writePlaintext] receives a sink that is measured, hashed, size-capped and encrypted
     * as it is written, so the whole document never has to exist as a `ByteArray` in memory
     * (H-18 memory discipline: a 20-page assembly must not materialise its own output).
     *
     * Identical guarantees to the pull overload, which now delegates here: same key, same
     * per-file random IV written as the first 12 bytes, plaintext [DocumentEncryptResult.sizeBytes]
     * and [DocumentEncryptResult.sha256] measured in the same single pass, and [destFile] deleted
     * on any failure rather than left partially written.
     */
    fun encryptToFile(destFile: File, maxBytes: Long, writePlaintext: (OutputStream) -> Unit): DocumentEncryptResult {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv

            var plainBytesWritten = 0L
            destFile.outputStream().use { fos ->
                fos.write(iv)
                CipherOutputStream(fos, cipher).use { cos ->
                    val measured = object : OutputStream() {
                        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                        override fun write(b: ByteArray, off: Int, len: Int) {
                            plainBytesWritten += len
                            if (plainBytesWritten > maxBytes) throw DocumentTooLargeException(maxBytes)
                            digest.update(b, off, len)
                            cos.write(b, off, len)
                        }
                    }
                    writePlaintext(measured)
                    measured.flush()
                }
            }

            val sha256Hex = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
            return DocumentEncryptResult(sizeBytes = plainBytesWritten, sha256 = sha256Hex)
        } catch (e: Throwable) {
            // Throwable, not Exception: a cancelled assembly coroutine unwinds through here as a
            // CancellationException, and a half-written ciphertext file must not survive it.
            destFile.delete()
            throw e
        }
    }

    /** Decrypts [srcFile]'s plaintext bytes into [output]. Throws
     *  [DocumentDecryptionFailedException] on a truncated IV or a failed GCM auth tag check —
     *  callers must surface this as an explicit "cannot be opened" error, never a blank view. */
    fun decryptToStream(srcFile: File, output: OutputStream) {
        try {
            srcFile.inputStream().use { fis ->
                val iv = ByteArray(GCM_IV_LENGTH_BYTES)
                var read = 0
                while (read < GCM_IV_LENGTH_BYTES) {
                    val n = fis.read(iv, read, GCM_IV_LENGTH_BYTES - read)
                    if (n == -1) throw DocumentDecryptionFailedException()
                    read += n
                }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                CipherInputStream(fis, cipher).use { cis -> cis.copyTo(output) }
            }
        } catch (e: DocumentDecryptionFailedException) {
            throw e
        } catch (e: Exception) {
            throw DocumentDecryptionFailedException(e)
        }
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

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "samd_document_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val GCM_IV_LENGTH_BYTES = 12
    }
}
