/*
 * Long-lived rootfs download/import worker.
 *
 * The previous implementation ran the copy in viewModelScope, so a process
 * pause + ViewModel destruction (e.g. the user backgrounding the app while a
 * 300 MB squashfs is mid-download) cancelled it. Backing the same state
 * machine with a @Singleton-scoped CoroutineScope means the download keeps
 * progressing in the background and the dialog picks the live progress up
 * the moment it's re-shown.
 *
 * The state is exposed as a StateFlow that any UI surface can collect; on
 * cold start it begins at Idle.
 */
package com.excp.podroid.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.excp.podroid.ui.vm.RootfsFileNameParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed class RootfsDownloadState {
    data object Idle : RootfsDownloadState()
    data class Copying(val bytesCopied: Long, val totalBytes: Long) : RootfsDownloadState()
    data class Failed(val message: String) : RootfsDownloadState()
    data class Done(val vmId: String, val name: String) : RootfsDownloadState()
}

@Singleton
class RootfsDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: VmRegistry,
) {
    // SupervisorJob() so a download crash doesn't tear down the scope and
    // block future downloads; Dispatchers.IO because every step here is
    // blocking I/O (HTTP read, SAF stream, file write).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<RootfsDownloadState>(RootfsDownloadState.Idle)
    val state: StateFlow<RootfsDownloadState> = _state.asStateFlow()

    private var current: Job? = null

    fun reset() {
        if (current?.isActive == true) return
        _state.value = RootfsDownloadState.Idle
    }

    fun importFromUri(
        src: Uri,
        name: String,
        distro: String,
        distroVersion: String,
        initSystem: String,
        storageSizeGb: Int,
    ) {
        if (current?.isActive == true) return
        current = scope.launch {
            runImport(name, distro, distroVersion, initSystem, storageSizeGb) { dest, progress ->
                val totalBytes = runCatching {
                    context.contentResolver.openFileDescriptor(src, "r")?.use { it.statSize }
                }.getOrNull() ?: -1L
                val input = context.contentResolver.openInputStream(src)
                    ?: throw IllegalStateException("ContentResolver returned no stream for $src")
                input.use { i ->
                    dest.outputStream().use { o ->
                        copyWithProgress(i, o, totalBytes, progress)
                    }
                }
            }
        }
    }

    fun importFromUrl(
        downloadUrl: String,
        announcedSize: Long,
        name: String,
        distro: String,
        distroVersion: String,
        initSystem: String,
        storageSizeGb: Int,
    ) {
        if (current?.isActive == true) return
        current = scope.launch {
            runImport(name, distro, distroVersion, initSystem, storageSizeGb) { dest, progress ->
                val conn = openWithRedirects(downloadUrl)
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${conn.responseCode} from $downloadUrl")
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: announcedSize
                conn.inputStream.use { i ->
                    dest.outputStream().use { o ->
                        copyWithProgress(i, o, total, progress)
                    }
                }
            }
        }
    }

    private suspend fun runImport(
        name: String,
        distro: String,
        distroVersion: String,
        initSystem: String,
        storageSizeGb: Int,
        body: suspend (File, (Long, Long) -> Unit) -> Unit,
    ) {
        _state.value = RootfsDownloadState.Copying(0L, 0L)
        val id = VmRegistry.newId()
        val dest = registry.rootfsFile(id)
        try {
            dest.parentFile?.mkdirs()
            body(dest) { copied, total ->
                _state.value = RootfsDownloadState.Copying(copied, total)
            }
            validateSquashfsOrThrow(dest)
            val record = VmRecord(
                id = id,
                name = name.ifBlank { defaultNameFor(distro, distroVersion) },
                distro = distro,
                distroVersion = distroVersion,
                initSystem = initSystem,
                storageSizeGb = storageSizeGb,
                createdAtMs = System.currentTimeMillis(),
            )
            registry.add(record)
            _state.value = RootfsDownloadState.Done(id, record.name)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            runCatching { dest.delete() }
            runCatching { registry.vmDir(id).delete() }
            _state.value = RootfsDownloadState.Failed(e.message ?: "Unknown error")
        }
    }

    /** Throttled progress write-through — the UI only needs ~10 Hz, and the
     *  Flow's collectors recompose more cheaply on coarser updates. */
    private fun copyWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long,
        progress: (Long, Long) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        var read = input.read(buffer)
        var lastTick = System.currentTimeMillis()
        while (read >= 0) {
            output.write(buffer, 0, read)
            copied += read
            val now = System.currentTimeMillis()
            if (now - lastTick > 100) {
                progress(copied, total)
                lastTick = now
            }
            read = input.read(buffer)
        }
        output.flush()
    }

    private fun openWithRedirects(url: String, maxHops: Int = 5): java.net.HttpURLConnection {
        var current = java.net.URL(url)
        repeat(maxHops) {
            val c = (current.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/octet-stream, */*")
                setRequestProperty("User-Agent", "Podroid/${android.os.Build.VERSION.SDK_INT}")
            }
            val code = c.responseCode
            if (code !in 300..399) return c
            val loc = c.getHeaderField("Location")
                ?: throw java.io.IOException("redirect $code from $current without Location")
            c.disconnect()
            current = java.net.URL(current, loc)
        }
        throw java.io.IOException("too many redirects starting at $url")
    }

    private fun validateSquashfsOrThrow(file: File) {
        if (!file.exists() || file.length() < 4) {
            throw IllegalStateException(
                "Imported file is empty (${file.length()} bytes). The download/copy didn't complete.",
            )
        }
        val head = ByteArray(16)
        file.inputStream().use { it.read(head) }
        val isSquashfs = head[0] == 0x68.toByte() && head[1] == 0x73.toByte() &&
            head[2] == 0x71.toByte() && head[3] == 0x73.toByte()
        if (!isSquashfs) {
            val hex = head.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
            val hint = when {
                head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() ->
                    "looks like a ZIP. GitHub Actions wraps artifacts in ZIPs — extract first."
                head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte() ->
                    "looks like a gzip / tar.gz — extract first."
                head[0] == 0x3c.toByte() ->
                    "looks like HTML — the download probably hit an error page."
                else -> "first bytes: $hex (not a squashfs)."
            }
            throw IllegalStateException("Not a valid Podroid rootfs — $hint")
        }
    }

    private fun defaultNameFor(distro: String, version: String) = when (distro.lowercase()) {
        "alpine" -> "Alpine $version"
        "debian" -> "Debian $version"
        "ubuntu" -> "Ubuntu $version"
        "kali"   -> "Kali $version"
        "arch"   -> "Arch Linux"
        "fedora" -> "Fedora $version"
        "void"   -> "Void Linux"
        else     -> distro.replaceFirstChar { it.uppercaseChar() } + " " + version
    }

    private companion object {
        const val TAG = "RootfsDownloader"
        const val BUFFER_SIZE = 256 * 1024
    }
}
