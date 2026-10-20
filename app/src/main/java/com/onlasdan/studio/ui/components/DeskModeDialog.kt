package com.onlasdan.studio.ui.components

import android.app.Activity
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.onlasdan.studio.process.AppProcess
import com.onlasdan.studio.ui.theme.PrimaryPink
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mode Meja / Layar Redup (AMOLED Saver):
 * Menjaga layar tetap aktif (FLAG_KEEP_SCREEN_ON) dengan kecerahan minimal (0.01f)
 * dan latar hitam pekat (#000000) hemat daya.
 * Mencegah OS Android & vendor OEM mematikan aplikasi saat proses impor berhari-hari.
 */
@Composable
fun DeskModeDialog(
    onDismissRequest: () -> Unit,
    activeProcess: AppProcess? = null
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Atur kecerahan layar ke tingkat terendah dan tahan layar tetap nyala
    DisposableEffect(Unit) {
        val window = activity?.window
        val originalBrightness = window?.attributes?.screenBrightness
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window?.attributes
        lp?.screenBrightness = 0.01f
        window?.attributes = lp

        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val resetLp = window?.attributes
            resetLp?.screenBrightness = originalBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window?.attributes = resetLp
        }
    }

    var currentTime by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(10000L)
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .padding(24.dp)
        ) {
            // Tombol keluar di sudut kanan atas
            IconButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.08f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Keluar Mode Meja",
                    tint = Color.White.copy(alpha = 0.6f)
                )
            }

            // Konten Tengah
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Nightlight,
                    contentDescription = null,
                    tint = PrimaryPink.copy(alpha = 0.7f),
                    modifier = Modifier.size(36.dp)
                )

                Text(
                    text = currentTime,
                    fontSize = 48.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Light,
                    color = Color.White.copy(alpha = 0.4f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (activeProcess != null) {
                    val percentInt = (activeProcess.progress * 100).toInt()
                    Text(
                        text = "$percentInt%",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryPink
                    )

                    LinearProgressIndicator(
                        progress = { activeProcess.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(4.dp),
                        color = PrimaryPink,
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )

                    Text(
                        text = activeProcess.message ?: "Memproses operasi berkas...",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f),
                        maxLines = 1
                    )

                    if (activeProcess.total > 0) {
                        Text(
                            text = "${activeProcess.current} / ${activeProcess.total} item",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.35f)
                        )
                    }
                } else {
                    Text(
                        text = "Tidak ada proses aktif yang berjalan",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Mode Meja Aktif • 100% Core CPU • Anti-Sleep",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.35f)
                    )
                }
            }

            // Tombol keluar di bawah
            Button(
                onClick = onDismissRequest,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.12f),
                    contentColor = Color.White.copy(alpha = 0.8f)
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.7f)
                    .height(44.dp)
            ) {
                Text(
                    text = "Kembali ke Tampilan Normal",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
