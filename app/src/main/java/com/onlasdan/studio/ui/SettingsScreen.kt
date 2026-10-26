package com.onlasdan.studio.ui

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.onlasdan.studio.ui.framework.ContextualDockItem
import com.onlasdan.studio.ui.framework.ToolPageScaffold
import com.onlasdan.studio.ui.settings.RuntimeSettingsSheet
import com.onlasdan.studio.ui.settings.RuntimeSettingsViewModel
import com.onlasdan.studio.ui.sheets.AboutBottomSheet
import com.onlasdan.studio.ui.theme.SectionHeaderStyle
import com.onlasdan.studio.ui.theme.SemanticColors
import com.onlasdan.studio.util.AppSettings
import com.onlasdan.studio.util.ShizukuManager
import com.onlasdan.studio.util.WindowMode
import kotlinx.coroutines.launch

/**
 * Dedicated Settings & System Screen.
 * Contains:
 * - Shizuku Status & Access management
 * - Window Mode (Multi-Window vs Single-Window)
 * - Theme & Appearance info (Noir & Blush)
 * - Runtime Process & Memory Budget Settings
 * - About & License information
 */
@Composable
fun SettingsScreen(
    dockItems: List<ContextualDockItem>,
    isShizukuActive: Boolean,
    onShizukuClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val currentWindowMode by AppSettings.getWindowModeFlow(context).collectAsState(initial = AppSettings.cachedWindowMode)

    var showRuntimeSettingsSheet by remember { mutableStateOf(false) }
    var showAboutSheet by remember { mutableStateOf(false) }

    ToolPageScaffold(
        title = "Settings",
        subtitle = "Preferences, runtime & system",
        isShizukuActive = isShizukuActive,
        onShizukuClick = onShizukuClick,
        dockItems = dockItems
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 100.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section: SHIZUKU & SYSTEM PERMISSIONS
                item(key = "settings_shizuku_section") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "SHIZUKU & SYSTEM ACCESS",
                            style = SectionHeaderStyle,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!ShizukuManager.isShizukuAvailable()) {
                                            Toast.makeText(context, "Shizuku service not detected. Open the Shizuku app first.", Toast.LENGTH_LONG).show()
                                        } else if (!ShizukuManager.hasPermission()) {
                                            ShizukuManager.requestPermission()
                                        } else {
                                            Toast.makeText(context, "Shizuku active: full Android/data access available.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(
                                            if (isShizukuActive) SemanticColors.SuccessContainer else SemanticColors.WarningContainer,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Bolt,
                                        contentDescription = null,
                                        tint = if (isShizukuActive) SemanticColors.Success else SemanticColors.Warning,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Shizuku Access Status",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (isShizukuActive) "Full Android/data access active (No root needed)"
                                        else "Access not granted. Tap to connect to Shizuku",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                AssistChip(
                                    onClick = {
                                        if (!ShizukuManager.isShizukuAvailable()) {
                                            Toast.makeText(context, "Shizuku service not detected.", Toast.LENGTH_SHORT).show()
                                        } else if (!ShizukuManager.hasPermission()) {
                                            ShizukuManager.requestPermission()
                                        } else {
                                            Toast.makeText(context, "Shizuku active.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = CircleShape,
                                    label = { Text(if (isShizukuActive) "Connected" else "Connect", style = MaterialTheme.typography.labelSmall) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (isShizukuActive) SemanticColors.SuccessContainer else SemanticColors.WarningContainer,
                                        labelColor = if (isShizukuActive) SemanticColors.OnSuccessContainer else SemanticColors.OnWarningContainer
                                    )
                                )
                            }
                        }
                    }
                }

                // Section: WINDOW MODE (MULTI-WINDOW)
                item(key = "settings_window_mode_section") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "WINDOW MODE (MULTI-WINDOW)",
                            style = SectionHeaderStyle,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                WindowMode.entries.forEach { mode ->
                                    val isSelected = (mode == currentWindowMode)
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else
                                            MaterialTheme.colorScheme.surfaceContainer,
                                        border = if (isSelected)
                                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                                        else
                                            null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable {
                                                coroutineScope.launch {
                                                    AppSettings.setWindowMode(context, mode)
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = isSelected,
                                                onClick = {
                                                    coroutineScope.launch {
                                                        AppSettings.setWindowMode(context, mode)
                                                    }
                                                },
                                                colors = RadioButtonDefaults.colors(
                                                    selectedColor = MaterialTheme.colorScheme.primary,
                                                    unselectedColor = MaterialTheme.colorScheme.outline
                                                )
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = mode.title,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = mode.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Section: THEME & RUNTIME PROCESS
                item(key = "settings_appearance_runtime_section") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "THEME & RUNTIME SETTINGS",
                            style = SectionHeaderStyle,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // Theme item
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Palette,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Noir & Blush Theme",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Pure OLED deep black with soft blush pink accent (fixed dark mode)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = "Active",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                                // Runtime process settings item
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showRuntimeSettingsSheet = true }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Memory,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Runtime Process & Memory Budget",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Configure RAM budget, worker threads, and foreground service",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Section: ABOUT
                item(key = "settings_about_section") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "APPLICATION INFO",
                            style = SectionHeaderStyle,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showAboutSheet = true }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "About ImgTxd Studio",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "v3.3.1 • High-speed Rust core & open-source licenses",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRuntimeSettingsSheet) {
        RuntimeSettingsSheet(onDismissRequest = { showRuntimeSettingsSheet = false })
    }

    if (showAboutSheet) {
        AboutBottomSheet(onDismissRequest = { showAboutSheet = false })
    }
}
