/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * AI Engine settings section — embedded into SettingsScreen. Self-injects
 * its own AiEngineSettingsViewModel via hiltViewModel(); the parent
 * SettingsScreen just calls AiEngineSection() in the right place.
 */
package com.excp.podroid.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.ai.AiBackend
import com.excp.podroid.ai.AiEngineService
import com.excp.podroid.ai.AiEngineState
import com.excp.podroid.ai.KvCacheType
import com.excp.podroid.ai.ModelCatalogue
import com.excp.podroid.ai.ModelManager
import com.excp.podroid.ui.components.PodroidListRow
import com.excp.podroid.ui.components.PodroidSectionLabel
import com.excp.podroid.ui.components.PodroidSwitch
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiEngineSection(vm: AiEngineSettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Probe once per composition. Hardware caps don't change at runtime.
    val caps = remember { vm.capabilities() }
    val binaryAvailable = remember { vm.isBinaryAvailable() }

    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val state by vm.processState.collectAsStateWithLifecycle()
    val downloadEvent by vm.downloadProgress.collectAsStateWithLifecycle(initialValue = null)
    // installedModelIds() reads the filesystem; recompute whenever a
    // download event flows through. Cheap (just listFiles + length check).
    val installed = remember(downloadEvent) { vm.installedModelIds() }

    PodroidSectionLabel("AI Engine")

    if (!binaryAvailable) {
        // The `ai` workflow job soft-fails — if the llama-server binary
        // didn't ship with this APK, show why and link the user to a
        // future rebuild rather than a permanently-broken toggle.
        Text(
            "AI engine not bundled in this build (libllama-server.so missing). " +
                "Re-run the `ai` step in build-app.yml — it's parallel and safe to retry. " +
                "Everything else in the app still works without it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }

    Text(
        "Local llama.cpp server. Reachable from the VM at " +
            "http://10.0.2.2:${vm.port}/v1/chat/completions (use: podroid-ai \"prompt\").",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )

    Text(
        "Detected: ${caps.socModel} · ${caps.totalRamGb} GB RAM · " +
            (if (caps.vulkanLevel >= 2) "Vulkan 1.${caps.vulkanLevel - 1}" else "No Vulkan") +
            " · Tier ${caps.tier.name.lowercase()}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp),
    )

    val p = profile ?: return
    val activeModelInstalled = p.modelId in installed

    PodroidListRow(
        label = "Enable AI engine",
        value = when (val s = state) {
            AiEngineState.Idle           -> if (activeModelInstalled) "stopped" else "no model — download below"
            AiEngineState.Starting       -> "starting…"
            is AiEngineState.Running     -> "running · ${s.modelId}"
            is AiEngineState.Stopping    -> "stopping (${s.reason})"
            is AiEngineState.Failed      -> "failed: ${s.message.take(40)}"
        },
        rightSlot = {
            // Disable the switch when no model file is present — flicking it
            // would only produce a Failed state, not a Running engine.
            PodroidSwitch(
                checked = enabled && activeModelInstalled,
                enabled = activeModelInstalled,
                onCheckedChange = { on ->
                    vm.setEnabled(on)
                    if (on) AiEngineService.start(context) else AiEngineService.stop(context)
                },
            )
        },
    )

    // ── Model picker ───────────────────────────────────────────────────────
    Text(
        "Model",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    ModelCatalogue.all.forEach { spec ->
        val isInstalled = spec.id in installed
        val isActive = spec.id == p.modelId
        val activeDownload = downloadEvent?.takeIf { it.modelId == spec.id }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        spec.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "active",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    "${spec.sizeMb} MB · ~${"%.1f".format(spec.ramRequiredMb / 1024.0)} GB RAM · ${spec.description}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (val ev = activeDownload) {
                    is ModelManager.DownloadEvent.Progress -> {
                        LinearProgressIndicator(
                            progress = {
                                if (ev.total > 0) (ev.written.toFloat() / ev.total) else 0f
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    is ModelManager.DownloadEvent.Failed -> {
                        Text(
                            "download failed: ${ev.reason}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> Unit
                }
            }
            when {
                !isInstalled -> TextButton(onClick = { vm.downloadModel(spec.id) }) { Text("Download") }
                !isActive    -> TextButton(onClick = { vm.setModelId(spec.id) })   { Text("Use") }
                else         -> TextButton(onClick = { vm.deleteModel(spec.id) })  { Text("Delete") }
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // ── Performance ────────────────────────────────────────────────────────
    Text(
        "Performance",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    SettingChipRow(
        label = "Backend",
        options = AiBackend.entries.filter {
            // Hexagon HTP only makes sense on Qualcomm; hide elsewhere.
            it != AiBackend.HEXAGON || caps.isQualcomm
        }.map { it to it.label },
        selected = p.backend,
        onSelect = vm::setBackend,
    )
    SettingChipRow(
        label = "Context size",
        options = listOf(1024, 2048, 4096, 8192).map { it to "$it tokens" },
        selected = p.contextSize,
        onSelect = vm::setContextSize,
    )
    SettingChipRow(
        label = "GPU layers",
        options = listOf(
            0 to "0 (CPU)", 12 to "12", 24 to "24", 32 to "32", -1 to "All",
        ),
        selected = p.gpuLayers,
        onSelect = vm::setGpuLayers,
    )
    SettingChipRow(
        label = "Threads",
        options = listOf(
            -1 to "Auto", 2 to "2", 4 to "4", 6 to "6", 8 to "8",
        ),
        selected = p.threads,
        onSelect = vm::setThreads,
    )
    SettingChipRow(
        label = "KV-cache precision",
        options = KvCacheType.entries.map { it to it.label },
        selected = p.kvCacheType,
        onSelect = vm::setKvCacheType,
    )
    SettingChipRow(
        label = "Batch size",
        options = listOf(128, 256, 512, 1024).map { it to it.toString() },
        selected = p.batchSize,
        onSelect = vm::setBatchSize,
    )
    PodroidListRow(
        label = "Flash attention",
        rightSlot = { PodroidSwitch(p.flashAttention, vm::setFlashAttention) },
    )
    PodroidListRow(
        label = "Memory map model (mmap)",
        rightSlot = { PodroidSwitch(p.mmap, vm::setMmap) },
    )
    PodroidListRow(
        label = "Pin weights in RAM (mlock)",
        rightSlot = { PodroidSwitch(p.mlock, vm::setMlock) },
    )

    // ── Power & thermal ────────────────────────────────────────────────────
    val pauseThermal by vm.pauseOnThermal.collectAsStateWithLifecycle(true)
    val smallLowBatt by vm.smallCoresLowBattery.collectAsStateWithLifecycle(true)
    Text(
        "Power & thermal",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    PodroidListRow(
        label = "Pause on thermal warning",
        rightSlot = { PodroidSwitch(pauseThermal, vm::setPauseOnThermal) },
    )
    PodroidListRow(
        label = "Small cores only when battery <30%",
        rightSlot = { PodroidSwitch(smallLowBatt, vm::setSmallCoresLowBattery) },
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    TextButton(onClick = { scope.launch { vm.resetToDetected() } }) {
        Text("Reset to detected defaults")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SettingChipRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, lbl) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(lbl, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}
