package yelloelefant.companion.data

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import yelloelefant.companion.shared.AccelCodec
import yelloelefant.companion.shared.HeartRateSample
import yelloelefant.companion.shared.WearPaths

// this handles the data sent from the watch
// onMessage for the acceleromter stuff
// onData for the heartrate stuff, then moves them all to the sensor repo same as last assemsent
class PhoneWearableListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearPaths.ACCEL_MESSAGE) return

        runCatching { AccelCodec.decode(event.data) }
            .onSuccess { SensorRepository.submitAccelBatch(it) }
            .onFailure { Log.w(TAG, "Malformed accel payload", it) }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            // TYPE_DELETED also exists; we only care about writes.
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != WearPaths.HEART_RATE_ITEM) continue

            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            SensorRepository.submitHeartRate(
                HeartRateSample(
                    bpm = map.getFloat(WearPaths.KEY_BPM),
                    timestampMs = map.getLong(WearPaths.KEY_TIMESTAMP),
                    availability = map.getInt(WearPaths.KEY_AVAILABILITY)
                )
            )
        }
        // No release() call: WearableListenerService releases the buffer for us
        // once this method returns. Releasing it here would double-release.
    }

    private companion object {
        const val TAG = "PhoneWearListener"
    }
}
