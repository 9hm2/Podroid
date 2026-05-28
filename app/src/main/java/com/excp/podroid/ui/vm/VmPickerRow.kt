/*
 * Horizontal card-row that shows every imported VM as a tap-to-activate chip,
 * plus a trailing "+ Add VM" tile. Lives at the top of the Home screen.
 *
 * Long-press on a record opens a small action sheet (rename/delete) so the
 * default flow stays "one tap to switch active VM".
 */
package com.excp.podroid.ui.vm

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.excp.podroid.data.repository.VmRecord

@Composable
fun VmPickerRow(
    modifier: Modifier = Modifier,
    viewModel: VmManagementViewModel = hiltViewModel(),
    vmRunning: Boolean = false,
) {
    val vms by viewModel.vms.collectAsStateWithLifecycle()
    val activeId by viewModel.activeVmId.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<VmRecord?>(null) }
    var renameTarget by remember { mutableStateOf<VmRecord?>(null) }

    if (showAdd) AddVmDialog(onDismiss = { showAdd = false })
    actionTarget?.let { target ->
        VmActionSheet(
            vm = target,
            onDismiss = { actionTarget = null },
            onRename = { renameTarget = target; actionTarget = null },
            onDelete = {
                if (target.id != activeId || !vmRunning) {
                    viewModel.delete(target.id)
                }
                actionTarget = null
            },
            canDelete = !(target.id == activeId && vmRunning),
        )
    }
    renameTarget?.let { target ->
        RenameVmDialog(
            currentName = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.rename(target.id, newName)
                renameTarget = null
            },
        )
    }

    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        vms.forEach { vm ->
            VmCard(
                vm = vm,
                active = vm.id == activeId,
                onSelect = { if (!(vmRunning && vm.id != activeId)) viewModel.setActive(vm.id) },
                onLongPress = { actionTarget = vm },
            )
        }
        AddVmTile(onClick = { showAdd = true })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VmCard(
    vm: VmRecord,
    active: Boolean,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
) {
    val borderColor =
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val borderWidth = if (active) 2.dp else 1.dp
    Box(
        modifier = Modifier
            .size(width = 132.dp, height = 64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onSelect, onLongClick = onLongPress)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(
                vm.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${vm.distro} ${vm.distroVersion} · ${vm.storageSizeGb} GB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AddVmTile(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 96.dp, height = 64.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline,
                RoundedCornerShape(14.dp),
            )
            .combinedClickableOnTap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add VM", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun VmActionSheet(
    vm: VmRecord,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(vm.name) },
        text = {
            Text(
                "${vm.distro.replaceFirstChar(Char::uppercase)} ${vm.distroVersion} • " +
                    "${vm.initSystem} • ${vm.storageSizeGb} GB",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            TextButton(onClick = onDelete, enabled = canDelete) {
                Text(if (canDelete) "Delete" else "Stop VM first")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRename) { Text("Rename") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun RenameVmDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename VM") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && name != currentName,
                onClick = { onConfirm(name.trim()) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// Compose has no combinedClickable that takes only onClick; this is a tiny
// shim to keep the Modifier chain in AddVmTile clean.
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableOnTap(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)
