/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Data classes and enums shared by the AI engine subsystem
 * (detector + repository + service + UI). Single file because each type
 * is tiny and they're tightly coupled.
 */
package com.excp.podroid.ai

/** Which compute backend `llama-server` is told to use. AUTO defers to
 *  the device tier the detector picks; the user can override. */
enum class AiBackend(val id: String, val label: String) {
    AUTO("auto", "Auto-detect"),
    CPU("cpu", "CPU + NEON"),
    VULKAN("vulkan", "Vulkan (GPU)"),
    HEXAGON("hexagon", "Hexagon HTP (experimental)"),
    ;
    companion object {
        fun fromId(id: String?): AiBackend = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** KV-cache precision. F16 is the quality default; Q8_0 and Q4_0 halve
 *  and quarter the cache footprint respectively for tight-RAM devices. */
enum class KvCacheType(val id: String, val label: String) {
    F16("f16", "FP16 (best quality)"),
    Q8_0("q8_0", "Q8 (half memory)"),
    Q4_0("q4_0", "Q4 (quarter memory)"),
    ;
    companion object {
        fun fromId(id: String?): KvCacheType = entries.firstOrNull { it.id == id } ?: F16
    }
}

/** Device performance class — assigned by [AiEngineDetector] from RAM,
 *  Vulkan capability, SoC. Drives default model + context + backend. */
enum class AiTier { HIGH, MID, LOW }

/** A downloadable / installable model. The URL points at a HuggingFace
 *  GGUF resolve link; sha256 is verified post-download. ramRequiredMb
 *  is a rough upper-bound including KV cache for the model's recommended
 *  context — used to gate which models the picker shows on small devices. */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val sizeMb: Int,
    val ramRequiredMb: Int,
    val recommendedContext: Int,
    val downloadUrl: String,
    val sha256: String,
    val description: String,
) {
    val fileName: String get() = "$id.gguf"
}

/** The runtime profile passed to `llama-server` as CLI flags. Built either
 *  by the detector (auto-detect) or by the user via Settings. */
data class BackendProfile(
    val backend: AiBackend,
    val modelId: String,
    val contextSize: Int,
    val gpuLayers: Int,        // -1 = all layers on GPU, 0 = CPU only, N = first N on GPU
    val threads: Int,          // -1 = pick big cores automatically, N = explicit
    val flashAttention: Boolean,
    val kvCacheType: KvCacheType,
    val batchSize: Int,
    val mmap: Boolean,
    val mlock: Boolean,
)

/** Current state of the AI engine. Mirrored as a StateFlow by
 *  [AiEngineManager] so the UI can render a status pill + start/stop
 *  button correctness. */
sealed class AiEngineState {
    data object Idle : AiEngineState()
    data object Starting : AiEngineState()
    data class Running(val modelId: String, val backend: AiBackend) : AiEngineState()
    data class Stopping(val reason: String) : AiEngineState()
    data class Failed(val message: String) : AiEngineState()
}

/** Reported device capability snapshot — surfaced in the Settings panel
 *  ("Detected: Adreno 740, 12 GB RAM, Vulkan 1.3"). */
data class DeviceCapabilities(
    val tier: AiTier,
    val totalRamGb: Int,
    val socModel: String,
    val vulkanLevel: Int,        // 0=none, 1=v1.0, 2=v1.1, 3=v1.2, 4=v1.3
    val isQualcomm: Boolean,
    val isHinge: Boolean,        // foldable hinge sensor available (Z Fold etc.)
)

/** The catalogue of models the picker shows. Hard-coded for predictability
 *  — adding a model means a code change with a verified SHA256. URLs are
 *  HuggingFace's `resolve/main` direct-download links. */
object ModelCatalogue {
    val all: List<ModelSpec> = listOf(
        ModelSpec(
            id = "qwen2.5-0.5b-q4",
            displayName = "Qwen2.5 0.5B (tiny, fast)",
            sizeMb = 397,
            ramRequiredMb = 800,
            recommendedContext = 4096,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sha256 = "",  // populated on first download; verified-from-then-on
            description = "Command synthesis only. Real-time on any device.",
        ),
        ModelSpec(
            id = "tinyllama-1.1b-q4",
            displayName = "TinyLlama 1.1B (balanced)",
            sizeMb = 669,
            ramRequiredMb = 1500,
            recommendedContext = 2048,
            downloadUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            sha256 = "",
            description = "Short Q&A, command help. ~10-15 tok/s on mid-tier.",
        ),
        ModelSpec(
            id = "qwen2.5-3b-q4",
            displayName = "Qwen2.5 3B Instruct (recommended)",
            sizeMb = 1929,
            ramRequiredMb = 3500,
            recommendedContext = 4096,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            sha256 = "",
            description = "Good chat, output explanation, scripting. Z Fold 5 ~15 tok/s.",
        ),
        ModelSpec(
            id = "phi-3-mini-q4",
            displayName = "Phi-3 Mini 3.8B (quality)",
            sizeMb = 2390,
            ramRequiredMb = 4500,
            recommendedContext = 4096,
            downloadUrl = "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf",
            sha256 = "",
            description = "Microsoft's flagship small model. Z Fold 5 ~10-12 tok/s.",
        ),
        ModelSpec(
            id = "llama-3.1-8b-q4",
            displayName = "Llama 3.1 8B Instruct (heavy)",
            sizeMb = 4920,
            ramRequiredMb = 6500,
            recommendedContext = 4096,
            downloadUrl = "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
            sha256 = "",
            description = "Full chat & reasoning. Needs ≥12 GB RAM phone. Z Fold 5 ~5-7 tok/s.",
        ),
    )

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }
}
