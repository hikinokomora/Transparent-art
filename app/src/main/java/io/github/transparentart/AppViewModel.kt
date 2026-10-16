package io.github.transparentart

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the overlay sits on the camera screen. */
data class TraceState(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotation: Float = 0f,
    val alpha: Float = 0.5f,
    val flipped: Boolean = false,
    val locked: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    var original by mutableStateOf<Bitmap?>(null)
        private set
    var sketch by mutableStateOf<Bitmap?>(null)
        private set
    var params by mutableStateOf(SketchParams())
        private set
    var trace by mutableStateOf(TraceState())
        private set
    var loading by mutableStateOf(false)
        private set
    var processing by mutableStateOf(false)
        private set
    var loadFailed by mutableStateOf(false)
        private set

    private var processor: SketchProcessor? = null
    private var renderJob: Job? = null

    fun loadImage(uri: Uri) {
        viewModelScope.launch {
            loading = true
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { loadBitmap(getApplication(), uri) }.getOrNull()
            }
            if (bitmap == null) {
                loadFailed = true
                loading = false
                return@launch
            }
            renderJob?.cancel()
            processor = withContext(Dispatchers.Default) { SketchProcessor(bitmap) }
            original = bitmap
            sketch = null
            trace = TraceState(alpha = trace.alpha)
            loading = false
            scheduleRender(delayMs = 0)
        }
    }

    fun consumeLoadError() {
        loadFailed = false
    }

    fun updateParams(newParams: SketchParams) {
        params = newParams
        scheduleRender(delayMs = 120)
    }

    fun updateTrace(newTrace: TraceState) {
        trace = newTrace
    }

    private fun scheduleRender(delayMs: Long) {
        val proc = processor ?: return
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            delay(delayMs)
            processing = true
            try {
                val p = params
                sketch = withContext(Dispatchers.Default) { proc.render(p) }
            } finally {
                if (isActive) processing = false
            }
        }
    }
}
