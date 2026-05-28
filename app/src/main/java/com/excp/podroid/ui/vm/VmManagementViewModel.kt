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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.RootfsCatalog
import com.excp.podroid.data.repository.RootfsCatalogEntry
import com.excp.podroid.data.repository.RootfsDownloadState
import com.excp.podroid.data.repository.RootfsDownloader
import com.excp.podroid.data.repository.VmRecord
import com.excp.podroid.data.repository.VmRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI-visible state of a running import. The dialog observes this to draw
 * progress / error / done. Mirrors RootfsDownloadState; kept as a separate
 * type so dialog code doesn't reach into the data-repository layer.
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
    private val downloader: RootfsDownloader,
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

    // Mirror the downloader's state into ImportState (the dialog's type).
    // viewModelScope.stateIn keeps the proxy alive for the lifetime of the VM
    // even when no collector is attached (background→foreground re-binds).
    val importState: StateFlow<ImportState> = downloader.state
        .map { it.toImportState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ImportState.Idle)

    fun resetImport() { downloader.reset() }

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
     * Trigger a SAF-Uri import. Delegates to RootfsDownloader (a Singleton)
     * so the file copy outlives this ViewModel — backgrounding the app no
     * longer cancels an in-flight import.
     */
    fun import(
        src: Uri,
        name: String,
        distro: String,
        distroVersion: String,
        initSystem: String,
        storageSizeGb: Int,
    ) = downloader.importFromUri(src, name, distro, distroVersion, initSystem, storageSizeGb)

    /**
     * Trigger a GitHub-Release URL download. Same singleton-backed job; the
     * dialog only acts as a remote control over [importState].
     */
    fun downloadAndImport(
        entry: RootfsCatalogEntry,
        name: String,
        storageSizeGb: Int,
    ) = downloader.importFromUrl(
        downloadUrl = entry.downloadUrl,
        announcedSize = entry.sizeBytes,
        name = name,
        distro = entry.distro,
        distroVersion = entry.distroVersion,
        initSystem = entry.initSystem,
        storageSizeGb = storageSizeGb,
    )
}

private fun RootfsDownloadState.toImportState(): ImportState = when (this) {
    RootfsDownloadState.Idle      -> ImportState.Idle
    is RootfsDownloadState.Copying -> ImportState.Copying(bytesCopied, totalBytes)
    is RootfsDownloadState.Failed  -> ImportState.Failed(message)
    is RootfsDownloadState.Done    -> ImportState.Done(vmId, name)
}

/** Slugify a candidate filename to extract distro + version hints. The Release
 *  naming convention is `podroid-rootfs-<distro>-aarch64.squashfs` (with an
 *  optional version slot in the middle for distros that have one). */
object RootfsFileNameParser {
    data class Parsed(val distro: String, val version: String, val initSystem: String)

    private val KNOWN_DISTROS = setOf(
        "alpine", "debian", "ubuntu", "kali",
        "arch", "fedora", "void", "parrot", "opensuse",
    )

    fun parse(displayName: String?): Parsed {
        val n = (displayName ?: "").lowercase()
        // Use the canonical 'podroid-rootfs-<distro>-...' filename shape rather
        // than a plain substring scan: a naive contains("arch") false-positives
        // on every other distro because "aarch64" contains "arch", classifying
        // Fedora/openSUSE/Parrot/Void as "Arch" in the picker.
        val structured = Regex("""podroid-rootfs-([a-z]+)(?:-([0-9a-z.]+))?-aarch64""")
            .find(n)
        val distro = (structured?.groupValues?.get(1) ?: "")
            .takeIf { it in KNOWN_DISTROS } ?: ""
        // Filename-encoded version, when present; otherwise extract any X.Y(.Z)
        // / 'rolling' token elsewhere in the name (for legacy/custom assets).
        val version = (structured?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
            ?: Regex("""[-_]([0-9]+(?:\.[0-9]+){0,2}|rolling)""")
                .find(n)?.groupValues?.get(1).orEmpty())
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
