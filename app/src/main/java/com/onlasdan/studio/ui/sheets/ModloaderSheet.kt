package com.onlasdan.studio.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.onlasdan.studio.R
import com.onlasdan.studio.core.ImgEntry
import com.onlasdan.studio.loader.LoaderLooseFile
import com.onlasdan.studio.ui.FloatingSheet
import com.onlasdan.studio.ui.ModelIdHint
import com.onlasdan.studio.ui.components.SearchableChoiceSheet
import com.onlasdan.studio.ui.components.SheetChoice
import com.onlasdan.studio.ui.components.SheetTitle
import com.onlasdan.studio.ui.theme.SectionHeaderStyle
import java.util.Locale

/**
 * Modloader sheet: sends one .dff/.txd payload — an entry from the open
 * IMG archive, or a loose file picked from storage — to the running game
 * through the in-game plugin (see loader/LoaderClient).
 * Stateless — all state lives in ImgToolViewModel.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ModloaderSheet(
    onDismissRequest: () -> Unit,
    hasArchive: Boolean,
    modelEntries: List<ImgEntry>,
    selectedEntry: ImgEntry?,
    selectedFile: LoaderLooseFile?,
    modelIdText: String,
    modelIdHint: ModelIdHint?,
    ideModelCount: Int?,
    ideBusy: Boolean,
    connected: Boolean?,
    busy: Boolean,
    feedback: String?,
    onCheckConnection: () -> Unit,
    onSelectEntry: (ImgEntry) -> Unit,
    onPickFile: () -> Unit,
    onModelIdChange: (String) -> Unit,
    onScanIde: () -> Unit,
    onSubstitute: () -> Unit,
    onReload: () -> Unit
) {
    var showEntryPicker by remember { mutableStateOf(false) }

    FloatingSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                SheetTitle(stringResource(R.string.modloader_title))
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.modloader_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Connection status + ping
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (connected) {
                                true -> stringResource(R.string.modloader_status_connected)
                                false -> stringResource(R.string.modloader_status_disconnected)
                                null -> stringResource(R.string.modloader_status_unknown)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (connected == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Text(
                            text = stringResource(R.string.modloader_plugin_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onCheckConnection,
                        enabled = !busy,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.modloader_check_connection))
                    }
                }
            }

            // Model payload picker: an entry of the open archive, or a
            // loose file from storage. Picking one clears the other.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "MODEL FILES",
                    style = SectionHeaderStyle,
                    color = MaterialTheme.colorScheme.primary
                )
                when {
                    !hasArchive -> Text(
                        text = stringResource(R.string.modloader_no_archive),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    modelEntries.isEmpty() -> Text(
                        text = stringResource(R.string.modloader_no_models),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { showEntryPicker = true }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedEntry?.cleanName
                                    ?: stringResource(R.string.modloader_no_entry_selected),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            if (selectedEntry != null) {
                                Text(
                                    text = formatBytes(selectedEntry.sizeInBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Outlined.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (selectedFile != null) {
                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(
                            text = selectedFile.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(
                                R.string.modloader_file_source,
                                formatBytes(selectedFile.sizeBytes)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OutlinedButton(
                    onClick = onPickFile,
                    enabled = !busy,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.modloader_choose_file))
                }
            }

            // IDE index (auto-fill source for the Model ID field)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "INDEKS IDE",
                    style = SectionHeaderStyle,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (ideModelCount != null) {
                            pluralStringResource(
                                R.plurals.modloader_ide_status,
                                ideModelCount,
                                ideModelCount
                            )
                        } else {
                            stringResource(R.string.modloader_ide_none)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onScanIde,
                        enabled = !busy && !ideBusy,
                        shape = CircleShape
                    ) {
                        if (ideBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            stringResource(
                                if (ideModelCount != null) {
                                    R.string.modloader_ide_rescan
                                } else {
                                    R.string.modloader_ide_scan
                                }
                            )
                        )
                    }
                }
            }

            // Model ID + actions
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = modelIdText,
                    onValueChange = onModelIdChange,
                    singleLine = true,
                    enabled = !busy,
                    label = { Text(stringResource(R.string.modloader_model_id_label)) },
                    supportingText = {
                        Text(
                            when (val hint = modelIdHint) {
                                ModelIdHint.FromIndex ->
                                    stringResource(R.string.modloader_ide_hint_from_index)

                                is ModelIdHint.TxdCandidates ->
                                    pluralStringResource(
                                        R.plurals.modloader_ide_hint_candidates,
                                        hint.count,
                                        hint.count
                                    )

                                ModelIdHint.NotInIndex ->
                                    stringResource(R.string.modloader_ide_hint_not_found)

                                null -> stringResource(R.string.modloader_model_id_help)
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onSubstitute,
                        enabled = !busy &&
                            (selectedEntry != null || selectedFile != null) &&
                            modelIdText.isNotEmpty(),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.modloader_substitute), fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = onReload,
                        enabled = !busy && modelIdText.isNotEmpty(),
                        shape = CircleShape,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.modloader_reload), fontWeight = FontWeight.SemiBold)
                    }
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }

            if (feedback != null) {
                Text(
                    text = feedback,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showEntryPicker) {
        // Archive entry lists can hold thousands of rows (gta3.img) — the
        // lazy searchable picker is mandatory here; ChoiceSheet composes
        // every row eagerly and ANRs on lists this size.
        SearchableChoiceSheet(
            title = stringResource(R.string.modloader_pick_entry_title),
            choices = modelEntries.map { entry ->
                SheetChoice(
                    label = entry.cleanName,
                    supporting = formatBytes(entry.sizeInBytes),
                    onClick = { onSelectEntry(entry) }
                )
            },
            searchLabel = stringResource(R.string.modloader_search_entry),
            emptyText = stringResource(R.string.modloader_search_no_results),
            onDismissRequest = { showEntryPicker = false }
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}
