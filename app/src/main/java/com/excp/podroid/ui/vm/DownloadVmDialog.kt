/*
 * Download-from-Release dialog. Lists the squashfs assets in the project's
 * latest GitHub Release, lets the user pick one + a storage size + a name,
 * then streams the file into the per-VM directory using the same ImportState
 * machine as the SAF importer.
 */
package com.excp.podroid.ui.vm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.data.repository.RootfsCatalogEntry

@Composable
fun DownloadVmDialog(
    onDismiss: () -> Unit,
    viewModel: VmManagementViewModel = hiltViewModel(),
) {
    val catalog by viewModel.rootfsCatalog.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()

    var selected by remember { mutableStateOf<RootfsCatalogEntry?>(null) }
    var name      by remember { mutableStateOf("") }
    var storageGb by remember { mutableIntStateOf(DefaultStorageSizeGb) }

    // Trigger the fetch the first time the dialog is shown.
    LaunchedEffect(Unit) {
        if (catalog == null) viewModel.fetchCatalog()
    }

    // Auto-dismiss when import completes.
    LaunchedEffect(importState) {
        if (importState is ImportState.Done) {
            onDismiss()
            viewModel.resetImport()
        }
    }

    val busy = importState is ImportState.Copying
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Download rootfs") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    catalog == null -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text("Fetching catalog…",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    catalog?.isEmpty() == true -> {
                        Text(
                            "No rootfs files found in the latest Release. " +
                                "Trigger the Build-rootfs workflow first, or import via Files.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        Text(
                            "Tap a rootfs to download into a new VM record:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(
                            modifier = Modifier
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            catalog!!.forEach { entry ->
                                CatalogRow(
                                    entry = entry,
                                    selected = entry == selected,
                                    enabled = !busy,
                                    onClick = {
                                        selected = entry
                                        if (name.isBlank()) {
                                            name = entry.distro
                                                .replaceFirstChar(Char::uppercase) +
                                                if (entry.distroVersion.isNotEmpty()) " ${entry.distroVersion}" else ""
                                        }
                                    },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Name") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Column {
                            Text(
                                "Persistent storage",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            StorageSizeChips(
                                selectedGb = storageGb,
                                onSelect = { storageGb = it },
                                enabled = !busy,
                            )
                        }
                    }
                }
                (importState as? ImportState.Copying)?.let { copying ->
                    val total = copying.totalBytes
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { (copying.bytesCopied.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${humanBytes(copying.bytesCopied)} / ${humanBytes(total)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                (importState as? ImportState.Failed)?.let { failed ->
                    Text(
                        "Download failed: ${failed.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && selected != null,
                onClick = {
                    val s = selected ?: return@TextButton
                    viewModel.downloadAndImport(
                        entry = s,
                        name = name.trim(),
                        storageSizeGb = storageGb,
                    )
                },
            ) { Text(if (busy) "Downloading…" else "Download") }
        },
        dismissButton = {
            TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) {
                Text("Cancel")
            }
        },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun CatalogRow(
    entry: RootfsCatalogEntry,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .border(
                if (selected) 2.dp else 1.dp,
                border,
                RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${entry.distro.replaceFirstChar(Char::uppercase)} ${entry.distroVersion}".trim(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${entry.initSystem} · ${humanBytes(entry.sizeBytes)} · ${entry.releaseTag}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun humanBytes(b: Long): String {
    if (b < 0) return "?"
    if (b < 1024) return "$b B"
    val units = listOf("KB", "MB", "GB")
    var v = b.toDouble() / 1024.0
    for (unit in units) {
        if (v < 1024.0) return "%.1f %s".format(v, unit)
        v /= 1024.0
    }
    return "%.1f TB".format(v)
}
