package com.bandrecorder.app

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.media.MediaPlayer
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import java.io.File
import kotlin.math.roundToInt

private enum class PendingAction {
    RUN_LEVEL_BALANCE,
    START_RECORDING,
    TEST_MIC,
    PROBE_STEREO,
    AUTO_MIC_SETUP,
    RUN_GUIDED_STEREO_TEST,
    RUN_AB_TEST
}

private sealed class AppRoute(val route: String) {
    data object Home : AppRoute("home")
    data object Balance : AppRoute("balance")
    data object MicSettings : AppRoute("mic_settings")
    data object GuidedStereoTest : AppRoute("guided_stereo_test")
    data object Player : AppRoute("player")
    data object Settings : AppRoute("settings")
    data object Link : AppRoute("link")
}

private enum class BalanceSection {
    GLOBAL,
    INSTRUMENTS
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
    val navController = rememberNavController()
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            if (pendingAction == PendingAction.AUTO_MIC_SETUP) {
                vm.markAutoSetupPermissionDenied()
            }
            pendingAction = null
            return@rememberLauncherForActivityResult
        }
        when (pendingAction) {
            PendingAction.RUN_LEVEL_BALANCE -> vm.runLevelBalance()
            PendingAction.START_RECORDING -> vm.startRecording()
            PendingAction.TEST_MIC -> vm.testSelectedMicrophone()
            PendingAction.PROBE_STEREO -> vm.probeStereoCapability()
            PendingAction.AUTO_MIC_SETUP -> vm.runAutoMicSetup()
            PendingAction.RUN_GUIDED_STEREO_TEST -> vm.runGuidedStereoTest()
            PendingAction.RUN_AB_TEST -> vm.runExternalABTest()
            null -> Unit
        }
        pendingAction = null
    }

    fun requestAudioPermission(action: PendingAction) {
        pendingAction = action
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    NavHost(
        navController = navController,
        startDestination = AppRoute.Home.route
    ) {
        composable(AppRoute.Home.route) {
            HomeScreen(
                ui = ui,
                onRecordToggle = {
                    if (ui.isRecording) {
                        vm.stopRecording()
                    } else {
                        requestAudioPermission(PendingAction.START_RECORDING)
                    }
                },
                onOpenBalance = { navController.navigate(AppRoute.Balance.route) },
                onOpenMicSettings = { navController.navigate(AppRoute.MicSettings.route) },
                onOpenPlayer = { navController.navigate(AppRoute.Player.route) },
                onOpenSettings = { navController.navigate(AppRoute.Settings.route) },
                onOpenLink = { navController.navigate(AppRoute.Link.route) }
            )
        }
        composable(AppRoute.Balance.route) {
            BalanceScreen(
                ui = ui,
                onBack = { navController.popBackStack() },
                onSetBalanceDuration = vm::setBalanceDuration,
                onRunLevelBalance = { requestAudioPermission(PendingAction.RUN_LEVEL_BALANCE) }
            )
        }
        composable(AppRoute.MicSettings.route) {
            MicSettingsScreen(
                ui = ui,
                onBack = { navController.popBackStack() },
                onRefreshMics = vm::refreshMicrophones,
                onSelectMic = vm::selectMicrophone,
                onSetTestDuration = vm::setTestDuration,
                onToggleStereoRequested = vm::setStereoModeRequested,
                onToggleStereoSwap = vm::setStereoChannelsSwapped,
                onRequestTestMic = { requestAudioPermission(PendingAction.TEST_MIC) },
                onRequestProbeStereo = { requestAudioPermission(PendingAction.PROBE_STEREO) },
                onOpenGuidedStereoTest = { navController.navigate(AppRoute.GuidedStereoTest.route) },
                onRequestAutoMicSetup = { requestAudioPermission(PendingAction.AUTO_MIC_SETUP) }
            )
        }
        composable(AppRoute.GuidedStereoTest.route) {
            GuidedStereoTestScreen(
                ui = ui,
                onBack = { navController.popBackStack() },
                onRequestStart = { requestAudioPermission(PendingAction.RUN_GUIDED_STEREO_TEST) }
            )
        }
        composable(AppRoute.Settings.route) {
            SettingsScreen(
                ui = ui,
                onBack = { navController.popBackStack() },
                onStorageChange = vm::setStorageLocation,
                onToggleDiagnostic = vm::setDiagnosticMode,
                onToggleAdvanced = vm::setShowAdvancedInternals,
                onRequestRunABTest = { requestAudioPermission(PendingAction.RUN_AB_TEST) }
            )
        }
        composable(AppRoute.Player.route) {
            PlayerScreen(
                ui = ui,
                onBack = { navController.popBackStack() },
                onSetPreset = vm::setPlayerPreset,
                onSetEqEnabled = vm::setPlayerEqEnabled,
                onSetCompressionEnabled = vm::setPlayerCompressionEnabled,
                onSetDeEsserEnabled = vm::setPlayerDeEsserEnabled,
                onSetEqIntensity = vm::setPlayerEqIntensity,
                onSetCompressionIntensity = vm::setPlayerCompressionIntensity,
                onSetDeEsserIntensity = vm::setPlayerDeEsserIntensity,
                onSetStatus = vm::setPlayerStatusMessage
            )
        }
        composable(AppRoute.Link.route) {
            PlaceholderScreen(
                title = "Link / WiFi Direct",
                subtitle = "La connexion multi-appareils et le mode télécommande arrivent prochainement.",
                navController = navController
            )
        }
    }
}

@Composable
private fun HomeScreen(
    ui: RecorderUiState,
    onRecordToggle: () -> Unit,
    onOpenBalance: () -> Unit,
    onOpenMicSettings: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLink: () -> Unit
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF8BCF2F),
            Color(0xFFFFF44F),
            Color(0xFFFF8C42)
        )
    )
    val isBusy = ui.isCalibrating || ui.isTestingMic || ui.isRunningABTest || ui.isRunningStereoGuidedTest

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SmallActionButton(text = "Mic", onClick = onOpenMicSettings)
            SmallActionButton(text = "Link", onClick = onOpenLink)
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-122).dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = formatTimer(ui.elapsedMs),
                fontSize = 72.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = ui.status,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Button(
            onClick = onRecordToggle,
            enabled = !isBusy || ui.isRecording,
            modifier = Modifier
                .align(Alignment.Center)
                .width(170.dp)
                .height(118.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE6006F))
        ) {
            Text(
                text = if (ui.isRecording) "STOP" else "REC",
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
        }

        Button(
            onClick = onOpenBalance,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 68.dp)
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE6006F))
        ) {
            Text(
                text = "BALANCE",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SmallActionButton(text = "Lecteur", onClick = onOpenPlayer)
            SmallActionButton(text = "Paramètres", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun SmallActionButton(
    text: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Color.White
        )
    ) {
        Text(text)
    }
}

@Composable
private fun BalanceScreen(
    ui: RecorderUiState,
    onBack: () -> Unit,
    onSetBalanceDuration: (Int) -> Unit,
    onRunLevelBalance: () -> Unit
) {
    val vuProgress = ((ui.peakDb + 60f) / 60f).coerceIn(0f, 1f)
    val vuColor = when {
        ui.peakDb > -3f -> Color(0xFFD32F2F)
        ui.peakDb > -9f -> Color(0xFFFF9800)
        else -> Color(0xFF2E7D32)
    }
    val saturationAlert = ui.peakDb > -3f
    val lowLevelAlert = ui.rmsDb < -38f && ui.isRecording
    var section by remember { mutableStateOf(BalanceSection.GLOBAL) }

    ScreenScaffold(title = "Balance", onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (section == BalanceSection.GLOBAL) {
                Button(onClick = { section = BalanceSection.GLOBAL }) { Text("Global") }
                OutlinedButton(onClick = { section = BalanceSection.INSTRUMENTS }) { Text("Instruments") }
            } else {
                OutlinedButton(onClick = { section = BalanceSection.GLOBAL }) { Text("Global") }
                Button(onClick = { section = BalanceSection.INSTRUMENTS }) { Text("Instruments") }
            }
        }

        when (section) {
            BalanceSection.GLOBAL -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x22FFFFFF)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Vérification niveau", fontWeight = FontWeight.Bold)
                        Text(
                            "Objectif: éviter la saturation pendant la prise. Le traitement créatif est réservé au Lecteur.",
                            style = MaterialTheme.typography.bodySmall
                        )

                        Text("Durée balance", fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ui.availableBalanceDurationsSec.forEach { sec ->
                                if (sec == ui.balanceDurationSec) {
                                    Button(
                                        onClick = { onSetBalanceDuration(sec) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("${sec}s") }
                                } else {
                                    OutlinedButton(
                                        onClick = { onSetBalanceDuration(sec) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("${sec}s") }
                                }
                            }
                        }

                        Button(
                            onClick = onRunLevelBalance,
                            enabled = !ui.isRecording && !ui.isCalibrating && !ui.isRunningLevelBalance && !ui.isRunningABTest && !ui.isRunningStereoGuidedTest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (ui.isRunningLevelBalance) "Balance en cours..." else "Lancer balance niveau")
                        }

                        if (ui.isRunningLevelBalance) {
                            Text("Progression: ${ui.balanceProgress}%")
                            LinearProgressIndicator(
                                progress = { ui.balanceProgress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        ui.balanceDecisionLabel?.let { label ->
                            val color = when (label) {
                                "Peak > -3 dBFS" -> Color(0xFFD32F2F)
                                "Peak entre -6 et -3 dBFS" -> Color(0xFFFF9800)
                                "Peak entre -12 et -6 dBFS (idéal)" -> Color(0xFF2E7D32)
                                "Peak entre -18 et -12 dBFS" -> Color(0xFF607D8B)
                                else -> Color(0xFF1565C0)
                            }
                            Text("Interprétation: $label", color = color, fontWeight = FontWeight.Bold)
                        }
                        ui.balanceRecommendationText?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "Référence Peak max: > -3 dBFS = trop fort | -6..-3 = limite | -12..-6 = idéal | -18..-12 = un peu bas | < -18 = trop faible",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Status: ${ui.status}")
                Spacer(modifier = Modifier.height(8.dp))

                Text("RMS: ${"%.1f".format(ui.rmsDb)} dBFS")
                Text("Peak: ${"%.1f".format(ui.peakDb)} dBFS")
                Text("Headroom: ${"%.1f".format(ui.headroomDb)} dB")
                Text("Peak max balance: ${"%.1f".format(ui.balancePeakMaxDb)} dBFS")
                Spacer(modifier = Modifier.height(6.dp))

                Text("VU meter")
                LinearProgressIndicator(
                    progress = { vuProgress },
                    color = vuColor,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                ui.recommendedGainDb?.let {
                    val signed = if (it >= 0f) "+${"%.1f".format(it)}" else "${"%.1f".format(it)}"
                    Text("Gain recommandé: $signed dB", fontWeight = FontWeight.SemiBold)
                }

                if (saturationAlert) {
                    Text("Alerte: risque de saturation", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
                } else if (lowLevelAlert) {
                    Text("Alerte: niveau trop faible", color = Color(0xFFFF9800), fontWeight = FontWeight.Bold)
                }

                ui.lastOutputPath?.let {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Dernier fichier: $it", style = MaterialTheme.typography.bodySmall)
                }
            }

            BalanceSection.INSTRUMENTS -> {
                Text(
                    "Balances instrument (affinage par type). Référence rapide basée sur RMS/Peak actuels.",
                    style = MaterialTheme.typography.bodySmall
                )
                val profiles = listOf(
                    Triple("Voix lead", Pair(-24f, -16f), Pair(-12f, -6f)),
                    Triple("Guitare électrique", Pair(-22f, -14f), Pair(-10f, -6f)),
                    Triple("Basse", Pair(-20f, -14f), Pair(-9f, -5f)),
                    Triple("Kick", Pair(-18f, -12f), Pair(-8f, -4f)),
                    Triple("Snare", Pair(-20f, -14f), Pair(-10f, -5f)),
                    Triple("Overheads", Pair(-26f, -18f), Pair(-14f, -8f)),
                    Triple("Piano/Clavier", Pair(-24f, -16f), Pair(-12f, -7f)),
                    Triple("Sax/Trompette", Pair(-22f, -14f), Pair(-10f, -6f))
                )

                profiles.forEach { (name, rmsRange, peakRange) ->
                    val rmsOk = ui.rmsDb in rmsRange.first..rmsRange.second
                    val peakOk = ui.peakDb in peakRange.first..peakRange.second
                    val label = when {
                        rmsOk && peakOk -> "OK"
                        ui.peakDb > peakRange.second -> "Trop fort"
                        ui.rmsDb < rmsRange.first -> "Trop faible"
                        else -> "À ajuster"
                    }
                    val color = when (label) {
                        "OK" -> Color(0xFF2E7D32)
                        "Trop fort" -> Color(0xFFD32F2F)
                        else -> Color(0xFFFF9800)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x14FFFFFF))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(name, fontWeight = FontWeight.SemiBold)
                        Text("Cible RMS: ${rmsRange.first.toInt()} à ${rmsRange.second.toInt()} dBFS", style = MaterialTheme.typography.bodySmall)
                        Text("Cible Peak: ${peakRange.first.toInt()} à ${peakRange.second.toInt()} dBFS", style = MaterialTheme.typography.bodySmall)
                        Text("État actuel: $label", color = color, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MicSettingsScreen(
    ui: RecorderUiState,
    onBack: () -> Unit,
    onRefreshMics: () -> Unit,
    onSelectMic: (Int?) -> Unit,
    onSetTestDuration: (Int) -> Unit,
    onToggleStereoRequested: (Boolean) -> Unit,
    onToggleStereoSwap: (Boolean) -> Unit,
    onRequestTestMic: () -> Unit,
    onRequestProbeStereo: () -> Unit,
    onOpenGuidedStereoTest: () -> Unit,
    onRequestAutoMicSetup: () -> Unit
) {
    var advancedExpanded by remember { mutableStateOf(false) }
    var autoSetupTriggered by remember { mutableStateOf(false) }
    val verificationRunning = ui.isAutoMicScanRunning || ui.isAutoStereoProbeRunning
    val busyAudioAction = ui.isRecording || ui.isCalibrating || ui.isTestingMic || ui.isRunningABTest || ui.isRunningStereoGuidedTest

    LaunchedEffect(Unit) {
        if (!autoSetupTriggered) {
            autoSetupTriggered = true
            onRequestAutoMicSetup()
        }
    }

    val pageGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0x338BCF2F),
            Color(0x33FFF44F),
            Color(0x33FF8C42)
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .background(pageGradient)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Réglages micro", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onBack) { Text("Retour") }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x22FFFFFF))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val compatibilityText = when {
                verificationRunning -> "Vérification automatique en cours..."
                ui.stereoSupported -> "Capture stéréo compatible sur cet appareil."
                else -> "Capture stéréo non confirmée."
            }
            Text(compatibilityText, fontWeight = FontWeight.Bold)
            Text("Pour valider totalement la stéréo, lance le test stéréo guidé en utilisant ta voix.")

            if (ui.guidedStereoPassed) {
                Text("Téléphone totalement compatible stéréo.", fontWeight = FontWeight.SemiBold, color = Color(0xFF1B5E20))
                Text(
                    "Paramètres optimaux enregistrés automatiquement: stéréo ${if (ui.stereoModeRequested) "ON" else "OFF"}, " +
                        "swap ${if (ui.stereoChannelsSwapped) "ON" else "OFF"}, micro ${ui.effectiveMicLabel ?: "Auto"}."
                )
            }

            if (!ui.autoMicSetupMessage.isNullOrBlank()) {
                Text(ui.autoMicSetupMessage, color = Color(0xFFB00020))
                OutlinedButton(onClick = onRequestAutoMicSetup, enabled = !verificationRunning && !busyAudioAction) {
                    Text("Réessayer")
                }
            }

            Button(
                onClick = onOpenGuidedStereoTest,
                enabled = !busyAudioAction,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Lancer test stéréo guidé")
            }

            OutlinedButton(
                onClick = { advancedExpanded = !advancedExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (advancedExpanded) "Masquer Avancé" else "Afficher Avancé")
            }

            if (advancedExpanded) {
                Text("Avancé", fontWeight = FontWeight.Bold)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.stereoModeRequested) {
                        Button(onClick = { onToggleStereoRequested(true) }) { Text("Stéréo ON") }
                        OutlinedButton(onClick = { onToggleStereoRequested(false) }) { Text("Stéréo OFF") }
                    } else {
                        OutlinedButton(onClick = { onToggleStereoRequested(true) }) { Text("Stéréo ON") }
                        Button(onClick = { onToggleStereoRequested(false) }) { Text("Stéréo OFF") }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.stereoChannelsSwapped) {
                        Button(onClick = { onToggleStereoSwap(true) }) { Text("Swap L/R ON") }
                        OutlinedButton(onClick = { onToggleStereoSwap(false) }) { Text("Swap L/R OFF") }
                    } else {
                        OutlinedButton(onClick = { onToggleStereoSwap(true) }) { Text("Swap L/R ON") }
                        Button(onClick = { onToggleStereoSwap(false) }) { Text("Swap L/R OFF") }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefreshMics, enabled = !busyAudioAction) { Text("Scan") }
                    Button(onClick = onRequestTestMic, enabled = !busyAudioAction) {
                        Text(if (ui.isTestingMic) "Test..." else "Test mic")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.availableTestDurationsSec.forEach { sec ->
                        if (sec == ui.selectedTestDurationSec) {
                            Button(onClick = { onSetTestDuration(sec) }) { Text("${sec}s") }
                        } else {
                            OutlinedButton(onClick = { onSetTestDuration(sec) }) { Text("${sec}s") }
                        }
                    }
                }

                OutlinedButton(
                    onClick = onRequestProbeStereo,
                    enabled = !busyAudioAction,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Probe stéréo")
                }

                Text("Résultat probe: ${ui.stereoProbeMessage}", style = MaterialTheme.typography.bodySmall)
                Text("Source input: ${ui.inputSourceLabel}", style = MaterialTheme.typography.bodySmall)
                Text("Route active: ${ui.inputRoutedDevice}", style = MaterialTheme.typography.bodySmall)
                Text("Traitements: ${ui.inputProcessingSummary}", style = MaterialTheme.typography.bodySmall)

                OutlinedButton(onClick = { onSelectMic(null) }) {
                    val autoLabel = ui.effectiveMicLabel ?: "none"
                    Text(if (ui.selectedMicId == null) "Auto (actif -> $autoLabel)" else "Auto")
                }

                if (ui.microphones.isEmpty()) {
                    Text("Aucun microphone détecté")
                } else {
                    ui.microphones.forEach { mic ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val selected = mic.id == ui.selectedMicId
                                if (selected) {
                                    Button(onClick = { onSelectMic(mic.id) }) { Text("Sélectionné") }
                                } else {
                                    OutlinedButton(onClick = { onSelectMic(mic.id) }) { Text("Choisir") }
                                }
                                Text(mic.displayName, fontWeight = FontWeight.SemiBold)
                            }
                            Text("Type: ${mic.typeLabel} (${mic.typeCode})")
                            Text("ID: ${mic.id}")
                            Text("Description: ${mic.description ?: "N/A"}")
                            Text("Location: ${mic.locationLabel ?: "N/A"}")
                            Text("Directionality: ${mic.directionalityLabel ?: "N/A"}")
                            Text("Raw channels: ${mic.rawChannelCounts ?: "N/A"}")
                            Text("Raw sample rates: ${mic.rawSampleRates ?: "N/A"}")
                            if (!mic.warning.isNullOrBlank()) {
                                Text("Warning: ${mic.warning}", color = Color(0xFFD32F2F), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                ui.micTestResult?.let {
                    Text("Résultat test: $it", style = MaterialTheme.typography.bodySmall)
                }
                ui.stereoGuidedTestResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun GuidedStereoTestScreen(
    ui: RecorderUiState,
    onBack: () -> Unit,
    onRequestStart: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = previous
        }
    }

    LaunchedEffect(Unit) {
        if (!ui.isRunningStereoGuidedTest) {
            onRequestStart()
        }
    }

    ScreenScaffold(title = "Test stéréo guidé", onBack = onBack) {
        Text("Suivez l'indicateur actif et faites du bruit du côté demandé.", fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GuidedStepCard(
                label = "GAUCHE",
                modifier = Modifier.weight(1f),
                isActive = ui.guidedStereoStep == GuidedStereoStep.PREP_LEFT || ui.guidedStereoStep == GuidedStereoStep.CAPTURE_LEFT
            )
            GuidedStepCard(
                label = "CENTRE",
                modifier = Modifier.weight(1f),
                isActive = ui.guidedStereoStep == GuidedStereoStep.PREP_CENTER || ui.guidedStereoStep == GuidedStereoStep.CAPTURE_CENTER
            )
            GuidedStepCard(
                label = "DROITE",
                modifier = Modifier.weight(1f),
                isActive = ui.guidedStereoStep == GuidedStereoStep.PREP_RIGHT || ui.guidedStereoStep == GuidedStereoStep.CAPTURE_RIGHT
            )
        }
        Text("Étape: ${ui.guidedStereoStep.name}", style = MaterialTheme.typography.bodyLarge)
        Text("Status: ${ui.status}", style = MaterialTheme.typography.bodyLarge)
        Button(
            onClick = onRequestStart,
            enabled = !ui.isRunningStereoGuidedTest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (ui.isRunningStereoGuidedTest) "Test en cours..." else "Relancer le test")
        }
        ui.stereoGuidedTestResult?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Text("Source input: ${ui.inputSourceLabel}", style = MaterialTheme.typography.bodySmall)
        Text("Route active: ${ui.inputRoutedDevice}", style = MaterialTheme.typography.bodySmall)
        Text("Traitements: ${ui.inputProcessingSummary}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GuidedStepCard(
    label: String,
    modifier: Modifier = Modifier,
    isActive: Boolean
) {
    Card(
        modifier = modifier
            .height(120.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFF00C853) else Color(0x33FFFFFF)
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isActive) "$label\nFAIS DU BRUIT" else label,
                textAlign = TextAlign.Center,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PlayerScreen(
    ui: RecorderUiState,
    onBack: () -> Unit,
    onSetPreset: (PlayerFxPreset) -> Unit,
    onSetEqEnabled: (Boolean) -> Unit,
    onSetCompressionEnabled: (Boolean) -> Unit,
    onSetDeEsserEnabled: (Boolean) -> Unit,
    onSetEqIntensity: (Float) -> Unit,
    onSetCompressionIntensity: (Float) -> Unit,
    onSetDeEsserIntensity: (Float) -> Unit,
    onSetStatus: (String) -> Unit
) {
    val playback = remember { PlaybackController() }

    DisposableEffect(Unit) {
        onDispose { playback.release() }
    }

    ScreenScaffold(title = "Lecteur", onBack = onBack) {
        Text(
            "Ces traitements n'altèrent pas la prise brute tant que vous n'exportez pas.",
            style = MaterialTheme.typography.bodySmall
        )

        val cfg = ui.playerFxConfig
        Text("Preset", fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presetButton("Flat", cfg.preset == PlayerFxPreset.FLAT, Modifier.weight(1f)) { onSetPreset(PlayerFxPreset.FLAT) }
            presetButton("Rock", cfg.preset == PlayerFxPreset.ROCK, Modifier.weight(1f)) { onSetPreset(PlayerFxPreset.ROCK) }
            presetButton("Pop", cfg.preset == PlayerFxPreset.POP, Modifier.weight(1f)) { onSetPreset(PlayerFxPreset.POP) }
        }

        ToggleRow("EQ tonal", cfg.eqEnabled, onSetEqEnabled)
        AdvancedConfigSlider("EQ intensité", cfg.eqIntensity, 0f..1f, onSetEqIntensity)

        ToggleRow("Compression légère", cfg.compressionEnabled, onSetCompressionEnabled)
        AdvancedConfigSlider("Compression intensité", cfg.compressionIntensity, 0f..1f, onSetCompressionIntensity)

        ToggleRow("De-esser cymbales", cfg.deEsserEnabled, onSetDeEsserEnabled)
        AdvancedConfigSlider("De-esser intensité", cfg.deEsserIntensity, 0f..1f, onSetDeEsserIntensity)

        val sourcePath = resolvePlaybackPath(ui.lastOutputPath)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    val path = sourcePath
                    if (path == null) {
                        onSetStatus("Aucun enregistrement à lire")
                        return@Button
                    }
                    val ok = playback.togglePlay(path, cfg, onError = onSetStatus)
                    onSetStatus(if (ok) "Lecture avec traitements active" else "Lecture arrêtée")
                }
            ) {
                Text(if (playback.isPlaying()) "Stop" else "Lire")
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    playback.refreshFx(cfg)
                    onSetStatus("Paramètres appliqués")
                }
            ) {
                Text("Appliquer FX")
            }
        }

        Text("Source: ${ui.lastOutputPath ?: "Aucun fichier"}", style = MaterialTheme.typography.bodySmall)
        Text("Statut lecteur: ${ui.playerStatusMessage}", style = MaterialTheme.typography.bodySmall)
        Text("Export traité: roadmap", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun presetButton(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

private fun resolvePlaybackPath(lastOutputPath: String?): String? {
    if (lastOutputPath.isNullOrBlank()) return null
    return if (lastOutputPath.startsWith("Downloads/")) {
        val relative = lastOutputPath.removePrefix("Downloads/").replace("/", File.separator)
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), relative).absolutePath
    } else {
        lastOutputPath
    }
}

private class PlaybackController {
    private var mediaPlayer: MediaPlayer? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    fun togglePlay(path: String, cfg: PlayerFxConfig, onError: (String) -> Unit): Boolean {
        if (mediaPlayer?.isPlaying == true) {
            release()
            return false
        }
        return runCatching {
            release()
            val player = MediaPlayer()
            player.setDataSource(path)
            player.prepare()
            player.start()
            player.setOnCompletionListener { release() }
            mediaPlayer = player
            attachFx(cfg)
            true
        }.getOrElse {
            release()
            onError("Lecture impossible: ${it.message ?: "erreur inconnue"}")
            false
        }
    }

    fun refreshFx(cfg: PlayerFxConfig) {
        if (mediaPlayer == null) return
        attachFx(cfg)
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { loudness?.release() }
        equalizer = null
        bassBoost = null
        loudness = null

        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    private fun attachFx(cfg: PlayerFxConfig) {
        val player = mediaPlayer ?: return
        val session = player.audioSessionId
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { loudness?.release() }

        equalizer = Equalizer(0, session).apply {
            enabled = cfg.eqEnabled || cfg.deEsserEnabled
            applyPreset(cfg)
        }
        bassBoost = BassBoost(0, session).apply {
            enabled = cfg.eqEnabled
            setStrength((cfg.eqIntensity * 1000f).toInt().coerceIn(0, 1000).toShort())
        }
        loudness = LoudnessEnhancer(session).apply {
            enabled = cfg.compressionEnabled
            val gainMb = (cfg.compressionIntensity * 300f).toInt()
            setTargetGain(gainMb)
        }
    }

    private fun Equalizer.applyPreset(cfg: PlayerFxConfig) {
        val bands = numberOfBands.toInt()
        if (bands <= 0) return
        for (band in 0 until bands) {
            setBandLevel(band.toShort(), 0)
        }
        val intensity = cfg.eqIntensity
        when (cfg.preset) {
            PlayerFxPreset.FLAT -> Unit
            PlayerFxPreset.ROCK -> {
                adjustBand(0, +300, intensity)
                adjustBand(1, +180, intensity)
                adjustBand(2, +80, intensity)
                adjustBand(bands - 1, -120, intensity)
            }
            PlayerFxPreset.POP -> {
                adjustBand(0, +220, intensity)
                adjustBand(1, +120, intensity)
                adjustBand(2, +180, intensity)
                adjustBand(bands - 1, -80, intensity)
            }
        }
        if (cfg.deEsserEnabled) {
            adjustBand(bands - 1, -320, cfg.deEsserIntensity)
            adjustBand((bands - 2).coerceAtLeast(0), -180, cfg.deEsserIntensity)
        }
    }

    private fun Equalizer.adjustBand(index: Int, levelMb: Int, intensity: Float) {
        val idx = index.coerceAtLeast(0).coerceAtMost(numberOfBands.toInt() - 1)
        val scaled = (levelMb * intensity).toInt().toShort()
        runCatching { setBandLevel(idx.toShort(), scaled) }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (enabled) {
                Button(
                    onClick = { onToggle(true) },
                    modifier = Modifier.weight(1f)
                ) { Text("ON") }
                OutlinedButton(
                    onClick = { onToggle(false) },
                    modifier = Modifier.weight(1f)
                ) { Text("OFF") }
            } else {
                OutlinedButton(
                    onClick = { onToggle(true) },
                    modifier = Modifier.weight(1f)
                ) { Text("ON") }
                Button(
                    onClick = { onToggle(false) },
                    modifier = Modifier.weight(1f)
                ) { Text("OFF") }
            }
        }
    }
}

@Composable
private fun AdvancedConfigSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    val display = if (range.endInclusive <= 1.2f) {
        "%.2f".format(value)
    } else {
        "%.1f".format(value)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("$label: $display", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range
        )
    }
}

@Composable
private fun SettingsScreen(
    ui: RecorderUiState,
    onBack: () -> Unit,
    onStorageChange: (StorageLocation) -> Unit,
    onToggleDiagnostic: (Boolean) -> Unit,
    onToggleAdvanced: (Boolean) -> Unit,
    onRequestRunABTest: () -> Unit
) {
    ScreenScaffold(title = "Paramètres", onBack = onBack) {
        Text("Emplacement d'enregistrement", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.storageLocation == StorageLocation.DOWNLOADS) {
                Button(onClick = { onStorageChange(StorageLocation.DOWNLOADS) }) { Text("Downloads") }
                OutlinedButton(onClick = { onStorageChange(StorageLocation.APP_PRIVATE) }) { Text("App private") }
            } else {
                OutlinedButton(onClick = { onStorageChange(StorageLocation.DOWNLOADS) }) { Text("Downloads") }
                Button(onClick = { onStorageChange(StorageLocation.APP_PRIVATE) }) { Text("App private") }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("Diagnostic", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.diagnosticModeEnabled) {
                Button(onClick = { onToggleDiagnostic(true) }) { Text("Diagnostic ON") }
                OutlinedButton(onClick = { onToggleDiagnostic(false) }) { Text("Diagnostic OFF") }
            } else {
                OutlinedButton(onClick = { onToggleDiagnostic(true) }) { Text("Diagnostic ON") }
                Button(onClick = { onToggleDiagnostic(false) }) { Text("Diagnostic OFF") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.showAdvancedInternals) {
                Button(onClick = { onToggleAdvanced(true) }) { Text("Advanced ON") }
                OutlinedButton(onClick = { onToggleAdvanced(false) }) { Text("Advanced OFF") }
            } else {
                OutlinedButton(onClick = { onToggleAdvanced(true) }) { Text("Advanced ON") }
                Button(onClick = { onToggleAdvanced(false) }) { Text("Advanced OFF") }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text("External A/B built-in test", fontWeight = FontWeight.Bold)
        Button(
            onClick = onRequestRunABTest,
            enabled = !ui.isRecording && !ui.isCalibrating && !ui.isTestingMic && !ui.isRunningABTest && !ui.isRunningStereoGuidedTest
        ) {
            Text(if (ui.isRunningABTest) "Running A/B..." else "Run external A/B")
        }
        ui.abTestResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Spacer(modifier = Modifier.height(6.dp))
        Text("Historique des tests", fontWeight = FontWeight.Bold)
        if (ui.testHistory.isEmpty()) {
            Text("Aucun test pour le moment")
        } else {
            ui.testHistory.forEach { entry ->
                Text(
                    "${entry.timestampIso} | ${entry.micLabel} | ${entry.durationSec}s | RMS ${"%.1f".format(entry.rmsDb)} | Peak ${"%.1f".format(entry.peakDb)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        ui.lastDiagnosticReportPath?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text("Dernier rapport: $it", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PlaceholderScreen(
    title: String,
    subtitle: String,
    navController: NavHostController
) {
    ScreenScaffold(title = title, onBack = { navController.popBackStack() }) {
        Text(subtitle, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onBack) {
                Text("Retour")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x10FFFFFF))
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

private fun formatTimer(ms: Long): String {
    val totalSec = (ms / 1000.0).roundToInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}
