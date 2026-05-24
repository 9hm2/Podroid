/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Modal bottom sheet for one-tap user commands. Lives over the terminal
 * screen so picking a command immediately runs it in the active tab and
 * dismisses back to the terminal. Run buttons are gated on VM state — a
 * banner explains the disabled state when the VM isn't Running yet.
 */
package com.excp.podroid.ui.screens.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.data.repository.CustomCommand
import com.excp.podroid.engine.VmState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomCommandsSheet(
    viewModel: TerminalViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val commands by viewModel.customCommands.collectAsStateWithLifecycle()
    val vmState by viewModel.vmState.collectAsStateWithLifecycle()
    val canRun = vmState is VmState.Running
    var showAdd by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                "Custom Commands",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "One-tap shortcuts that run in the active terminal tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!canRun) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Start the VM first — commands run inside the VM's shell.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))

            if (commands.isEmpty()) {
                Text(
                    "No commands yet — add one below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp).fillMaxWidth(),
                ) {
                    items(commands, key = { it.id }) { cc ->
                        CommandRow(
                            cc = cc,
                            canRun = canRun,
                            onRun = {
                                viewModel.runCommand(cc)
                                onDismiss()
                            },
                            onDelete = { viewModel.removeCustomCommand(cc.id) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showAdd = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  Add command")
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showAdd) {
        AddCommandDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, cmd, newTab ->
                viewModel.addCustomCommand(name, cmd, newTab)
                showAdd = false
            },
        )
    }
}

@Composable
private fun CommandRow(
    cc: CustomCommand,
    canRun: Boolean,
    onRun: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(cc.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                cc.command,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (cc.openNewTab) {
                Text(
                    "Runs in the next tab",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(onClick = onRun, enabled = canRun) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Run",
                tint = if (canRun) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AddCommandDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, command: String, openNewTab: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }
    var newTab by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add command") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cmd,
                    onValueChange = { cmd = it },
                    label = { Text("Command") },
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = newTab, onCheckedChange = { newTab = it })
                    Text(
                        "Open in next tab (auto-switches)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, cmd, newTab) },
                enabled = name.isNotBlank() && cmd.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
