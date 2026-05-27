/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Device capability detector for the AI engine. Picks a [BackendProfile]
 * that the user can either accept (Auto) or override field-by-field in
 * Settings. The tier heuristic is intentionally simple — RAM bucket +
 * Vulkan capability + Qualcomm/Tensor probe — because the device pool is
 * too fragmented for exhaustive whitelists. A later "Run device
 * benchmark" button (TODO) supersedes the heuristic with measured tok/s.
 */
package com.excp.podroid.ai

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiEngineDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Snapshot of what we can infer at install time. Cheap to call. */
    fun probe(): DeviceCapabilities {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val totalRamGb = (mem.totalMem / GB).toInt()

        // Build.SOC_MODEL is API 31+. Empty / "unknown" on older devices.
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL ?: "unknown"
        } else {
            "${Build.HARDWARE} (${Build.BOARD})"
        }
        val isQualcomm = soc.contains("SM", ignoreCase = true) ||
            soc.contains("SDM", ignoreCase = true) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                Build.SOC_MANUFACTURER.equals("QTI", ignoreCase = true))

        val vulkanLevel = probeVulkanLevel()

        // Foldable hinge sensor — Z Fold series exposes TYPE_HINGE_ANGLE.
        // Used later by the service to pause inference while the device is
        // mid-fold (GPU contention causes UI hitching).
        val isHinge = run {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            sm?.getDefaultSensor(SENSOR_TYPE_HINGE_ANGLE) != null
        }

        val tier = pickTier(totalRamGb, vulkanLevel)
        return DeviceCapabilities(tier, totalRamGb, soc, vulkanLevel, isQualcomm, isHinge)
    }

    /** Default BackendProfile for the detected tier. The repository keeps
     *  any explicit user override across launches; this is only consulted
     *  when nothing has been written yet, or when the user taps "Reset to
     *  detected defaults". */
    fun defaultProfileFor(caps: DeviceCapabilities): BackendProfile = when (caps.tier) {
        AiTier.HIGH -> BackendProfile(
            backend = AiBackend.AUTO,
            modelId = "qwen2.5-3b-q4",
            // Bumped from 4096 → 16384 so Crush / gptme's ~9 KB tool-
            // prompt fits with room for response. Qwen2.5 family supports
            // 32K native, KV cache stays under 5 GB at 16K with F16.
            contextSize = 16384,
            gpuLayers = -1,
            threads = -1,
            flashAttention = true,
            kvCacheType = KvCacheType.F16,
            batchSize = 2048,        // bumped from 512 — prompt eval throughput
                                     // grows almost linearly with batch on NEON.
            mmap = true,
            mlock = caps.totalRamGb >= 12,
        )
        AiTier.MID -> BackendProfile(
            backend = AiBackend.AUTO,
            modelId = "tinyllama-1.1b-q4",
            contextSize = 8192,    // up from 2048 — TinyLlama's native is 2K but
                                   // Qwen models the user might pick handle more.
            gpuLayers = -1,
            threads = -1,
            flashAttention = true,
            kvCacheType = KvCacheType.F16,
            batchSize = 1024,        // up from 256
            mmap = true,
            mlock = false,
        )
        AiTier.LOW -> BackendProfile(
            backend = AiBackend.CPU,
            modelId = "qwen2.5-0.5b-q4",
            contextSize = 4096,    // up from 1024 — Qwen 0.5B is 32K-capable.
            gpuLayers = 0,
            threads = 4,
            flashAttention = false,
            kvCacheType = KvCacheType.Q8_0,
            batchSize = 512,
            mmap = true,
            mlock = false,
        )
    }

    /** Resolve `backend = AUTO` against the device capabilities. If the
     *  user explicitly picked CPU / VULKAN / HEXAGON we honour that even
     *  if it'll be slow.
     *
     *  Adreno reality: even with Q4_0 quants, llama.cpp's Vulkan backend
     *  on Snapdragon 8 Gen 2/3 (Adreno 7xx) fails to compile multiple
     *  matmul shader variants — not just the K-quant ones. The bug is
     *  driver-level (missing extensions) and llama.cpp doesn't fall
     *  back gracefully for the failing shaders. End-to-end testing
     *  shows the only reliable path on Qualcomm is CPU+NEON; the
     *  Cortex-X3 cluster delivers 12-15 tok/s on TinyLlama Q4 which
     *  is plenty for Aider / shell-gpt workflows.
     *
     *  AUTO therefore picks CPU on Qualcomm regardless of tier. Mali /
     *  PowerVR / Intel / AMD keep the Vulkan path (their drivers
     *  compile llama.cpp's shaders cleanly).
     */
    fun resolveBackend(profile: BackendProfile, caps: DeviceCapabilities): AiBackend =
        if (profile.backend != AiBackend.AUTO) profile.backend
        else when {
            caps.isQualcomm -> AiBackend.CPU
            caps.tier == AiTier.LOW -> AiBackend.CPU
            caps.vulkanLevel >= 2 -> AiBackend.VULKAN
            else -> AiBackend.CPU
        }

    private fun pickTier(ramGb: Int, vulkanLevel: Int): AiTier = when {
        // ≥11 GiB RAM (12 GB phone) + Vulkan 1.2+ = high tier. Z Fold 5,
        // Pixel 8 Pro, S24 Ultra, OnePlus 12 all qualify.
        ramGb >= 11 && vulkanLevel >= 3 -> AiTier.HIGH
        // Mid-tier: 8+ GB + workable Vulkan. Pixel 7, S22, mid-tier OPlus.
        ramGb >= 7 && vulkanLevel >= 2 -> AiTier.MID
        // Everything else — CPU-only, smallest model.
        else -> AiTier.LOW
    }

    /** Vulkan capability via PackageManager — cheap, doesn't load
     *  libvulkan.so. Returns 0=none, 2=1.1, 3=1.2, 4=1.3. */
    private fun probeVulkanLevel(): Int {
        val pm = context.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)) return 0
        // FEATURE_VULKAN_HARDWARE_VERSION's `version` field is the encoded
        // VK_MAKE_VERSION(major,minor,patch). We only branch on minor.
        val features = pm.systemAvailableFeatures
        val v = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
            ?: return 0
        val major = (v.version shr 22) and 0x7F
        val minor = (v.version shr 12) and 0x3FF
        return when {
            major == 1 && minor >= 3 -> 4
            major == 1 && minor == 2 -> 3
            major == 1 && minor == 1 -> 2
            major == 1 && minor == 0 -> 1
            major > 1 -> 4
            else -> 0
        }
    }

    companion object {
        private const val GB = 1024L * 1024 * 1024
        // Sensor.TYPE_HINGE_ANGLE = 36 (API 30+); reference numerically to
        // avoid pinning the import to a specific Sensor constant rename.
        private const val SENSOR_TYPE_HINGE_ANGLE = 36
    }
}
