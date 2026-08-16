"""attachments: consultation media, metadata only in v1.

The device's AttachmentEntity.uri is a content:// or file:// URI that means nothing off that
device. It is stored here as local_uri alongside blob_status and a nullable object_key, so the
row is honest about what the server does and does not hold.

Binary upload is out of scope for v1 (api-contract.md section 10). blob_status is always
NOT_UPLOADED. Object storage is a separate design, and pretending otherwise by naming the column
`uri` would suggest the server can fetch something it cannot.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Index, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.models.enums import AttachmentType, BlobStatus
from app.models.mixins import CLIENT_ID_LENGTH, SyncMixin, enum_check, sync_state_check


class Attachment(Base, SyncMixin):
    __tablename__ = "attachments"
    __table_args__ = (
        sync_state_check("attachments"),
        enum_check("type", AttachmentType, "ck_attachments_type"),
        enum_check("blob_status", BlobStatus, "ck_attachments_blob_status"),
        Index("ix_attachments_consultation_id", "consultation_id"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    consultation_id: Mapped[str] = mapped_column(
        String(CLIENT_ID_LENGTH),
        ForeignKey("consultations.id", ondelete="RESTRICT"),
        nullable=False,
    )
    type: Mapped[str] = mapped_column(String(30), nullable=False)
    local_uri: Mapped[str] = mapped_column(Text, nullable=False)
    blob_status: Mapped[str] = mapped_column(
        String(20),
        nullable=False,
        default=BlobStatus.NOT_UPLOADED.value,
        server_default=BlobStatus.NOT_UPLOADED.value,
    )
    # Reserved for object storage. Null in v1, present so adding uploads is not a migration.
    object_key: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
