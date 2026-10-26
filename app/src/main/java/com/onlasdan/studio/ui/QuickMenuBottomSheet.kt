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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                        text = "ImgTxd Studio",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Quick actions & game archive management",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Group: NAVIGATION & GAME ENGINES
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "PRIMARY TOOLS & ARCHIVES",
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
                            title = "Open IMG Archive",
                            subtitle = "Buka arsip .img (GTA SA / VC / III) untuk modifikasi model & tekstur",
                            onClick = {
                                onDismissRequest()
                                onOpenArchive()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.Image,
                            title = "TXD Editor (View & Edit)",
                            subtitle = "Buka dan edit file .txd (lihat tekstur, replace, tambah gambar, ekstrak PNG)",
                            onClick = {
                                onDismissRequest()
                                onOpenTxdEditor()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.CreateNewFolder,
                            title = "TXD Maker (Buat TXD Baru)",
                            subtitle = "Buat arsip kamus tekstur .txd baru dari awal untuk modding",
                            onClick = {
                                onDismissRequest()
                                onCreateNewTxd()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.ViewInAr,
                            title = "DFF View & Inspector",
                            subtitle = "Inspeksi model 3D .dff (struktur RW, mesh, daftar tekstur & rename)",
                            onClick = {
                                onDismissRequest()
                                onInspectDff()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.Layers,
                            title = "Texdb Cache Browser",
                            subtitle = "Buka folder cache tekstur Android (texdb: gta3, gta_int, txd, player)",
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
                    text = "MODDING UTILITIES",
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
                            title = "Game Texture Audit",
                            subtitle = "Audit tekstur hilang & tak terpakai di folder game (DFF vs texdb)",
                            onClick = {
                                onDismissRequest()
                                onGameAudit()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.FileCopy,
                            title = "Paste Mod Data Lines",
                            subtitle = "Tempel baris data mod (handling.cfg / vehicles.ide / carcols.dat)",
                            onClick = {
                                onDismissRequest()
                                onDataLines()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.Audiotrack,
                            title = "Fix SA Audio (.osw)",
                            subtitle = "Rebuild index .idx untuk arsip audio .osw yang dimodifikasi",
                            onClick = {
                                onDismissRequest()
                                onFixAudio()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.Collections,
                            title = "Export Multiple TXD",
                            subtitle = "Ekstrak massal seluruh tekstur dari banyak file .txd sekaligus",
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
                        text = "ARCHIVE OPERATIONS",
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
                                title = "Add New File",
                                subtitle = "Add .dff, .txd, etc. entries to the archive",
                                onClick = {
                                    onDismissRequest()
                                    onAddFile()
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            QuickMenuItem(
                                icon = Icons.Outlined.Checklist,
                                title = "Select All Files",
                                subtitle = "Select all archive entries",
                                onClick = {
                                    onDismissRequest()
                                    onSelectAll()
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            QuickMenuItem(
                                icon = Icons.Outlined.Checklist,
                                title = "Check Archive Health",
                                subtitle = "Duplikat, tumpang tindih, entri rusak, ruang terbuang (engine Rust)",
                                onClick = {
                                    onDismissRequest()
                                    onVerifyArchive()
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            QuickMenuItem(
                                icon = Icons.Outlined.Build,
                                title = "Rebuild Archive",
                                subtitle = "Re-sequence entry sectors and save space",
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
                    text = "SYSTEM & SETTINGS",
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
                                    text = "Shizuku Access Status",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isShizukuActive) "Full Android/data access active" else "Android/data access not granted yet",
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
                                label = { Text(if (isShizukuActive) "Tersambung" else "Cek Izin", style = MaterialTheme.typography.labelSmall) },
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
                            title = "Settings",
                            subtitle = "Window mode (multi-window) & theme info",
                            onClick = {
                                onDismissRequest()
                                onOpenSettings()
                            }
                        )

                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                        // History Item
                        QuickMenuItem(
                            icon = Icons.Outlined.History,
                            title = "Operations History",
                            subtitle = "Latest export, rebuild, and background op results",
                            onClick = {
                                onDismissRequest()
                                onOpenHistory()
                            }
                        )

                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                        // About Item
                        QuickMenuItem(
                            icon = Icons.Outlined.Info,
                            title = "About",
                            subtitle = "v2.4.1 • Independen & Open-Source",
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
