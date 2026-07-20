package com.example.samdapp.presentation.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.samdapp.R

/** Institutional logo for the report header — `res/drawable-nodpi/logo.png`. Decoded once by the
 *  caller (`ReportScreen`/`ReportPdfExporter`); the renderer itself never touches resources. */
fun decodeReportLogo(context: Context): Bitmap? = runCatching {
    BitmapFactory.decodeResource(context.resources, R.drawable.logo)
}.getOrNull()

/** Decodes a consultation attachment's `content://`/`file://` URI to a [Bitmap] for the report's
 *  image blocks. Returns null on any failure (deleted file, permission revoked, corrupt data) —
 *  the renderer falls back to an "Image unavailable" placeholder rather than crashing the export. */
fun decodeAttachmentBitmap(context: Context, uriString: String): Bitmap? = runCatching {
    context.contentResolver.openInputStream(Uri.parse(uriString))?.use { BitmapFactory.decodeStream(it) }
}.getOrNull()

/** Mock handwritten-signature ink squiggle for the report footer — `assets/sign.png`. Decoded once
 *  by the caller, same posture as [decodeReportLogo]; null on failure just omits the image. */
fun decodeReportSignature(context: Context): Bitmap? = runCatching {
    context.assets.open("sign.png").use { BitmapFactory.decodeStream(it) }
}.getOrNull()
