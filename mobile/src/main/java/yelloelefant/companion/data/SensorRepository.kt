package yelloelefant.companion.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import yelloelefant.companion.shared.AccelBatch
import yelloelefant.companion.shared.HeartRateSample

/**
 * The join point between the WearableListenerService and the UI.
 *
 * Why an object (process singleton)? The listener service is started by the
 * Data Layer on its own schedule, in the same process as the Activity but with
 * no reference to it. A process-scoped singleton is the simplest correct way
 * for the two to meet.
 *
 * The honest caveat, and a fair code-review question: this is in-memory only.
 * If Android kills the process, buffered data is gone, and data that arrives
 * while the app is dead only restarts the service, it does not restore history.
 * A production build would persist to Room or DataStore in the service and let
 * the UI observe the database instead. For a live-visualisation assignment,
 * keeping it in memory is a deliberate scope decision, not an oversight.
 */
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
