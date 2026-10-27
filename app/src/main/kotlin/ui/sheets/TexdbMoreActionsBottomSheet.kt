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
 * Modern contextual sheet for Texdb Browser options matching the standardized 3-dots sheet design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TexdbMoreActionsBottomSheet(
    onDismissRequest: () -> Unit,
    folderName: String,
    onImportImages: () -> Unit,
    onImportTxd: () -> Unit,
    onRebuildCache: () -> Unit,
    onRepackDxt: () -> Unit,
    onRepackEtc: () -> Unit,
    onRepackRaw: () -> Unit,
    onCheckDff: () -> Unit,
    onApplyToGame: (() -> Unit)? = null,
    onCacheInfo: () -> Unit,
    onSelectCategory: (() -> Unit)? = null,
    categoryName: String? = null,
    sortMode: Int,
    onSetSortMode: (Int) -> Unit,
    onCloseCache: (() -> Unit)? = null
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
            // Header: Judul Sheet & Nama Folder
            Column {
                Text(
                    text = stringResource(R.string.texdb_options_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = folderName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Grup: IMPORT & REBUILD CACHE
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.cache_operations_section),
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
                            icon = Icons.Outlined.AddPhotoAlternate,
                            title = stringResource(R.string.import_from_images),
                            subtitle = stringResource(R.string.import_from_images_desc),
                            onClick = {
                                onDismissRequest()
                                onImportImages()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.FolderZip,
                            title = stringResource(R.string.import_from_txd),
                            subtitle = stringResource(R.string.import_from_txd_desc),
                            onClick = {
                                onDismissRequest()
                                onImportTxd()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.Build,
                            title = stringResource(R.string.rebuild_cache),
                            subtitle = stringResource(R.string.rebuild_cache_desc),
                            onClick = {
                                onDismissRequest()
                                onRebuildCache()
                            }
                        )
                        if (onApplyToGame != null) {
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            MoreActionRow(
                                icon = Icons.Outlined.SaveAlt,
                                title = stringResource(R.string.apply_to_game),
                                subtitle = stringResource(R.string.apply_to_game_desc),
                                onClick = {
                                    onDismissRequest()
                                    onApplyToGame()
                                }
                            )
                        }
                    }
                }
            }

            // Grup: FORMAT CONVERSION & AUDIT
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.format_integrity_section),
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
                            icon = Icons.Outlined.AutoFixHigh,
                            title = stringResource(R.string.repack_to_dxt),
                            subtitle = stringResource(R.string.repack_to_dxt_desc),
                            onClick = {
                                onDismissRequest()
                                onRepackDxt()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.AutoFixHigh,
                            title = stringResource(R.string.repack_to_etc),
                            subtitle = stringResource(R.string.repack_to_etc_desc),
                            onClick = {
                                onDismissRequest()
                                onRepackEtc()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.AutoFixHigh,
                            title = stringResource(R.string.repack_to_raw),
                            subtitle = stringResource(R.string.repack_to_raw_desc),
                            onClick = {
                                onDismissRequest()
                                onRepackRaw()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.Checklist,
                            title = stringResource(R.string.check_dff_textures),
                            subtitle = stringResource(R.string.check_dff_textures_desc),
                            onClick = {
                                onDismissRequest()
                                onCheckDff()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.Info,
                            title = stringResource(R.string.cache_info),
                            subtitle = stringResource(R.string.cache_info_desc),
                            onClick = {
                                onDismissRequest()
                                onCacheInfo()
                            }
                        )
                    }
                }
            }

            // Grup: FILTER & SORTING
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.view_sorting_section),
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
                        if (onSelectCategory != null) {
                            MoreActionRow(
                                icon = Icons.Outlined.Category,
                                title = stringResource(R.string.category_filter_format, categoryName ?: "All"),
                                subtitle = stringResource(R.string.filter_textures_desc),
                                onClick = {
                                    onDismissRequest()
                                    onSelectCategory()
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        MoreActionRow(
                            icon = Icons.Outlined.SortByAlpha,
                            title = if (sortMode == 0) stringResource(R.string.sort_by_name_active) else stringResource(R.string.sort_by_name),
                            subtitle = stringResource(R.string.sort_alphabetical_desc),
                            onClick = {
                                onSetSortMode(0)
                                onDismissRequest()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.Straighten,
                            title = if (sortMode == 1) stringResource(R.string.sort_by_size_active) else stringResource(R.string.sort_by_size),
                            subtitle = stringResource(R.string.sort_size_desc),
                            onClick = {
                                onSetSortMode(1)
                                onDismissRequest()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.AspectRatio,
                            title = if (sortMode == 2) stringResource(R.string.sort_by_dimensions_active) else stringResource(R.string.sort_by_dimensions),
                            subtitle = stringResource(R.string.sort_dimensions_desc),
                            onClick = {
                                onSetSortMode(2)
                                onDismissRequest()
                            }
                        )
                    }
                }
            }

            // Grup: CLOSE CACHE (jika diberikan)
            if (onCloseCache != null) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MoreActionRow(
                        icon = Icons.Outlined.Close,
                        title = stringResource(R.string.close_cache),
                        subtitle = stringResource(R.string.close_cache_desc),
                        iconTint = MaterialTheme.colorScheme.error,
                        onClick = {
                            onDismissRequest()
                            onCloseCache()
                        }
                    )
                }
            }
        }
    }
}
