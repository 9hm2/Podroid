/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Wraps the llama-server child-process lifecycle. The binary is shipped
 * as libllama-server.so under jniLibs/arm64-v8a and laid down at
 * applicationInfo.nativeLibraryDir at install time (Android handles
 * extraction + +x bit). Same trick as libpodroid-bridge.so /
 * libqemu-system-aarch64.so — they're PIE executables, the .so name is
 * purely to satisfy the installer's "this is a native lib" filter.
 *
 * Stdout/stderr are tail-captured into a rolling log file (5 MiB cap)
 * so the Settings panel can show the boot banner / errors. The exit
 * watcher emits a state flow consumed by AiEngineService for restart /
 * notification updates.
 */
package com.excp.podroid.ai

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlamaServerProcess @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val detector: AiEngineDetector,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var watcher: Job? = null

    private val _state = MutableStateFlow<AiEngineState>(AiEngineState.Idle)
    val state: StateFlow<AiEngineState> = _state.asStateFlow()

    /** Append-only log file the UI tails for debugging. Truncated at
     *  LOG_CAP_BYTES on each successful start. */
    val logFile: File by lazy { File(context.cacheDir, "llama-server.log") }

    /** True iff we have a live subprocess. Used to short-circuit double
     *  starts and to drive the foreground notification. */
    val isRunning: Boolean get() = process?.let { runCatching { it.exitValue() }.isFailure } == true

    @Synchronized
    fun start(profile: BackendProfile, port: Int): Boolean {
        if (isRunning) {
            Log.d(TAG, "start() ignored — already running")
            return true
        }
        val model = modelManager.fileFor(profile.modelId) ?: run {
            _state.value = AiEngineState.Failed("Model '${profile.modelId}' not installed")
            return false
        }
        val bin = File(context.applicationInfo.nativeLibraryDir, "libllama-server.so")
        if (!bin.exists()) {
            _state.value = AiEngineState.Failed("libllama-server.so not in nativeLibraryDir — rebuild with build-all.sh ai")
            return false
        }

        val caps = detector.probe()
        val effectiveBackend = detector.resolveBackend(profile, caps)
        val cmd = buildCommand(bin, model, profile, effectiveBackend, port, caps)

        _state.value = AiEngineState.Starting
        logFile.writeText("[podroid-ai] launching: ${cmd.joinToString(" ")}\n")

        return runCatching {
            val pb = ProcessBuilder(cmd)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            // LD_LIBRARY_PATH lets the Vulkan loader find libvulkan.so;
            // /system/lib64 is normally already on the search path, but
            // belt-and-braces for OEMs that strip it from child envs.
            pb.environment()["LD_LIBRARY_PATH"] =
                "${context.applicationInfo.nativeLibraryDir}:/system/lib64:/vendor/lib64"
            // Keep tokeniser parallelism in check — llama.cpp respects this.
            pb.environment().remove("OMP_NUM_THREADS")

            val proc = pb.start()
            process = proc
            _state.value = AiEngineState.Running(profile.modelId, effectiveBackend)
            watcher = scope.launch { watch(proc, profile.modelId, effectiveBackend) }
            true
        }.getOrElse { e ->
            Log.e(TAG, "llama-server start failed", e)
            _state.value = AiEngineState.Failed(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    @Synchronized
    fun stop(reason: String = "user") {
        val p = process ?: run {
            _state.value = AiEngineState.Idle
            return
        }
        _state.value = AiEngineState.Stopping(reason)
        runCatching { p.destroy() }
        // Give it a beat to flush, then force.
        Thread.sleep(300)
        if (p.isAlive) runCatching { p.destroyForcibly() }
        process = null
        watcher?.cancel()
        watcher = null
        _state.value = AiEngineState.Idle
    }

    private suspend fun watch(p: Process, modelId: String, backend: AiBackend) {
        try {
            val rc = p.waitFor()
            // If we asked it to stop, state is already Stopping/Idle.
            if (_state.value is AiEngineState.Running) {
                _state.value = if (rc == 0) AiEngineState.Idle
                else AiEngineState.Failed("llama-server exited rc=$rc — see logs")
            }
        } catch (_: InterruptedException) {
            // scope cancellation — ignored
        } finally {
            process = null
        }
    }

    private fun buildCommand(
        bin: File, model: File, profile: BackendProfile,
        backend: AiBackend, port: Int, caps: DeviceCapabilities,
    ): List<String> = buildList {
        add(bin.absolutePath)
        add("--host"); add("127.0.0.1")
        add("--port"); add(port.toString())
        add("-m"); add(model.absolutePath)
        add("-c"); add(profile.contextSize.toString())

        // GPU-layers: -ngl 999 = "as many as fit"; 0 = pure CPU.
        when (backend) {
            AiBackend.VULKAN -> {
                add("-ngl"); add(if (profile.gpuLayers < 0) "999" else profile.gpuLayers.toString())
            }
            AiBackend.CPU, AiBackend.HEXAGON, AiBackend.AUTO -> {
                add("-ngl"); add("0")
                // HEXAGON path falls through to CPU here — QNN delegate
                // isn't wired in this version (TODO). AUTO shouldn't reach
                // this branch (resolveBackend() rewrites it), but be safe.
            }
        }

        // Threads: -1 = let llama.cpp pick (it counts perf-cores on big.LITTLE
        // sensibly since v0.3). Otherwise honour the user explicit value.
        if (profile.threads > 0) { add("-t"); add(profile.threads.toString()) }

        if (profile.flashAttention) add("-fa")
        add("-ctk"); add(profile.kvCacheType.id)
        add("-ctv"); add(profile.kvCacheType.id)
        add("-b"); add(profile.batchSize.toString())
        if (!profile.mmap) add("--no-mmap")
        if (profile.mlock) add("--mlock")

        // Server-side niceties: OpenAI-compatible routes, JSON-mode support,
        // and quiet logging so the file doesn't balloon.
        add("--alias"); add("podroid")
        add("--log-disable")  // keep llama-server's own verbose logs off; ours capture stdout.
        // Cache-prompt enables prefix-cache reuse — big speedup on chat-y
        // workflows where each turn shares the prior conversation.
        add("--cache-reuse"); add("256")
    }

    /** Cooperative shutdown helper for app exit. */
    fun shutdown() {
        stop("shutdown")
        scope.cancel()
    }

    companion object {
        private const val TAG = "LlamaServerProc"
        private const val LOG_CAP_BYTES = 5L * 1024 * 1024
    }
}
