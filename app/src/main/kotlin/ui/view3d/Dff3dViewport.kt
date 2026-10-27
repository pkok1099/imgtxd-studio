package com.onlasdan.studio.ui.view3d

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.onlasdan.studio.R

enum class TouchInteractionMode {
    ROTATE, // Drag memutar objek
    PAN     // Drag menggeser posisi objek
}

/**
 * 3D Viewport Composable dengan tombol mode jelas (Putar vs Geser), tombol Zoom (+ / -), dan tombol Reset.
 */
@Composable
fun Dff3dViewport(
    mesh: Dff3dMesh?,
    modifier: Modifier = Modifier
) {
    var rotX by remember { mutableFloatStateOf(-20f) }
    var rotY by remember { mutableFloatStateOf(30f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var wireframe by remember { mutableStateOf(false) }
    var activeMode by remember { mutableStateOf(TouchInteractionMode.ROTATE) }

    val renderer = remember { DffGlRenderer() }

    LaunchedEffect(mesh) {
        renderer.mesh = mesh
    }

    LaunchedEffect(rotX, rotY, scale, panX, panY, wireframe) {
        renderer.rotX = rotX
        renderer.rotY = rotY
        renderer.zoomScale = scale
        renderer.panX = panX
        renderer.panY = panY
        renderer.wireframe = wireframe
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF161619))
            .pointerInput(activeMode) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    when (activeMode) {
                        TouchInteractionMode.ROTATE -> {
                            rotY += dragAmount.x * 0.45f
                            rotX = (rotX + dragAmount.y * 0.45f).coerceIn(-89f, 89f)
                        }
                        TouchInteractionMode.PAN -> {
                            panX += dragAmount.x * 0.4f
                            panY += dragAmount.y * 0.4f
                        }
                    }
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            },
            update = { _ ->
                renderer.mesh = mesh
                renderer.rotX = rotX
                renderer.rotY = rotY
                renderer.zoomScale = scale
                renderer.panX = panX
                renderer.panY = panY
                renderer.wireframe = wireframe
            }
        )

        // Bar Mode Kontrol Atas Tengah: [Putar Objek] | [Geser Objek]
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 6.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tombol Mode Putar
                FilterChip(
                    selected = activeMode == TouchInteractionMode.ROTATE,
                    onClick = { activeMode = TouchInteractionMode.ROTATE },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Rotate90DegreesCcw,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    label = { Text(stringResource(R.string.viewport_rotate_object), fontWeight = FontWeight.SemiBold) },
                    shape = RoundedCornerShape(12.dp)
                )

                // Tombol Mode Geser
                FilterChip(
                    selected = activeMode == TouchInteractionMode.PAN,
                    onClick = { activeMode = TouchInteractionMode.PAN },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.PanTool,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    label = { Text(stringResource(R.string.viewport_pan_object), fontWeight = FontWeight.SemiBold) },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        // Panel Tombol Aksi Kanan (Zoom +, Zoom -, Wireframe, Reset)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Zoom In (+)
            FilledTonalIconButton(
                onClick = { scale = (scale * 1.25f).coerceAtMost(8.0f) },
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Icon(Icons.Outlined.ZoomIn, contentDescription = stringResource(R.string.viewport_zoom_in))
            }

            // Zoom Out (-)
            FilledTonalIconButton(
                onClick = { scale = (scale * 0.8f).coerceAtLeast(0.2f) },
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Icon(Icons.Outlined.ZoomOut, contentDescription = stringResource(R.string.viewport_zoom_out))
            }

            // Wireframe toggle
            FilledTonalIconButton(
                onClick = { wireframe = !wireframe },
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (wireframe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Icon(
                    imageVector = if (wireframe) Icons.Outlined.ViewInAr else Icons.Outlined.GridOn,
                    contentDescription = stringResource(R.string.viewport_toggle_wireframe),
                    tint = if (wireframe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }

            // Reset Posisi & Rotasi
            FilledTonalIconButton(
                onClick = {
                    rotX = -20f
                    rotY = 30f
                    scale = 1f
                    panX = 0f
                    panY = 0f
                },
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.viewport_reset_camera))
            }
        }

        // Petunjuk & Ringkasan Mesh Bawah
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.75f)
            ) {
                Text(
                    text = if (activeMode == TouchInteractionMode.ROTATE) stringResource(R.string.viewport_hint_rotate) else stringResource(R.string.viewport_hint_pan),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            if (mesh != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = stringResource(R.string.viewport_mesh_summary, mesh.vertexCount, mesh.indexCount / 3, (scale * 100).toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}
