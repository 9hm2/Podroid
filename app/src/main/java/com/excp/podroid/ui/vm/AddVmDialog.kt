/*
 * Add-VM dialog — wraps the SAF file picker, asks for a friendly name +
 * persistent storage size, and triggers the import on the ViewModel.
 *
 * Lifecycle:
 *   1. User opens the dialog from HomeScreen / SetupScreen.
 *   2. They tap the file-picker row; SAF returns a Uri. We sniff the display
 *      name and pre-fill distro + version (RootfsFileNameParser).
 *   3. They confirm; the ViewModel runs the import on Dispatchers.IO. While
 *      copying we show a determinate progress bar.
 *   4. On Done we dismiss; on Failed we show the error inline.
 */
package com.excp.podroid.ui.vm

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AddVmDialog(
    onDismiss: () -> Unit,
    viewModel: VmManagementViewModel = hiltViewModel(),
) {
    val importState by viewModel.importState.collectAsStateWithLifecycle()

    var pickedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var displayName by remember { mutableStateOf("") }
    var name        by remember { mutableStateOf("") }
    var distro      by remember { mutableStateOf("") }
    var version     by remember { mutableStateOf("") }
    var initSystem  by remember { mutableStateOf("openrc") }
    var storageGb   by remember { mutableIntStateOf(DefaultStorageSizeGb) }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pickedUri = uri
        // Take a persistable URI permission so a later background import
        // (we re-stream on confirm) survives the activity lifecycle.
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        // Pull the human display name + guess distro/version.
        val dn = runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        displayName = dn.orEmpty()
        val parsed = RootfsFileNameParser.parse(displayName)
        if (distro.isBlank())     distro = parsed.distro
        if (version.isBlank())    version = parsed.version
        if (parsed.distro.isNotEmpty()) initSystem = parsed.initSystem
        if (name.isBlank() && parsed.distro.isNotEmpty()) {
            name = parsed.distro.replaceFirstChar(Char::uppercase) +
                if (parsed.version.isNotEmpty()) " ${parsed.version}" else ""
        }
    }

    // Auto-dismiss when import completes (parent handles the snackbar).
    LaunchedEffect(importState) {
        if (importState is ImportState.Done) {
            onDismiss()
            viewModel.resetImport()
        }
    }

    val busy = importState is ImportState.Copying
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Import VM") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Pick a Podroid rootfs squashfs file. The file is copied into the app's private storage; you can delete the source after.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                AssistChip(
                    onClick = {
                        picker.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    label = {
                        Text(
                            if (displayName.isNotEmpty()) displayName
                            else "Choose .squashfs file…",
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    },
                    enabled = !busy,
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = distro,
                        onValueChange = { distro = it },
                        label = { Text("Distro") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = version,
                        onValueChange = { version = it },
                        label = { Text("Version") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("openrc", "systemd", "runit").forEach { kind ->
                        AssistChip(
                            onClick = { initSystem = kind },
                            label = { Text(kind) },
                            enabled = !busy,
                            colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                                containerColor = if (initSystem == kind)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        )
                    }
                }

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
                    Text(
                        "Fixed at import; ext4 image grows up to this on use, can't be shrunk later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        "Import failed: ${failed.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && pickedUri != null && distro.isNotBlank() && version.isNotBlank(),
                onClick = {
                    val uri = pickedUri ?: return@TextButton
                    viewModel.import(
                        src = uri,
                        name = name.trim(),
                        distro = distro.trim().lowercase(),
                        distroVersion = version.trim(),
                        initSystem = initSystem,
                        storageSizeGb = storageGb,
                    )
                },
            ) { Text(if (busy) "Importing…" else "Import") }
        },
        dismissButton = {
            TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) {
                Text("Cancel")
            }
        },
        shape = RoundedCornerShape(20.dp),
    )
}

private fun humanBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = listOf("KB", "MB", "GB")
    var v = b.toDouble() / 1024.0
    for (unit in units) {
        if (v < 1024.0) return "%.1f %s".format(v, unit)
        v /= 1024.0
    }
    return "%.1f TB".format(v)
}
