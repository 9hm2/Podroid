package com.excp.podroid.ui.screens.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.BuildConfig
import com.excp.podroid.engine.VmState
import com.excp.podroid.ui.components.AdaptiveContainer
import com.excp.podroid.ui.components.PodroidDestructiveButton
import com.excp.podroid.ui.components.PodroidGhostButton
import com.excp.podroid.ui.components.PodroidListRow
import com.excp.podroid.ui.components.PodroidPrimaryButton
import com.excp.podroid.ui.components.PodroidSectionLabel
import com.excp.podroid.ui.components.PodroidStatus
import com.excp.podroid.ui.components.PodroidStatusColors
import com.excp.podroid.ui.components.PodroidTopBar
import com.excp.podroid.ui.theme.PodroidTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateToTerminal: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val vmState by viewModel.vmState.collectAsStateWithLifecycle()
    val bootStage by viewModel.bootStage.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val meta by viewModel.meta.collectAsStateWithLifecycle()
    val uptimeTick by viewModel.uptimeTicker.collectAsStateWithLifecycle()

    val isRunning  = vmState is VmState.Running
    val isStarting = vmState is VmState.Starting
    val uptimeLabel = viewModel.uptimeLabel(uptimeTick)
    val phoneIp = viewModel.phoneIp()

    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdate() },
            icon  = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
            title = { Text("Update available") },
            text  = { Text("Version ${info.latestVersion} is available. You have ${BuildConfig.VERSION_NAME}.") },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl)))
                    viewModel.dismissUpdate()
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdate() }) { Text("Later") }
            },
        )
    }

    Scaffold(
        topBar = {
            PodroidTopBar(
                title = "Podroid",
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val isCompactHeight = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            maxWidth = if (isCompactHeight) 900 else 600,
        ) {
            if (isCompactHeight) {
                // Landscape phone / split-screen: hero on left, action column on right.
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = PodroidTokens.Spacing.XL2, vertical = PodroidTokens.Spacing.LG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = PodroidTokens.Spacing.XL2)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        HomeStatusBlock(isStarting, isRunning, vmState, bootStage, meta, uptimeLabel)
                        HomeDataSection(isRunning, vmState, meta, phoneIp)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
                    ) {
                        HomeActionButtons(
                            isRunning = isRunning,
                            isStarting = isStarting,
                            vmState = vmState,
                            onStart = { viewModel.startPodroid() },
                            onStop = { viewModel.stopVm() },
                            onRestart = { viewModel.restartVm() },
                            onOpenTerminal = onNavigateToTerminal,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = PodroidTokens.Spacing.XL),
                    verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.MD),
                ) {
                    Spacer(Modifier.height(PodroidTokens.Spacing.XL))
                    HomeStatusBlock(isStarting, isRunning, vmState, bootStage, meta, uptimeLabel)
                    HomeDataSection(isRunning, vmState, meta, phoneIp)
                    Spacer(Modifier.weight(1f))
                    HomeActionButtons(
                        isRunning = isRunning,
                        isStarting = isStarting,
                        vmState = vmState,
                        onStart = { viewModel.startPodroid() },
                        onStop = { viewModel.stopVm() },
                        onRestart = { viewModel.restartVm() },
                        onOpenTerminal = onNavigateToTerminal,
                    )
                    Spacer(Modifier.height(PodroidTokens.Spacing.XL))
                }
            }
        }
    }
}

@Composable
private fun HomeStatusBlock(
    isStarting: Boolean,
    isRunning: Boolean,
    vmState: VmState,
    bootStage: String,
    meta: HomeMeta,
    uptimeLabel: String?,
) {
    PodroidSectionLabel("VM Status")
    Text(
        text = when {
            isStarting -> "Starting"
            isRunning  -> "Running"
            else       -> "Stopped"
        },
        style = MaterialTheme.typography.displayLarge,
        color = when {
            isRunning  -> MaterialTheme.colorScheme.primary
            isStarting -> MaterialTheme.colorScheme.tertiary
            else       -> MaterialTheme.colorScheme.onSurface
        },
    )
    Spacer(Modifier.height(PodroidTokens.Spacing.SM))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (dot, label) = when {
            isRunning  -> PodroidStatusColors.Running  to (uptimeLabel ?: "Up")
            isStarting -> PodroidStatusColors.Starting to bootStage.ifEmpty { "Starting" }
            else       -> PodroidStatusColors.Stopped  to "Idle"
        }
        PodroidStatus(label = label, dotColor = dot)
        Text(
            text = meta.resourcesLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (vmState is VmState.Error) {
        Spacer(Modifier.height(PodroidTokens.Spacing.MD))
        PodroidSectionLabel("Error")
        Text(
            text = vmState.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    // Starting state: the meta row already shows the amber dot + boot-stage
    // text, which is the canonical boot indicator. No need for a separate ring.
}

@Composable
private fun HomeDataSection(
    isRunning: Boolean,
    vmState: VmState,
    meta: HomeMeta,
    phoneIp: String,
) {
    val showStarting = vmState is VmState.Starting
    val showError = vmState is VmState.Error
    if (showStarting || showError) return
    Spacer(Modifier.height(PodroidTokens.Spacing.MD))
    if (isRunning) {
        PodroidSectionLabel("Network")
        PodroidListRow(label = "Phone IP", value = phoneIp, mono = true)
        PodroidListRow(
            label = "SSH",
            value = if (meta.sshEnabled) ":9922 · podroid" else "Off",
            mono = meta.sshEnabled,
        )
        PodroidListRow(
            label = "Port forwards",
            value = if (meta.portForwardCount == 0) "None" else "${meta.portForwardCount} active",
        )
    } else {
        PodroidSectionLabel("Last session")
        if (meta.lastBootDurationMs > 0L) {
            PodroidListRow(
                label = "Booted in",
                value = formatBootDuration(meta.lastBootDurationMs),
            )
        }
        PodroidListRow(
            label = "Build",
            value = "v${BuildConfig.VERSION_NAME} · QEMU ${BuildConfig.QEMU_VERSION}",
            mono = true,
        )
    }
}

private fun formatBootDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return if (totalSec >= 60) {
        val m = totalSec / 60
        val s = totalSec % 60
        if (s == 0L) "${m}m" else "${m}m ${s}s"
    } else {
        val tenths = (ms / 100) % 10
        if (tenths == 0L) "${totalSec}s" else "${totalSec}.${tenths}s"
    }
}

@Composable
private fun HomeActionButtons(
    isRunning: Boolean,
    isStarting: Boolean,
    vmState: VmState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    if (isRunning) {
        PodroidPrimaryButton(text = "Open Terminal", onClick = onOpenTerminal)
        Spacer(Modifier.height(PodroidTokens.Spacing.SM))
        Row(horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM)) {
            PodroidGhostButton(text = "Restart", onClick = onRestart, modifier = Modifier.weight(1f))
            PodroidDestructiveButton(text = "Stop", onClick = onStop, modifier = Modifier.weight(1f))
        }
    } else if (isStarting) {
        PodroidDestructiveButton(text = "Stop", onClick = onStop)
    } else if (vmState is VmState.Error) {
        PodroidPrimaryButton(text = "Try again", onClick = onStart)
    } else {
        PodroidPrimaryButton(text = "Start VM", onClick = onStart)
    }
}
