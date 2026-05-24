/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * User-defined one-tap commands ("Custom Commands" — the NetHunter analog).
 * Stored as JSON-encoded entries in a single DataStore string-set; the
 * repository handles encode/decode so callers see a tidy [CustomCommand]
 * data class.
 */
package com.excp.podroid.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
        private val KEY = stringSetPreferencesKey("custom_commands")
    }

    /** Sorted by lowercase name for a stable list UI. */
    val commands: Flow<List<CustomCommand>> = context.dataStore.data.map { prefs ->
        (prefs[KEY] ?: emptySet()).mapNotNull(::decode)
            .sortedBy { it.name.lowercase() }
    }

    suspend fun snapshot(): List<CustomCommand> = commands.first()

    suspend fun add(name: String, command: String, openNewTab: Boolean) {
        val cc = CustomCommand(UUID.randomUUID().toString(), name, command, openNewTab)
        context.dataStore.edit { prefs ->
            prefs[KEY] = (prefs[KEY] ?: emptySet()) + encode(cc)
        }
    }

    suspend fun update(cc: CustomCommand) {
        context.dataStore.edit { prefs ->
            val others = (prefs[KEY] ?: emptySet())
                .filterNot { decode(it)?.id == cc.id }
                .toSet()
            prefs[KEY] = others + encode(cc)
        }
    }

    suspend fun remove(id: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY] = (prefs[KEY] ?: emptySet())
                .filterNot { decode(it)?.id == id }
                .toSet()
        }
    }

    private fun encode(cc: CustomCommand): String = JSONObject().apply {
        put("id", cc.id)
        put("name", cc.name)
        put("cmd", cc.command)
        put("nt", cc.openNewTab)
    }.toString()

    private fun decode(s: String): CustomCommand? = runCatching {
        val j = JSONObject(s)
        CustomCommand(
            id = j.getString("id"),
            name = j.getString("name"),
            command = j.getString("cmd"),
            openNewTab = j.optBoolean("nt"),
        )
    }.getOrNull()
}
