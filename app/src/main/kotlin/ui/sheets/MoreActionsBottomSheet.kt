package com.onlasdan.studio.ui.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onlasdan.studio.R
import com.onlasdan.studio.ui.FloatingSheet
import com.onlasdan.studio.ui.theme.SectionHeaderStyle

/**
 * Contextual More Actions Sheet for active files (IMG Archive or TXD Editor).
 * Strictly contains file-specific operations (no general system/settings shortcuts).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreActionsBottomSheet(
    onDismissRequest: () -> Unit,
    fileName: String,
    isTxd: Boolean = false,
    isFileBusy: Boolean = false,
    onExportAll: () -> Unit,
    onRenameFile: (() -> Unit)? = null,
    onRebuildArchive: (() -> Unit)? = null,
    isLargeImage: Boolean = false,
    onToggleTxdViewMode: (() -> Unit)? = null,
    onAddTextures: (() -> Unit)? = null,
    onCloseFile: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    FloatingSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Judul Sheet & Nama File
            Column {
                Text(
                    text = if (isTxd) stringResource(R.string.more_actions_txd_title) else stringResource(R.string.more_actions_img_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Grup: OPERASI BERKAS SPESIFIK
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isTxd) stringResource(R.string.more_actions_txd_section) else stringResource(R.string.more_actions_img_section),
                    style = SectionHeaderStyle,
                    color = MaterialTheme.colorScheme.primary
                )

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        MoreActionRow(
                            icon = Icons.Outlined.FileDownload,
                            title = if (isTxd) stringResource(R.string.export_all_png) else stringResource(R.string.export_all_files),
                            subtitle = if (isTxd) stringResource(R.string.export_all_png_desc) else stringResource(R.string.export_all_files_desc),
                            onClick = {
                                onDismissRequest()
                                onExportAll()
                            }
                        )

                        if (isTxd && onAddTextures != null) {
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            MoreActionRow(
                                icon = Icons.Outlined.AddPhotoAlternate,
                                title = stringResource(R.string.add_new_texture),
                                subtitle = stringResource(R.string.add_new_texture_desc),
                                onClick = {
                                    onDismissRequest()
                                    onAddTextures()
                                }
                            )
                        }

                        if (isTxd && onToggleTxdViewMode != null) {
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            MoreActionRow(
                                icon = if (isLargeImage) Icons.Outlined.ViewCompact else Icons.Outlined.PhotoSizeSelectActual,
                                title = if (isLargeImage) stringResource(R.string.view_large_thumbnails) else stringResource(R.string.view_small_thumbnails),
                                subtitle = if (isLargeImage) stringResource(R.string.view_large_thumbnails_desc) else stringResource(R.string.view_small_thumbnails_desc),
                                onClick = {
                                    onDismissRequest()
                                    onToggleTxdViewMode()
                                }
                            )
                        }

                        if (onRebuildArchive != null && !isTxd) {
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            MoreActionRow(
                                icon = Icons.Outlined.Build,
                                title = stringResource(R.string.rebuild_archive),
                                subtitle = stringResource(R.string.rebuild_archive_desc),
                                onClick = {
                                    onDismissRequest()
                                    onRebuildArchive()
                                }
                            )
                        }

                        if (onRenameFile != null) {
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            MoreActionRow(
                                icon = Icons.Outlined.DriveFileRenameOutline,
                                title = stringResource(R.string.rename_file),
                                subtitle = stringResource(R.string.rename_file_desc),
                                onClick = {
                                    onDismissRequest()
                                    onRenameFile()
                                }
                            )
                        }
                    }
                }
            }

            // Grup: KELUAR & TUTUP BERKAS
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.session_management),
                    style = SectionHeaderStyle,
                    color = MaterialTheme.colorScheme.primary
                )

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MoreActionRow(
                        icon = Icons.Outlined.Close,
                        title = stringResource(R.string.close_file),
                        subtitle = if (isFileBusy) stringResource(R.string.waiting_for_process) else stringResource(R.string.close_file_return_home),
                        iconTint = if (isFileBusy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        isEnabled = !isFileBusy,
                        onClick = {
                            onDismissRequest()
                            onCloseFile()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MoreActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isEnabled: Boolean = true,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    val alpha = if (isEnabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint.copy(alpha = alpha),
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
        }
        if (isEnabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
