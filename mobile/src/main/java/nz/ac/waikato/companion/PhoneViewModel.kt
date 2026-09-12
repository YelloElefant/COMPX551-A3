package yelloelefant.companion

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import yelloelefant.companion.data.SensorRepository
import yelloelefant.companion.shared.HrAvailability

data class MotionUiState(
    val trace: FloatArray = FloatArray(0),   // filtered linear magnitude, oldest first
    val latest: Float = 0f,
    val min: Float = 0f,
    val max: Float = 0f,
    val mean: Float = 0f,
    val standardDeviation: Float = 0f,
    val peakCount: Int = 0,
    val samplesReceived: Long = 0L,
    val lastBatchAtMs: Long = 0L
) {
    // FloatArray in a data class breaks structural equality, so spell it out.
    // Without this, Compose either never recomposes or recomposes constantly.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MotionUiState) return false
        return samplesReceived == other.samplesReceived &&
            peakCount == other.peakCount &&
            latest == other.latest
    }

    override fun hashCode(): Int =
        samplesReceived.hashCode() * 31 + peakCount * 31 + latest.hashCode()
}

data class HeartRateUiState(
    val raw: Float = 0f,
    val smoothed: Float = 0f,
    val min: Float = 0f,
    val max: Float = 0f,
    val mean: Float = 0f,
    val zone: HeartRateZone = HeartRateZone.UNKNOWN,
    val availability: Int = HrAvailability.UNKNOWN,
    val lastUpdateMs: Long = 0L
)

/**
 * Part D and the data half of Part E.
 *
 * Each data type gets its own pipeline because each one has different
 * properties. The accelerometer is fast, noisy and vector valued, so it needs
 * dimensionality reduction, filtering and event detection. Heart rate is slow,
 * already smoothed by the watch firmware and scalar, so it needs mild
 * smoothing and classification, nothing more. Applying the same processing to
 * both would be cargo culting.
 */
class PhoneViewModel(app: Application) : AndroidViewModel(app) {

    // ---- motion pipeline state ----
    private val motionFilter = LowPassFilter(alpha = MOTION_ALPHA)
    private val motionStats = RollingStats(windowSize = MOTION_WINDOW)
    private val peakDetector = PeakDetector(
        threshold = PEAK_THRESHOLD_MS2,
        refractoryMs = 250L
    )
    private var samplesReceived = 0L

    // ---- heart rate pipeline state ----
    private val hrFilter = LowPassFilter(alpha = HR_ALPHA)
    private val hrStats = RollingStats(windowSize = HR_WINDOW)

    private val _motion = MutableStateFlow(MotionUiState())
    val motion: StateFlow<MotionUiState> = _motion.asStateFlow()

    private val _heartRate = MutableStateFlow(HeartRateUiState())
    val heartRate: StateFlow<HeartRateUiState> = _heartRate.asStateFlow()

    init {
        viewModelScope.launch { collectMotion() }
        viewModelScope.launch { collectHeartRate() }
    }

    /**
     * Pipeline per sample:
     *   (x, y, z) -> |a| - g -> low pass -> rolling stats
     *                                    -> peak detector
     *
     * Note the UI state is published once per batch, not once per sample. At
     * 50 Hz, emitting per sample would ask Compose to recompose 50 times a
     * second for a chart that is only 60 Hz anyway. Per batch is ~2.5 Hz of
     * recomposition for a visually identical result.
     */
    private suspend fun collectMotion() {
        SensorRepository.accelBatches.collect { batch ->
            for (sample in batch.samples) {
                val linear = Kinematics.linearMagnitude(sample.x, sample.y, sample.z)
                val filtered = motionFilter.next(linear)
                motionStats.add(filtered)
                peakDetector.update(filtered, sample.timestampMs)
            }
            samplesReceived += batch.samples.size

            _motion.value = MotionUiState(
                trace = motionStats.window(),
                latest = motionStats.window().lastOrNull() ?: 0f,
                min = motionStats.min,
                max = motionStats.max,
                mean = motionStats.mean,
                standardDeviation = motionStats.standardDeviation,
                peakCount = peakDetector.count,
                samplesReceived = samplesReceived,
                lastBatchAtMs = batch.sentAtMs
            )
        }
    }

    /**
     * Heart rate gets a much gentler filter (alpha 0.35 at ~1 Hz, so a corner
     * near 0.09 Hz) because the sensor already does optical signal processing
     * internally. We are only knocking the edge off the occasional bad frame.
     */
    private suspend fun collectHeartRate() {
        SensorRepository.heartRate.collect { sample ->
            if (sample.bpm.isNaN() || sample.bpm <= 0f) {
                _heartRate.value = _heartRate.value.copy(availability = sample.availability)
                return@collect
            }
            val smoothed = hrFilter.next(sample.bpm)
            hrStats.add(smoothed)

            _heartRate.value = HeartRateUiState(
                raw = sample.bpm,
                smoothed = smoothed,
                min = hrStats.min,
                max = hrStats.max,
                mean = hrStats.mean,
                zone = HeartRateZone.of(smoothed),
                availability = sample.availability,
                lastUpdateMs = sample.timestampMs
            )
        }
    }

    fun resetPeaks() {
        peakDetector.reset()
        _motion.value = _motion.value.copy(peakCount = 0)
    }

    private companion object {
        /** ~2 Hz corner at 50 Hz sampling. Keeps gait, drops MEMS hash. */
        const val MOTION_ALPHA = 0.2f

        /** 250 samples at 50 Hz = a 5 second statistics window. */
        const val MOTION_WINDOW = 250

        /**
         * 2.0 m/s^2 above/below gravity. Empirical: a deliberate wrist flick
         * or a footstep clears it comfortably, typing or breathing does not.
         * Tune this live on the emulator and say so in the review.
         */
        const val PEAK_THRESHOLD_MS2 = 2.0f

        const val HR_ALPHA = 0.35f

        /** 60 readings at roughly 1 Hz = about a minute of heart rate history. */
        const val HR_WINDOW = 60
    }
}
