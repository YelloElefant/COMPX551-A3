package yelloelefant.companion

import kotlin.math.abs
import kotlin.math.sqrt



// all verified with kotlin playground

/**
 * Part D lives here: small, testable, allocation-free processing primitives.
 *
 * They are deliberately plain classes rather than Flow operators so they can
 * be unit tested with a for loop and no coroutine machinery, and so the
 * ViewModel reads as a pipeline you can point at during the code review.
 */

/**
 * First-order IIR low-pass filter (exponential moving average).
 *
 *     y[n] = a * x[n] + (1 - a) * y[n-1]
 *
 * Why this and not a moving average? Same job, O(1) memory and one multiply
 * per sample, and no group delay spike. The tradeoff is a gentle -6 dB/octave
 * rolloff rather than a sharp cutoff, which is fine for killing sensor hash.
 *
 * The -3 dB corner frequency for sample rate fs is roughly
 *     fc ~= fs * a / (2 * pi * (1 - a))
 * so at fs = 50 Hz and a = 0.2, fc lands near 2 Hz. Human arm movement lives
 * below about 5 Hz, MEMS noise is broadband, so 2 Hz keeps the motion and
 * throws away the fuzz.
 */
class LowPassFilter(private val alpha: Float) {
    init { require(alpha > 0f && alpha <= 1f) { "alpha must be in (0, 1]" } }

    private var value = Float.NaN

    fun next(x: Float): Float {
        value = if (value.isNaN()) x else alpha * x + (1f - alpha) * value
        return value
    }

    fun reset() { value = Float.NaN }
}

/**
 * Rolling min / max / mean / population standard deviation over a fixed window.
 *
 * Mean and sd come from running sum and sum-of-squares, which is O(1) per
 * sample. That form is numerically sloppy for huge or badly scaled data
 * (catastrophic cancellation in sumSq - n*mean^2); at our magnitudes, a few
 * hundred samples of single-digit m/s^2, it is nowhere near a problem. If it
 * ever were, the fix is Welford's algorithm.
 *
 * Min and max are recomputed by scanning the window, which is O(window) on
 * demand rather than per sample. A monotonic deque would make it O(1) but at
 * 50 Hz over 250 samples that is premature optimisation.
 */
class RollingStats(private val windowSize: Int) {
    init { require(windowSize > 1) }

    private val buffer = FloatArray(windowSize)
    private var count = 0
    private var head = 0
    private var sum = 0.0
    private var sumSq = 0.0

    fun add(x: Float) {
        if (count == windowSize) {
            val evicted = buffer[head]
            sum -= evicted
            sumSq -= evicted.toDouble() * evicted
        } else {
            count++
        }
        buffer[head] = x
        sum += x
        sumSq += x.toDouble() * x
        head = (head + 1) % windowSize
    }

    val size: Int get() = count

    val mean: Float get() = if (count == 0) 0f else (sum / count).toFloat()

    val standardDeviation: Float
        get() {
            if (count < 2) return 0f
            val m = sum / count
            val variance = (sumSq / count) - m * m
            return sqrt(variance.coerceAtLeast(0.0)).toFloat()
        }

    val min: Float get() = window().minOrNull() ?: 0f
    val max: Float get() = window().maxOrNull() ?: 0f

    /** The window contents, oldest first. Used by the chart. */
    fun window(): FloatArray {
        if (count < windowSize) return buffer.copyOfRange(0, count)
        val out = FloatArray(windowSize)
        for (i in 0 until windowSize) out[i] = buffer[(head + i) % windowSize]
        return out
    }
}

object Kinematics {
    const val GRAVITY = 9.80665f

    /** Euclidean norm of the acceleration vector, still including gravity. */
    fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    /**
     * Orientation-independent "how hard is this thing being moved" scalar.
     *
     * Subtracting a constant g from the norm is a shortcut: the honest version
     * high-passes the vector to estimate the gravity direction, then projects.
     * But |a| - g is exact when the device is still in any orientation, and
     * close enough during motion, while being one subtraction. Signed result
     * is kept on purpose - negative means net acceleration toward free fall.
     */
    fun linearMagnitude(x: Float, y: Float, z: Float): Float = magnitude(x, y, z) - GRAVITY
}

/** Coarse heart rate zones, used to colour the gauge. */
enum class HeartRateZone(val label: String) {
    UNKNOWN("no reading"),
    RESTING("resting"),
    LIGHT("light"),
    MODERATE("moderate"),
    VIGOROUS("vigorous");

    companion object {
        /**
         * Percentage-of-max-HR bands, with max estimated as 220 - age. That
         * formula is a crude population fit with a standard deviation around
         * 10-12 bpm, so these are indicative bands for a demo, not anything
         * anyone should train by.
         */
        fun of(bpm: Float, age: Int = 20): HeartRateZone {
            if (bpm <= 0f || bpm.isNaN()) return UNKNOWN
            val maxHr = (220 - age).toFloat()
            return when (bpm / maxHr) {
                in 0f..0.50f -> RESTING
                in 0.50f..0.64f -> LIGHT
                in 0.64f..0.77f -> MODERATE
                else -> VIGOROUS
            }
        }
    }
}
