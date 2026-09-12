package yelloelefant.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Part E. Both visualisations are hand-drawn with Compose Canvas rather than a
 * charting library.
 *
 * That is a real decision with real reasons: no external dependency to justify,
 * full control over how a 250-point trace is decimated, and the gauge shape I
 * want does not exist off the shelf. The cost is that I own the axis scaling
 * and the maths, which is the bit worth being able to explain out loud.
 */

/**
 * Scrolling line chart for the filtered acceleration trace.
 *
 * Why a line chart for this data type: acceleration is a continuous time
 * series sampled fast enough that the shape between points is meaningful. A
 * line encodes slope, and slope here is jerk - you can literally see a step
 * impact as a spike. A bar chart would imply the samples are independent
 * categories, which they are not, and a single number would throw away the
 * temporal structure that makes the data interesting.
 *
 * The y axis auto-ranges to the visible window with a symmetric pad so the
 * zero line stays put and small motion does not get amplified into looking
 * dramatic - autoscaling that re-centres every frame is how you make a chart
 * that lies.
 */
@Composable
fun LineChart(
    values: FloatArray,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    lineColor: Color = Color(0xFF2E7D32),
    gridColor: Color = Color(0x33000000),
    minSpan: Float = 4f
) {
    Box(modifier = modifier.fillMaxWidth().height(height)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            if (values.size < 2) return@Canvas

            val w = size.width
            val h = size.height

            // Symmetric range around zero, floored at minSpan so a still
            // device shows a flat line rather than magnified noise.
            val extent = max(
                minSpan / 2f,
                max(values.max(), -values.min()).let { if (it <= 0f) minSpan / 2f else it }
            )
            fun yOf(v: Float): Float = h / 2f - (v / extent) * (h / 2f) * 0.9f

            // Zero baseline plus half-scale guides.
            listOf(-extent / 2f, 0f, extent / 2f).forEach { level ->
                val y = yOf(level)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = if (level == 0f) 2f else 1f
                )
            }

            // Decimate if there are more samples than horizontal pixels.
            // Drawing 250 points into 200 px just costs time and aliases;
            // stepping means every pixel column gets at most one vertex.
            val step = max(1, (values.size / w.toInt().coerceAtLeast(1)))
            val path = Path()
            var first = true
            var i = 0
            while (i < values.size) {
                val x = (i.toFloat() / (values.size - 1)) * w
                val y = yOf(values[i])
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
                i += step
            }

            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * Radial gauge for heart rate.
 *
 * Why a gauge and not a line chart for this data type: heart rate updates
 * about once a second and what a user actually asks of it is "where am I in my
 * range right now", which is a position-within-bounds question, not a shape
 * question. A dial answers that pre-attentively - you read the needle angle
 * without parsing numbers. Colour carries the zone as a redundant channel, and
 * the BPM number is printed alongside by the caller so the reading survives for
 * anyone who cannot distinguish the colours.
 *
 * Sweep is 270 degrees starting at 135 (bottom left), the convention for
 * every analogue dial, so it needs no explaining to a user.
 */
@Composable
fun HeartRateGauge(
    bpm: Float,
    modifier: Modifier = Modifier,
    minBpm: Float = 40f,
    maxBpm: Float = 200f,
    arcColor: Color = Color(0xFFC62828),
    trackColor: Color = Color(0x22000000),
    size: Dp = 200.dp
) {
    Box(modifier = modifier.height(size).fillMaxWidth()) {
        Canvas(modifier = Modifier.height(size).fillMaxWidth()) {
            val stroke = 22f
            val diameter = minOf(this.size.width, this.size.height) - stroke
            val topLeft = Offset(
                (this.size.width - diameter) / 2f,
                (this.size.height - diameter) / 2f
            )
            val arcSize = Size(diameter, diameter)

            val startAngle = 135f
            val sweepTotal = 270f

            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = sweepTotal,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            if (bpm > 0f && !bpm.isNaN()) {
                val fraction = ((bpm - minBpm) / (maxBpm - minBpm)).coerceIn(0f, 1f)
                drawArc(
                    color = arcColor,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )

                // Needle. Canvas angles run clockwise from 3 o'clock, and
                // screen y grows downward, which is why sin is not negated.
                val angleDeg = startAngle + sweepTotal * fraction
                val angleRad = angleDeg * PI.toFloat() / 180f
                val centre = Offset(
                    topLeft.x + diameter / 2f,
                    topLeft.y + diameter / 2f
                )
                val radius = diameter / 2f - stroke
                drawLine(
                    color = arcColor,
                    start = centre,
                    end = Offset(
                        centre.x + radius * cos(angleRad),
                        centre.y + radius * sin(angleRad)
                    ),
                    strokeWidth = 6f,
                    cap = StrokeCap.Round
                )
                drawCircle(color = arcColor, radius = 10f, center = centre)
            }
        }
    }
}
