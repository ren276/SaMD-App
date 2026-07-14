package com.example.samdapp.domain.model

import java.time.Instant

enum class AttachmentType { IMAGE, VIDEO, AUDIO, AFFECTED_AREA_PHOTO }

data class Attachment(
    val id: String,
    val consultationId: String,
    val type: AttachmentType,
    val uri: String,
    val createdAt: Instant,
)
