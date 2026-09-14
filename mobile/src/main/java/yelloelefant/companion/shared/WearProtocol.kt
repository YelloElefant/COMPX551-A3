package yelloelefant.companion.shared

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The wire contract between the watch app and the phone app.
 *
 * This file is byte-for-byte identical in both modules (same package name, so
 * the two copies never diverge in a way the compiler hides from you).
 */
object WearPaths {
    /** High-frequency accelerometer batches -> MessageClient (fire and forget). */
    const val ACCEL_MESSAGE = "/sensor/accel"

    /** Low-frequency heart rate -> DataClient (synced, latest value persists). */
    const val HEART_RATE_ITEM = "/sensor/heart_rate"

    // DataMap keys for the heart rate DataItem
    const val KEY_BPM = "bpm"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_AVAILABILITY = "availability"
}

/** One accelerometer reading in device coordinates, m/s^2, plus wall-clock time. */
data class AccelSample(
    val x: Float,
    val y: Float,
    val z: Float,
    val timestampMs: Long
)

/** A decoded batch of accelerometer samples as it arrived from the watch. */
data class AccelBatch(
    val sentAtMs: Long,
    val samples: List<AccelSample>
)

/** One heart rate reading in BPM. [availability] mirrors Health Services availability. */
data class HeartRateSample(
    val bpm: Float,
    val timestampMs: Long,
    val availability: Int = HrAvailability.UNKNOWN
)

object HrAvailability {
    const val UNKNOWN = 0
    const val AVAILABLE = 1
    const val ACQUIRING = 2
    const val UNAVAILABLE = 3

    fun label(code: Int): String = when (code) {
        AVAILABLE -> "available"
        ACQUIRING -> "acquiring"
        UNAVAILABLE -> "unavailable"
        else -> "unknown"
    }
}

/**
 * Compact binary codec for accelerometer batches.
 *
 * Why bother instead of JSON? A MessageClient payload is capped (100 KB) and
 * every message is an RPC over Bluetooth. 50 Hz of x/y/z as JSON is roughly
 * 60 bytes per sample of mostly punctuation; here it is 12 bytes flat. Batching
 * 20 samples costs 252 bytes and ~2.5 messages/second instead of 50.
 *
 * Layout (little endian):
 *   [0..7]   int64  sentAtMs            wall clock when the batch left the watch
 *   [8..11]  int32  count               number of samples that follow
 *   [12..]   float32 x, y, z  * count   sample values, oldest first
 *
 */
object AccelCodec {

    const val HEADER_BYTES = 12
    const val SAMPLE_BYTES = 12

    fun encode(sentAtMs: Long, samples: List<AccelSample>): ByteArray {
        val buffer = ByteBuffer
            .allocate(HEADER_BYTES + samples.size * SAMPLE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.putLong(sentAtMs)
        buffer.putInt(samples.size)
        for (s in samples) {
            buffer.putFloat(s.x)
            buffer.putFloat(s.y)
            buffer.putFloat(s.z)
        }
        return buffer.array()
    }

    /**
     * @param nominalPeriodMs assumed gap between samples, used to rebuild timestamps.
     */
    fun decode(bytes: ByteArray, nominalPeriodMs: Long = 20L): AccelBatch {
        require(bytes.size >= HEADER_BYTES) { "payload too short: ${bytes.size} bytes" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val sentAtMs = buffer.long
        val count = buffer.int
        require(count >= 0 && bytes.size == HEADER_BYTES + count * SAMPLE_BYTES) {
            "payload size ${bytes.size} does not match declared count $count"
        }

        // Oldest sample sits furthest back in time from sentAtMs.
        val firstTs = sentAtMs - (count - 1).coerceAtLeast(0) * nominalPeriodMs
        val samples = ArrayList<AccelSample>(count)
        for (i in 0 until count) {
            samples += AccelSample(
                x = buffer.float,
                y = buffer.float,
                z = buffer.float,
                timestampMs = firstTs + i * nominalPeriodMs
            )
        }
        return AccelBatch(sentAtMs, samples)
    }
}
