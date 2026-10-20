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
import com.onlasdan.studio.ui.theme.SemanticColors

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
    onOpenWindowMode: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRuntimeSettings: () -> Unit = {},
    onOpenAbout: () -> Unit,
    onCloseFile: () -> Unit,
    onOpenShizuku: () -> Unit
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
            // Header: Judul Sheet
            Column {
                Text(
                    text = "Menu & Opsi Berkas",
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

            // Grup: OPERASI BERKAS
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "OPERASI BERKAS",
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
                            title = if (isTxd) "Ekspor Semua PNG" else "Ekspor Semua Berkas",
                            subtitle = "Ekstrak seluruh isi file ke penyimpanan",
                            onClick = {
                                onDismissRequest()
                                onExportAll()
                            }
                        )

                        if (isTxd && onToggleTxdViewMode != null) {
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            MoreActionRow(
                                icon = if (isLargeImage) Icons.Outlined.ViewCompact else Icons.Outlined.PhotoSizeSelectActual,
                                title = if (isLargeImage) "Tampilan: Gambar Besar" else "Tampilan: Gambar Kecil",
                                subtitle = if (isLargeImage) "Ketuk untuk beralih ke daftar kompak (gambar kecil)" else "Ketuk untuk beralih ke kartu pratinjau besar",
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
                                title = "Rebuild Arsip",
                                subtitle = "Padatkan dan rapikan sektor file IMG",
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
                                title = "Ganti Nama Berkas",
                                subtitle = "Ubah nama file fisik di penyimpanan",
                                onClick = {
                                    onDismissRequest()
                                    onRenameFile()
                                }
                            )
                        }
                    }
                }
            }

            // Grup: SISTEM & INTEGRASI
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "SISTEM & INTEGRASI",
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
                            icon = Icons.Outlined.Bolt,
                            title = "Status & Akses Shizuku",
                            subtitle = "Kelola izin akses folder Android/data",
                            onClick = {
                                onDismissRequest()
                                onOpenShizuku()
                            }
                        )

                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.Layers,
                            title = "Mode Jendela",
                            subtitle = "Atur jendela multi-window atau single-window",
                            onClick = {
                                onDismissRequest()
                                onOpenWindowMode()
                            }
                        )

                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.Settings,
                            title = "Pengaturan",
                            subtitle = "Konfigurasi tema & preferensi aplikasi",
                            onClick = {
                                onDismissRequest()
                                onOpenSettings()
                            }
                        )

                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.Memory,
                            title = "Pengaturan Proses Runtime",
                            subtitle = "Service latar belakang & anggaran RAM",
                            isEnabled = true,
                            onClick = {
                                onDismissRequest()
                                onOpenRuntimeSettings()
                            }
                        )

                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        MoreActionRow(
                            icon = Icons.Outlined.Info,
                            title = "Tentang ImgTxd Studio",
                            subtitle = "Informasi versi & lisensi aplikasi",
                            onClick = {
                                onDismissRequest()
                                onOpenAbout()
                            }
                        )
                    }
                }
            }

            // Grup: KELUAR & TUTUP
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "MANAJEMEN SESI",
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
                        title = "Tutup Berkas",
                        subtitle = if (isFileBusy) "Menunggu proses selesai..." else "Tutup berkas dan kembali ke beranda",
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
private fun MoreActionRow(
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
