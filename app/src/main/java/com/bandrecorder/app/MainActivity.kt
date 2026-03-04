package com.bandrecorder.app

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
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
import com.bandrecorder.core.audio.DspOutputMode
import com.bandrecorder.core.audio.GlobalBalanceConfig
import com.bandrecorder.core.audio.MixProfile
import kotlin.math.roundToInt

private enum class PendingAction {
    CALIBRATE,
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
            PendingAction.CALIBRATE -> vm.runCalibration()
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
                onRequestCalibrate = { requestAudioPermission(PendingAction.CALIBRATE) },
                onSetAutoBalance = vm::setAutoBalance,
                onSetCompression = vm::setCompression,
                onSetDeEsser = vm::setDeEsser,
                onSetDspOutputMode = vm::setDspOutputMode,
                onSetMixProfile = vm::setMixProfile,
                onUpdateAdvancedConfig = vm::updateAdvancedBalanceConfig,
                onResetProfile = vm::resetBalanceProfile
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
            PlaceholderScreen(
                title = "Lecteur",
                subtitle = "Le lecteur sera ajouté dans une prochaine étape de la roadmap.",
                navController = navController
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
    onRequestCalibrate: () -> Unit,
    onSetAutoBalance: (Boolean) -> Unit,
    onSetCompression: (Boolean) -> Unit,
    onSetDeEsser: (Boolean) -> Unit,
    onSetDspOutputMode: (DspOutputMode) -> Unit,
    onSetMixProfile: (MixProfile) -> Unit,
    onUpdateAdvancedConfig: (GlobalBalanceConfig) -> Unit,
    onResetProfile: () -> Unit
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
    var advancedOpen by remember { mutableStateOf(false) }
    val cfg = ui.globalBalanceConfig

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
                        Text("Traitement live", fontWeight = FontWeight.Bold)
                        Text(
                            "Auto Balance ajuste le mix en temps réel pour une écoute claire sans saturation.",
                            style = MaterialTheme.typography.bodySmall
                        )

                        ToggleRow(
                            label = "Auto Balance",
                            enabled = cfg.autoBalanceEnabled,
                            onToggle = onSetAutoBalance
                        )
                        ToggleRow(
                            label = "Compression légère",
                            enabled = cfg.compressionEnabled,
                            onToggle = onSetCompression
                        )
                        ToggleRow(
                            label = "De-esser cymbales",
                            enabled = cfg.deEsserEnabled,
                            onToggle = onSetDeEsser
                        )

                        Text("Sortie DSP", fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (cfg.dspOutputMode == DspOutputMode.MONITORING_ONLY) {
                                Button(
                                    onClick = { onSetDspOutputMode(DspOutputMode.MONITORING_ONLY) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Monitoring") }
                                OutlinedButton(
                                    onClick = { onSetDspOutputMode(DspOutputMode.MONITORING_AND_RECORDING) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Monitoring+Fichier") }
                            } else {
                                OutlinedButton(
                                    onClick = { onSetDspOutputMode(DspOutputMode.MONITORING_ONLY) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Monitoring") }
                                Button(
                                    onClick = { onSetDspOutputMode(DspOutputMode.MONITORING_AND_RECORDING) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Monitoring+Fichier") }
                            }
                        }

                        val dspBadgeText = when {
                            ui.isGlobalDspCpuGuardActive -> "CPU élevé"
                            ui.isNearClipping -> "Risque saturation"
                            ui.isGlobalDspRunning -> "Actif"
                            else -> "Bypass"
                        }
                        val dspBadgeColor = when (dspBadgeText) {
                            "Actif" -> Color(0xFF2E7D32)
                            "Bypass" -> Color(0xFF546E7A)
                            "CPU élevé" -> Color(0xFFFF9800)
                            else -> Color(0xFFD32F2F)
                        }
                        Text("État DSP: $dspBadgeText", color = dspBadgeColor, fontWeight = FontWeight.Bold)
                        ui.dspStatusMessage?.let { Text("Status moteur: $it", style = MaterialTheme.typography.bodySmall) }
                        if (cfg.dspOutputMode == DspOutputMode.MONITORING_AND_RECORDING) {
                            Text("Traitement imprimé dans le fichier.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB00020))
                        }

                        OutlinedButton(
                            onClick = { advancedOpen = !advancedOpen },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (advancedOpen) "Masquer Avancé" else "Afficher Avancé")
                        }

                        if (advancedOpen) {
                            Text("Profil cible", fontWeight = FontWeight.SemiBold)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onSetMixProfile(MixProfile.ROCK_POP_BALANCED) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Rock/Pop équilibré")
                                }
                                OutlinedButton(
                                    onClick = onResetProfile,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Reset profil")
                                }
                            }

                            AdvancedConfigSlider(
                                label = "EQ intensité",
                                value = cfg.eqIntensity,
                                range = 0f..1f,
                                onValueChange = { onUpdateAdvancedConfig(cfg.copy(eqIntensity = it)) }
                            )
                            AdvancedConfigSlider(
                                label = "Comp intensité",
                                value = cfg.compIntensity,
                                range = 0f..1f,
                                onValueChange = { onUpdateAdvancedConfig(cfg.copy(compIntensity = it)) }
                            )
                            AdvancedConfigSlider(
                                label = "De-esser intensité",
                                value = cfg.deEsserIntensity,
                                range = 0f..1f,
                                onValueChange = { onUpdateAdvancedConfig(cfg.copy(deEsserIntensity = it)) }
                            )
                            AdvancedConfigSlider(
                                label = "Comp seuil (dB)",
                                value = cfg.compressorThresholdDb,
                                range = -40f..-6f,
                                onValueChange = { onUpdateAdvancedConfig(cfg.copy(compressorThresholdDb = it)) }
                            )
                            AdvancedConfigSlider(
                                label = "Comp ratio",
                                value = cfg.compressorRatio,
                                range = 1.1f..6f,
                                onValueChange = { onUpdateAdvancedConfig(cfg.copy(compressorRatio = it)) }
                            )
                            AdvancedConfigSlider(
                                label = "De-esser freq (Hz)",
                                value = cfg.deEsserFrequencyHz,
                                range = 3500f..11000f,
                                onValueChange = { onUpdateAdvancedConfig(cfg.copy(deEsserFrequencyHz = it)) }
                            )
                            AdvancedConfigSlider(
                                label = "De-esser largeur (Hz)",
                                value = cfg.deEsserWidthHz,
                                range = 800f..6000f,
                                onValueChange = { onUpdateAdvancedConfig(cfg.copy(deEsserWidthHz = it)) }
                            )
                            AdvancedConfigSlider(
                                label = "Limiteur (dB)",
                                value = cfg.limiterCeilingDb,
                                range = -6f..-0.2f,
                                onValueChange = { onUpdateAdvancedConfig(cfg.copy(limiterCeilingDb = it)) }
                            )
                            AdvancedConfigSlider(
                                label = "Trim sortie (dB)",
                                value = cfg.outputTrimDb,
                                range = -12f..6f,
                                onValueChange = { onUpdateAdvancedConfig(cfg.copy(outputTrimDb = it)) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Status: ${ui.status}")
                Spacer(modifier = Modifier.height(8.dp))

                Text("RMS: ${"%.1f".format(ui.rmsDb)} dBFS")
                Text("Peak: ${"%.1f".format(ui.peakDb)} dBFS")
                Text("Headroom: ${"%.1f".format(ui.headroomDb)} dB")
                Text("Comp GR: ${"%.1f".format(ui.compGainReductionDb)} dB")
                Text("De-esser GR: ${"%.1f".format(ui.deEsserGainReductionDb)} dB")
                Text("Limiter hits: ${ui.globalDspLimiterHits}")
                Spacer(modifier = Modifier.height(6.dp))

                Text("VU meter")
                LinearProgressIndicator(
                    progress = { vuProgress },
                    color = vuColor,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (ui.isCalibrating) {
                    Text("Calibration en cours: ${ui.calibrationProgress}%")
                    LinearProgressIndicator(
                        progress = { ui.calibrationProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Button(
                    onClick = onRequestCalibrate,
                    enabled = !ui.isCalibrating && !ui.isTestingMic && !ui.isRecording && !ui.isRunningABTest && !ui.isRunningStereoGuidedTest
                ) {
                    Text("Calibrage 30s")
                }

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
