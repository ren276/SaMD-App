package com.example.samdapp.domain.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** H-18, Build 3a: magic-byte validation must never trust extension or claimed MIME type. */
class DocumentTypeValidatorTest {

    @Test
    fun `PDF magic bytes are detected regardless of trailing content`() {
        val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34) // "%PDF-1.4"
        assertEquals(ValidatedDocumentType.PDF, DocumentTypeValidator.detect(bytes))
    }

    @Test
    fun `JPEG magic bytes are detected`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0)
        assertEquals(ValidatedDocumentType.JPEG, DocumentTypeValidator.detect(bytes))
    }

    @Test
    fun `PNG magic bytes are detected`() {
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        assertEquals(ValidatedDocumentType.PNG, DocumentTypeValidator.detect(bytes))
    }

    /** A mis-typed file: a plain-text file whose header matches no whitelisted signature must be
     *  rejected outright, not fall back to any type. */
    @Test
    fun `a file with no matching magic bytes is rejected — not misidentified as a supported type`() {
        val plainText = "Not a real document, just text".toByteArray().copyOf(8)
        assertNull(DocumentTypeValidator.detect(plainText))
    }

    /** A picker/renamed file claiming to be a PDF (by extension or claimed MIME) but whose actual
     *  bytes are a JPEG must resolve to the JPEG signature, not the claim — the caller layer is
     *  responsible for treating that resolved-vs-claimed mismatch as a rejection. */
    @Test
    fun `detection is driven entirely by content, not by what a caller claims`() {
        val jpegBytesNamedAsPdf = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0)
        assertEquals(ValidatedDocumentType.JPEG, DocumentTypeValidator.detect(jpegBytesNamedAsPdf))
    }

    @Test
    fun `a truncated header shorter than any signature is rejected, not partially matched`() {
        val tooShort = byteArrayOf(0x25, 0x50, 0x44) // "%PD", not enough for "%PDF-"
        assertNull(DocumentTypeValidator.detect(tooShort))
    }

    @Test
    fun `an empty byte array is rejected`() {
        assertNull(DocumentTypeValidator.detect(ByteArray(0)))
    }
}
