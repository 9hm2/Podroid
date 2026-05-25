/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * DataStore-backed persistence for the AI engine settings. Mirrors the
 * SettingsRepository pattern: one preferences DataStore, typed Flows
 * per setting, suspend setters. Defaults seed from AiEngineDetector
 * the first time the user opens the panel.
 */
package com.excp.podroid.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.aiDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_engine")

@Singleton
class AiEngineRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val detector: AiEngineDetector,
) {
    private object Keys {
        val ENABLED               = booleanPreferencesKey("enabled")
        val BACKEND               = stringPreferencesKey("backend")
        val MODEL_ID              = stringPreferencesKey("model_id")
        val CONTEXT_SIZE          = intPreferencesKey("context_size")
        val GPU_LAYERS            = intPreferencesKey("gpu_layers")
        val THREADS               = intPreferencesKey("threads")
        val FLASH_ATTENTION       = booleanPreferencesKey("flash_attention")
        val KV_CACHE_TYPE         = stringPreferencesKey("kv_cache_type")
        val BATCH_SIZE            = intPreferencesKey("batch_size")
        val MMAP                  = booleanPreferencesKey("mmap")
        val MLOCK                 = booleanPreferencesKey("mlock")
        val PAUSE_ON_THERMAL      = booleanPreferencesKey("pause_on_thermal")
        val SMALL_CORES_LOW_BATT  = booleanPreferencesKey("small_cores_low_batt")
        val IDLE_SHUTDOWN_MIN     = intPreferencesKey("idle_shutdown_min")
        val PORT                  = intPreferencesKey("port")
    }

    /** Lazy default — only built when the first flow value is requested. */
    private val defaultsByDetector: BackendProfile by lazy {
        detector.defaultProfileFor(detector.probe())
    }

    val enabled: Flow<Boolean> = pref(Keys.ENABLED, false)

    /** The full BackendProfile assembled from individual setting Flows.
     *  combine() only has 5-arg typed overloads; we use the vararg form
     *  (lambda receives Array<Any>) since we have 10 inputs of mixed types. */
    val profile: Flow<BackendProfile> = combine(
        pref(Keys.BACKEND, defaultsByDetector.backend.id),
        pref(Keys.MODEL_ID, defaultsByDetector.modelId),
        pref(Keys.CONTEXT_SIZE, defaultsByDetector.contextSize),
        pref(Keys.GPU_LAYERS, defaultsByDetector.gpuLayers),
        pref(Keys.THREADS, defaultsByDetector.threads),
        pref(Keys.FLASH_ATTENTION, defaultsByDetector.flashAttention),
        pref(Keys.KV_CACHE_TYPE, defaultsByDetector.kvCacheType.id),
        pref(Keys.BATCH_SIZE, defaultsByDetector.batchSize),
        pref(Keys.MMAP, defaultsByDetector.mmap),
        pref(Keys.MLOCK, defaultsByDetector.mlock),
    ) { vals ->
        BackendProfile(
            backend = AiBackend.fromId(vals[0] as String),
            modelId = vals[1] as String,
            contextSize = vals[2] as Int,
            gpuLayers = vals[3] as Int,
            threads = vals[4] as Int,
            flashAttention = vals[5] as Boolean,
            kvCacheType = KvCacheType.fromId(vals[6] as String),
            batchSize = vals[7] as Int,
            mmap = vals[8] as Boolean,
            mlock = vals[9] as Boolean,
        )
    }

    val pauseOnThermal: Flow<Boolean>     = pref(Keys.PAUSE_ON_THERMAL, true)
    val smallCoresLowBattery: Flow<Boolean> = pref(Keys.SMALL_CORES_LOW_BATT, true)
    val idleShutdownMinutes: Flow<Int>    = pref(Keys.IDLE_SHUTDOWN_MIN, 10)
    /** Loopback port the server binds to; SLIRP routes VM → 10.0.2.2:port. */
    val port: Flow<Int>                   = pref(Keys.PORT, DEFAULT_PORT)

    suspend fun snapshotProfile(): BackendProfile = profile.first()
    suspend fun snapshotPort(): Int = port.first()
    suspend fun isEnabled(): Boolean = enabled.first()

    suspend fun setEnabled(v: Boolean)                = set(Keys.ENABLED, v)
    suspend fun setBackend(v: AiBackend)              = set(Keys.BACKEND, v.id)
    suspend fun setModelId(v: String)                 = set(Keys.MODEL_ID, v)
    suspend fun setContextSize(v: Int)                = set(Keys.CONTEXT_SIZE, v)
    suspend fun setGpuLayers(v: Int)                  = set(Keys.GPU_LAYERS, v)
    suspend fun setThreads(v: Int)                    = set(Keys.THREADS, v)
    suspend fun setFlashAttention(v: Boolean)         = set(Keys.FLASH_ATTENTION, v)
    suspend fun setKvCacheType(v: KvCacheType)        = set(Keys.KV_CACHE_TYPE, v.id)
    suspend fun setBatchSize(v: Int)                  = set(Keys.BATCH_SIZE, v)
    suspend fun setMmap(v: Boolean)                   = set(Keys.MMAP, v)
    suspend fun setMlock(v: Boolean)                  = set(Keys.MLOCK, v)
    suspend fun setPauseOnThermal(v: Boolean)         = set(Keys.PAUSE_ON_THERMAL, v)
    suspend fun setSmallCoresLowBattery(v: Boolean)   = set(Keys.SMALL_CORES_LOW_BATT, v)
    suspend fun setIdleShutdownMinutes(v: Int)        = set(Keys.IDLE_SHUTDOWN_MIN, v)

    /** Wipe every key — used by "Reset to detected defaults" in the UI. */
    suspend fun resetToDetected() {
        context.aiDataStore.edit { it.clear() }
    }

    private fun <T> pref(key: Preferences.Key<T>, default: T): Flow<T> =
        context.aiDataStore.data
            .catch { e -> if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }
            .map { it[key] ?: default }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.aiDataStore.edit { it[key] = value }
    }

    companion object {
        const val DEFAULT_PORT = 8089
    }
}
