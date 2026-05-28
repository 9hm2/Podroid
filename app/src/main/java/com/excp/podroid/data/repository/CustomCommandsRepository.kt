/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * User-defined one-tap commands ("Custom Commands" — the NetHunter analog).
 * Stored as a JSON-encoded array in a single DataStore string entry; the
 * array preserves user-defined ordering (reorder + edit operations live in
 * the sheet UI). A legacy unordered-set value from v1.1.x is transparently
 * migrated to the ordered storage on first read.
 */
package com.excp.podroid.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One saved command. [openNewTab] = run in the next tab instead of the
 *  currently-selected one (multi-tab terminal only). */
data class CustomCommand(
    val id: String,
    val name: String,
    val command: String,
    val openNewTab: Boolean = false,
)

@Singleton
class CustomCommandsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        // v2: ordered JSON array. The list reflects the user-chosen order
        // exactly — no implicit name-sort.
        private val KEY_LIST = stringPreferencesKey("custom_commands_v2")
        // v1.1.x: unordered StringSet — read once, then forgotten.
        private val KEY_LEGACY_SET = stringSetPreferencesKey("custom_commands")
    }

    val commands: Flow<List<CustomCommand>> =
        context.dataStore.data.map(::parseCurrent)

    suspend fun add(name: String, command: String, openNewTab: Boolean) =
        mutateList { it + CustomCommand(UUID.randomUUID().toString(), name, command, openNewTab) }

    suspend fun update(cc: CustomCommand) =
        mutateList { list -> list.map { if (it.id == cc.id) cc else it } }

    suspend fun remove(id: String) =
        mutateList { list -> list.filterNot { it.id == id } }

    /** Swap [id] with its neighbour. Direction = -1 for up, +1 for down.
     *  No-op when the row is already at the requested edge. */
    suspend fun move(id: String, direction: Int) = mutateList { list ->
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return@mutateList list
        val target = idx + direction
        if (target !in list.indices) return@mutateList list
        list.toMutableList().also { it[idx] = list[target]; it[target] = list[idx] }
    }

    private suspend fun mutateList(transform: (List<CustomCommand>) -> List<CustomCommand>) {
        context.dataStore.edit { prefs ->
            val next = transform(parseCurrent(prefs))
            prefs[KEY_LIST] = encodeList(next)
            // Drop the legacy entry once we've taken responsibility for the
            // ordered storage — otherwise a partial migration could resurface
            // old entries after a v2 wipe.
            prefs.remove(KEY_LEGACY_SET)
        }
    }

    private fun parseCurrent(prefs: Preferences): List<CustomCommand> {
        prefs[KEY_LIST]?.takeIf { it.isNotEmpty() }?.let { return decodeList(it) }
        // First read after upgrade — migrate from the unordered legacy set,
        // initial order = name asc (matches what the old UI displayed).
        return (prefs[KEY_LEGACY_SET] ?: emptySet())
            .mapNotNull(::decodeLegacyEntry)
            .sortedBy { it.name.lowercase() }
    }

    private fun encodeList(list: List<CustomCommand>): String =
        JSONArray().apply {
            for (cc in list) put(JSONObject().apply {
                put("id", cc.id)
                put("name", cc.name)
                put("cmd", cc.command)
                put("nt", cc.openNewTab)
            })
        }.toString()

    private fun decodeList(s: String): List<CustomCommand> = runCatching {
        val arr = JSONArray(s)
        (0 until arr.length()).mapNotNull { i -> decodeEntry(arr.getJSONObject(i)) }
    }.getOrElse { emptyList() }

    private fun decodeEntry(j: JSONObject): CustomCommand? = runCatching {
        CustomCommand(
            id = j.getString("id"),
            name = j.getString("name"),
            command = j.getString("cmd"),
            openNewTab = j.optBoolean("nt"),
        )
    }.getOrNull()

    private fun decodeLegacyEntry(s: String): CustomCommand? = runCatching {
        decodeEntry(JSONObject(s))
    }.getOrNull()
}
