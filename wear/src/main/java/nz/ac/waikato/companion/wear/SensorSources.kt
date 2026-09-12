package yelloelefant.companion.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.guava.await
import yelloelefant.companion.shared.AccelSample
import yelloelefant.companion.shared.HeartRateSample
import yelloelefant.companion.shared.HrAvailability

// flows give a sorta blueprint function for a model or activity to call and run in the background
fun accelerometerFlow(
    context: Context,
    samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME
): Flow<AccelSample> = callbackFlow {
    val sensorManager = context.getSystemService(SensorManager::class.java)
    val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    if (sensor == null) {
        close(IllegalStateException("No accelerometer on this device"))
        return@callbackFlow
    }


    // gets the sensor reading and push onto the "belt" for the colector to use
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // event.timestamp is nanos since boot, not epoch. We want epoch
            // millis so the phone can reason about it, so use wall clock here.
            trySend(
                AccelSample(
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    timestampMs = System.currentTimeMillis()
                )
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    sensorManager.registerListener(listener, sensor, samplingPeriodUs)
    awaitClose { sensorManager.unregisterListener(listener) }
}

// same thing but just for hearate, so the colector needs health services
fun heartRateFlow(context: Context): Flow<HeartRateSample> = callbackFlow {
    val measureClient = HealthServices.getClient(context).measureClient

    // Capability check first. On a watch without an HR sensor (or an emulator
    // with no synthetic provider running) registering would just silently
    // never deliver, which is miserable to debug.
    val capabilities = measureClient.getCapabilitiesAsync().await()
    if (DataType.HEART_RATE_BPM !in capabilities.supportedDataTypesMeasure) {
        close(IllegalStateException("HEART_RATE_BPM is not supported on this device"))
        return@callbackFlow
    }

    val callback = object : MeasureCallback {
        override fun onAvailabilityChanged(
            dataType: DeltaDataType<*, *>,
            availability: Availability
        ) {
            // Availability is genuinely useful UI state: "acquiring" means the
            // watch is on a wrist but has not locked on yet.
            val code = when (availability) {
                DataTypeAvailability.AVAILABLE -> HrAvailability.AVAILABLE
                DataTypeAvailability.ACQUIRING -> HrAvailability.ACQUIRING
                DataTypeAvailability.UNAVAILABLE,
                DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY -> HrAvailability.UNAVAILABLE
                else -> HrAvailability.UNKNOWN
            }
            // bpm = NaN flags "this is a status update, not a reading".
            trySend(HeartRateSample(Float.NaN, System.currentTimeMillis(), code))
        }

        override fun onDataReceived(data: DataPointContainer) {
            data.getData(DataType.HEART_RATE_BPM).forEach { point ->
                trySend(
                    HeartRateSample(
                        bpm = point.value.toFloat(),
                        timestampMs = System.currentTimeMillis(),
                        availability = HrAvailability.AVAILABLE
                    )
                )
            }
        }
    }

    measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
    awaitClose {
        measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
    }
}
