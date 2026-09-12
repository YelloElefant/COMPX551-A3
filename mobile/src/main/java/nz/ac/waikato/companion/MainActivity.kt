package nz.ac.waikato.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.ac.waikato.companion.shared.HrAvailability
import nz.ac.waikato.companion.ui.HeartRateGauge
import nz.ac.waikato.companion.ui.LineChart

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PhoneScreen()
                }
            }
        }
    }
}

@Composable
private fun PhoneScreen(viewModel: PhoneViewModel = viewModel()) {
    val motion by viewModel.motion.collectAsStateWithLifecycle()
    val hr by viewModel.heartRate.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Sensor Companion", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = if (motion.samplesReceived > 0)
                "${motion.samplesReceived} accelerometer samples received"
            else "Waiting for the watch. Open the watch app and grant BODY_SENSORS.",
            style = MaterialTheme.typography.bodySmall
        )

        // ---------------- Data type 1: motion ----------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Linear acceleration", style = MaterialTheme.typography.titleMedium)
                Text(
                    "|a| minus gravity, low-pass filtered, 5 s window",
                    style = MaterialTheme.typography.bodySmall
                )

                LineChart(values = motion.trace)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatColumn("now", motion.latest)
                    StatColumn("min", motion.min)
                    StatColumn("max", motion.max)
                    StatColumn("mean", motion.mean)
                    StatColumn("sd", motion.standardDeviation)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "peaks detected: ${motion.peakCount}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(onClick = viewModel::resetPeaks) { Text("Reset") }
                }
            }
        }

        // ---------------- Data type 2: heart rate ----------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Heart rate", style = MaterialTheme.typography.titleMedium)

                HeartRateGauge(
                    bpm = hr.smoothed,
                    arcColor = zoneColour(hr.zone)
                )

                Text(
                    text = if (hr.smoothed > 0f) "${hr.smoothed.toInt()} bpm" else "--",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(hr.zone.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "raw ${hr.raw.toInt()} | min ${hr.min.toInt()} | " +
                        "max ${hr.max.toInt()} | mean ${hr.mean.toInt()} | " +
                        "sensor ${HrAvailability.label(hr.availability)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text("%.2f".format(value), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun zoneColour(zone: HeartRateZone): Color = when (zone) {
    HeartRateZone.UNKNOWN -> Color(0xFF9E9E9E)
    HeartRateZone.RESTING -> Color(0xFF1565C0)
    HeartRateZone.LIGHT -> Color(0xFF2E7D32)
    HeartRateZone.MODERATE -> Color(0xFFEF6C00)
    HeartRateZone.VIGOROUS -> Color(0xFFC62828)
}
