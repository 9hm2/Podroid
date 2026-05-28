/*
 * Reusable storage-size chip selector. Used by AddVmDialog and
 * DownloadVmDialog (and any future VM-related flow). The size buckets match
 * what the single-VM setup wizard used to offer: 2 / 4 / 8 / 16 / 32 / 64 GB.
 *
 * The chosen value is fixed at import time — the ext4 image can grow up to
 * this on use but can't be shrunk without rebuilding the VM. Same constraint
 * the old setup-wizard chooser had.
 */
package com.excp.podroid.ui.vm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Canonical storage-size buckets shared by every VM-creation flow. */
val StorageSizeOptions = listOf(2, 4, 8, 16, 32, 64)

const val DefaultStorageSizeGb = 8

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StorageSizeChips(
    selectedGb: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean = true,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3,
    ) {
        StorageSizeOptions.forEach { gb ->
            FilterChip(
                selected = gb == selectedGb,
                onClick = { onSelect(gb) },
                enabled = enabled,
                label = {
                    Text(
                        text = "$gb GB",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (gb == selectedGb) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                shape = RoundedCornerShape(16.dp),
            )
        }
    }
}
