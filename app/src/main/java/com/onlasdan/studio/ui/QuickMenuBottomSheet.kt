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
                        text = "Aksi cepat & manajemen arsip game",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Group: NAVIGASI & BERKAS
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "NAVIGASI & BERKAS",
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
                            subtitle = "Pilih berkas arsip .img dari penyimpanan",
                            onClick = {
                                onDismissRequest()
                                onOpenArchive()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.Layers,
                            title = "Texdb",
                            subtitle = "Telusuri & edit cache tekstur GTA (texdb) — pilih FOLDER cache-nya",
                            onClick = {
                                onDismissRequest()
                                onOpenTexdbCache()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.ViewInAr,
                            title = "Inspect DFF",
                            subtitle = "Laporan struktur model (versi RW, geometri, tekstur) + ganti nama tekstur di dalam DFF",
                            onClick = {
                                onDismissRequest()
                                onInspectDff()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.Checklist,
                            title = "Game Texture Audit",
                            subtitle = "Cari tekstur hilang & tak terpakai di seluruh folder game (DFF vs cache texdb)",
                            onClick = {
                                onDismissRequest()
                                onGameAudit()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.FileCopy,
                            title = "Paste Mod Data Lines",
                            subtitle = "Tempel baris handling.cfg / vehicles.ide / carcols.dat dari readme mod ke berkas game",
                            onClick = {
                                onDismissRequest()
                                onDataLines()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.Audiotrack,
                            title = "Fix SA Audio (.osw)",
                            subtitle = "Buat ulang file .idx untuk arsip audio .osw hasil modifikasi",
                            onClick = {
                                onDismissRequest()
                                onFixAudio()
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        QuickMenuItem(
                            icon = Icons.Outlined.FileCopy,
                            title = "Export from Multiple TXDs",
                            subtitle = "Ekspor semua tekstur dari beberapa berkas .txd sekaligus",
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
                        text = "OPERASI ARSIP",
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
                                subtitle = "Tambahkan entri .dff, .txd, dll ke arsip",
                                onClick = {
                                    onDismissRequest()
                                    onAddFile()
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            QuickMenuItem(
                                icon = Icons.Outlined.Checklist,
                                title = "Select All Files",
                                subtitle = "Aktifkan seleksi ke semua entri arsip",
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
                                subtitle = "Susun ulang sektor entri dan hemat ruang",
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
                    text = "SISTEM & PENGATURAN",
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
                                    text = "Status Akses Shizuku",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isShizukuActive) "Akses penuh Android/data aktif" else "Akses Android/data belum diizinkan",
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
                            subtitle = "Mode jendela (multi-window) & info tema",
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
                            subtitle = "Hasil ekspor, rebuild, dan operasi latar terakhir",
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
