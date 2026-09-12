package nz.ac.waikato.companion.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import nz.ac.waikato.companion.shared.AccelSample
import nz.ac.waikato.companion.shared.WearPaths


// global datasender bit, 2 sending methods one for heartrate and one for acceleromter
// accel sent through messageclient or send and forget messages that arent persistent
// heartrate sent through dataclient for a persistent data setup (mqtt retain type thing)
class WearDataSender(context: Context) {

    // fir and forget data sending
    private val messageClient = Wearable.getMessageClient(context)

    // retained message system, (mqtt retain message type thing)
    private val dataClient = Wearable.getDataClient(context)

    // conected devices manager and state querrier
    private val nodeClient = Wearable.getNodeClient(context)

    // chaching the descovered nodes so i dont have to constantly search
    @Volatile
    private var cachedNodes: List<Node> = emptyList()
    @Volatile
    private var cachedAtMs: Long = 0L



    suspend fun connectedNodes(forceRefresh: Boolean = false): List<Node> {
        // invalidate cache on timing
        val stale = System.currentTimeMillis() - cachedAtMs > NODE_CACHE_TTL_MS
        if (forceRefresh || stale || cachedNodes.isEmpty()) {
            // gets connected nodes and caches them
            cachedNodes = runCatching { nodeClient.connectedNodes.await() }
                .getOrElse {
                    Log.w(TAG, "connectedNodes failed", it)
                    emptyList()
                }
            cachedAtMs = System.currentTimeMillis()
        }
        // returns the found nodes
        return cachedNodes
    }

    // takes a list of accelromter smaples and sends then to the phone, returns fail boolean
    suspend fun sendAccelBatch(samples: List<AccelSample>): Boolean {
        if (samples.isEmpty()) return false
        val payload = nz.ac.waikato.companion.shared.AccelCodec
            .encode(System.currentTimeMillis(), samples)

        val nodes = connectedNodes()
        if (nodes.isEmpty()) return false

        var anySent = false
        for (node in nodes) {
            runCatching {
                messageClient.sendMessage(node.id, WearPaths.ACCEL_MESSAGE, payload).await()
            }.onSuccess {
                anySent = true
            }.onFailure {
                Log.w(TAG, "sendMessage to ${node.displayName} failed", it)
                cachedAtMs = 0L // force a node refresh next time round
            }
        }
        return anySent
    }

    // send persistent data/retain message, updates local sql server which is synced between
    // phone and watch by android. setUrgently makes it not wait for changed as android lkes todo
    suspend fun sendHeartRate(bpm: Float, availability: Int) {
        val request = PutDataMapRequest.create(WearPaths.HEART_RATE_ITEM).apply {
            dataMap.putFloat(WearPaths.KEY_BPM, bpm)
            dataMap.putLong(WearPaths.KEY_TIMESTAMP, System.currentTimeMillis())
            dataMap.putInt(WearPaths.KEY_AVAILABILITY, availability)
        }.asPutDataRequest().setUrgent()

        runCatching { dataClient.putDataItem(request).await() }
            .onFailure { Log.w(TAG, "putDataItem failed", it) }
    }

    private companion object {
        const val TAG = "WearDataSender"
        const val NODE_CACHE_TTL_MS = 10_000L
    }
}
