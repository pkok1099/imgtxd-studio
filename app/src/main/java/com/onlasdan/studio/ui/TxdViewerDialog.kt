package com.onlasdan.studio.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.onlasdan.studio.txd.TxdEntry
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxdViewerDialog(
    txdName: String,
    textures: List<TxdEntry>,
    isLoading: Boolean,
    onDismissRequest: () -> Unit,
    onExportSingle: (entry: TxdEntry, destDir: File) -> Unit,
    onExportAll: (destDir: File) -> Unit,
    onReplaceTexture: (entry: TxdEntry, imagePath: String, isShizuku: Boolean) -> Unit
) {
    var previewEntry by remember { mutableStateOf<TxdEntry?>(textures.firstOrNull()) }
    var entryToReplace by remember { mutableStateOf<TxdEntry?>(null) }
    var entryToExport by remember { mutableStateOf<TxdEntry?>(null) }
    var showExportAllPicker by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = txdName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${textures.size} Tekstur RenderWare",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismissRequest) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Tutup")
                        }
                    },
                    actions = {
                        if (textures.isNotEmpty()) {
                            FilledTonalButton(
                                onClick = { showExportAllPicker = true },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Export Semua")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (textures.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Tidak ada tekstur di dalam file TXD ini",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Top Preview Card for Active Texture
                        previewEntry?.let { active ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = active.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${active.dimensionsLabel} • ${active.formatLabel} • ${active.depth}-bit",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Row {
                                            IconButton(onClick = { entryToExport = active }) {
                                                Icon(Icons.Default.FileDownload, contentDescription = "Export PNG")
                                            }
                                            IconButton(onClick = { entryToReplace = active }) {
                                                Icon(Icons.Default.SwapHoriz, contentDescription = "Ganti Tekstur")
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(12.dp))

                                    // Checkerboard background for transparency preview
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(200.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF222222)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (active.bitmap != null) {
                                            Image(
                                                bitmap = active.bitmap!!.asImageBitmap(),
                                                contentDescription = active.name,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Fit
                                            )
                                        } else {
                                            Text(
                                                text = "Preview tidak tersedia untuk format ini",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.LightGray
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Texture Grid List
                        Text(
                            text = "Daftar Tekstur (${textures.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )

                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 130.dp),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(textures, key = { it.name }) { entry ->
                                val isSelected = (previewEntry?.name == entry.name)
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { previewEntry = entry },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else
                                            MaterialTheme.colorScheme.surface
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(100.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFF222222)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (entry.bitmap != null) {
                                                Image(
                                                    bitmap = entry.bitmap!!.asImageBitmap(),
                                                    contentDescription = entry.name,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Fit
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Default.Image,
                                                    contentDescription = null,
                                                    tint = Color.LightGray
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = entry.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${entry.dimensionsLabel} • ${entry.formatLabel}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    // 1. Pick folder for Export Single
    entryToExport?.let { target ->
        FilePickerDialog(
            title = "Pilih Folder Ekspor untuk ${target.name}.png",
            mode = PickerMode.PICK_FOLDER,
            onDismissRequest = { entryToExport = null },
            onFileSelected = { folderPath, _ ->
                val targetDir = File(folderPath)
                onExportSingle(target, targetDir)
                entryToExport = null
            }
        )
    }

    // 2. Pick folder for Export All
    if (showExportAllPicker) {
        FilePickerDialog(
            title = "Pilih Folder untuk Ekspor Semua Tekstur",
            mode = PickerMode.PICK_FOLDER,
            onDismissRequest = { showExportAllPicker = false },
            onFileSelected = { folderPath, _ ->
                showExportAllPicker = false
                val targetDir = File(folderPath)
                onExportAll(targetDir)
            }
        )
    }

    // 3. Pick image to Replace Texture
    entryToReplace?.let { target ->
        FilePickerDialog(
            title = "Pilih Gambar Baru untuk ${target.name}",
            mode = PickerMode.PICK_FILE,
            onDismissRequest = { entryToReplace = null },
            onFileSelected = { imgPath, isShizuku ->
                val targetEntry = entryToReplace
                entryToReplace = null
                if (targetEntry != null) {
                    onReplaceTexture(targetEntry, imgPath, isShizuku)
                }
            }
        )
    }
}
