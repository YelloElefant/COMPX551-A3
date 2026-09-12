package yelloelefant.companion.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import yelloelefant.companion.shared.AccelSample
import yelloelefant.companion.shared.HrAvailability
import kotlin.math.sqrt

data class WearUiState(
    val streaming: Boolean = false,
    val accelMagnitude: Float = 0f,
    val batchesSent: Int = 0,
    val bpm: Float = 0f,
    val hrAvailability: Int = HrAvailability.UNKNOWN,
    val linkUp: Boolean = false,
    val error: String? = null
)

// corordinator of the hole logic, model stays open as long as the app is open even if
// activty is re made, this pull sensor data inits the datasending, and changes the current state
// for hte front end
class WearViewModel(app: Application) : AndroidViewModel(app) {

    private val sender = WearDataSender(app)

    private val _uiState = MutableStateFlow(WearUiState())
    val uiState: StateFlow<WearUiState> = _uiState.asStateFlow()

    // batch the accelromter, this sensor updates fast so shouldnt send a full frame per reading
    private val pending = ArrayList<AccelSample>(BATCH_SIZE)

    fun start() {
        if (_uiState.value.streaming) return
        _uiState.value = _uiState.value.copy(streaming = true, error = null)

        // Accelerometer: batch then send.
        viewModelScope.launch {
            accelerometerFlow(getApplication())
                .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                .collect { sample ->
                    pending += sample

                    // Cheap local feedback: raw magnitude including gravity,
                    // so a still watch reads about 9.8 and i know it works.
                    _uiState.value = _uiState.value.copy(
                        accelMagnitude = sqrt(
                            sample.x * sample.x + sample.y * sample.y + sample.z * sample.z
                        )
                    )

                    if (pending.size >= BATCH_SIZE) {
                        val batch = ArrayList(pending)
                        pending.clear()
                        val ok = sender.sendAccelBatch(batch)
                        _uiState.value = _uiState.value.copy(
                            linkUp = ok,
                            batchesSent = _uiState.value.batchesSent + if (ok) 1 else 0
                        )
                    }
                }
        }

        // Heart rate: send each reading straight through as a DataItem.
        viewModelScope.launch {
            heartRateFlow(getApplication())
                .catch { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                .collect { sample ->
                    _uiState.value = _uiState.value.copy(
                        hrAvailability = sample.availability,
                        bpm = if (sample.bpm.isNaN()) _uiState.value.bpm else sample.bpm
                    )
                    if (!sample.bpm.isNaN()) {
                        sender.sendHeartRate(sample.bpm, sample.availability)
                    }
                }
        }
    }

    private companion object {
        // batch size definition, 20 readings at 50hz is about 400ms between message
        const val BATCH_SIZE = 20
    }
}
