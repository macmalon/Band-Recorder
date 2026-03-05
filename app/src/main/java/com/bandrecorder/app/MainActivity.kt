package com.bandrecorder.app

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
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
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.res.painterResource
import com.bandrecorder.app.ui.vintage.VintageDimensions
import com.bandrecorder.app.ui.vintage.VintageColors
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.absoluteValue
import kotlin.math.min
import kotlin.math.sin
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

private val AmpBgDark = Color(0xFF202226)
private val AmpBgMid = Color(0xFF2B2E33)
private val AmpPanelDark = Color(0xFF17191C)
private val AmpPanelBorder = Color(0xFF43474E)
private val AmpAccentAmber = Color(0xFFF2B63D)
private val AmpAccentAmberSoft = Color(0x66F2B63D)
private val AmpMetalLight = Color(0xFFC9CDD2)
private val AmpMetalDark = Color(0xFF878D95)
private val AmpText = Color(0xFFF4F6F8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = AmpAccentAmber,
                    secondary = AmpAccentAmber,
                    tertiary = AmpAccentAmber,
                    primaryContainer = AmpAccentAmberSoft,
                    secondaryContainer = AmpAccentAmberSoft
                )
            ) {
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
            HomeRoute(
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
                onRequestAutoMicSetup = { requestAudioPermission(PendingAction.AUTO_MIC_SETUP) },
                onRequestRunABTest = { requestAudioPermission(PendingAction.RUN_AB_TEST) }
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
                onToggleIgnoreSilence = vm::setIgnoreSilenceEnabled,
                onToggleSplitOnSilence = vm::setSplitOnSilenceEnabled,
                onToggleDiagnostic = vm::setDiagnosticMode,
                onToggleAdvanced = vm::setShowAdvancedInternals,
                onToggleVintageV2 = vm::setUiVintageV2Enabled
            )
        }
        composable(AppRoute.Player.route) {
            PlayerRoute(
                ui = ui,
                onBack = { navController.popBackStack() },
                onRefreshRecordings = vm::refreshPlayerRecordings,
                onToggleFavorite = vm::toggleRecordingFavorite,
                onSetPreset = vm::setPlayerPreset,
                onSetEqEnabled = vm::setPlayerEqEnabled,
                onSetCompressionEnabled = vm::setPlayerCompressionEnabled,
                onSetDeEsserEnabled = vm::setPlayerDeEsserEnabled,
                onSetEqIntensity = vm::setPlayerEqIntensity,
                onSetCompressionIntensity = vm::setPlayerCompressionIntensity,
                onSetDeEsserIntensity = vm::setPlayerDeEsserIntensity,
                onSetBoostIntensity = vm::setPlayerBoostIntensity,
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
private fun HomeRoute(
    ui: RecorderUiState,
    onRecordToggle: () -> Unit,
    onOpenBalance: () -> Unit,
    onOpenMicSettings: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLink: () -> Unit
) {
    if (ui.uiVintageV2Enabled) {
        RecorderScreenV2(
            ui = ui,
            onRecordToggle = onRecordToggle,
            onOpenBalance = onOpenBalance,
            onOpenMicSettings = onOpenMicSettings,
            onOpenPlayer = onOpenPlayer,
            onOpenSettings = onOpenSettings,
            onOpenLink = onOpenLink
        )
    } else {
        HomeScreen(
            ui = ui,
            onRecordToggle = onRecordToggle,
            onOpenBalance = onOpenBalance,
            onOpenMicSettings = onOpenMicSettings,
            onOpenPlayer = onOpenPlayer,
            onOpenSettings = onOpenSettings,
            onOpenLink = onOpenLink
        )
    }
}

@Composable
private fun PlayerRoute(
    ui: RecorderUiState,
    onBack: () -> Unit,
    onRefreshRecordings: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSetPreset: (PlayerFxPreset) -> Unit,
    onSetEqEnabled: (Boolean) -> Unit,
    onSetCompressionEnabled: (Boolean) -> Unit,
    onSetDeEsserEnabled: (Boolean) -> Unit,
    onSetEqIntensity: (Float) -> Unit,
    onSetCompressionIntensity: (Float) -> Unit,
    onSetDeEsserIntensity: (Float) -> Unit,
    onSetBoostIntensity: (Float) -> Unit,
    onSetStatus: (String) -> Unit
) {
    if (ui.uiVintageV2Enabled) {
        PlayerScreenV2(
            ui = ui,
            onBack = onBack,
            onRefreshRecordings = onRefreshRecordings,
            onToggleFavorite = onToggleFavorite,
            onSetPreset = onSetPreset,
            onSetEqEnabled = onSetEqEnabled,
            onSetCompressionEnabled = onSetCompressionEnabled,
            onSetDeEsserEnabled = onSetDeEsserEnabled,
            onSetEqIntensity = onSetEqIntensity,
            onSetCompressionIntensity = onSetCompressionIntensity,
            onSetDeEsserIntensity = onSetDeEsserIntensity,
            onSetBoostIntensity = onSetBoostIntensity,
            onSetStatus = onSetStatus
        )
    } else {
        PlayerScreen(
            ui = ui,
            onBack = onBack,
            onRefreshRecordings = onRefreshRecordings,
            onToggleFavorite = onToggleFavorite,
            onSetPreset = onSetPreset,
            onSetEqEnabled = onSetEqEnabled,
            onSetCompressionEnabled = onSetCompressionEnabled,
            onSetDeEsserEnabled = onSetDeEsserEnabled,
            onSetEqIntensity = onSetEqIntensity,
            onSetCompressionIntensity = onSetCompressionIntensity,
            onSetDeEsserIntensity = onSetDeEsserIntensity,
            onSetBoostIntensity = onSetBoostIntensity,
            onSetStatus = onSetStatus
        )
    }
}

@Composable
private fun RecorderScreenV2(
    ui: RecorderUiState,
    onRecordToggle: () -> Unit,
    onOpenBalance: () -> Unit,
    onOpenMicSettings: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLink: () -> Unit
) {
    val isBusy = ui.isCalibrating || ui.isTestingMic || ui.isRunningABTest || ui.isRunningStereoGuidedTest
    var recPressedFeedback by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(VintageDimensions.screenPadding),
        contentAlignment = Alignment.TopCenter
    ) {
        TolexBackgroundV2()

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .widthIn(max = VintageDimensions.facadeMaxWidth),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TopMetalPanelV2(
                modifier = Modifier.fillMaxWidth(),
                isRecording = ui.isRecording,
                isPausedVisual = recPressedFeedback && !ui.isRecording,
                timerText = formatTimer(ui.elapsedMs)
            )

            Spacer(modifier = Modifier.height(VintageDimensions.topToVuSpacing))

            VuMetersPanelV2(
                leftVu = ui.leftVu,
                rightVu = ui.rightVu,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(VintageDimensions.vuToRecordSpacing))

            RecordButtonV2(
                isRecording = ui.isRecording,
                isBusy = isBusy,
                onRecordToggle = {
                    recPressedFeedback = true
                    onRecordToggle()
                    scope.launch {
                        delay(420)
                        recPressedFeedback = false
                    }
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(VintageDimensions.recordToKnobSpacing))

            KnobPanelV2(
                onOpenBalance = onOpenBalance,
                onOpenMicSettings = onOpenMicSettings,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(VintageDimensions.knobToHardwareSpacing))

            HardwareBarV2(
                onOpenLink = onOpenLink,
                onOpenPlayer = onOpenPlayer,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PlayerScreenV2(
    ui: RecorderUiState,
    onBack: () -> Unit,
    onRefreshRecordings: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSetPreset: (PlayerFxPreset) -> Unit,
    onSetEqEnabled: (Boolean) -> Unit,
    onSetCompressionEnabled: (Boolean) -> Unit,
    onSetDeEsserEnabled: (Boolean) -> Unit,
    onSetEqIntensity: (Float) -> Unit,
    onSetCompressionIntensity: (Float) -> Unit,
    onSetDeEsserIntensity: (Float) -> Unit,
    onSetBoostIntensity: (Float) -> Unit,
    onSetStatus: (String) -> Unit
) {
    PlayerScreen(
        ui = ui,
        onBack = onBack,
        onRefreshRecordings = onRefreshRecordings,
        onToggleFavorite = onToggleFavorite,
        onSetPreset = onSetPreset,
        onSetEqEnabled = onSetEqEnabled,
        onSetCompressionEnabled = onSetCompressionEnabled,
        onSetDeEsserEnabled = onSetDeEsserEnabled,
        onSetEqIntensity = onSetEqIntensity,
        onSetCompressionIntensity = onSetCompressionIntensity,
        onSetDeEsserIntensity = onSetDeEsserIntensity,
        onSetBoostIntensity = onSetBoostIntensity,
        onSetStatus = onSetStatus
    )
}

@Composable
private fun TolexBackgroundV2() {
    val safeScale = VintageDimensions.tolexScale.coerceIn(
        VintageDimensions.tolexMinScale,
        VintageDimensions.tolexMaxScale
    )
    Image(
        painter = painterResource(id = R.drawable.tolex_bg),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = safeScale
                scaleY = safeScale
                translationX = VintageDimensions.tolexTranslationXPx
                translationY = VintageDimensions.tolexTranslationYPx
            }
    )
}

@Composable
private fun TopMetalPanelV2(
    modifier: Modifier = Modifier,
    isRecording: Boolean,
    isPausedVisual: Boolean,
    timerText: String
) {
    Box(
        modifier = modifier
            .height(VintageDimensions.topPanelHeight)
            .shadow(3.dp, RoundedCornerShape(10.dp))
    ) {
        Image(
            painter = painterResource(id = R.drawable.hardware_panel),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(10.dp))
        )
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RecIndicatorV2(isRecording = isRecording, isPausedVisual = isPausedVisual)
            Text(
                text = timerText,
                color = AmpMetalLight,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 30.sp
            )
        }
    }
}

@Composable
private fun RecIndicatorV2(isRecording: Boolean, isPausedVisual: Boolean) {
    val pulse = rememberInfiniteTransition(label = "led_pulse")
    val pulseProgress by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = VintageDimensions.ledPulseDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "led_progress"
    )

    val pulseAlpha = VintageDimensions.ledPulseMinAlpha +
        (VintageDimensions.ledPulseMaxAlpha - VintageDimensions.ledPulseMinAlpha) * pulseProgress
    val glowPulse = (1f - VintageDimensions.ledGlowVariation) +
        (2f * VintageDimensions.ledGlowVariation * pulseProgress)

    val ledColor = when {
        isRecording -> VintageColors.ledRec
        isPausedVisual -> VintageColors.ledRec
        else -> VintageColors.ledOff
    }
    val ledAlpha = when {
        isRecording -> 1f
        isPausedVisual -> pulseAlpha
        else -> 1f
    }
    val glowAlpha = when {
        isRecording -> 0.32f
        isPausedVisual -> 0.32f * glowPulse
        else -> 0.08f
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VintageDimensions.smallSpacing)) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(VintageDimensions.recLedSize * 1.9f)) {
                drawCircle(color = VintageColors.ledRec.copy(alpha = glowAlpha))
            }
            Canvas(modifier = Modifier.size(VintageDimensions.recLedSize)) {
                drawCircle(color = ledColor.copy(alpha = ledAlpha))
            }
        }
        Text(
            text = if (isRecording || isPausedVisual) "REC" else "STBY",
            color = if (isRecording || isPausedVisual) VintageColors.ledRec else AmpMetalDark,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun VuMetersPanelV2(leftVu: Float, rightVu: Float, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            VUMeterV2(
                label = "LEFT",
                value = leftVu,
                modifier = Modifier
                    .width(VintageDimensions.vuWidth)
                    .height(VintageDimensions.vuHeight)
            )
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            VUMeterV2(
                label = "RIGHT",
                value = rightVu,
                modifier = Modifier
                    .width(VintageDimensions.vuWidth)
                    .height(VintageDimensions.vuHeight)
            )
        }
    }
}

@Composable
private fun VUMeterV2(label: String, value: Float, modifier: Modifier = Modifier) {
    val safeValue = value.coerceIn(0f, 1f)
    val animatedValue by animateFloatAsState(
        targetValue = safeValue,
        animationSpec = tween(durationMillis = VintageDimensions.vuAnimationDurationMs, easing = FastOutSlowInEasing),
        label = "vu_meter_value"
    )
    val angle = VintageDimensions.vuMinAngle +
        animatedValue * (VintageDimensions.vuMaxAngle - VintageDimensions.vuMinAngle) +
        VintageDimensions.vuNeedleBaseAngleOffset

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(VintageDimensions.smallSpacing)) {
        Box(
            modifier = modifier
                .requiredWidth(VintageDimensions.vuWidth)
                .requiredHeight(VintageDimensions.vuHeight)
                .clip(RoundedCornerShape(VintageDimensions.vuCornerRadius))
        ) {
            Image(
                painter = painterResource(id = R.drawable.vu_meter_frame),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize()
            )
            Image(
                painter = painterResource(id = R.drawable.vu_needle),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        rotationZ = angle
                        transformOrigin = TransformOrigin(
                            pivotFractionX = VintageDimensions.vuNeedlePivotX,
                            pivotFractionY = VintageDimensions.vuNeedlePivotY
                        )
                    }
            )
        }
        Text(label, color = AmpMetalLight, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RecordButtonV2(
    isRecording: Boolean,
    isBusy: Boolean,
    onRecordToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = !isBusy || isRecording
    var isPressed by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val imageRes = if (isPressed) R.drawable.record_button_pressed else R.drawable.record_button
    Box(
        modifier = modifier
            .size(VintageDimensions.recordButtonSize)
            .clip(RoundedCornerShape(percent = 50))
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = {
                        if (!enabled) return@detectTapGestures
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRecordToggle()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = imageRes),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.matchParentSize()
        )
        Text(
            text = if (isRecording) "STOP" else "REC",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 26.sp,
            color = Color(0xFFF5F0E8),
            modifier = Modifier.shadow(1.dp)
        )
    }
}

@Composable
private fun KnobControlV2(label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(VintageDimensions.smallSpacing)) {
        Box(
            modifier = Modifier.size(VintageDimensions.knobSize),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.knob),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onClick)
            )
        }
        Text(label, fontWeight = FontWeight.Bold, color = AmpMetalLight)
    }
}

@Composable
private fun LinkSwitchV2(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(VintageDimensions.switchWidth)
            .height(VintageDimensions.switchHeight)
            .clickable(onClick = onClick)
    ) {
        Image(
            painter = painterResource(id = R.drawable.hardware_panel),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(10.dp))
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .size(width = 24.dp, height = 28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFADB3BA))
        )
        Text("LINK", color = AmpAccentAmber, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp))
    }
}

@Composable
private fun HardwareButtonV2(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(VintageDimensions.navButtonHeight)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.hardware_panel),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(VintageDimensions.hardwareButtonCorner))
        )
        Text(label, fontWeight = FontWeight.Bold, color = AmpAccentAmber, modifier = Modifier.padding(horizontal = 14.dp))
    }
}

@Composable
private fun KnobPanelV2(
    onOpenBalance: () -> Unit,
    onOpenMicSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        KnobControlV2(label = "GAIN", onClick = onOpenBalance)
        KnobControlV2(label = "MIC", onClick = onOpenMicSettings)
    }
}

@Composable
private fun HardwareBarV2(
    onOpenLink: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(74.dp)
            .shadow(2.dp, RoundedCornerShape(12.dp))
    ) {
        Image(
            painter = painterResource(id = R.drawable.hardware_panel),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(12.dp))
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LinkSwitchV2(onClick = onOpenLink)
            HardwareButtonV2(label = "LECTEUR", onClick = onOpenPlayer)
            HardwareButtonV2(label = "PARAMS", onClick = onOpenSettings)
        }

        Canvas(modifier = Modifier.matchParentSize()) {
            val screws = listOf(
                Offset(12f, 12f),
                Offset(size.width - 12f, 12f),
                Offset(12f, size.height - 12f),
                Offset(size.width - 12f, size.height - 12f)
            )
            screws.forEach { c ->
                drawCircle(color = VintageColors.screwDark, radius = 5.2f, center = c)
                drawLine(
                    color = Color(0x66222222),
                    start = Offset(c.x - 2.6f, c.y - 2.6f),
                    end = Offset(c.x + 2.6f, c.y + 2.6f),
                    strokeWidth = 1.3f
                )
            }
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
    val isBusy = ui.isCalibrating || ui.isTestingMic || ui.isRunningABTest || ui.isRunningStereoGuidedTest

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(AmpBgDark, AmpBgMid, AmpBgDark)))
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
                color = AmpText,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = ui.status,
                color = AmpAccentAmber,
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
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A4E55))
        ) {
            Text(
                text = if (ui.isRecording) "STOP" else "REC",
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color = AmpAccentAmber
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
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3C4046))
        ) {
            Text(
                text = "BALANCE",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = AmpAccentAmber
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
            contentColor = AmpAccentAmber,
            containerColor = Color(0xFF2A2D32)
        ),
        border = BorderStroke(1.dp, AmpPanelBorder)
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
    ScreenScaffold(title = "Balance", onBack = onBack) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ui.availableBalanceDurationsSec.forEach { sec ->
                if (sec == ui.balanceDurationSec) {
                    Button(onClick = { onSetBalanceDuration(sec) }, modifier = Modifier.weight(1f)) { Text("${sec}s") }
                } else {
                    OutlinedButton(onClick = { onSetBalanceDuration(sec) }, modifier = Modifier.weight(1f)) { Text("${sec}s") }
                }
            }
        }

        Button(
            onClick = onRunLevelBalance,
            enabled = !ui.isRecording && !ui.isCalibrating && !ui.isRunningLevelBalance && !ui.isRunningABTest && !ui.isRunningStereoGuidedTest,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D424A), contentColor = AmpAccentAmber)
        ) {
            Text(if (ui.isRunningLevelBalance) "Balance en cours..." else "Faire les balances", fontWeight = FontWeight.ExtraBold)
        }

        AnalogVuMeter(
            peakDb = ui.peakDb,
            isRunning = ui.isRunningLevelBalance,
            modifier = Modifier.fillMaxWidth().height(170.dp)
        )

        Text(
            "RMS ${"%.1f".format(ui.rmsDb)} dBFS   |   Peak ${"%.1f".format(ui.peakDb)} dBFS   |   Headroom ${"%.1f".format(ui.headroomDb)} dB",
            style = MaterialTheme.typography.bodySmall,
            color = AmpText
        )

        if (ui.isRunningLevelBalance) {
            Text("Mesure en cours... ${ui.balanceProgress}%", color = AmpAccentAmber, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = { ui.balanceProgress / 100f }, color = AmpAccentAmber, modifier = Modifier.fillMaxWidth())
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2D32))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Résultat balance", color = AmpMetalLight, fontWeight = FontWeight.Bold)
                if (ui.balanceDecisionLabel == null) {
                    Text("Aucun résultat pour le moment.", color = AmpText)
                } else {
                    Text(ui.balanceDecisionLabel, color = AmpAccentAmber, fontWeight = FontWeight.Bold)
                    Text(ui.balanceRecommendationText ?: "", color = AmpText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text(
            "Référence Peak max: > -3 dBFS trop fort | -6..-3 limite | -12..-6 idéal | -18..-12 un peu bas | < -18 trop faible",
            color = AmpMetalLight,
            style = MaterialTheme.typography.bodySmall
        )

        ui.lastOutputPath?.let {
            Text("Dernier fichier: $it", style = MaterialTheme.typography.bodySmall, color = AmpMetalDark)
        }
    }
}

@Composable
private fun AnalogVuMeter(
    peakDb: Float,
    isRunning: Boolean,
    modifier: Modifier = Modifier
) {
    val normalized = ((peakDb + 36f) / 36f).coerceIn(0f, 1f)
    // Classic VU sweep: left to right on the upper arc only.
    val targetAngle = 210f + (normalized * 120f)
    val animatedAngle by animateFloatAsState(
        targetValue = targetAngle,
        animationSpec = tween(durationMillis = 140),
        label = "vu_needle_angle"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1C1C1C))
            .border(BorderStroke(1.dp, AmpPanelBorder), RoundedCornerShape(18.dp))
            .padding(12.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val panelInset = 8f
            val panelTop = panelInset
            val panelLeft = panelInset
            val panelWidth = w - panelInset * 2f
            val panelHeight = h - panelInset * 2f

            // Cream meter face.
            drawRoundRect(
                color = Color(0xFFF2E9C9),
                topLeft = Offset(panelLeft, panelTop),
                size = Size(panelWidth, panelHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
            )

            // Soft amber backlight.
            drawRoundRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x66FFD56A), Color.Transparent),
                    center = Offset(panelLeft + panelWidth * 0.5f, panelTop + panelHeight * 0.45f),
                    radius = panelWidth * 0.6f
                ),
                topLeft = Offset(panelLeft, panelTop),
                size = Size(panelWidth, panelHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
            )

            val center = Offset(panelLeft + panelWidth * 0.5f, panelTop + panelHeight * 0.92f)
            val radius = min(panelWidth * 0.46f, panelHeight * 0.86f)

            // Main scale arc + red overload segment on the right.
            val arcTopLeft = Offset(center.x - radius, center.y - radius - 10f)
            drawArc(
                color = Color(0xFF2A2A2A),
                startAngle = 210f,
                sweepAngle = 92f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )
            drawArc(
                color = Color(0xFFB83A2B),
                startAngle = 302f,
                sweepAngle = 28f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )

            val peakOn = peakDb > -3f
            val peakCenter = Offset(panelLeft + panelWidth * 0.90f, panelTop + panelHeight * 0.32f)
            drawCircle(
                color = if (peakOn) Color(0xFFE23A2E) else Color(0xFF7A4B48),
                radius = 7f,
                center = peakCenter
            )
            drawCircle(
                color = Color(0xFF2A1A19),
                radius = 9f,
                center = peakCenter,
                style = Stroke(width = 1.2f)
            )

            for (i in 0..10) {
                val angleDeg = 210f + (i * 12f)
                val a = Math.toRadians(angleDeg.toDouble())
                val p1 = Offset(
                    x = center.x + (cos(a).toFloat() * (radius - 16f)),
                    y = center.y + (sin(a).toFloat() * (radius - 16f))
                )
                val p2 = Offset(
                    x = center.x + (cos(a).toFloat() * (radius - 2f)),
                    y = center.y + (sin(a).toFloat() * (radius - 2f))
                )
                drawLine(color = Color(0xFF2D2D2D), start = p1, end = p2, strokeWidth = if (i % 2 == 0) 2.2f else 1.5f)
            }

            // Needle constrained to the same arc.
            val needleRadians = Math.toRadians(animatedAngle.toDouble())
            val needleLength = radius - 40f
            val needleEnd = Offset(
                x = center.x + (cos(needleRadians).toFloat() * needleLength),
                y = center.y + (sin(needleRadians).toFloat() * needleLength)
            )
            drawLine(
                color = Color(0xFFD64C32),
                start = center,
                end = needleEnd,
                strokeWidth = 5f,
                cap = StrokeCap.Round
            )
            drawCircle(color = Color(0xFF2E2E2E), radius = 10f, center = center)
            drawCircle(color = Color(0xFFD1D1D1), radius = 5f, center = center)

            // Bottom label.
            val labelY = panelTop + panelHeight * 0.63f
            drawLine(
                color = if (isRunning) Color(0x55F2B63D) else Color(0x33000000),
                start = Offset(panelLeft + panelWidth * 0.18f, labelY),
                end = Offset(panelLeft + panelWidth * 0.82f, labelY),
                strokeWidth = 1.2f
            )
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
    onRequestAutoMicSetup: () -> Unit,
    onRequestRunABTest: () -> Unit
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .background(Brush.verticalGradient(listOf(AmpBgDark, AmpBgMid, AmpBgDark)))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Réglages micro", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = AmpMetalLight)
            OutlinedButton(onClick = onBack) { Text("Retour") }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF2A2D32))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CompositionLocalProvider(LocalContentColor provides AmpText) {
                val compatibilityText = when {
                    verificationRunning -> "Vérification automatique en cours..."
                    ui.stereoSupported -> "Capture stéréo compatible sur cet appareil."
                    else -> "Capture stéréo non confirmée."
                }
                Text(compatibilityText, fontWeight = FontWeight.Bold)
                Text("Pour valider totalement la stéréo, lance le test stéréo guidé en utilisant ta voix.")

                if (ui.guidedStereoPassed) {
                    Text("Téléphone totalement compatible stéréo.", fontWeight = FontWeight.SemiBold, color = AmpAccentAmber)
                    Text(
                        "Paramètres optimaux enregistrés automatiquement: stéréo ${if (ui.stereoModeRequested) "ON" else "OFF"}, " +
                            "swap ${if (ui.stereoChannelsSwapped) "ON" else "OFF"}, micro ${ui.effectiveMicLabel ?: "Auto"}."
                    )
                }

                if (!ui.autoMicSetupMessage.isNullOrBlank()) {
                    Text(ui.autoMicSetupMessage, color = AmpAccentAmber)
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
                        OutlinedButton(onClick = onRefreshMics, enabled = !busyAudioAction) { Text("Scanner") }
                        Button(onClick = onRequestTestMic, enabled = !busyAudioAction) {
                            Text(if (ui.isTestingMic) "Test..." else "Tester le micro")
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
                        Text("Sonder la stéréo")
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Comparaison A/B des micros internes", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Comment l'utiliser: place le téléphone à l'endroit réel d'enregistrement, " +
                            "laisse 2 secondes de silence puis joue 6 secondes. " +
                            "Le test compare deux micros internes et recommande le plus propre.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = onRequestRunABTest,
                        enabled = !busyAudioAction,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (ui.isRunningABTest) "Comparaison A/B en cours..." else "Lancer comparaison A/B")
                    }
                    ui.abTestResult?.let {
                        Text("Résultat A/B: $it", style = MaterialTheme.typography.bodySmall)
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
                                    Text("Warning: ${mic.warning}", color = AmpAccentAmber, style = MaterialTheme.typography.bodySmall)
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
    onRefreshRecordings: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSetPreset: (PlayerFxPreset) -> Unit,
    onSetEqEnabled: (Boolean) -> Unit,
    onSetCompressionEnabled: (Boolean) -> Unit,
    onSetDeEsserEnabled: (Boolean) -> Unit,
    onSetEqIntensity: (Float) -> Unit,
    onSetCompressionIntensity: (Float) -> Unit,
    onSetDeEsserIntensity: (Float) -> Unit,
    onSetBoostIntensity: (Float) -> Unit,
    onSetStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val playback = remember { PlaybackController(context) }
    var selected by remember { mutableStateOf<RecordingListItem?>(null) }
    var toolsOpen by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf("Preset") }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(1L) }

    DisposableEffect(Unit) {
        onDispose { playback.release() }
    }
    LaunchedEffect(Unit) { onRefreshRecordings() }
    LaunchedEffect(playback.isPlaying()) {
        while (playback.isPlaying()) {
            positionMs = playback.currentPositionMs()
            durationMs = maxOf(1L, playback.durationMs())
            kotlinx.coroutines.delay(120)
        }
    }

    ScreenScaffold(title = "Lecteur", onBack = onBack) {
        if (selected == null) {
            Text("Enregistrements", fontWeight = FontWeight.Bold)
            if (ui.playerRecordings.isEmpty()) {
                Text("Aucun enregistrement trouvé.")
            } else {
                ui.playerRecordings.forEach { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E3238))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.SemiBold)
                                Text("${item.dateLabel}  •  ${formatDuration(item.durationMs)}", style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = { onToggleFavorite(item.key) }) {
                                Text(if (item.isFavorite) "★" else "☆")
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(onClick = {
                                selected = item
                                positionMs = 0L
                                durationMs = maxOf(1L, item.durationMs)
                                onSetStatus("Fichier sélectionné: ${item.title}")
                            }) {
                                Text("Ouvrir")
                            }
                        }
                    }
                }
            }
            return@ScreenScaffold
        }

        val current = selected!!
        val cfg = ui.playerFxConfig
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = current.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            OutlinedButton(onClick = {
                playback.release()
                selected = null
            }) { Text("Retour liste") }
        }

        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val wheelSize = (maxWidth * 0.82f).coerceAtMost(320.dp).coerceAtLeast(220.dp)
            Box(
                modifier = Modifier
                    .size(wheelSize)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF4A4F57), Color(0xFF2A2E34), Color(0xFF1A1D22)),
                            center = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                            radius = 900f
                        )
                    )
                    .border(BorderStroke(2.dp, AmpPanelBorder), RoundedCornerShape(percent = 50))
                    .pointerInput(Unit) {
                        detectDragGestures { change: PointerInputChange, dragAmount: Offset ->
                            val delta = dragAmount.x - dragAmount.y
                            val speedFactor = 1f + (delta.absoluteValue / 26f)
                            val deltaMs = (delta * 18f * speedFactor).toLong()
                            playback.scrubBy(deltaMs)
                            positionMs = playback.currentPositionMs()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val r = size.minDimension / 2f
                    // Outer ring highlight.
                    drawCircle(
                        brush = Brush.sweepGradient(
                            colors = listOf(Color(0x80FFFFFF), Color(0x20FFFFFF), Color(0x80FFFFFF))
                        ),
                        radius = r - 6f,
                        style = Stroke(width = 10f)
                    )
                    // Finger groove ring.
                    drawCircle(
                        color = Color(0xAA0F1114),
                        radius = r - 38f,
                        style = Stroke(width = 28f)
                    )
                    drawArc(
                        color = Color(0x66FFFFFF),
                        startAngle = 220f,
                        sweepAngle = 100f,
                        useCenter = false,
                        topLeft = Offset(r - (r - 38f), r - (r - 38f)),
                        size = Size((r - 38f) * 2, (r - 38f) * 2),
                        style = Stroke(width = 6f, cap = StrokeCap.Round)
                    )
                }
                Button(
                    onClick = {
                        val ok = playback.togglePlay(current, cfg, onError = onSetStatus)
                        onSetStatus(if (ok) "Lecture active" else "Lecture arrêtée")
                        durationMs = maxOf(1L, playback.durationMs().coerceAtLeast(current.durationMs))
                        positionMs = playback.currentPositionMs()
                    },
                    modifier = Modifier.size((wheelSize.value * 0.42f).dp),
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E434A), contentColor = AmpAccentAmber)
                ) {
                    Text(if (playback.isPlaying()) "⏸" else "▶", fontSize = 34.sp)
                }
            }
        }

        Slider(
            value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
            onValueChange = {
                positionMs = it.toLong()
                playback.seekTo(positionMs)
            },
            valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
            modifier = Modifier.fillMaxWidth().height(40.dp)
        )
        Text("${formatDuration(positionMs)} / ${formatDuration(durationMs)}", style = MaterialTheme.typography.bodySmall)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { toolsOpen = !toolsOpen }, modifier = Modifier.weight(1f)) { Text("OUTILS") }
            OutlinedButton(onClick = { onSetStatus("Découpe: placeholder") }, modifier = Modifier.weight(1f)) { Text("Découpe") }
        }

        if (toolsOpen) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactToolButton(
                    label = "Preset",
                    selected = tool == "Preset",
                    modifier = Modifier.weight(1f),
                    onClick = { tool = "Preset" }
                )
                CompactToolButton(
                    label = "EQ tonal",
                    selected = tool == "EQ tonal",
                    modifier = Modifier.weight(1f),
                    onClick = { tool = "EQ tonal" }
                )
                CompactToolButton(
                    label = "Compression",
                    selected = tool == "Compression",
                    modifier = Modifier.weight(1f),
                    onClick = { tool = "Compression" }
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactToolButton(
                    label = "De-esser",
                    selected = tool == "De-esser",
                    modifier = Modifier.weight(1f),
                    onClick = { tool = "De-esser" }
                )
                CompactToolButton(
                    label = "Boost",
                    selected = tool == "Boost",
                    modifier = Modifier.weight(1f),
                    onClick = { tool = "Boost" }
                )
                Spacer(modifier = Modifier.weight(1f))
            }
            Text(tool, fontWeight = FontWeight.Bold)
            when (tool) {
                "Preset" -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presetButton("Flat", cfg.preset == PlayerFxPreset.FLAT, Modifier.weight(1f)) { onSetPreset(PlayerFxPreset.FLAT); playback.refreshFx(cfg.copy(preset = PlayerFxPreset.FLAT)) }
                    presetButton("Rock", cfg.preset == PlayerFxPreset.ROCK, Modifier.weight(1f)) { onSetPreset(PlayerFxPreset.ROCK); playback.refreshFx(cfg.copy(preset = PlayerFxPreset.ROCK)) }
                    presetButton("Pop", cfg.preset == PlayerFxPreset.POP, Modifier.weight(1f)) { onSetPreset(PlayerFxPreset.POP); playback.refreshFx(cfg.copy(preset = PlayerFxPreset.POP)) }
                }
                "EQ tonal" -> {
                    ToggleRow("EQ tonal", cfg.eqEnabled, onSetEqEnabled)
                    AdvancedConfigSlider("EQ intensité", cfg.eqIntensity, 0f..1f) { onSetEqIntensity(it); playback.refreshFx(cfg.copy(eqIntensity = it)) }
                }
                "Compression" -> {
                    ToggleRow("Compression", cfg.compressionEnabled, onSetCompressionEnabled)
                    AdvancedConfigSlider("Compression", cfg.compressionIntensity, 0f..1f) { onSetCompressionIntensity(it); playback.refreshFx(cfg.copy(compressionIntensity = it)) }
                }
                "De-esser" -> {
                    ToggleRow("De-esser", cfg.deEsserEnabled, onSetDeEsserEnabled)
                    AdvancedConfigSlider("De-esser", cfg.deEsserIntensity, 0f..1f) { onSetDeEsserIntensity(it); playback.refreshFx(cfg.copy(deEsserIntensity = it)) }
                }
                "Boost" -> {
                    AdvancedConfigSlider("Boost", cfg.boostIntensity, 0f..1f) { onSetBoostIntensity(it); playback.refreshFx(cfg.copy(boostIntensity = it)) }
                }
            }
        }

        Text("Statut lecteur: ${ui.playerStatusMessage}", style = MaterialTheme.typography.bodySmall)
        Text("Export traité: placeholder roadmap", style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun CompactToolButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val content: @Composable () -> Unit = {
        Text(
            text = label,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false
        )
    }
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) { content() }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) { content() }
    }
}

private class PlaybackController(private val context: android.content.Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    fun currentPositionMs(): Long = mediaPlayer?.currentPosition?.toLong() ?: 0L
    fun durationMs(): Long = mediaPlayer?.duration?.toLong() ?: 0L
    fun seekTo(ms: Long) {
        runCatching { mediaPlayer?.seekTo(ms.toInt().coerceAtLeast(0)) }
    }
    fun scrubBy(deltaMs: Long) {
        val next = currentPositionMs() + deltaMs
        seekTo(next.coerceIn(0L, durationMs().coerceAtLeast(1L)))
    }

    fun togglePlay(item: RecordingListItem, cfg: PlayerFxConfig, onError: (String) -> Unit): Boolean {
        if (mediaPlayer?.isPlaying == true) {
            runCatching { mediaPlayer?.pause() }
            return false
        }
        if (mediaPlayer != null) {
            runCatching { mediaPlayer?.start() }
            return true
        }
        return runCatching {
            release()
            val player = MediaPlayer()
            when {
                item.sourceUri != null -> player.setDataSource(context, item.sourceUri)
                !item.filePath.isNullOrBlank() -> player.setDataSource(item.filePath)
                else -> error("No data source")
            }
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
            enabled = cfg.compressionEnabled || cfg.boostIntensity > 0f
            val gainMb = (cfg.compressionIntensity * 300f + cfg.boostIntensity * 1200f).toInt()
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

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSec / 60L
    val s = totalSec % 60L
    return "%02d:%02d".format(m, s)
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
    onToggleIgnoreSilence: (Boolean) -> Unit,
    onToggleSplitOnSilence: (Boolean) -> Unit,
    onToggleDiagnostic: (Boolean) -> Unit,
    onToggleAdvanced: (Boolean) -> Unit,
    onToggleVintageV2: (Boolean) -> Unit
) {
    ScreenScaffold(title = "Paramètres", onBack = onBack) {
        CompositionLocalProvider(
            androidx.compose.material3.LocalTextStyle provides TextStyle(fontSize = 12.sp)
        ) {
            Text("Paramètres d'enregistrement", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            SettingToggleRow(
                label = "Ignorer les blancs",
                checked = ui.ignoreSilenceEnabled,
                onCheckedChange = onToggleIgnoreSilence
            )
            SettingToggleRow(
                label = "Découper",
                checked = ui.splitOnSilenceEnabled,
                enabled = ui.ignoreSilenceEnabled,
                onCheckedChange = onToggleSplitOnSilence
            )
            if (!ui.ignoreSilenceEnabled) {
                Text(
                    "Active d'abord « Ignorer les blancs » pour autoriser « Découper ».",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text("Emplacement d'enregistrement", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            SettingToggleRow(
                label = "Stockage app privée",
                checked = ui.storageLocation == StorageLocation.APP_PRIVATE,
                onCheckedChange = { isPrivate ->
                    onStorageChange(if (isPrivate) StorageLocation.APP_PRIVATE else StorageLocation.DOWNLOADS)
                }
            )
            Text(
                if (ui.storageLocation == StorageLocation.APP_PRIVATE) "Actuel: stockage privé app" else "Actuel: Downloads",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text("Diagnostic", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            SettingToggleRow(
                label = "Diagnostic",
                checked = ui.diagnosticModeEnabled,
                onCheckedChange = onToggleDiagnostic
            )
            SettingToggleRow(
                label = "Avancé",
                checked = ui.showAdvancedInternals,
                onCheckedChange = onToggleAdvanced
            )
            SettingToggleRow(
                label = "UI Vintage V2",
                checked = ui.uiVintageV2Enabled,
                onCheckedChange = onToggleVintageV2
            )

            Spacer(modifier = Modifier.height(6.dp))
            Text("Historique des tests", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.55f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AmpAccentAmber,
                checkedTrackColor = AmpAccentAmberSoft,
                checkedBorderColor = AmpAccentAmber,
                uncheckedThumbColor = AmpMetalLight,
                uncheckedTrackColor = Color(0xFF3A3E44),
                uncheckedBorderColor = AmpPanelBorder,
                disabledCheckedThumbColor = AmpAccentAmber.copy(alpha = 0.5f),
                disabledCheckedTrackColor = AmpAccentAmberSoft.copy(alpha = 0.35f),
                disabledUncheckedThumbColor = AmpMetalLight.copy(alpha = 0.45f),
                disabledUncheckedTrackColor = Color(0xFF3A3E44).copy(alpha = 0.45f)
            )
        )
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
            .background(Brush.verticalGradient(listOf(AmpBgDark, AmpBgMid, AmpBgDark)))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onBack,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AmpAccentAmber, containerColor = Color(0xFF2A2D32)),
                border = BorderStroke(1.dp, AmpPanelBorder)
            ) {
                Text("Retour")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.verticalGradient(listOf(AmpMetalLight, AmpMetalDark, AmpMetalLight)))
                    .padding(vertical = 8.dp, horizontal = 12.dp)
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF1F2328))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF24282D))
                .border(BorderStroke(1.dp, AmpPanelBorder), RoundedCornerShape(16.dp))
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CompositionLocalProvider(LocalContentColor provides AmpText) {
                content()
            }
        }
    }
}

private fun formatTimer(ms: Long): String {
    val totalSec = (ms / 1000.0).roundToInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}
