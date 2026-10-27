package io.github.nideta231.hermesremote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nideta231.hermesremote.data.fmtClock
import kotlin.math.roundToInt

/** One line of a [LineChart]; [format] renders a value in the readout (e.g. "34%", "2.1 MB/s"). */
class ChartSeries(val label: String, val values: List<Double?>, val color: Color, val format: (Double) -> String)

/** Index of the sample under [x] on a chart [width] px wide with [n] samples. */
internal fun sampleAt(x: Float, width: Int, n: Int): Int =
    if (n <= 1 || width <= 0) 0 else ((x / width) * (n - 1)).roundToInt().coerceIn(0, n - 1)

/**
 * Line chart you can read exactly: tap or drag across it and the readout above shows the time and
 * each series' value at that point (a crosshair marks it). Tap the same point again to clear.
 * [maxValue] fixes the top of the scale (100 for percentages); null scales to the data.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LineChart(series: List<ChartSeries>, ts: List<Long>, height: Dp, modifier: Modifier = Modifier,
              maxValue: Double? = 100.0, axisFormat: ((Double) -> String)? = null) {
    val n = series.maxOfOrNull { it.values.size } ?: 0
    var selected by remember(ts.firstOrNull(), n) { mutableStateOf<Int?>(null) }
    val top = maxValue ?: ((series.flatMap { it.values }.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0) * 1.1)
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val cross = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val onVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val withDay = ts.size >= 2 && ts.last() - ts.first() > 86_400
    Column(modifier) {
        // Readout: the legend until a point is picked, then that point's time and exact values.
        Box(Modifier.fillMaxWidth().heightIn(min = 20.dp).padding(bottom = 6.dp)) {
            val i = selected?.takeIf { it < n }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (i != null) {
                    Text(ts.getOrNull(i)?.let { fmtClock(it, withDay) } ?: "", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold)
                }
                series.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp)) { Canvas(Modifier.fillMaxSize()) { drawCircle(s.color) } }
                        Spacer(Modifier.width(4.dp))
                        val v = i?.let { s.values.getOrNull(it) }
                        Text(if (i == null) s.label else "${s.label} ${v?.let(s.format) ?: "–"}",
                            style = MaterialTheme.typography.labelSmall, color = if (i == null) onVariant else s.color)
                    }
                }
                if (i == null) Text("Tap for exact values", style = MaterialTheme.typography.labelSmall, color = onVariant.copy(alpha = 0.7f))
            }
        }
        Box(Modifier.fillMaxWidth().height(height)) {
            Canvas(Modifier.fillMaxSize()
                .pointerInput(n) {
                    detectTapGestures { o -> val k = sampleAt(o.x, size.width, n); selected = if (selected == k) null else k }
                }
                .pointerInput(n) {
                    detectHorizontalDragGestures(onDragStart = { o -> selected = sampleAt(o.x, size.width, n) }) { change, _ ->
                        selected = sampleAt(change.position.x, size.width, n)
                    }
                }) {
                for (k in 0..4) {
                    val y = size.height * k / 4f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                fun xOf(i: Int, count: Int) = if (count <= 1) size.width / 2 else size.width * i / (count - 1f)
                fun yOf(v: Double) = size.height - size.height * (v / top).coerceIn(0.0, 1.0).toFloat()
                series.forEach { s ->
                    val path = Path()
                    var started = false
                    s.values.forEachIndexed { i, v ->
                        if (v == null) { started = false; return@forEachIndexed }
                        val x = xOf(i, s.values.size); val y = yOf(v)
                        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                    }
                    if (s.values.count { it != null } == 1) s.values.indexOfFirst { it != null }.let { i ->
                        drawCircle(s.color, 3.dp.toPx(), Offset(xOf(i, s.values.size), yOf(s.values[i]!!)))
                    }
                    drawPath(path, s.color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                }
                selected?.takeIf { it < n }?.let { i ->
                    val x = xOf(i, n)
                    drawLine(cross, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    series.forEach { s -> s.values.getOrNull(i)?.let { v -> drawCircle(s.color, 4.dp.toPx(), Offset(x, yOf(v))) } }
                }
            }
            axisFormat?.let { f ->
                Text(f(top), style = MaterialTheme.typography.labelSmall, color = onVariant.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.TopEnd))
            }
        }
    }
}
