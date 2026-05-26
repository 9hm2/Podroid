/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Model file management — list installed, download (HuggingFace resolve
 * URL), verify SHA-256 if the catalogue entry pins one, delete. Models
 * live in getExternalFilesDir/models so uninstall reclaims the disk.
 *
 * Downloads stream to a .tmp file and atomically rename on success; a
 * killed download leaves nothing usable behind, only the .tmp which the
 * next attempt overwrites.
 */
package com.excp.podroid.ai

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File by lazy {
        File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    }

    private val _progress = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 16)
    val progress: SharedFlow<DownloadEvent> = _progress.asSharedFlow()

    /** Returns the model file iff present and ≥1 MiB (rejects truncated). */
    fun fileFor(modelId: String): File? {
        val f = File(dir, "$modelId.gguf")
        return if (f.exists() && f.length() > 1L * 1024 * 1024) f else null
    }

    fun isInstalled(modelId: String): Boolean = fileFor(modelId) != null

    /** True if the installed file's recorded source URL no longer matches
     *  the catalogue entry — happens when we swap a model's URL (e.g. the
     *  Q4_K_M → Q4_0 catalogue change for Adreno compatibility). The
     *  picker UI can offer a "Re-download" affordance in that case.
     *
     *  Files downloaded before the .url stamp was introduced have no
     *  stamp at all — treat those as stale too. Users who upgrade the
     *  app and still have pre-stamping models on disk see the
     *  re-download hint and can recover with one tap. After they
     *  accept, the new download writes a stamp and future calls are
     *  exact-URL-match. */
    fun isStale(spec: ModelSpec): Boolean {
        val f = fileFor(spec.id) ?: return false
        val stampFile = File(f.parentFile, "${spec.id}.url")
        if (!stampFile.exists()) return true   // legacy file, no provenance
        return stampFile.readText().trim() != spec.downloadUrl
    }

    /** Installed catalogue intersection — used by the picker. */
    fun installedCatalogue(): List<ModelSpec> =
        ModelCatalogue.all.filter { isInstalled(it.id) }

    /** Streamed download with progress events. Throws on HTTP failure or
     *  SHA-256 mismatch; cancellation deletes the .tmp. Idempotent: a
     *  fully-downloaded model is a no-op. */
    suspend fun download(spec: ModelSpec): File = withContext(Dispatchers.IO) {
        val dest = File(dir, spec.fileName)
        if (dest.exists() && dest.length() > 1L * 1024 * 1024) {
            _progress.emit(DownloadEvent.Done(spec.id, dest))
            return@withContext dest
        }
        val tmp = File(dir, spec.fileName + ".tmp")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(spec.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Podroid/AI-Engine")
            }
            if (conn.responseCode !in 200..299) {
                throw RuntimeException("HTTP ${conn.responseCode} from ${spec.downloadUrl}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var lastEmit = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        // Throttle progress emissions to every 1 MiB (~20-30/sec
                        // would otherwise flood the UI; ZRLE/touch don't need
                        // sub-MiB granularity here).
                        if (written - lastEmit >= 1L * 1024 * 1024) {
                            lastEmit = written
                            _progress.tryEmit(DownloadEvent.Progress(spec.id, written, total))
                        }
                    }
                }
            }
            // SHA verification only if the catalogue pinned one. Self-hosted
            // models added later can leave sha256 = "" to skip the check.
            if (spec.sha256.isNotBlank()) {
                val gotHex = digest.digest().joinToString("") { "%02x".format(it) }
                if (!gotHex.equals(spec.sha256, ignoreCase = true)) {
                    throw RuntimeException("SHA-256 mismatch for ${spec.id}: expected ${spec.sha256}, got $gotHex")
                }
            }
            if (!tmp.renameTo(dest)) throw RuntimeException("rename ${tmp.name} → ${dest.name} failed")
            // Stamp the source URL so a future catalogue URL bump can be
            // detected (isStale) and the picker can offer a re-download
            // without making the user think to delete + reinstall.
            File(dir, "${spec.id}.url").writeText(spec.downloadUrl)
            _progress.emit(DownloadEvent.Done(spec.id, dest))
            dest
        } catch (e: CancellationException) {
            tmp.delete(); throw e
        } catch (e: Throwable) {
            tmp.delete()
            Log.e(TAG, "Model download failed (${spec.id})", e)
            _progress.emit(DownloadEvent.Failed(spec.id, e.message ?: e.javaClass.simpleName))
            throw e
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    fun delete(modelId: String): Boolean {
        val ok = File(dir, "$modelId.gguf").let { it.exists() && it.delete() }
        // Also drop the URL stamp — otherwise isStale() lies after a delete.
        File(dir, "$modelId.url").delete()
        return ok
    }

    fun totalDiskUsageBytes(): Long =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile }.sumOf { it.length() }

    sealed class DownloadEvent {
        abstract val modelId: String
        data class Progress(override val modelId: String, val written: Long, val total: Long) : DownloadEvent()
        data class Done(override val modelId: String, val file: File) : DownloadEvent()
        data class Failed(override val modelId: String, val reason: String) : DownloadEvent()
    }

    companion object { private const val TAG = "AiModelMgr" }
}
