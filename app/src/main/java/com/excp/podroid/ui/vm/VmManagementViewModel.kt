/*
 * VM management view model — backs the VM picker row on the Home screen and
 * the AddVmDialog used both by the Setup flow and from Home itself.
 *
 * Owns three jobs:
 *   1. Expose the imported-VM list + the active id as StateFlows.
 *   2. Run a SAF-driven import: copy a user-selected .squashfs into the
 *      per-VM directory, build a VmRecord, persist it.
 *   3. Set the active VM and delete records (with on-disk cleanup).
 *
 * The actual file copy runs on Dispatchers.IO; the UI observes [importState]
 * for progress + result.
 */
package com.excp.podroid.ui.vm

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.RootfsCatalog
import com.excp.podroid.data.repository.RootfsCatalogEntry
import com.excp.podroid.data.repository.VmRecord
import com.excp.podroid.data.repository.VmRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * UI-visible state of a running import. The dialog observes this to draw
 * progress / error / done.
 */
sealed class ImportState {
    data object Idle : ImportState()
    data class Copying(val bytesCopied: Long, val totalBytes: Long) : ImportState()
    data class Failed(val message: String) : ImportState()
    data class Done(val vmId: String, val name: String) : ImportState()
}

@HiltViewModel
class VmManagementViewModel @Inject constructor(
    app: Application,
    private val registry: VmRegistry,
    private val catalog: RootfsCatalog,
) : AndroidViewModel(app) {

    // Catalog of rootfs files published in the latest GitHub Release. Lazy-fetched
    // by the Download dialog when it opens; null until the first fetch resolves.
    private val _catalog = MutableStateFlow<List<RootfsCatalogEntry>?>(null)
    val rootfsCatalog: StateFlow<List<RootfsCatalogEntry>?> = _catalog.asStateFlow()

    fun fetchCatalog() {
        viewModelScope.launch(Dispatchers.IO) {
            _catalog.value = catalog.fetchLatest()
        }
    }

    val vms: StateFlow<List<VmRecord>> = registry.vms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeVmId: StateFlow<String?> = registry.activeVmId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    fun resetImport() { _importState.value = ImportState.Idle }

    fun setActive(id: String) {
        viewModelScope.launch { registry.setActive(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { registry.remove(id) }
    }

    fun rename(id: String, newName: String) {
        viewModelScope.launch {
            val current = registry.snapshot().firstOrNull { it.id == id } ?: return@launch
            registry.update(current.copy(name = newName.ifBlank { current.name }))
        }
    }

    /**
     * Import a user-picked .squashfs from [src] (SAF Uri) as a new VM record.
     * The file is copied into the per-VM dir; on success the new VM becomes
     * active automatically (VmRegistry's first-add rule).
     */
    fun import(
        src: Uri,
        name: String,
        distro: String,
        distroVersion: String,
        initSystem: String,
        storageSizeGb: Int,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _importState.value = ImportState.Copying(0L, 0L)
            val id = VmRegistry.newId()
            val dest = registry.rootfsFile(id)
            try {
                dest.parentFile?.mkdirs()
                val ctx = getApplication<Application>()
                val totalBytes = runCatching {
                    ctx.contentResolver.openFileDescriptor(src, "r")?.use { it.statSize }
                }.getOrNull() ?: -1L

                val input = ctx.contentResolver.openInputStream(src)
                    ?: throw IllegalStateException("ContentResolver returned no stream for $src")
                input.use { i ->
                    dest.outputStream().use { o ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var copied = 0L
                        var read = i.read(buffer)
                        var lastTick = System.currentTimeMillis()
                        while (read >= 0) {
                            o.write(buffer, 0, read)
                            copied += read
                            // Update progress at most ~10×/sec so the Flow
                            // doesn't recompose the dialog into oblivion.
                            val now = System.currentTimeMillis()
                            if (now - lastTick > 100) {
                                _importState.value = ImportState.Copying(copied, totalBytes)
                                lastTick = now
                            }
                            read = i.read(buffer)
                        }
                        o.flush()
                    }
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
                _importState.value = ImportState.Done(id, record.name)
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                runCatching { dest.delete() }
                runCatching { registry.vmDir(id).delete() }
                _importState.value = ImportState.Failed(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Streams a rootfs squashfs from a GitHub Release directly into the per-VM
     * dir; identical state machine to import(), but the source is an HTTP
     * stream instead of a SAF Uri. The same ImportState transitions drive the
     * dialog's progress UI.
     */
    fun downloadAndImport(
        entry: RootfsCatalogEntry,
        name: String,
        storageSizeGb: Int,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _importState.value = ImportState.Copying(0L, entry.sizeBytes)
            val id = VmRegistry.newId()
            val dest = registry.rootfsFile(id)
            try {
                dest.parentFile?.mkdirs()
                // GitHub Release browser_download_url 302-redirects to
                // objects.githubusercontent.com. HttpURLConnection's automatic
                // follow drops on some Android stacks (e.g. when the Location
                // is on a different host but same scheme), so handle it
                // manually with a small bounded loop.
                val conn = openWithRedirects(entry.downloadUrl)
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${conn.responseCode} from ${entry.downloadUrl}")
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: entry.sizeBytes
                conn.inputStream.use { i ->
                    dest.outputStream().use { o ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var copied = 0L
                        var read = i.read(buffer)
                        var lastTick = System.currentTimeMillis()
                        while (read >= 0) {
                            o.write(buffer, 0, read)
                            copied += read
                            val now = System.currentTimeMillis()
                            if (now - lastTick > 100) {
                                _importState.value = ImportState.Copying(copied, total)
                                lastTick = now
                            }
                            read = i.read(buffer)
                        }
                        o.flush()
                    }
                }
                validateSquashfsOrThrow(dest)
                val record = VmRecord(
                    id = id,
                    name = name.ifBlank { defaultNameFor(entry.distro, entry.distroVersion) },
                    distro = entry.distro,
                    distroVersion = entry.distroVersion,
                    initSystem = entry.initSystem,
                    storageSizeGb = storageSizeGb,
                    createdAtMs = System.currentTimeMillis(),
                )
                registry.add(record)
                _importState.value = ImportState.Done(id, record.name)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${entry.filename}", e)
                runCatching { dest.delete() }
                runCatching { registry.vmDir(id).delete() }
                _importState.value = ImportState.Failed(e.message ?: "Unknown error")
            }
        }
    }

    /** Manually follow up to [maxHops] 3xx redirects. HttpURLConnection's own
     *  instanceFollowRedirects is unreliable for cross-host same-scheme jumps,
     *  which is exactly what github.com → objects.githubusercontent.com is. */
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

    /**
     * Squashfs files start with the magic uint32 0x73717368 ("hsqs" in
     * little-endian ASCII at byte 0). Cheap to check, expensive to skip —
     * mounting a bogus file inside the guest only surfaces as
     * "Can't find SQUASHFS superblock on /dev/vdb" after the user has
     * spent ~30 s booting. Common culprits:
     *   - ZIP file (the GitHub Actions artifact wraps the .squashfs)
     *     → 'PK\x03\x04' magic
     *   - tar.gz (download truncated mid-stream)        → '\x1f\x8b'
     *   - HTML error page (cross-host redirect failure) → '<!DOCTYPE…'
     * Any of those fail this check with a head-of-file hex dump so the
     * user knows immediately what kind of file they actually picked.
     */
    private fun validateSquashfsOrThrow(file: File) {
        if (!file.exists() || file.length() < 4) {
            throw IllegalStateException(
                "Imported file is empty (${file.length()} bytes). The download/copy didn't complete.",
            )
        }
        val head = ByteArray(16)
        file.inputStream().use { it.read(head) }
        // 0x73717368 LE = 0x68 0x73 0x71 0x73 = "hsqs"
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
                    "looks like HTML — the download probably hit an error page (auth / 404 / redirect)."
                else -> "first bytes: $hex (not a squashfs)."
            }
            throw IllegalStateException("Not a valid Podroid rootfs — $hint")
        }
    }

    private fun defaultNameFor(distro: String, version: String) =
        when (distro.lowercase()) {
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
        const val TAG = "VmManagementVM"
        const val BUFFER_SIZE = 256 * 1024
    }
}

/** Slugify a candidate filename to extract distro + version hints. The Release
 *  naming convention is `podroid-rootfs-<distro>-<version>-aarch64.squashfs`. */
object RootfsFileNameParser {
    data class Parsed(val distro: String, val version: String, val initSystem: String)

    fun parse(displayName: String?): Parsed {
        val n = (displayName ?: "").lowercase()
        val distro = listOf("alpine", "debian", "ubuntu", "kali", "arch", "fedora", "void", "parrot", "opensuse")
            .firstOrNull { n.contains(it) } ?: ""
        val version = Regex("""[-_]([0-9]+(?:\.[0-9]+){0,2}|rolling)""")
            .find(n)?.groupValues?.get(1).orEmpty()
        val initSystem = when (distro) {
            "alpine"                       -> "openrc"
            "void"                         -> "runit"
            "debian", "ubuntu", "kali",
            "arch", "fedora", "parrot",
            "opensuse"                     -> "systemd"
            else                            -> "openrc"
        }
        return Parsed(distro, version, initSystem)
    }
}
