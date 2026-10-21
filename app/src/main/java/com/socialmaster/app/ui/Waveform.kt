package com.socialmaster.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.socialmaster.app.ui.theme.AppChipShape
import kotlin.math.abs

/** Draws a mirrored peak waveform from a list of 0..1 peaks. */
@Composable
fun Waveform(
    peaks: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    progress: Float = 1f,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
    onSeek: ((Float) -> Unit)? = null
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(AppChipShape)
            .background(background)
            .then(if (onSeek == null) Modifier else Modifier
                .pointerInput(onSeek) {
                    detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(onSeek) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                })
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        if (peaks.isEmpty()) return@Canvas
        val step = w / peaks.size
        for (i in peaks.indices) {
            val p = abs(peaks[i]).coerceIn(0f, 1f)
            val barH = (p * (h * 0.45f)).coerceAtLeast(1f)
            val x = i * step
            val played = (i.toFloat() / peaks.size) <= progress
            val c = if (played) color else color.copy(alpha = 0.4f)
            drawLine(
                color = c,
                start = Offset(x, mid - barH),
                end = Offset(x, mid + barH),
                strokeWidth = (step * 0.7f).coerceAtLeast(1f)
            )
        }
    }
}

/** Downsample a PCM short array into [count] peak values (0..1). */
fun peaksFromSamples(samples: ShortArray, count: Int): List<Float> {
    if (samples.isEmpty()) return emptyList()
    val out = FloatArray(count)
    val bucket = samples.size / count
    for (i in 0 until count) {
        var peak = 0
        val start = i * bucket
        val end = minOf(start + bucket, samples.size)
        for (j in start until end) {
            val v = samples[j].toInt()
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
        }
        out[i] = peak / 32768f
    }
    return out.toList()
}
