package com.bandrecorder.app

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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen(vm: RecorderViewModel = viewModel()) {
    val ui by vm.uiState.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.toggleRecording()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Band Recorder MVP", style = MaterialTheme.typography.headlineMedium)
        Text(text = "Status: ${ui.status}")
        Text(text = "RMS: ${"%.1f".format(ui.rmsDb)} dBFS")
        Text(text = "Peak: ${"%.1f".format(ui.peakDb)} dBFS")
        Text(text = "Headroom: ${"%.1f".format(ui.headroomDb)} dB")

        Button(onClick = {
            if (ui.isRecording) {
                vm.toggleRecording()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }) {
            Text(if (ui.isRecording) "Stop" else "Start")
        }
    }
}
