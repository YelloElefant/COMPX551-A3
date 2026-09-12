package yelloelefant.companion.wear

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import yelloelefant.companion.shared.HrAvailability

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WearScreen()
            }
        }
    }
}

@Composable
private fun WearScreen(viewModel: WearViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // permission box
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.start() }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.BODY_SENSORS)
    }


    //  your standard compose definition watching a mutablestaeof varible for redraws
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (state.linkUp) "Phone connected" else "Waiting for phone",
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center
        )
        Text(
            text = "|a| ${"%.2f".format(state.accelMagnitude)} m/s2",
            style = MaterialTheme.typography.title3
        )
        Text(
            text = if (state.bpm > 0f) "${state.bpm.toInt()} bpm"
            else HrAvailability.label(state.hrAvailability),
            style = MaterialTheme.typography.title3
        )
        Text(
            text = "batches ${state.batchesSent}",
            style = MaterialTheme.typography.caption2
        )
        state.error?.let {
            Text(text = it, style = MaterialTheme.typography.caption3)
        }
    }
}
