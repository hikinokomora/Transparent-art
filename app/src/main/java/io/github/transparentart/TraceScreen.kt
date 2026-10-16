package io.github.transparentart

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

@Composable
fun TraceScreen(
    sketch: Bitmap,
    state: TraceState,
    onStateChange: (TraceState) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var hasCamera by remember { mutableStateOf(isCameraGranted(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCamera = granted }

    LaunchedEffect(Unit) {
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    // The user may grant the permission in system settings and come back.
    LifecycleResumeEffect(Unit) {
        hasCamera = isCameraGranted(context)
        onPauseOrDispose { }
    }

    KeepScreenOn()
    BackHandler(onBack = onBack)

    var controlsVisible by remember { mutableStateOf(true) }
    var overlayVisible by remember { mutableStateOf(true) }
    var torchOn by remember { mutableStateOf(false) }
    val image = remember(sketch) { sketch.asImageBitmap() }
    val current by rememberUpdatedState(state)
    val update by rememberUpdatedState(onStateChange)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            CameraPreview(torchOn = torchOn, modifier = Modifier.fillMaxSize())
        }

        // Gesture layer: pinch/drag/twist moves the picture, a tap toggles the controls.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                }
                .pointerInput(state.locked) {
                    if (state.locked) return@pointerInput
                    detectTransformGestures { _, pan, zoom, rotation ->
                        val s = current
                        update(
                            s.copy(
                                scale = (s.scale * zoom).coerceIn(0.1f, 10f),
                                offsetX = s.offsetX + pan.x,
                                offsetY = s.offsetY + pan.y,
                                rotation = s.rotation + rotation,
                            ),
                        )
                    }
                },
        ) {
            if (overlayVisible) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = state.scale * if (state.flipped) -1f else 1f
                            scaleY = state.scale
                            rotationZ = state.rotation
                            translationX = state.offsetX
                            translationY = state.offsetY
                            alpha = state.alpha
                        },
                )
            }
        }

        if (!hasCamera) {
            PermissionRequest(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Opacity, stringResource(R.string.opacity), tint = Color.White)
                    Slider(
                        value = state.alpha,
                        onValueChange = { update(current.copy(alpha = it)) },
                        valueRange = 0.05f..1f,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ToolButton(
                        icon = if (state.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        description = stringResource(R.string.lock),
                        active = state.locked,
                    ) { update(current.copy(locked = !current.locked)) }
                    ToolButton(
                        icon = Icons.Filled.Flip,
                        description = stringResource(R.string.flip),
                        active = state.flipped,
                        enabled = !state.locked,
                    ) { update(current.copy(flipped = !current.flipped)) }
                    ToolButton(
                        icon = if (overlayVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        description = stringResource(R.string.toggle_overlay),
                        active = !overlayVisible,
                    ) { overlayVisible = !overlayVisible }
                    ToolButton(
                        icon = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        description = stringResource(R.string.torch),
                        active = torchOn,
                        enabled = hasCamera,
                    ) { torchOn = !torchOn }
                    ToolButton(
                        icon = Icons.Filled.RestartAlt,
                        description = stringResource(R.string.reset),
                        active = false,
                        enabled = !state.locked,
                    ) { update(TraceState(alpha = current.alpha)) }
                }
                Text(
                    stringResource(R.string.tap_to_hide),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    description: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimary else Color.White,
            disabledContentColor = Color.White.copy(alpha = 0.3f),
        ),
    ) {
        Icon(icon, description)
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.camera_permission_needed),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) {
            Text(stringResource(R.string.grant_permission))
        }
        TextButton(onClick = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        }) {
            Text(stringResource(R.string.open_settings), color = Color.White)
        }
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

private fun isCameraGranted(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
