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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                    text = "Texdb Cache Options",
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
                    text = "CACHE OPERATIONS",
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
                            title = "Import from Images",
                            subtitle = "Encode and import PNG/JPG files into this cache",
                            onClick = {
                                onDismissRequest()
                                onImportImages()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.FolderZip,
                            title = "Import from TXD Archives",
                            subtitle = "Batch import textures from .txd files",
                            onClick = {
                                onDismissRequest()
                                onImportTxd()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.Build,
                            title = "Rebuild Cache",
                            subtitle = "Defragment texture files and recalculate listing offsets",
                            onClick = {
                                onDismissRequest()
                                onRebuildCache()
                            }
                        )
                        if (onApplyToGame != null) {
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            MoreActionRow(
                                icon = Icons.Outlined.SaveAlt,
                                title = "Apply to Game Folder",
                                subtitle = "Copy staged edits back to Android/data via Shizuku",
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
                    text = "FORMAT & INTEGRITY",
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
                            title = "Repack to DXT",
                            subtitle = "Convert textures to DXT1/DXT5 compression",
                            onClick = {
                                onDismissRequest()
                                onRepackDxt()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.AutoFixHigh,
                            title = "Repack to ETC",
                            subtitle = "Convert textures to ETC1/ETC2 compression",
                            onClick = {
                                onDismissRequest()
                                onRepackEtc()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.AutoFixHigh,
                            title = "Repack to Raw (565/4444)",
                            subtitle = "Convert textures to uncompressed 16-bit raster",
                            onClick = {
                                onDismissRequest()
                                onRepackRaw()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.Checklist,
                            title = "Check DFF Textures",
                            subtitle = "Match models against textures present in this cache",
                            onClick = {
                                onDismissRequest()
                                onCheckDff()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.Info,
                            title = "Cache Info",
                            subtitle = "View texture formats, resolutions, and statistics",
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
                    text = "VIEW & SORTING",
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
                                title = "Category: ${categoryName ?: "All"}",
                                subtitle = "Filter textures by architecture / subfolder",
                                onClick = {
                                    onDismissRequest()
                                    onSelectCategory()
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        MoreActionRow(
                            icon = Icons.Outlined.SortByAlpha,
                            title = "Sort by Name" + if (sortMode == 0) " (Active)" else "",
                            subtitle = "Alphabetical order",
                            onClick = {
                                onSetSortMode(0)
                                onDismissRequest()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.Straighten,
                            title = "Sort by Size" + if (sortMode == 1) " (Active)" else "",
                            subtitle = "Largest file payload first",
                            onClick = {
                                onSetSortMode(1)
                                onDismissRequest()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.AspectRatio,
                            title = "Sort by Dimensions" + if (sortMode == 2) " (Active)" else "",
                            subtitle = "Largest resolution first",
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
                        title = "Close Cache",
                        subtitle = "Close this Texdb cache and release memory",
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
