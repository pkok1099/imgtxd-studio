package com.onlasdan.studio.ui.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.onlasdan.studio.ui.components.DeskModeDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.onlasdan.studio.R
import com.onlasdan.studio.ui.FloatingSheet
import com.onlasdan.studio.ui.theme.PrimaryPink
import com.onlasdan.studio.ui.theme.SectionHeaderStyle
import com.onlasdan.studio.ui.theme.SemanticColors
import com.onlasdan.studio.worker.WorkerContracts
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeSettingsSheet(
    onDismissRequest: () -> Unit,
    viewModel: RuntimeSettingsViewModel? = null
) {
    val context = LocalContext.current
    val vm: RuntimeSettingsViewModel = viewModel ?: remember(context) {
        val app = context.applicationContext as Application
        val owner = context as? ViewModelStoreOwner
        if (owner != null) {
            ViewModelProvider(
                owner,
                ViewModelProvider.AndroidViewModelFactory.getInstance(app)
            )[RuntimeSettingsViewModel::class.java]
        } else {
            RuntimeSettingsViewModel(app)
        }
    }

    LaunchedEffect(Unit) {
        vm.refreshMemoryInfo()
    }

    val uiState by vm.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDeskMode by remember { mutableStateOf(false) }

    val currentProcess by com.onlasdan.studio.process.ProcessRepositoryLocator.get(context).currentProcess.collectAsState()

    if (showDeskMode) {
        DeskModeDialog(
            onDismissRequest = { showDeskMode = false },
            activeProcess = currentProcess
        )
    }

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
            // 1. Header: Ikon bulat primary 48dp, Judul Serif "Proses Runtime", Subjudul
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
                        imageVector = Icons.Outlined.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.runtime_process_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.runtime_process_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 2. Grup Pengaturan: STATUS RUNTIME (Card 20dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.runtime_status_section),
                    style = SectionHeaderStyle,
                    color = MaterialTheme.colorScheme.primary
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
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { vm.setRuntimeEnabled(!uiState.isRuntimeEnabled) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.enable_runtime_process),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.enable_runtime_process_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = uiState.isRuntimeEnabled,
                            onCheckedChange = { vm.setRuntimeEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = PrimaryPink,
                                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        )
                    }
                }
            }

            // 3. Grup Alokasi Memori: ALOKASI MEMORI (Card 20dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "MEMORY ALLOCATION",
                    style = SectionHeaderStyle,
                    color = MaterialTheme.colorScheme.primary
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
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Pilihan Mode: Segmented Button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val isAuto = uiState.memoryMode == "AUTO"
                            val isManual = uiState.memoryMode == "MANUAL"

                            // Otomatis (dinamis)
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { vm.setMemoryMode("AUTO") },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isAuto) PrimaryPink else Color.Transparent
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.memory_mode_auto),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isAuto) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isAuto) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Manual
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { vm.setMemoryMode("MANUAL") },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isManual) PrimaryPink else Color.Transparent
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.memory_mode_manual),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isManual) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isManual) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Jika Mode Manual dipilih
                        if (uiState.memoryMode == "MANUAL") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.allocation_mb, uiState.manualBudgetMb),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = PrimaryPink
                                    )
                                    Text(
                                        text = stringResource(R.string.available_system_mem, uiState.availableDeviceMemMb),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                val minMb = WorkerContracts.MIN_BUDGET_MB // 256
                                val maxMb = remember(uiState.totalDeviceMemMb, uiState.maxHeapMb) {
                                    val deviceLimit = if (uiState.totalDeviceMemMb > 0) uiState.totalDeviceMemMb else 2048
                                    minOf(4096, maxOf(1024, minOf(deviceLimit, 2048)))
                                }
                                val stepSize = 128
                                val stepsCount = maxOf(0, ((maxMb - minMb) / stepSize) - 1)

                                Slider(
                                    value = uiState.manualBudgetMb.toFloat().coerceIn(minMb.toFloat(), maxMb.toFloat()),
                                    onValueChange = { newVal ->
                                        val stepped = ((newVal - minMb) / stepSize).roundToInt() * stepSize + minMb
                                        vm.setManualBudget(stepped.coerceIn(minMb, maxMb))
                                    },
                                    valueRange = minMb.toFloat()..maxMb.toFloat(),
                                    steps = stepsCount,
                                    colors = SliderDefaults.colors(
                                        thumbColor = PrimaryPink,
                                        activeTrackColor = PrimaryPink,
                                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        activeTickColor = Color.White.copy(alpha = 0.7f),
                                        inactiveTickColor = MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "$minMb MB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "$maxMb MB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Teks Info
                        Text(
                            text = "A higher limit speeds up the process on supported devices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Kartu Peringatan (Card 16dp) berlatar SemanticColors.WarningContainer / SemanticColors.Warning
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = SemanticColors.WarningContainer),
                            border = BorderStroke(1.dp, SemanticColors.Warning.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.WarningAmber,
                                    contentDescription = null,
                                    tint = SemanticColors.Warning,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "A high memory limit can slow the process down on some devices. Raise it only if the process keeps failing at lower limits.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SemanticColors.Warning
                                )
                            }
                        }
                    }
                }
            }

            // 4. Card Grup: ALOKASI CPU CORE (Foreground vs Background)
            if (uiState.isRuntimeEnabled) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.cpu_allocation_section),
                            style = SectionHeaderStyle
                        )

                        Text(
                            text = stringResource(R.string.cpu_allocation_desc, uiState.totalCpuCores),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.background_core_limit, uiState.backgroundCpuCores),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PrimaryPink
                                )
                                Text(
                                    text = stringResource(R.string.total_cores_format, uiState.totalCpuCores),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            val maxCores = maxOf(1, uiState.totalCpuCores)
                            val steps = maxOf(0, maxCores - 2)

                            Slider(
                                value = uiState.backgroundCpuCores.toFloat().coerceIn(1f, maxCores.toFloat()),
                                onValueChange = { newVal ->
                                    vm.setBackgroundCpuCores(newVal.roundToInt().coerceIn(1, maxCores))
                                },
                                valueRange = 1f..maxCores.toFloat(),
                                steps = steps,
                                colors = SliderDefaults.colors(
                                    thumbColor = PrimaryPink,
                                    activeTrackColor = PrimaryPink,
                                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    activeTickColor = Color.White.copy(alpha = 0.7f),
                                    inactiveTickColor = MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.core_eco), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.core_balanced, maxCores / 2), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.core_max, maxCores), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Text(
                            text = stringResource(R.string.limiting_cores_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // 5. Tombol Fitur: Buka Mode Meja (Layar Redup AMOLED)
            Button(
                onClick = { showDeskMode = true },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Nightlight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.open_desk_mode),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // 6. Tombol Aksi Opsional: Abaikan Optimasi Baterai
            val powerManager = remember(context) {
                context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            }
            val isIgnoringBatteryOptimizations = remember(context) {
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            }

            OutlinedButton(
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(fallbackIntent)
                        } catch (e2: Exception) {
                            // Abaikan jika tidak didukung oleh perangkat
                        }
                    }
                },
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = if (isIgnoringBatteryOptimizations) Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryAlert,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isIgnoringBatteryOptimizations) SemanticColors.Success else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isIgnoringBatteryOptimizations) stringResource(R.string.battery_opt_already_ignored) else stringResource(R.string.ignore_battery_opt),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // 7. Benchmark Engine (R0 — baseline sebelum keputusan Rust)
            var benchmarkRunning by remember { mutableStateOf(false) }
            var benchmarkResult by remember { mutableStateOf<String?>(null) }
            val benchmarkScope = rememberCoroutineScope()
            OutlinedButton(
                onClick = {
                    benchmarkRunning = true
                    benchmarkScope.launch {
                        benchmarkResult = com.onlasdan.studio.core.EngineBenchmark.run(context)
                        benchmarkRunning = false
                    }
                },
                enabled = !benchmarkRunning,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = if (benchmarkRunning) stringResource(R.string.benchmark_running) else stringResource(R.string.run_engine_benchmark),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            benchmarkResult?.let { result ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}
