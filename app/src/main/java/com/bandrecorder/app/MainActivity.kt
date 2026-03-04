package com.bandrecorder.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

private enum class PendingAction {
    CALIBRATE,
    START_RECORDING,
    TEST_MIC
}

private enum class ScreenTab {
    RECORD,
    OPTIONS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
private fun MainScreen(vm: RecorderViewModel = viewModel()) {
    val ui by vm.uiState.collectAsState()
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    var tab by remember { mutableStateOf(ScreenTab.RECORD) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) return@rememberLauncherForActivityResult
        when (pendingAction) {
            PendingAction.CALIBRATE -> vm.runCalibration()
            PendingAction.START_RECORDING -> vm.startRecording()
            PendingAction.TEST_MIC -> vm.testSelectedMicrophone()
            null -> Unit
        }
        pendingAction = null
    }

    Column(
        modifier = Modifier
            .padding(20.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Band Recorder V1", style = MaterialTheme.typography.headlineMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tab == ScreenTab.RECORD) {
                Button(onClick = { tab = ScreenTab.RECORD }) { Text("Record") }
                OutlinedButton(onClick = { tab = ScreenTab.OPTIONS }) { Text("Options") }
            } else {
                OutlinedButton(onClick = { tab = ScreenTab.RECORD }) { Text("Record") }
                Button(onClick = { tab = ScreenTab.OPTIONS }) { Text("Options") }
            }
        }

        when (tab) {
            ScreenTab.RECORD -> {
                RecordTab(
                    ui = ui,
                    onRequestCalibrate = {
                        pendingAction = PendingAction.CALIBRATE
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onRequestStart = {
                        pendingAction = PendingAction.START_RECORDING
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onStop = { vm.stopRecording() }
                )
            }

            ScreenTab.OPTIONS -> {
                OptionsTab(
                    ui = ui,
                    onStorageChange = vm::setStorageLocation,
                    onRefreshMics = vm::refreshMicrophones,
                    onSelectMic = vm::selectMicrophone,
                    onTestMic = {
                        pendingAction = PendingAction.TEST_MIC
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )
            }
        }
    }
}

@Composable
private fun RecordTab(
    ui: RecorderUiState,
    onRequestCalibrate: () -> Unit,
    onRequestStart: () -> Unit,
    onStop: () -> Unit
) {
    val vuProgress = ((ui.peakDb + 60f) / 60f).coerceIn(0f, 1f)
    val vuColor = when {
        ui.peakDb > -3f -> Color(0xFFD32F2F)
        ui.peakDb > -9f -> Color(0xFFFF9800)
        else -> Color(0xFF2E7D32)
    }

    val saturationAlert = ui.peakDb > -3f
    val lowLevelAlert = ui.rmsDb < -38f

    Text("Status: ${ui.status}")

    if (ui.isCalibrating) {
        Text("Calibration en cours: ${ui.calibrationProgress}%")
        LinearProgressIndicator(
            progress = { ui.calibrationProgress / 100f },
            modifier = Modifier.fillMaxWidth()
        )
    }

    ui.recommendedGainDb?.let {
        val signed = if (it >= 0f) "+${"%.1f".format(it)}" else "${"%.1f".format(it)}"
        Text("Gain recommande: $signed dB", fontWeight = FontWeight.SemiBold)
    }

    Text("RMS: ${"%.1f".format(ui.rmsDb)} dBFS")
    Text("Peak: ${"%.1f".format(ui.peakDb)} dBFS")
    Text("Headroom: ${"%.1f".format(ui.headroomDb)} dB")

    Text("VU meter")
    LinearProgressIndicator(
        progress = { vuProgress },
        color = vuColor,
        modifier = Modifier.fillMaxWidth()
    )

    if (ui.isRecording) {
        Text("Duree: ${formatTimer(ui.elapsedMs)}", fontWeight = FontWeight.SemiBold)
    }

    if (saturationAlert) {
        Text("Alerte: saturation possible", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
    } else if (lowLevelAlert && ui.isRecording) {
        Text("Alerte: niveau trop faible", color = Color(0xFFFF9800), fontWeight = FontWeight.Bold)
    }

    if (ui.isRecording) {
        Button(onClick = onStop) {
            Text("Stop")
        }
    } else {
        Button(onClick = onRequestCalibrate, enabled = !ui.isCalibrating && !ui.isTestingMic) {
            Text("Calibrer 30s")
        }

        Button(onClick = onRequestStart, enabled = !ui.isCalibrating && !ui.isTestingMic) {
            Text("Start Recording")
        }
    }

    ui.lastOutputPath?.let {
        Text("Fichier: $it", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OptionsTab(
    ui: RecorderUiState,
    onStorageChange: (StorageLocation) -> Unit,
    onRefreshMics: () -> Unit,
    onSelectMic: (Int?) -> Unit,
    onTestMic: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Emplacement d'enregistrement", fontWeight = FontWeight.Bold)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.storageLocation == StorageLocation.DOWNLOADS) {
                Button(onClick = { onStorageChange(StorageLocation.DOWNLOADS) }) { Text("Downloads") }
                OutlinedButton(onClick = { onStorageChange(StorageLocation.APP_PRIVATE) }) { Text("App privée") }
            } else {
                OutlinedButton(onClick = { onStorageChange(StorageLocation.DOWNLOADS) }) { Text("Downloads") }
                Button(onClick = { onStorageChange(StorageLocation.APP_PRIVATE) }) { Text("App privée") }
            }
        }

        Text(
            if (ui.storageLocation == StorageLocation.DOWNLOADS)
                "Par defaut: Downloads/Band Recorder"
            else
                "Par defaut: dossier privé de l'app"
        )

        Text("Microphones", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefreshMics) { Text("Scanner") }
            Button(onClick = onTestMic, enabled = !ui.isRecording && !ui.isCalibrating && !ui.isTestingMic) {
                Text(if (ui.isTestingMic) "Test..." else "Tester 5s")
            }
        }

        OutlinedButton(onClick = { onSelectMic(null) }) {
            Text(if (ui.selectedMicId == null) "Auto (selection active)" else "Auto")
        }

        if (ui.microphones.isEmpty()) {
            Text("Aucun micro detecte")
        } else {
            ui.microphones.forEach { mic ->
                val selected = mic.id == ui.selectedMicId
                if (selected) {
                    Button(onClick = { onSelectMic(mic.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${mic.label} (selectionne)")
                    }
                } else {
                    OutlinedButton(onClick = { onSelectMic(mic.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(mic.label)
                    }
                }
            }
        }

        ui.micTestResult?.let {
            Text("Resultat test: $it")
        }

        Text("Options a venir: format FLAC, gain manuel, fade auto, seuil silence", style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatTimer(ms: Long): String {
    val totalSec = (ms / 1000.0).roundToInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}
