/*
 * One-shot migration from the single-VM layout (filesDir/storage.img +
 * filesDir/alpine-rootfs.squashfs) to the multi-VM layout (filesDir/vms/<id>/).
 *
 * Pre-multi-VM builds extracted the bundled Alpine squashfs into filesDir and
 * grew a single storage.img beside it. The new code requires a VmRecord for
 * every running VM, so without migration an upgrading user would lose access
 * to their containers, configs, and `apk add`ed packages.
 *
 * Strategy: on each Application onCreate, if filesDir/storage.img exists AND
 * the VmRegistry has no VMs yet, MOVE both legacy files into vms/<new-id>/
 * and persist a VmRecord. After that the multi-VM code paths take over.
 *
 * Idempotent — the second invocation is a no-op because the registry is no
 * longer empty.
 */
package com.excp.podroid.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LegacyVmMigrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: VmRegistry,
    private val settings: SettingsRepository,
) {
    /**
     * Runs once. Safe to call from any thread (uses suspending DataStore
     * APIs internally). Returns true when a migration actually happened.
     */
    suspend fun migrateIfNeeded(): Boolean {
        val legacyStorage = File(context.filesDir, "storage.img")
        val legacyRootfs  = File(context.filesDir, "alpine-rootfs.squashfs")

        if (!legacyStorage.exists()) return false
        if (registry.snapshot().isNotEmpty()) return false

        val id = VmRegistry.newId()
        val vmDir = registry.vmDir(id)
        if (!vmDir.mkdirs() && !vmDir.exists()) {
            Log.w(TAG, "Could not create $vmDir; leaving legacy files in place")
            return false
        }

        // Move (renameTo is atomic on the same filesystem) — fall back to
        // copy+delete on EXDEV (shouldn't happen since both live under filesDir).
        val storageOk = moveFile(legacyStorage, registry.storageFile(id))
        // The legacy rootfs is optional: if it's missing (e.g. user had wiped
        // it manually) the VmRecord still gets created so the storage.img is
        // preserved. They can re-import the rootfs through the UI.
        if (legacyRootfs.exists()) {
            val rootfsOk = moveFile(legacyRootfs, registry.rootfsFile(id))
            if (!rootfsOk) Log.w(TAG, "Failed to migrate rootfs — VM will need a re-import")
        }
        if (!storageOk) {
            Log.w(TAG, "Failed to migrate storage.img — aborting migration")
            return false
        }

        // Storage size is in settings (it was a global setting before).
        val storageSizeGb = runCatching { settings.getStorageSizeGbSnapshot() }
            .getOrDefault(2)

        registry.add(
            VmRecord(
                id = id,
                name = "Alpine Linux (migrated)",
                distro = "alpine",
                distroVersion = "3.23",
                initSystem = "openrc",
                storageSizeGb = storageSizeGb,
                createdAtMs = System.currentTimeMillis(),
                lastUsedAtMs = System.currentTimeMillis(),
            ),
        )
        Log.i(TAG, "Migrated legacy single-VM data to vms/$id (size=${storageSizeGb}GB)")
        return true
    }

    private fun moveFile(src: File, dst: File): Boolean {
        if (!src.exists()) return false
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return true
        // Cross-device fallback — copy + delete the source.
        return runCatching {
            src.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            src.delete()
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "LegacyVmMigrator"
    }
}
