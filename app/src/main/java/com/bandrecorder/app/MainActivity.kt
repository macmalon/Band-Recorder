package com.bandrecorder.app

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private enum class PendingAction {
    CALIBRATE,
    START_RECORDING,
    TEST_MIC,
    PROBE_STEREO,
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
        if (!granted) return@rememberLauncherForActivityResult
        when (pendingAction) {
            PendingAction.CALIBRATE -> vm.runCalibration()
            PendingAction.START_RECORDING -> vm.startRecording()
            PendingAction.TEST_MIC -> vm.testSelectedMicrophone()
            PendingAction.PROBE_STEREO -> vm.probeStereoCapability()
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
                onRequestCalibrate = { requestAudioPermission(PendingAction.CALIBRATE) }
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
                onRequestTestMic = { requestAudioPermission(PendingAction.TEST_MIC) },
                onRequestProbeStereo = { requestAudioPermission(PendingAction.PROBE_STEREO) },
                onOpenGuidedStereoTest = { navController.navigate(AppRoute.GuidedStereoTest.route) }
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
    val pagerState = rememberPagerState(pageCount = { 2 })
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
            Spacer(modifier = Modifier.height(30.dp))

            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 48.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
            ) { page ->
                val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
                val targetScale = if (pageOffset < 0.5f) 1f else 0.82f
                val targetAlpha = if (pageOffset < 0.5f) 1f else 0.68f
                val scale by animateFloatAsState(targetValue = targetScale, label = "cardScale")
                val alpha by animateFloatAsState(targetValue = targetAlpha, label = "cardAlpha")

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (page == 0) {
                        MainCarouselCard(
                            title = if (ui.isRecording) "STOP" else "REC",
                            subtitle = if (ui.isRecording) "Arrêter l'enregistrement" else "Démarrer l'enregistrement",
                            enabled = !isBusy || ui.isRecording,
                            onClick = onRecordToggle
                        )
                    } else {
                        MainCarouselCard(
                            title = "BALANCE",
                            subtitle = "Mesure et calibrage",
                            enabled = true,
                            onClick = onOpenBalance
                        )
                    }
                }
            }
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
private fun MainCarouselCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.size(210.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE6006F)),
        shape = CircleShape
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                contentColor = Color.White,
                disabledContentColor = Color(0xCCFFFFFF)
            )
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = subtitle,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun BalanceScreen(
    ui: RecorderUiState,
    onBack: () -> Unit,
    onRequestCalibrate: () -> Unit
) {
    val vuProgress = ((ui.peakDb + 60f) / 60f).coerceIn(0f, 1f)
    val vuColor = when {
        ui.peakDb > -3f -> Color(0xFFD32F2F)
        ui.peakDb > -9f -> Color(0xFFFF9800)
        else -> Color(0xFF2E7D32)
    }
    val saturationAlert = ui.peakDb > -3f
    val lowLevelAlert = ui.rmsDb < -38f && ui.isRecording

    ScreenScaffold(title = "Balance", onBack = onBack) {
        Text("Status: ${ui.status}")
        Spacer(modifier = Modifier.height(8.dp))

        Text("RMS: ${"%.1f".format(ui.rmsDb)} dBFS")
        Text("Peak: ${"%.1f".format(ui.peakDb)} dBFS")
        Text("Headroom: ${"%.1f".format(ui.headroomDb)} dB")
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
}

@Composable
private fun MicSettingsScreen(
    ui: RecorderUiState,
    onBack: () -> Unit,
    onRefreshMics: () -> Unit,
    onSelectMic: (Int?) -> Unit,
    onSetTestDuration: (Int) -> Unit,
    onToggleStereoRequested: (Boolean) -> Unit,
    onRequestTestMic: () -> Unit,
    onRequestProbeStereo: () -> Unit,
    onOpenGuidedStereoTest: () -> Unit
) {
    ScreenScaffold(title = "Réglages micro", onBack = onBack) {
        Text("Microphones", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefreshMics) { Text("Scan") }
            Button(onClick = onRequestTestMic, enabled = !ui.isRecording && !ui.isCalibrating && !ui.isTestingMic && !ui.isRunningABTest && !ui.isRunningStereoGuidedTest) {
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

        Spacer(modifier = Modifier.height(4.dp))
        Text("Capture stéréo", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.stereoModeRequested) {
                Button(onClick = { onToggleStereoRequested(true) }) { Text("Stéréo ON") }
                OutlinedButton(onClick = { onToggleStereoRequested(false) }) { Text("Stéréo OFF") }
            } else {
                OutlinedButton(onClick = { onToggleStereoRequested(true) }) { Text("Stéréo ON") }
                Button(onClick = { onToggleStereoRequested(false) }) { Text("Stéréo OFF") }
            }
        }
        OutlinedButton(
            onClick = onRequestProbeStereo,
            enabled = !ui.isRecording && !ui.isCalibrating && !ui.isTestingMic && !ui.isRunningABTest && !ui.isRunningStereoGuidedTest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Probe stéréo")
        }
        Button(
            onClick = onOpenGuidedStereoTest,
            enabled = !ui.isRecording && !ui.isCalibrating && !ui.isTestingMic && !ui.isRunningABTest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Ouvrir test stéréo guidé")
        }
        Text("Résultat probe: ${ui.stereoProbeMessage}")
        Text("Source input: ${ui.inputSourceLabel}", style = MaterialTheme.typography.bodySmall)
        Text("Route active: ${ui.inputRoutedDevice}", style = MaterialTheme.typography.bodySmall)
        Text("Traitements: ${ui.inputProcessingSummary}", style = MaterialTheme.typography.bodySmall)
        ui.stereoGuidedTestResult?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(4.dp))
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
                    if (!mic.warning.isNullOrBlank()) {
                        Text("Warning: ${mic.warning}", color = Color(0xFFD32F2F), style = MaterialTheme.typography.bodySmall)
                    }
                    if (ui.showAdvancedInternals) {
                        Text("ID: ${mic.id}")
                        Text("Description: ${mic.description ?: "N/A"}")
                        Text("Location: ${mic.locationLabel ?: "N/A"}")
                        Text("Directionality: ${mic.directionalityLabel ?: "N/A"}")
                        Text("Raw sample rates: ${mic.rawSampleRates ?: "N/A"}")
                    }
                }
            }
        }

        ui.micTestResult?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text("Résultat test: $it")
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
        if (!ui.isRunningStereoGuidedTest && ui.guidedStereoStep == GuidedStereoStep.IDLE) {
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
                label = "DROITE",
                modifier = Modifier.weight(1f),
                isActive = ui.guidedStereoStep == GuidedStereoStep.PREP_RIGHT || ui.guidedStereoStep == GuidedStereoStep.CAPTURE_RIGHT
            )
            GuidedStepCard(
                label = "CENTRE",
                modifier = Modifier.weight(1f),
                isActive = ui.guidedStereoStep == GuidedStereoStep.PREP_CENTER || ui.guidedStereoStep == GuidedStereoStep.CAPTURE_CENTER
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
