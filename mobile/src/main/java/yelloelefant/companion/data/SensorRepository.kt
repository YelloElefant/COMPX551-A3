package yelloelefant.companion.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import yelloelefant.companion.shared.AccelBatch
import yelloelefant.companion.shared.HeartRateSample

// the join between the listener and the front end,
// holds all in memory just latest thing
object SensorRepository {

    /**
     * extraBufferCapacity + DROP_OLDEST is the important bit. If the UI is
     * mid-recomposition when three batches land, we would rather bin the
     * oldest than suspend the Data Layer callback thread - that thread
     * blocking is how you end up with ANRs in a listener service.
     */
    private val _accelBatches = MutableSharedFlow<AccelBatch>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val accelBatches: SharedFlow<AccelBatch> = _accelBatches.asSharedFlow()

    /** replay = 1 so a freshly opened screen immediately shows the last BPM. */
    private val _heartRate = MutableSharedFlow<HeartRateSample>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val heartRate: SharedFlow<HeartRateSample> = _heartRate.asSharedFlow()

    fun submitAccelBatch(batch: AccelBatch) {
        _accelBatches.tryEmit(batch)
    }

    fun submitHeartRate(sample: HeartRateSample) {
        _heartRate.tryEmit(sample)
    }
}
