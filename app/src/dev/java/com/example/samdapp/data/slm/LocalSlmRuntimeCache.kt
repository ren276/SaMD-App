package com.example.samdapp.data.slm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * What an engine start found in the derived runtime cache (on-device SLM memo 6.3, C-2).
 *
 * The runtime writes derived files (the GPU program cache, about 1.6 GB at S2) into its cache
 * directory and keys them by name, mtime and size, not by digest, so the artifact digest does not
 * cover them. A manifest written at every clean release pins them. Threat model: corruption (a
 * crash mid-write, a storage fault), not an attacker with app-private write access, who could
 * rewrite the manifest too.
 */
enum class LocalSlmCacheCheck {
    /** No manifest and nothing in the directory: a cold start. */
    EMPTY,

    /** Every listed file matched in size and sha256, and nothing unlisted was there. */
    VERIFIED,

    /** A manifest existed and the directory did not match it: both deleted, cold start. */
    DISCARDED_MISMATCH,

    /** Files with no manifest, so the last session did not end in a clean release: deleted, cold start. */
    DISCARDED_NO_MANIFEST,
}

/**
 * Runs before every engine start. Anything that does not match the manifest exactly deletes the
 * whole directory and the manifest, so the runtime cold-starts from the verified artifact alone.
 *
 * @throws IOException when a discard cannot be completed: a cache that could not be cleared must
 *   not be handed to the runtime.
 */
internal fun verifyRuntimeCache(dir: File, manifest: File): LocalSlmCacheCheck {
    tempFileFor(manifest).delete()
    val present = filesUnder(dir)
    if (!manifest.exists()) {
        if (present.isEmpty()) return LocalSlmCacheCheck.EMPTY
        discard(dir, manifest)
        return LocalSlmCacheCheck.DISCARDED_NO_MANIFEST
    }
    val listed = readManifest(manifest)
    val matches = listed != null && listed.keys == present.keys && listed.all { (path, entry) ->
        val file = present.getValue(path)
        try {
            file.length() == entry.size && sha256Hex(file) == entry.sha256
        } catch (e: IOException) {
            false
        }
    }
    if (matches) return LocalSlmCacheCheck.VERIFIED
    discard(dir, manifest)
    return LocalSlmCacheCheck.DISCARDED_MISMATCH
}

/**
 * Runs at every clean release, after the engine closed normally. The old manifest is deleted
 * first and the new one is written to a temp file, synced, then renamed over the target, so a
 * crash at any point leaves either no manifest or a complete one, never a partial one.
 *
 * @param beforeRename the crash point between the write and the rename; tests throw from it.
 */
internal fun writeRuntimeCacheManifest(dir: File, manifest: File, beforeRename: () -> Unit = {}) {
    if (manifest.exists() && !manifest.delete()) throw IOException("old manifest not deletable")
    val json = buildJsonObject {
        putJsonArray("files") {
            filesUnder(dir).forEach { (path, file) ->
                addJsonObject {
                    put("path", path)
                    put("size", file.length())
                    put("sha256", sha256Hex(file))
                }
            }
        }
    }.toString()
    val temp = tempFileFor(manifest)
    FileOutputStream(temp).use { out ->
        out.write(json.toByteArray(Charsets.UTF_8))
        out.fd.sync()
    }
    beforeRename()
    Files.move(temp.toPath(), manifest.toPath(), StandardCopyOption.ATOMIC_MOVE)
}

internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private class ManifestEntry(val size: Long, val sha256: String)

/** Every regular file under [dir], keyed by its path relative to [dir] with `/` separators. */
private fun filesUnder(dir: File): Map<String, File> =
    dir.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(dir).invariantSeparatorsPath }

/** Null for anything unreadable, malformed or duplicated: the caller treats null as a mismatch. */
private fun readManifest(manifest: File): Map<String, ManifestEntry>? = try {
    val entries = Json.parseToJsonElement(manifest.readText()).jsonObject.getValue("files").jsonArray.map {
        val entry = it.jsonObject
        entry.getValue("path").jsonPrimitive.content to
            ManifestEntry(entry.getValue("size").jsonPrimitive.long, entry.getValue("sha256").jsonPrimitive.content)
    }
    entries.toMap().takeIf { it.size == entries.size }
} catch (e: Exception) {
    null
}

private fun discard(dir: File, manifest: File) {
    if (!dir.deleteRecursively()) throw IOException("runtime cache not deletable")
    if (manifest.exists() && !manifest.delete()) throw IOException("manifest not deletable")
}

private fun tempFileFor(manifest: File) = File(manifest.path + ".tmp")
