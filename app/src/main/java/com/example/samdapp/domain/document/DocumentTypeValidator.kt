package com.example.samdapp.domain.document

/**
 * H-18, Build 3a. The whitelist of clinical document file types this app will store, PDF/JPEG/PNG
 * only (memo B6). [mimeType] and [extension] are the VALIDATED values — what actually gets stored
 * on [com.example.samdapp.domain.model.ConsultationDocument] and used to build both the canonical
 * name and the storage key — never the picker's claimed MIME type or the source filename.
 */
enum class ValidatedDocumentType(val mimeType: String, val extension: String) {
    PDF("application/pdf", "pdf"),
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
}

/**
 * Pure byte-content sniffing, no I/O, no Android dependency, so it's unit-testable directly
 * against byte arrays. Detects by MAGIC BYTES only, never by file extension and never by
 * `ContentResolver.getType()` — a content provider's claimed MIME type is untrusted input.
 */
object DocumentTypeValidator {
    private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // "%PDF-"
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_MAGIC =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** The longest magic prefix above (PNG's 8 bytes) — callers should read at least this many
     *  header bytes before calling [detect]. */
    const val REQUIRED_HEADER_BYTES = 8

    /** Null when [headerBytes] matches none of the whitelisted magic-byte signatures — the
     *  caller's rejection path, not a fallback to the claimed type. */
    fun detect(headerBytes: ByteArray): ValidatedDocumentType? = when {
        headerBytes.startsWith(PDF_MAGIC) -> ValidatedDocumentType.PDF
        headerBytes.startsWith(JPEG_MAGIC) -> ValidatedDocumentType.JPEG
        headerBytes.startsWith(PNG_MAGIC) -> ValidatedDocumentType.PNG
        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { i -> this[i] == prefix[i] }
}
