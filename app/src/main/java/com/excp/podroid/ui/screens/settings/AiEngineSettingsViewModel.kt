/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * ViewModel scoped to the AI Engine settings section. Bridges Compose
 * onClick handlers to AiEngineRepository setters, exposes the live
 * BackendProfile + process state, and drives ModelManager downloads.
 */
package com.excp.podroid.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.ai.AiBackend
import com.excp.podroid.ai.AiEngineDetector
import com.excp.podroid.ai.AiEngineRepository
import com.excp.podroid.ai.BackendProfile
import com.excp.podroid.ai.DeviceCapabilities
import com.excp.podroid.ai.KvCacheType
import com.excp.podroid.ai.LlamaServerProcess
import com.excp.podroid.ai.ModelCatalogue
import com.excp.podroid.ai.ModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiEngineSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AiEngineRepository,
    private val detector: AiEngineDetector,
    private val modelManager: ModelManager,
    private val process: LlamaServerProcess,
) : ViewModel() {

    /** True iff the cross-compiled llama-server binary actually shipped in
     *  the APK's jniLibs. False on the (rare) first run where the `ai`
     *  workflow job failed but the rest succeeded — we hide the engine
     *  toggle entirely in that case so the user doesn't see a permanently
     *  failing switch. */
    fun isBinaryAvailable(): Boolean =
        File(context.applicationInfo.nativeLibraryDir, "libllama-server.so").exists()

    val enabled: StateFlow<Boolean> = repository.enabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val profile: StateFlow<BackendProfile?> = repository.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val processState = process.state
    val pauseOnThermal = repository.pauseOnThermal
    val smallCoresLowBattery = repository.smallCoresLowBattery
    val port: Int = AiEngineRepository.DEFAULT_PORT

    /** Re-emits whenever a download completes — drives the picker's
     *  "installed?" recompute. */
    private val _installedTick = MutableStateFlow(0)
    val downloadProgress: Flow<ModelManager.DownloadEvent?> = modelManager.progress
        .onEach { ev ->
            if (ev is ModelManager.DownloadEvent.Done || ev is ModelManager.DownloadEvent.Failed) {
                _installedTick.value++
            }
        }

    /** Cheap (just listFiles) — re-read from the UI side when the tick changes. */
    fun installedModelIds(): Set<String> =
        ModelCatalogue.all.filter { modelManager.isInstalled(it.id) }.map { it.id }.toSet()

    fun capabilities(): DeviceCapabilities = detector.probe()

    // ── Setters (each fires-and-forgets a coroutine) ───────────────────────
    fun setEnabled(v: Boolean)               { viewModelScope.launch { repository.setEnabled(v) } }
    fun setBackend(v: AiBackend)             { viewModelScope.launch { repository.setBackend(v) } }
    fun setModelId(v: String)                { viewModelScope.launch { repository.setModelId(v) } }
    fun setContextSize(v: Int)               { viewModelScope.launch { repository.setContextSize(v) } }
    fun setGpuLayers(v: Int)                 { viewModelScope.launch { repository.setGpuLayers(v) } }
    fun setThreads(v: Int)                   { viewModelScope.launch { repository.setThreads(v) } }
    fun setFlashAttention(v: Boolean)        { viewModelScope.launch { repository.setFlashAttention(v) } }
    fun setKvCacheType(v: KvCacheType)       { viewModelScope.launch { repository.setKvCacheType(v) } }
    fun setBatchSize(v: Int)                 { viewModelScope.launch { repository.setBatchSize(v) } }
    fun setMmap(v: Boolean)                  { viewModelScope.launch { repository.setMmap(v) } }
    fun setMlock(v: Boolean)                 { viewModelScope.launch { repository.setMlock(v) } }
    fun setPauseOnThermal(v: Boolean)        { viewModelScope.launch { repository.setPauseOnThermal(v) } }
    fun setSmallCoresLowBattery(v: Boolean)  { viewModelScope.launch { repository.setSmallCoresLowBattery(v) } }

    fun downloadModel(id: String) {
        val spec = ModelCatalogue.byId(id) ?: return
        viewModelScope.launch {
            runCatching {
                modelManager.download(spec)
                // Auto-activate the freshly-downloaded model — that's why the
                // user tapped Download. Skipping this would leave them on the
                // pre-download active model wondering why nothing changed.
                repository.setModelId(id)
            }
        }
    }

    fun deleteModel(id: String) {
        viewModelScope.launch {
            modelManager.delete(id)
            _installedTick.value++
        }
    }

    suspend fun resetToDetected() { repository.resetToDetected() }
}
