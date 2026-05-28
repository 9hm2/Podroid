/*
 * Podroid - Multi-distribution VM registry.
 *
 * Holds the set of imported VMs and tracks which one is active. Each VM owns
 * its own rootfs squashfs and persistent ext4 overlay under filesDir/vms/<id>/.
 *
 * Persistence model: a single DataStore Preferences entry serialises the whole
 * record list as JSON (records are small — maybe 10-20 of them at most). The
 * "active VM" pointer is a separate key so re-ordering the list never has to
 * write the active value, and vice versa.
 *
 * Concurrency: all writes go through DataStore (single-writer); readers are
 * Flow-based and emit on every change. The on-disk vms/<id>/ directory is
 * managed here too so the registry and the filesystem can't drift.
 */
package com.excp.podroid.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One imported VM. Immutable; create a copy() with new values and pass to
 * [VmRegistry.update] to mutate.
 *
 * - [id]            stable UUID, also the on-disk directory name
 * - [name]          user-facing label (renamable)
 * - [distro]        distro family slug — "alpine", "debian", "ubuntu", "kali", "arch", "fedora", "void"
 * - [distroVersion] version label as the user/builder set it ("3.23", "13", "24.04", "rolling")
 * - [initSystem]    "openrc" | "systemd" | "runit"; drives boot-stage detection
 * - [storageSizeGb] persistent ext4 overlay size in GiB, chosen at import time
 * - [createdAtMs]   System.currentTimeMillis() at import
 * - [lastUsedAtMs]  updated on each successful VM start; 0L if never started
 */
data class VmRecord(
    val id: String,
    val name: String,
    val distro: String,
    val distroVersion: String,
    val initSystem: String,
    val storageSizeGb: Int,
    val createdAtMs: Long,
    val lastUsedAtMs: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("distro", distro)
        put("distroVersion", distroVersion)
        put("initSystem", initSystem)
        put("storageSizeGb", storageSizeGb)
        put("createdAtMs", createdAtMs)
        put("lastUsedAtMs", lastUsedAtMs)
    }

    companion object {
        fun fromJson(o: JSONObject) = VmRecord(
            id = o.getString("id"),
            name = o.getString("name"),
            distro = o.getString("distro"),
            distroVersion = o.getString("distroVersion"),
            initSystem = o.optString("initSystem", "openrc"),
            storageSizeGb = o.getInt("storageSizeGb"),
            createdAtMs = o.getLong("createdAtMs"),
            lastUsedAtMs = o.optLong("lastUsedAtMs", 0L),
        )
    }
}

@Singleton
class VmRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val ds = context.vmRegistryDataStore

    /** Sorted-by-name list of all imported VMs. */
    val vms: Flow<List<VmRecord>> = ds.data.map { prefs ->
        val raw = prefs[KEY_VMS] ?: return@map emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return@map emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                runCatching { VmRecord.fromJson(arr.getJSONObject(i)) }.getOrNull()?.let(::add)
            }
        }.sortedBy { it.name.lowercase() }
    }

    /** ID of the VM the user last selected. Null while the list is empty. */
    val activeVmId: Flow<String?> = ds.data.map { it[KEY_ACTIVE_ID] }

    /** Snapshot helpers — the engine reads these synchronously at start. */
    suspend fun snapshot(): List<VmRecord> = vms.first()

    suspend fun activeIdSnapshot(): String? = activeVmId.first()

    suspend fun activeSnapshot(): VmRecord? {
        val id = activeIdSnapshot() ?: return null
        return snapshot().firstOrNull { it.id == id }
    }

    /** Filesystem layout owned by this registry — never opened from outside. */
    fun vmDir(id: String): File = File(context.filesDir, "vms/$id")
    fun rootfsFile(id: String): File = File(vmDir(id), "rootfs.squashfs")
    fun storageFile(id: String): File = File(vmDir(id), "storage.img")

    /**
     * Adds a new record. Caller is expected to have already populated the
     * VM dir on disk (rootfs.squashfs + later storage.img created on first boot).
     */
    suspend fun add(record: VmRecord) = mutate { current ->
        // Reject duplicates by id; name collisions are allowed (UUID disambiguates).
        if (current.any { it.id == record.id }) current else current + record
    }

    /** Replaces a record by id; ignored if the id isn't in the list. */
    suspend fun update(record: VmRecord) = mutate { current ->
        if (current.none { it.id == record.id }) current
        else current.map { if (it.id == record.id) record else it }
    }

    /**
     * Removes a record AND deletes its on-disk directory. If the removed VM was
     * the active one, the next VM (sorted by lastUsedAtMs desc) becomes active;
     * if no others exist, the active pointer clears.
     */
    suspend fun remove(id: String) {
        ds.edit { prefs ->
            val current = readList(prefs[KEY_VMS])
            val filtered = current.filter { it.id != id }
            prefs[KEY_VMS] = serialise(filtered)
            if (prefs[KEY_ACTIVE_ID] == id) {
                val next = filtered.maxByOrNull { it.lastUsedAtMs }?.id
                if (next != null) prefs[KEY_ACTIVE_ID] = next
                else prefs.remove(KEY_ACTIVE_ID)
            }
        }
        // Best-effort cleanup of the on-disk dir. The DataStore write is the
        // source of truth, so a failure here only wastes space — never a
        // half-state where the registry references a missing dir.
        runCatching { vmDir(id).deleteRecursively() }
    }

    suspend fun setActive(id: String) = ds.edit { it[KEY_ACTIVE_ID] = id }

    /** Bump lastUsedAtMs to "now". Called by the engine on a successful start. */
    suspend fun markUsed(id: String) = mutate { current ->
        current.map {
            if (it.id == id) it.copy(lastUsedAtMs = System.currentTimeMillis()) else it
        }
    }

    private suspend fun mutate(transform: (List<VmRecord>) -> List<VmRecord>) {
        ds.edit { prefs ->
            val current = readList(prefs[KEY_VMS])
            val next = transform(current)
            prefs[KEY_VMS] = serialise(next)
            // First add → become active automatically.
            if (prefs[KEY_ACTIVE_ID] == null && next.isNotEmpty()) {
                prefs[KEY_ACTIVE_ID] = next.first().id
            }
        }
    }

    private fun readList(raw: String?): List<VmRecord> {
        if (raw == null) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                runCatching { VmRecord.fromJson(arr.getJSONObject(i)) }.getOrNull()?.let(::add)
            }
        }
    }

    private fun serialise(list: List<VmRecord>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    companion object {
        private val KEY_VMS = stringPreferencesKey("vms_registry_v1")
        private val KEY_ACTIVE_ID = stringPreferencesKey("vms_active_id")

        /** Generate a fresh VM id. UUID strings give us "good enough" entropy and
         *  serve as a perfectly safe filename. */
        fun newId(): String = UUID.randomUUID().toString()
    }
}

// Separate DataStore from SettingsRepository so an unrelated edit on one can't
// stall the other on a long write. Module-level delegate (Preferences DataStore
// requires the Context receiver be reused across calls).
private val android.content.Context.vmRegistryDataStore by
    androidx.datastore.preferences.preferencesDataStore(name = "vm_registry")
