package com.onlasdan.studio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onlasdan.studio.R
import com.onlasdan.studio.ui.theme.SectionHeaderStyle
import com.onlasdan.studio.ui.theme.SemanticColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickMenuBottomSheet(
    onDismissRequest: () -> Unit,
    hasActiveArchive: Boolean,
    isShizukuActive: Boolean,
    onOpenArchive: () -> Unit,
    onAddFile: () -> Unit,
    onRebuildArchive: () -> Unit,
    onSelectAll: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestShizuku: () -> Unit,
    onExportMultipleTxd: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onFixAudio: () -> Unit = {},
    onOpenTexdbCache: () -> Unit = {},
    onOpenTxdEditor: () -> Unit = {},
    onCreateNewTxd: () -> Unit = {},
    onInspectDff: () -> Unit = {},
    onVerifyArchive: () -> Unit = {},
    onGameAudit: () -> Unit = {},
    onDataLines: () -> Unit = {}
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
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Header: Avatar 48dp + Judul Serif + Subjudul
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.quick_menu_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Group: NAVIGATION & GAME ENGINES
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.primary_tools_archives),
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
                        QuickMenuItem(
                            icon = Icons.Outlined.FolderOpen,
                            title = stringResource(R.string.menu_open_img),
                            subtitle = stringResource(R.string.menu_open_img_desc),
                            onClick = {
                                onDismissRequest()
                                onOpenArchive()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.Image,
                            title = stringResource(R.string.menu_txd_editor),
                            subtitle = stringResource(R.string.menu_txd_editor_desc),
                            onClick = {
                                onDismissRequest()
                                onOpenTxdEditor()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.CreateNewFolder,
                            title = stringResource(R.string.menu_txd_maker),
                            subtitle = stringResource(R.string.menu_txd_maker_desc),
                            onClick = {
                                onDismissRequest()
                                onCreateNewTxd()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.ViewInAr,
                            title = stringResource(R.string.menu_dff_view),
                            subtitle = stringResource(R.string.menu_dff_view_desc),
                            onClick = {
                                onDismissRequest()
                                onInspectDff()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.Layers,
                            title = stringResource(R.string.menu_texdb_browser),
                            subtitle = stringResource(R.string.menu_texdb_browser_desc),
                            onClick = {
                                onDismissRequest()
                                onOpenTexdbCache()
                            }
                        )
                    }
                }
            }

            // Group: MODDING & UTILITIES
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.modding_utilities),
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
                        QuickMenuItem(
                            icon = Icons.Outlined.Checklist,
                            title = stringResource(R.string.menu_texture_audit),
                            subtitle = stringResource(R.string.menu_texture_audit_desc),
                            onClick = {
                                onDismissRequest()
                                onGameAudit()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.FileCopy,
                            title = stringResource(R.string.menu_paste_data_lines),
                            subtitle = stringResource(R.string.menu_paste_data_lines_desc),
                            onClick = {
                                onDismissRequest()
                                onDataLines()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.Audiotrack,
                            title = stringResource(R.string.menu_fix_audio),
                            subtitle = stringResource(R.string.menu_fix_audio_desc),
                            onClick = {
                                onDismissRequest()
                                onFixAudio()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.Collections,
                            title = stringResource(R.string.menu_export_multi_txd),
                            subtitle = stringResource(R.string.menu_export_multi_txd_desc),
                            onClick = {
                                onDismissRequest()
                                onExportMultipleTxd()
                            }
                        )
                    }
                }
            }

            // Group: OPERASI ARSIP (Hanya jika ada arsip aktif)
            if (hasActiveArchive) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.archive_operations_section),
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
                            QuickMenuItem(
                                icon = Icons.Outlined.Add,
                                title = stringResource(R.string.menu_add_new_file),
                                subtitle = stringResource(R.string.menu_add_new_file_desc),
                                onClick = {
                                    onDismissRequest()
                                    onAddFile()
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            QuickMenuItem(
                                icon = Icons.Outlined.Checklist,
                                title = stringResource(R.string.menu_select_all),
                                subtitle = stringResource(R.string.menu_select_all_desc),
                                onClick = {
                                    onDismissRequest()
                                    onSelectAll()
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            QuickMenuItem(
                                icon = Icons.Outlined.Checklist,
                                title = stringResource(R.string.menu_check_health),
                                subtitle = stringResource(R.string.menu_check_health_desc),
                                onClick = {
                                    onDismissRequest()
                                    onVerifyArchive()
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            QuickMenuItem(
                                icon = Icons.Outlined.Build,
                                title = stringResource(R.string.menu_rebuild_archive),
                                subtitle = stringResource(R.string.menu_rebuild_archive_desc),
                                onClick = {
                                    onDismissRequest()
                                    onRebuildArchive()
                                }
                            )
                        }
                    }
                }
            }

            // Group: SISTEM & PENGATURAN
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.system_settings_section),
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
                        // Shizuku status row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable {
                                    onDismissRequest()
                                    onRequestShizuku()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Bolt,
                                contentDescription = null,
                                tint = if (isShizukuActive) SemanticColors.Success else SemanticColors.Warning,
                                modifier = Modifier.size(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.shizuku_access_status),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isShizukuActive) stringResource(R.string.shizuku_full_access) else stringResource(R.string.shizuku_not_granted),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            AssistChip(
                                onClick = {
                                    onDismissRequest()
                                    onRequestShizuku()
                                },
                                shape = CircleShape,
                                label = { Text(if (isShizukuActive) stringResource(R.string.shizuku_badge_connected) else stringResource(R.string.shizuku_badge_check_permission), style = MaterialTheme.typography.labelSmall) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (isShizukuActive) SemanticColors.SuccessContainer else SemanticColors.WarningContainer,
                                    labelColor = if (isShizukuActive) SemanticColors.OnSuccessContainer else SemanticColors.OnWarningContainer
                                )
                            )
                        }

                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                        // Settings Item
                        QuickMenuItem(
                            icon = Icons.Outlined.Settings,
                            title = stringResource(R.string.menu_settings),
                            subtitle = stringResource(R.string.menu_settings_desc),
                            onClick = {
                                onDismissRequest()
                                onOpenSettings()
                            }
                        )

                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                        // History Item
                        QuickMenuItem(
                            icon = Icons.Outlined.History,
                            title = stringResource(R.string.menu_operations_history),
                            subtitle = stringResource(R.string.menu_operations_history_desc),
                            onClick = {
                                onDismissRequest()
                                onOpenHistory()
                            }
                        )

                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                        // About Item
                        QuickMenuItem(
                            icon = Icons.Outlined.Info,
                            title = stringResource(R.string.menu_about),
                            subtitle = stringResource(R.string.menu_about_desc),
                            onClick = {
                                onDismissRequest()
                                onOpenSettings()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}
