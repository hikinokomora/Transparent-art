package io.github.transparentart

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: AppViewModel, onStartTracing: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.loadImage(uri)
    }
    val pick = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val snackbar = remember { SnackbarHostState() }
    val failedText = stringResource(R.string.load_failed)
    LaunchedEffect(vm.loadFailed) {
        if (vm.loadFailed) {
            vm.consumeLoadError()
            snackbar.showSnackbar(failedText)
        }
    }
    val neuralFailedText = stringResource(R.string.neural_failed)
    LaunchedEffect(vm.neuralFailed) {
        if (vm.neuralFailed) {
            vm.consumeNeuralError()
            snackbar.showSnackbar(neuralFailedText)
        }
    }

    val original = vm.original
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (original != null) {
                        IconButton(onClick = pick) {
                            Icon(Icons.Filled.AddPhotoAlternate, stringResource(R.string.pick_photo))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (original == null) {
            EmptyState(loading = vm.loading, onPick = pick, modifier = Modifier.padding(padding))
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                SketchPreview(
                    original = original,
                    sketch = vm.sketch,
                    busy = vm.loading || vm.processing,
                    modifier = Modifier.weight(1.2f).fillMaxWidth(),
                )
                Controls(
                    params = vm.params,
                    onChange = vm::updateParams,
                    onStart = onStartTracing,
                    startEnabled = vm.sketch != null,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(loading: Boolean, onPick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Draw,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.pick_photo_hint),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (loading) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onPick) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.pick_photo))
            }
        }
    }
}

@Composable
private fun SketchPreview(original: Bitmap, sketch: Bitmap?, busy: Boolean, modifier: Modifier) {
    var showOriginal by remember { mutableStateOf(false) }
    val shown = if (showOriginal || sketch == null) original else sketch
    val image = remember(shown) { shown.asImageBitmap() }

    Box(
        modifier
            .background(Color.White)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    showOriginal = true
                    tryAwaitRelease()
                    showOriginal = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(8.dp),
            contentScale = ContentScale.Fit,
        )
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(24.dp),
                strokeWidth = 3.dp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Controls(
    params: SketchParams,
    onChange: (SketchParams) -> Unit,
    onStart: () -> Unit,
    startEnabled: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val modes = listOf(SketchMode.NEURAL to R.string.mode_neural, SketchMode.CLASSIC to R.string.mode_classic)
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = params.mode == mode,
                    onClick = { onChange(params.copy(mode = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) { Text(stringResource(label)) }
            }
        }
        Text(
            stringResource(if (params.mode == SketchMode.NEURAL) R.string.mode_neural_hint else R.string.mode_classic_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            stringResource(R.string.hold_to_compare),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (params.mode == SketchMode.NEURAL) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                listOf(NeuralStyle.CONTOUR to R.string.style_contour, NeuralStyle.SKETCH to R.string.style_sketch)
                    .forEach { (style, label) ->
                        FilterChip(
                            selected = params.style == style,
                            onClick = { onChange(params.copy(style = style)) },
                            label = { Text(stringResource(label)) },
                        )
                    }
            }
            LabeledSlider(stringResource(R.string.cleanliness), params.cleanliness, { onChange(params.copy(cleanliness = it)) })
            LabeledSlider(stringResource(R.string.darkness), params.darkness, { onChange(params.copy(darkness = it)) })
        } else {
            LabeledSlider(stringResource(R.string.detail), params.detail, { onChange(params.copy(detail = it)) })
            LabeledSlider(stringResource(R.string.sensitivity), params.sensitivity, { onChange(params.copy(sensitivity = it)) })
            LabeledSlider(stringResource(R.string.thickness), params.thickness, { onChange(params.copy(thickness = it)) })
        }

        val shadingOn = params.shadeLevels >= 2
        LabeledSlider(
            label = stringResource(R.string.shading_levels),
            value = if (shadingOn) (params.shadeLevels - 1).toFloat() else 0f,
            onValueChange = { v ->
                val n = v.roundToInt()
                onChange(params.copy(shadeLevels = if (n == 0) 0 else n + 1))
            },
            valueRange = 0f..4f,
            steps = 3,
            valueText = if (shadingOn) params.shadeLevels.toString() else stringResource(R.string.shading_off),
        )
        LabeledSlider(
            label = stringResource(R.string.shading_strength),
            value = params.shadeStrength,
            onValueChange = { onChange(params.copy(shadeStrength = it)) },
            enabled = shadingOn,
        )

        Text(
            stringResource(R.string.ink_color),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            INK_COLORS.forEach { color ->
                ColorDot(color, selected = params.inkColor == color) {
                    onChange(params.copy(inkColor = color))
                }
            }
        }

        Button(
            onClick = onStart,
            enabled = startEnabled,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Icon(Icons.Filled.Draw, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.start_tracing))
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    valueText: String = "${(value * 100).roundToInt()}%",
    enabled: Boolean = true,
) {
    Column(Modifier.padding(top = 4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
        )
    }
}

@Composable
private fun ColorDot(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}
