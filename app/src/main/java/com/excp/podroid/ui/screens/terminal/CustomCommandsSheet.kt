/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Modal bottom sheet for one-tap user commands. Lives over the terminal
 * screen so picking a command immediately runs it in the active tab and
 * dismisses back to the terminal. Each row carries inline reorder (↑/↓),
 * edit (pencil) and delete controls; the editor dialog is reused for both
 * "Add" and "Edit". Run buttons are gated on VM state — a banner explains
 * the disabled state when the VM isn't Running yet.
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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

/** Editor state — closed, adding fresh, or editing an existing row. */
private sealed interface EditorTarget {
    data object Closed : EditorTarget
    data object Adding : EditorTarget
    data class Editing(val cc: CustomCommand) : EditorTarget
}

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
    var editor by remember { mutableStateOf<EditorTarget>(EditorTarget.Closed) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                "Custom Commands",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "One-tap shortcuts that run in the active terminal tab. Use ↑/↓ to reorder, pencil to edit.",
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
                    itemsIndexed(commands, key = { _, cc -> cc.id }) { index, cc ->
                        CommandRow(
                            cc = cc,
                            canRun = canRun,
                            canMoveUp = index > 0,
                            canMoveDown = index < commands.lastIndex,
                            onRun = {
                                viewModel.runCommand(cc)
                                onDismiss()
                            },
                            onMoveUp = { viewModel.moveCustomCommand(cc.id, -1) },
                            onMoveDown = { viewModel.moveCustomCommand(cc.id, +1) },
                            onEdit = { editor = EditorTarget.Editing(cc) },
                            onDelete = { viewModel.removeCustomCommand(cc.id) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { editor = EditorTarget.Adding },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  Add command")
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    when (val state = editor) {
        EditorTarget.Closed -> Unit
        EditorTarget.Adding -> CommandEditorDialog(
            existing = null,
            onDismiss = { editor = EditorTarget.Closed },
            onSave = { _, name, cmd, newTab ->
                viewModel.addCustomCommand(name, cmd, newTab)
                editor = EditorTarget.Closed
            },
        )
        is EditorTarget.Editing -> CommandEditorDialog(
            existing = state.cc,
            onDismiss = { editor = EditorTarget.Closed },
            onSave = { id, name, cmd, newTab ->
                viewModel.updateCustomCommand(
                    state.cc.copy(name = name, command = cmd, openNewTab = newTab),
                )
                editor = EditorTarget.Closed
            },
        )
    }
}

@Composable
private fun CommandRow(
    cc: CustomCommand,
    canRun: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRun: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Compact ↑/↓ stack — 32 dp each so two arrows fit in the height
        // of a single 48 dp IconButton, no taller than the row's content.
        Column {
            IconButton(
                onClick = onMoveUp,
                enabled = canMoveUp,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "Move up",
                    modifier = Modifier.size(20.dp),
                    tint = if (canMoveUp) MaterialTheme.colorScheme.onSurface
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = canMoveDown,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Move down",
                    modifier = Modifier.size(20.dp),
                    tint = if (canMoveDown) MaterialTheme.colorScheme.onSurface
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            }
        }
        Spacer(Modifier.size(4.dp))
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
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** Shared dialog for both Add and Edit. [existing] = null means Add mode;
 *  non-null pre-fills the fields and switches confirmButton to "Save". The
 *  CustomCommand id is preserved across edits so DataStore updates in place
 *  instead of inserting a duplicate. */
@Composable
private fun CommandEditorDialog(
    existing: CustomCommand?,
    onDismiss: () -> Unit,
    onSave: (id: String?, name: String, command: String, openNewTab: Boolean) -> Unit,
) {
    // remember(existing?.id) ensures the fields reset when the editor target
    // changes from one Editing(x) to Editing(y) without unmounting first.
    var name   by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var cmd    by remember(existing?.id) { mutableStateOf(existing?.command ?: "") }
    var newTab by remember(existing?.id) { mutableStateOf(existing?.openNewTab ?: false) }
    val isEdit = existing != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Edit command" else "Add command") },
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
                onClick = { onSave(existing?.id, name, cmd, newTab) },
                enabled = name.isNotBlank() && cmd.isNotBlank(),
            ) { Text(if (isEdit) "Save" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
