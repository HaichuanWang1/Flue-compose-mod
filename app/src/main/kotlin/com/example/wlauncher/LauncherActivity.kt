package com.flue.launcher

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.core.FastOutSlowInEasing
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.service.FlueAccessibilityService
import com.flue.launcher.ui.anim.appListLayerValues
import com.flue.launcher.ui.anim.faceLayerValues
import com.flue.launcher.ui.anim.scaleBlurAlpha
import com.flue.launcher.ui.controlcenter.ControlCenterLayer
import com.flue.launcher.ui.drawer.AppFolderOverlay
import com.flue.launcher.ui.drawer.HoneycombScreen
import com.flue.launcher.ui.drawer.ListDrawerScreen
import com.flue.launcher.ui.drawer.appListPalette
import com.flue.launcher.ui.drawer.vibrateHaptic
import com.flue.launcher.ui.home.WatchFaceLayer
import com.flue.launcher.ui.home.WatchFaceBottomFadeOverlay
import com.flue.launcher.ui.home.WatchFaceStatusIndicatorOverlay
import com.flue.launcher.ui.home.rememberAppListSeedColor
import com.flue.launcher.ui.navigation.GestureHost
import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.ui.navigation.ScreenState
import com.flue.launcher.ui.notification.NotificationLayer
import com.flue.launcher.ui.smartstack.SmartStackLayer
import com.flue.launcher.ui.smartstack.WidgetPageLayer
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.ThemeMode
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.util.applyFadeCloseTransition
import com.flue.launcher.util.applyFadeOpenTransition
import com.flue.launcher.util.RecentsVisibility
import com.flue.launcher.viewmodel.HoneycombFastScrollOptimizationMode
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.BUILT_IN_WATCHFACE_ID
import com.flue.launcher.watchface.BuiltInWatchFaceOptions
import com.flue.launcher.watchface.LunchWatchFaceHost
import com.flue.launcher.watchface.jbwatch.JbWatchFaceHost
import androidx.compose.animation.core.Spring
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val BASE_LAUNCH_MASK_DELAY_MS = 180L
private const val SIDE_SCREEN_TRANSITION_MS = 260
private const val NOTIFICATION_TRANSITION_MS = 240
private const val APP_LIST_OPEN_SHADOW_DELAY_MS = 280L
private const val APP_LIST_RETURN_SHADOW_DELAY_MS = 320L
private const val APP_LIST_RETURN_ORIGIN_HOLD_MS = 420L
private const val APP_RETURN_MAINTENANCE_DELAY_MS = 260L
private const val APP_LIST_LAYER_RELEASE_HOLD_MS = 32L
private const val LAUNCH_MASK_EXIT_RETAIN_MS = 260L

class LauncherActivity : ComponentActivity() {

    private lateinit var vm: LauncherViewModel
    private var deferredResumeMaintenance: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecentsVisibility.apply(this)
        CrashHandler(applicationContext).install()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        onBackPressedDispatcher.addCallback(this) { vm.requestBackPress() }
        setContent {
            val viewModel: LauncherViewModel = viewModel()
            vm = viewModel
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val uiStyle by viewModel.uiStyle.collectAsStateWithLifecycle()
            val globalUiScalePercent by viewModel.globalUiScalePercent.collectAsStateWithLifecycle()
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle) {
                ProvideGlobalUiScale(globalUiScalePercent) {
                    LauncherScreen(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::vm.isInitialized && intent.isLauncherHomeIntent()) {
            vm.requestHomePress()
        }
    }

    override fun onResume() {
        super.onResume()
        RecentsVisibility.apply(this)
        if (::vm.isInitialized && vm.animationOverrideEnabled.value && vm.hasPendingExternalLaunchReturn()) {
            applyFadeCloseTransition()
        }
        if (::vm.isInitialized) {
            vm.setLauncherInteractive(true)
            vm.onReturnToLauncher()
            scheduleDeferredResumeMaintenance()
        }
    }

    override fun onPause() {
        super.onPause()
        deferredResumeMaintenance?.let(window.decorView::removeCallbacks)
        deferredResumeMaintenance = null
        if (::vm.isInitialized) {
            vm.setLauncherInteractive(false)
        }
        if (::vm.isInitialized && vm.animationOverrideEnabled.value) {
            applyFadeCloseTransition()
        }
    }

    private fun scheduleDeferredResumeMaintenance() {
        deferredResumeMaintenance?.let(window.decorView::removeCallbacks)
        val task = Runnable {
            deferredResumeMaintenance = null
            if (::vm.isInitialized) {
                vm.refreshWatchFaces()
                vm.refreshNotificationAccess()
            }
        }
        deferredResumeMaintenance = task
        window.decorView.postDelayed(task, APP_RETURN_MAINTENANCE_DELAY_MS)
    }
}

private fun Intent.isLauncherHomeIntent(): Boolean {
    return action == Intent.ACTION_MAIN &&
        hasCategory(Intent.CATEGORY_HOME)
}

@Composable
fun LauncherScreen(vm: LauncherViewModel) {
    val context = LocalContext.current
    val screenState by vm.screenState.collectAsStateWithLifecycle()
    val launcherInteractive by vm.launcherInteractive.collectAsStateWithLifecycle()
    val layoutMode by vm.layoutMode.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val sideScreenEnabled by vm.sideScreenEnabled.collectAsStateWithLifecycle()
    val sideScreenShortcuts by vm.sideScreenShortcuts.collectAsStateWithLifecycle()
    val blurEnabled by vm.blurEnabled.collectAsStateWithLifecycle()
    val edgeBlurEnabled by vm.edgeBlurEnabled.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val allApps by vm.allApps.collectAsStateWithLifecycle()
    val allSelectableApps by vm.allSelectableApps.collectAsStateWithLifecycle()
    val openFolder by vm.openFolder.collectAsStateWithLifecycle()
    val openFolderItems by vm.openFolderItems.collectAsStateWithLifecycle()
    val appOpenOrigin by vm.appOpenOrigin.collectAsStateWithLifecycle()
    val splashIcon by vm.splashIcon.collectAsStateWithLifecycle()
    val splashDelay by vm.splashDelay.collectAsStateWithLifecycle()
    val directLaunchAppListEnabled by vm.directLaunchAppListEnabled.collectAsStateWithLifecycle()
    val directLaunchAppListPreferenceLoaded by vm.directLaunchAppListPreferenceLoaded.collectAsStateWithLifecycle()
    val currentLaunchIcon by vm.currentLaunchIcon.collectAsStateWithLifecycle()
    val honeycombCols by vm.honeycombCols.collectAsStateWithLifecycle()
    val honeycombEdgeBlurRadius by vm.honeycombEdgeBlurRadius.collectAsStateWithLifecycle()
    val honeycombTopFade by vm.honeycombTopFade.collectAsStateWithLifecycle()
    val honeycombBottomFade by vm.honeycombBottomFade.collectAsStateWithLifecycle()
    val honeycombFastScrollOptimization by vm.honeycombFastScrollOptimization.collectAsStateWithLifecycle()
    val honeycombFastScrollOptimizationMode by vm.honeycombFastScrollOptimizationMode.collectAsStateWithLifecycle()
    val appListFisheyeEnabled by vm.appListFisheyeEnabled.collectAsStateWithLifecycle()
    val materialHoneycombTopFisheyeEnabled by vm.materialHoneycombTopFisheyeEnabled.collectAsStateWithLifecycle()
    val appListLeftSafeInsetPercent by vm.appListLeftSafeInsetPercent.collectAsStateWithLifecycle()
    val appListFisheyeRangeRows by vm.appListFisheyeRangeRows.collectAsStateWithLifecycle()
    val appListFisheyeStrengthPercent by vm.appListFisheyeStrengthPercent.collectAsStateWithLifecycle()
    val appListEdgeSpacingCompressionEnabled by vm.appListEdgeSpacingCompressionEnabled.collectAsStateWithLifecycle()
    val appListScalePercent by vm.appListScalePercent.collectAsStateWithLifecycle()
    val appListRowBorderEnabled by vm.appListRowBorderEnabled.collectAsStateWithLifecycle()
    val appListFoldersEnabled by vm.appListFoldersEnabled.collectAsStateWithLifecycle()
    val fastFlowAnimationEnabled by vm.fastFlowAnimationEnabled.collectAsStateWithLifecycle()
    val twoToneIconsEnabled by vm.twoToneIconsEnabled.collectAsStateWithLifecycle()
    val iconShadowEnabled by vm.iconShadowEnabled.collectAsStateWithLifecycle()
    val classicReturnAnimationEnabled by vm.classicReturnAnimationEnabled.collectAsStateWithLifecycle()
    val showStepCount by vm.showStepCount.collectAsStateWithLifecycle()
    val showNotification by vm.showNotification.collectAsStateWithLifecycle()
    val rotaryHapticsEnabled by vm.rotaryHapticsEnabled.collectAsStateWithLifecycle()
    val showWidgetPage by vm.showWidgetPage.collectAsStateWithLifecycle()
    val showControlCenter by vm.showControlCenter.collectAsStateWithLifecycle()
    val watchFaceChargingPowerText by vm.watchFaceChargingPowerText.collectAsStateWithLifecycle()
    val watchFaceStatusIndicators by vm.watchFaceStatusIndicators.collectAsStateWithLifecycle()
    val watchFaceBottomFadeEnabled by vm.watchFaceBottomFadeEnabled.collectAsStateWithLifecycle()
    val doubleTapLockScreenEnabled by vm.doubleTapLockScreenEnabled.collectAsStateWithLifecycle()
    val powerMenuButtonEnabled by vm.powerMenuButtonEnabled.collectAsStateWithLifecycle()
    val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
    val notificationAccessGranted by vm.notificationAccessGranted.collectAsStateWithLifecycle()
    val sideScreenPreviewGroups by vm.sideScreenPreviewGroups.collectAsStateWithLifecycle()
    val notificationGroups by vm.notificationGroups.collectAsStateWithLifecycle()
    val revealedNotificationTarget by vm.revealedNotificationTarget.collectAsStateWithLifecycle()
    val showMusicControls by vm.showMusicControls.collectAsStateWithLifecycle()
    val showMediaCustomActions by vm.showMediaCustomActions.collectAsStateWithLifecycle()
    val swapMusicNotificationComponents by vm.swapMusicNotificationComponents.collectAsStateWithLifecycle()
    val musicTextSwitchAnimation by vm.musicTextSwitchAnimation.collectAsStateWithLifecycle()
    val launchSourceState by vm.launchSourceState.collectAsStateWithLifecycle()
    val selectedWatchFaceId by vm.selectedWatchFaceId.collectAsStateWithLifecycle()
    val selectedWatchFace by vm.selectedWatchFace.collectAsStateWithLifecycle()
    val watchFaceSelectionReady by vm.watchFaceSelectionReady.collectAsStateWithLifecycle()
    val watchFaceRefreshToken by vm.watchFaceRefreshToken.collectAsStateWithLifecycle()
    val builtInPhotoPath by vm.builtInPhotoPath.collectAsStateWithLifecycle()
    val builtInVideoPath by vm.builtInVideoPath.collectAsStateWithLifecycle()
    val builtInPhotoClockPosition by vm.builtInPhotoClockPosition.collectAsStateWithLifecycle()
    val builtInVideoClockPosition by vm.builtInVideoClockPosition.collectAsStateWithLifecycle()
    val builtInPhotoClockSize by vm.builtInPhotoClockSize.collectAsStateWithLifecycle()
    val builtInVideoClockSize by vm.builtInVideoClockSize.collectAsStateWithLifecycle()
    val builtInPhotoClockBold by vm.builtInPhotoClockBold.collectAsStateWithLifecycle()
    val builtInVideoClockBold by vm.builtInVideoClockBold.collectAsStateWithLifecycle()
    val builtInVideoFillScreen by vm.builtInVideoFillScreen.collectAsStateWithLifecycle()
    val builtInVideoClockColorMode by vm.builtInVideoClockColorMode.collectAsStateWithLifecycle()
    val builtInWatchFaceFontPath by vm.builtInWatchFaceFontPath.collectAsStateWithLifecycle()
    val builtInPhotoClockStyle by vm.builtInPhotoClockStyle.collectAsStateWithLifecycle()
    val builtInVideoClockStyle by vm.builtInVideoClockStyle.collectAsStateWithLifecycle()
    val builtInPhotoMd3eShape by vm.builtInPhotoMd3eShape.collectAsStateWithLifecycle()
    val builtInVideoMd3eShape by vm.builtInVideoMd3eShape.collectAsStateWithLifecycle()
    val builtInPhotoUseThemeTextColor by vm.builtInPhotoUseThemeTextColor.collectAsStateWithLifecycle()
    val builtInVideoUseThemeTextColor by vm.builtInVideoUseThemeTextColor.collectAsStateWithLifecycle()
    val builtInPhotoTextColorArgb by vm.builtInPhotoTextColorArgb.collectAsStateWithLifecycle()
    val builtInVideoTextColorArgb by vm.builtInVideoTextColorArgb.collectAsStateWithLifecycle()
    val builtInPhotoMd3eAutoColors by vm.builtInPhotoMd3eAutoColors.collectAsStateWithLifecycle()
    val builtInVideoMd3eAutoColors by vm.builtInVideoMd3eAutoColors.collectAsStateWithLifecycle()
    val builtInPhotoMd3eTextColorArgb by vm.builtInPhotoMd3eTextColorArgb.collectAsStateWithLifecycle()
    val builtInVideoMd3eTextColorArgb by vm.builtInVideoMd3eTextColorArgb.collectAsStateWithLifecycle()
    val builtInPhotoMd3eFaceColorArgb by vm.builtInPhotoMd3eFaceColorArgb.collectAsStateWithLifecycle()
    val builtInVideoMd3eFaceColorArgb by vm.builtInVideoMd3eFaceColorArgb.collectAsStateWithLifecycle()
    val builtInPhotoMd3eHourColorArgb by vm.builtInPhotoMd3eHourColorArgb.collectAsStateWithLifecycle()
    val builtInVideoMd3eHourColorArgb by vm.builtInVideoMd3eHourColorArgb.collectAsStateWithLifecycle()
    val builtInPhotoMd3eMinuteColorArgb by vm.builtInPhotoMd3eMinuteColorArgb.collectAsStateWithLifecycle()
    val builtInVideoMd3eMinuteColorArgb by vm.builtInVideoMd3eMinuteColorArgb.collectAsStateWithLifecycle()
    val builtInPhotoMd3eSecondColorArgb by vm.builtInPhotoMd3eSecondColorArgb.collectAsStateWithLifecycle()
    val builtInVideoMd3eSecondColorArgb by vm.builtInVideoMd3eSecondColorArgb.collectAsStateWithLifecycle()
    val builtInPhotoShowSeconds by vm.builtInPhotoShowSeconds.collectAsStateWithLifecycle()
    val builtInVideoShowSeconds by vm.builtInVideoShowSeconds.collectAsStateWithLifecycle()
    val builtInPhotoCustomText by vm.builtInPhotoCustomText.collectAsStateWithLifecycle()
    val builtInVideoCustomText by vm.builtInVideoCustomText.collectAsStateWithLifecycle()
    val effectiveDarkMode = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val launcherStyle = LauncherTheme.style
    val photoWatchOptions = BuiltInWatchFaceOptions(
        clockPosition = builtInPhotoClockPosition,
        clockSizeSp = builtInPhotoClockSize,
        boldClock = builtInPhotoClockBold,
        clockStyle = builtInPhotoClockStyle,
        showSeconds = builtInPhotoShowSeconds,
        customText = builtInPhotoCustomText,
        fontPath = builtInWatchFaceFontPath,
        md3eShape = builtInPhotoMd3eShape,
        useThemeTextColor = builtInPhotoUseThemeTextColor,
        textColorArgb = builtInPhotoTextColorArgb,
        md3eAutoColors = builtInPhotoMd3eAutoColors,
        md3eTextColorArgb = builtInPhotoMd3eTextColorArgb,
        md3eFaceColorArgb = builtInPhotoMd3eFaceColorArgb,
        md3eHourHandColorArgb = builtInPhotoMd3eHourColorArgb,
        md3eMinuteHandColorArgb = builtInPhotoMd3eMinuteColorArgb,
        md3eSecondHandColorArgb = builtInPhotoMd3eSecondColorArgb
    )
    val videoWatchOptions = BuiltInWatchFaceOptions(
        clockPosition = builtInVideoClockPosition,
        clockSizeSp = builtInVideoClockSize,
        boldClock = builtInVideoClockBold,
        cropToFill = builtInVideoFillScreen,
        clockColorMode = builtInVideoClockColorMode,
        clockStyle = builtInVideoClockStyle,
        showSeconds = builtInVideoShowSeconds,
        customText = builtInVideoCustomText,
        fontPath = builtInWatchFaceFontPath,
        md3eShape = builtInVideoMd3eShape,
        useThemeTextColor = builtInVideoUseThemeTextColor,
        textColorArgb = builtInVideoTextColorArgb,
        md3eAutoColors = builtInVideoMd3eAutoColors,
        md3eTextColorArgb = builtInVideoMd3eTextColorArgb,
        md3eFaceColorArgb = builtInVideoMd3eFaceColorArgb,
        md3eHourHandColorArgb = builtInVideoMd3eHourColorArgb,
        md3eMinuteHandColorArgb = builtInVideoMd3eMinuteColorArgb,
        md3eSecondHandColorArgb = builtInVideoMd3eSecondColorArgb
    )
    val appListThemeColor = rememberAppListSeedColor(
        watchFaceId = selectedWatchFaceId,
        photoPath = builtInPhotoPath,
        videoPath = builtInVideoPath,
        photoOptions = photoWatchOptions,
        videoOptions = videoWatchOptions,
        watchFaceDescriptor = selectedWatchFace,
        enabled = false
    )
    val useAppListWatchFaceColors = false
    val appListColors = appListPalette(
        seed = appListThemeColor,
        darkMode = effectiveDarkMode,
        uiStyle = uiStyle,
        useWatchFaceColor = useAppListWatchFaceColors
    )
    val watchFaceFadeSeed = if (uiStyle == UiStyle.MATERIAL_3 && appListThemeColor.alpha <= 0.01f) {
        launcherStyle.accentColor
    } else {
        appListThemeColor
    }
    val watchFaceFadePalette = appListPalette(
        seed = watchFaceFadeSeed,
        darkMode = effectiveDarkMode,
        uiStyle = uiStyle,
        useWatchFaceColor = if (uiStyle == UiStyle.MATERIAL_3) true else useAppListWatchFaceColors
    )
    val launchBackdropColor = when (launchSourceState) {
        ScreenState.Apps -> appListColors.background
        else -> launcherStyle.screenBackground
    }
    val layerBlurEnabled =
        blurEnabled && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || screenState != ScreenState.App)
    val reduceLegacyDrawerEffects = Build.VERSION.SDK_INT < Build.VERSION_CODES.S && screenState == ScreenState.App
    val notificationsEnabled = showNotification
    val notificationSceneActive = screenState == ScreenState.Notifications
    val notificationStackTint = appListColors.item
    val openWatchFaceChooser: () -> Unit = remember(context) {
        {
            vibrateHaptic(context)
            context.startActivity(
                Intent(context, SettingsActivity::class.java)
                    .putExtra(EXTRA_SETTINGS_DESTINATION, SETTINGS_DESTINATION_WATCH_FACES)
                    .putExtra(EXTRA_SETTINGS_RETURN_TO_FACE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            (context as? Activity)?.applyFadeOpenTransition()
            Unit
        }
    }
    val openAccessibilitySettings: () -> Unit = remember(context) {
        {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Unit
        }
    }
    val lockScreenWithAccessibility: () -> Unit = remember(context, openAccessibilitySettings) {
        {
            if (FlueAccessibilityService.lockScreen()) {
                vibrateHaptic(context)
            } else {
                if (FlueAccessibilityService.isEnabled(context)) {
                    Toast.makeText(context, "Flue 无障碍服务连接中，请稍后重试", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "请先授权 Flue 无障碍服务", Toast.LENGTH_SHORT).show()
                    openAccessibilitySettings()
                }
            }
        }
    }
    val openPowerDialogWithAccessibility: () -> Unit = remember(context, openAccessibilitySettings) {
        {
            if (!FlueAccessibilityService.openPowerDialog()) {
                if (FlueAccessibilityService.isEnabled(context)) {
                    Toast.makeText(context, "Flue 无障碍服务连接中，请稍后重试", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "请先授权 Flue 无障碍服务", Toast.LENGTH_SHORT).show()
                    openAccessibilitySettings()
                }
            }
        }
    }
    val launchAppFromLauncher: (AppInfo, Offset, ScreenState) -> Unit = { appInfo, origin, returnState ->
        val launchDelay = BASE_LAUNCH_MASK_DELAY_MS + if (splashIcon) splashDelay.toLong() else 0L
        vm.openApp(appInfo, origin, launchDelay, returnState)
    }
    var prevState by remember { mutableStateOf(screenState) }
    var appListLayerReleaseHold by remember { mutableStateOf(false) }
    var directLaunchAppListConsumed by rememberSaveable { mutableStateOf(false) }
    var directLaunchStartupWindowOpen by rememberSaveable { mutableStateOf(true) }
    var directLaunchInitialScrollResetKey by rememberSaveable { mutableStateOf(0) }
    var directLaunchInitialScrollResetPosted by rememberSaveable { mutableStateOf(false) }
    var widgetPageEditGestureLocked by rememberSaveable { mutableStateOf(false) }
    val isDirectLaunchEnteringAppList =
        directLaunchStartupWindowOpen && directLaunchAppListConsumed && screenState == ScreenState.Apps
    val isReturningFromApp = prevState == ScreenState.App && screenState == ScreenState.Apps
    val isOpeningAppList =
        (prevState == ScreenState.Face && screenState == ScreenState.Apps) || isDirectLaunchEnteringAppList
    val isClosingAppListToFace = prevState == ScreenState.Apps && screenState == ScreenState.Face
    val isEnteringAppList = prevState != ScreenState.Apps && screenState == ScreenState.Apps
    var appListReturnOriginActive by remember {
        mutableStateOf(screenState == ScreenState.App && launchSourceState == ScreenState.Apps)
    }
    var appListShadowsReady by remember { mutableStateOf(screenState == ScreenState.Apps) }
    LaunchedEffect(screenState, launchSourceState) {
        if (launchSourceState != ScreenState.Apps) {
            appListReturnOriginActive = false
            return@LaunchedEffect
        }
        when (screenState) {
            ScreenState.App -> appListReturnOriginActive = true
            ScreenState.Apps -> {
                if (appListReturnOriginActive || isReturningFromApp) {
                    appListReturnOriginActive = true
                    delay(APP_LIST_RETURN_ORIGIN_HOLD_MS)
                    appListReturnOriginActive = false
                } else {
                    appListReturnOriginActive = false
                }
            }
            else -> appListReturnOriginActive = false
        }
    }
    LaunchedEffect(
        directLaunchAppListPreferenceLoaded,
        directLaunchAppListEnabled,
        directLaunchAppListConsumed,
        screenState
    ) {
        if (!directLaunchStartupWindowOpen || !directLaunchAppListPreferenceLoaded) return@LaunchedEffect
        when {
            !directLaunchAppListEnabled -> directLaunchStartupWindowOpen = false
            directLaunchAppListConsumed && screenState == ScreenState.Apps -> {
                delay(360)
                directLaunchStartupWindowOpen = false
            }
        }
    }
    LaunchedEffect(
        directLaunchAppListPreferenceLoaded,
        directLaunchAppListEnabled,
        directLaunchStartupWindowOpen,
        screenState
    ) {
        if (
            !directLaunchAppListConsumed &&
            directLaunchStartupWindowOpen &&
            directLaunchAppListPreferenceLoaded &&
            directLaunchAppListEnabled &&
            screenState == ScreenState.Face
        ) {
            vm.setState(ScreenState.Apps)
            directLaunchAppListConsumed = true
        }
    }
    LaunchedEffect(directLaunchAppListConsumed, screenState, apps.size) {
        if (
            directLaunchAppListConsumed &&
            !directLaunchInitialScrollResetPosted &&
            screenState == ScreenState.Apps
        ) {
            // Let Apps become active before issuing the one-shot top reset; otherwise
            // direct launch can sample fish-eye positions from the hidden first frame.
            delay(96)
            directLaunchInitialScrollResetKey += 1
            directLaunchInitialScrollResetPosted = true
        }
    }
    LaunchedEffect(screenState) {
        val previousState = prevState
        appListLayerReleaseHold = false
        try {
            if (previousState == ScreenState.Apps && screenState == ScreenState.App) {
                appListLayerReleaseHold = true
                delay(APP_LIST_LAYER_RELEASE_HOLD_MS)
            } else {
                appListLayerReleaseHold = false
            }
        } finally {
            appListLayerReleaseHold = false
            prevState = screenState
        }
    }
    LaunchedEffect(screenState, iconShadowEnabled) {
        if (!iconShadowEnabled || screenState != ScreenState.Apps) {
            appListShadowsReady = false
            return@LaunchedEffect
        }
        appListShadowsReady = false
        val shadowDelayMs = when {
            isReturningFromApp -> APP_LIST_RETURN_SHADOW_DELAY_MS
            isEnteringAppList -> APP_LIST_OPEN_SHADOW_DELAY_MS
            else -> 0L
        }
        if (shadowDelayMs > 0L) {
            delay(shadowDelayMs)
        }
        appListShadowsReady = true
    }
    val launcherScope = rememberCoroutineScope()
    var retainedLaunchIcon by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var retainedLaunchSplash by remember { mutableStateOf(false) }
    val notificationTransition = remember { Animatable(if (notificationSceneActive) 1f else 0f) }
    var notificationGestureActive by remember { mutableStateOf(false) }
    var notificationTargetProgress by remember { mutableFloatStateOf(if (notificationSceneActive) 1f else 0f) }
    var notificationReturnState by remember { mutableStateOf(ScreenState.Stack) }
    val backPressRequests by vm.backPressRequests.collectAsStateWithLifecycle()
    val homePressRequests by vm.homePressRequests.collectAsStateWithLifecycle()

    val appListTransformOrigin = when {
        launchSourceState != ScreenState.Apps -> null
        screenState == ScreenState.App -> appOpenOrigin
        appListReturnOriginActive && !classicReturnAnimationEnabled -> appOpenOrigin
        else -> null
    }
    val appListIconReturnActive =
        launchSourceState == ScreenState.Apps &&
            screenState == ScreenState.Apps &&
            !classicReturnAnimationEnabled &&
            (isReturningFromApp || appListReturnOriginActive)
    val appListLayerOrigin = if (isOpeningAppList) {
        Offset(0.5f, 0.5f)
    } else if (appListIconReturnActive) {
        appOpenOrigin
    } else {
        appListTransformOrigin
    }
    val appListBackgroundProgress by animateFloatAsState(
        targetValue = if (screenState == ScreenState.Apps) 1f else 0f,
        animationSpec = if (screenState == ScreenState.Apps) {
            tween(durationMillis = 400)
        } else {
            tween(durationMillis = 400)
        },
        label = "app_list_background_progress"
    )
    val appListMotionSpec = when {
        isOpeningAppList || isClosingAppListToFace -> tween<Float>(durationMillis = 220)
        uiStyle == UiStyle.MATERIAL_3 && (isReturningFromApp || appListIconReturnActive) -> tween(
            durationMillis = 260,
            easing = FastOutSlowInEasing
        )
        else -> spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
    }
    val faceLayerBlurEnabled = layerBlurEnabled
    val appListLayerBlurEnabled = layerBlurEnabled
    val effectiveIconShadowEnabled = iconShadowEnabled && appListShadowsReady
    val fadeLaunch = launchSourceState == ScreenState.Stack || launchSourceState == ScreenState.Notifications
    val iconLaunchAvailable = currentLaunchIcon != null || retainedLaunchIcon != null
    val iconlessFadeLaunch = fadeLaunch && !iconLaunchAvailable
    val directLaunchBlackBackdropVisible = directLaunchStartupWindowOpen && (
        !directLaunchAppListPreferenceLoaded ||
            (!directLaunchAppListConsumed && directLaunchAppListEnabled && screenState == ScreenState.Face) ||
            (directLaunchAppListConsumed && screenState == ScreenState.Apps)
        )

    LaunchedEffect(notificationSceneActive) {
        if (!notificationGestureActive) {
            notificationTargetProgress = if (notificationSceneActive) 1f else 0f
        }
    }

    LaunchedEffect(notificationTargetProgress, notificationGestureActive) {
        if (!notificationGestureActive) {
            notificationTransition.animateTo(
                notificationTargetProgress,
                tween(durationMillis = NOTIFICATION_TRANSITION_MS)
            )
        }
    }

    LaunchedEffect(sideScreenEnabled, screenState) {
        if (!sideScreenEnabled && (screenState == ScreenState.Stack || screenState == ScreenState.Notifications)) {
            vm.setState(ScreenState.Face)
        }
    }

    var showSplash by remember { mutableStateOf(false) }
    val showLaunchBackdrop = screenState == ScreenState.App && (iconlessFadeLaunch || iconLaunchAvailable)
    val effectiveLaunchIcon = currentLaunchIcon ?: retainedLaunchIcon
    val effectiveLaunchSplash = !iconlessFadeLaunch && (
            (showSplash && currentLaunchIcon != null) ||
            (retainedLaunchSplash && retainedLaunchIcon != null)
    )
    LaunchedEffect(screenState, splashIcon, splashDelay, currentLaunchIcon, iconlessFadeLaunch) {
        if (!iconlessFadeLaunch && screenState == ScreenState.App && splashIcon && currentLaunchIcon != null) {
            showSplash = false
            delay(BASE_LAUNCH_MASK_DELAY_MS)
            showSplash = true
        } else {
            showSplash = false
        }
    }
    LaunchedEffect(showLaunchBackdrop, currentLaunchIcon, showSplash, iconlessFadeLaunch) {
        if (!iconlessFadeLaunch && showLaunchBackdrop && currentLaunchIcon != null) {
            retainedLaunchIcon = currentLaunchIcon
            retainedLaunchSplash = showSplash
        } else if (!showLaunchBackdrop) {
            delay(LAUNCH_MASK_EXIT_RETAIN_MS)
            retainedLaunchIcon = null
            retainedLaunchSplash = false
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(launcherStyle.screenBackground)
    ) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val notificationProgress = notificationTransition.value.coerceIn(0f, 1f)
        val keepNotificationLayer = notificationProgress > 0.001f || notificationSceneActive || notificationGestureActive
        val sidePageProgress by animateFloatAsState(
            targetValue = if (
                sideScreenEnabled &&
                (screenState == ScreenState.Stack ||
                    (screenState == ScreenState.Notifications && notificationReturnState != ScreenState.ControlCenter))
            ) {
                1f
            } else {
                0f
            },
            animationSpec = tween(durationMillis = SIDE_SCREEN_TRANSITION_MS),
            label = "side_page_progress"
        )
        val widgetPageProgress by animateFloatAsState(
            targetValue = if (showWidgetPage && screenState == ScreenState.Widgets) 1f else 0f,
            animationSpec = tween(durationMillis = SIDE_SCREEN_TRANSITION_MS),
            label = "widget_page_progress"
        )
        val controlCenterProgress by animateFloatAsState(
            targetValue = if (
                showControlCenter &&
                (screenState == ScreenState.ControlCenter ||
                    (screenState == ScreenState.Notifications && notificationReturnState == ScreenState.ControlCenter))
            ) 1f else 0f,
            animationSpec = tween(durationMillis = SIDE_SCREEN_TRANSITION_MS),
            label = "control_center_progress"
        )
        val faceAnimState = screenState
        val updateNotificationTransitionProgress: (Float) -> Unit = { progress ->
            notificationGestureActive = true
            launcherScope.launch {
                notificationTransition.snapTo(progress.coerceIn(0f, 1f))
            }
        }
        val settleNotificationTransition: (Float) -> Unit = { target ->
            notificationGestureActive = false
            notificationTargetProgress = target.coerceIn(0f, 1f)
        }
        val openNotificationsFrom: (ScreenState) -> Unit = { origin ->
            notificationReturnState = origin
            vm.collapseNotificationGroups()
            vm.setRevealedNotificationTarget(null)
            vm.setState(ScreenState.Notifications)
            settleNotificationTransition(1f)
        }
        val closeNotificationsToOrigin: () -> Unit = {
            vm.setState(
                when (notificationReturnState) {
                    ScreenState.ControlCenter -> ScreenState.ControlCenter
                    ScreenState.Stack,
                    ScreenState.Notifications -> if (sideScreenEnabled) ScreenState.Stack else ScreenState.Face
                    else -> notificationReturnState
                }
            )
            settleNotificationTransition(0f)
        }
        LaunchedEffect(backPressRequests) {
            if (backPressRequests > 0L) {
                if (screenState == ScreenState.Notifications) {
                    closeNotificationsToOrigin()
                } else {
                    vm.handleBackPress()
                }
            }
        }
        LaunchedEffect(homePressRequests) {
            if (homePressRequests > 0L) {
                if (screenState == ScreenState.Notifications) {
                    closeNotificationsToOrigin()
                } else {
                    vm.handleHomePress()
                }
            }
        }
        val sideScreenVisible = sidePageProgress > 0.001f || screenState == ScreenState.Stack || screenState == ScreenState.Notifications
        val widgetPageVisible = widgetPageProgress > 0.001f || screenState == ScreenState.Widgets
        val controlCenterVisible = controlCenterProgress > 0.001f || screenState == ScreenState.ControlCenter
        val sideSceneOverlayActive = sideScreenVisible || widgetPageVisible || controlCenterVisible
        val appListLayerRaised = screenState == ScreenState.Apps ||
            isOpeningAppList ||
            appListLayerReleaseHold ||
            (screenState == ScreenState.App && launchSourceState == ScreenState.Apps && showLaunchBackdrop)
        val suppressAppListHeavyEffects =
            reduceLegacyDrawerEffects ||
                isOpeningAppList ||
                isClosingAppListToFace ||
                isReturningFromApp ||
                appListIconReturnActive ||
                appListReturnOriginActive ||
                appListLayerReleaseHold

        GestureHost(
            screenState = screenState,
            onStateChange = { vm.setState(it) },
            sideScreenEnabled = sideScreenEnabled,
            showNotification = false,
            showWidgetPage = showWidgetPage,
            showControlCenter = showControlCenter,
            widgetsBackGestureLocked = widgetPageEditGestureLocked,
            modifier = Modifier.fillMaxSize()
        ) {
            if (directLaunchBlackBackdropVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (screenState == ScreenState.Face) 4f else 2f)
                        .background(Color.Black)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (screenState == ScreenState.Face && !sideSceneOverlayActive) 3f else 1f)
                    .graphicsLayer {
                        clip = true
                    }
                    .scaleBlurAlpha(
                        targetValues = faceLayerValues(faceAnimState, uiStyle),
                        screenHeight = screenHeightPx,
                        blurEnabled = faceLayerBlurEnabled,
                        origin = Offset(0.5f, 0.5f)
                    )
            ) {
                AnimatedContent(
                    targetState = if (
                        watchFaceSelectionReady ||
                        selectedWatchFace.isBuiltin ||
                        selectedWatchFaceId == BUILT_IN_WATCHFACE_ID ||
                        (
                            selectedWatchFace.id == selectedWatchFaceId &&
                                selectedWatchFace.isJbWatch &&
                                !selectedWatchFace.sourceDirPath.isNullOrBlank()
                            )
                    ) {
                        selectedWatchFace.stableKey
                    } else {
                        "loading"
                    },
                    transitionSpec = {
                        fadeIn(animationSpec = launcherStyle.faceSwitchEnterSpec) togetherWith
                            fadeOut(animationSpec = launcherStyle.faceSwitchExitSpec)
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "watchface_switch"
                ) { targetKey ->
                    if (targetKey == "loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                        )
                    } else if (selectedWatchFace.isBuiltin) {
                        WatchFaceLayer(
                            watchFaceId = selectedWatchFace.id,
                            photoPath = builtInPhotoPath,
                            videoPath = builtInVideoPath,
                            isFaceVisible = launcherInteractive && screenState == ScreenState.Face,
                            uiStyle = uiStyle,
                            photoOptions = photoWatchOptions,
                            videoOptions = videoWatchOptions,
                            showChargingPowerText = watchFaceChargingPowerText,
                            showStatusIndicators = watchFaceStatusIndicators,
                            showBottomFade = watchFaceBottomFadeEnabled,
                            bottomFadeColor = watchFaceFadePalette.fadeEdge,
                            bottomFadeHeightDp = honeycombBottomFade,
                            bottomFadeBlurRadiusDp = if (blurEnabled && edgeBlurEnabled) honeycombEdgeBlurRadius else 0f,
                            hasNotifications = showNotification && notificationGroups.isNotEmpty(),
                            onLongPress = if (launcherInteractive && screenState == ScreenState.Face && !sideSceneOverlayActive) openWatchFaceChooser else null,
                            onDoubleTap = if (
                                doubleTapLockScreenEnabled &&
                                launcherInteractive &&
                                screenState == ScreenState.Face &&
                                !sideSceneOverlayActive
                            ) lockScreenWithAccessibility else null
                        )
                    } else if (selectedWatchFace.isJbWatch) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            JbWatchFaceHost(
                                descriptor = selectedWatchFace,
                                isFaceVisible = launcherInteractive && screenState == ScreenState.Face,
                                refreshToken = watchFaceRefreshToken,
                                onLongPress = if (launcherInteractive && screenState == ScreenState.Face && !sideSceneOverlayActive) openWatchFaceChooser else null,
                                onDoubleTap = if (
                                    doubleTapLockScreenEnabled &&
                                    launcherInteractive &&
                                    screenState == ScreenState.Face &&
                                    !sideSceneOverlayActive
                                ) lockScreenWithAccessibility else null,
                                onLoadFailure = { descriptor, error ->
                                    val rootCause = generateSequence(error) { it.cause }.last()
                                    vm.fallbackToBuiltIn("${descriptor.displayName}: ${rootCause.message ?: rootCause.javaClass.simpleName}")
                                }
                            )
                            if (watchFaceBottomFadeEnabled) {
                                WatchFaceBottomFadeOverlay(
                                    color = watchFaceFadePalette.fadeEdge,
                                    heightDp = honeycombBottomFade,
                                    blurRadiusDp = if (blurEnabled && edgeBlurEnabled) honeycombEdgeBlurRadius else 0f
                                )
                            }
                            WatchFaceStatusIndicatorOverlay(
                                showStatusIndicators = watchFaceStatusIndicators,
                                hasNotifications = showNotification && notificationGroups.isNotEmpty(),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 18.dp)
                            )
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            LunchWatchFaceHost(
                                descriptor = selectedWatchFace,
                                isFaceVisible = launcherInteractive && screenState == ScreenState.Face,
                                refreshToken = watchFaceRefreshToken,
                                onLongPress = if (launcherInteractive && screenState == ScreenState.Face && !sideSceneOverlayActive) openWatchFaceChooser else null,
                                onDoubleTap = if (
                                    doubleTapLockScreenEnabled &&
                                    launcherInteractive &&
                                    screenState == ScreenState.Face &&
                                    !sideSceneOverlayActive
                                ) lockScreenWithAccessibility else null,
                                onLoadFailure = { descriptor, error ->
                                    val rootCause = generateSequence(error) { it.cause }.last()
                                    vm.fallbackToBuiltIn("${descriptor.displayName}: ${rootCause.message ?: rootCause.javaClass.simpleName}")
                                }
                            )
                            if (watchFaceBottomFadeEnabled) {
                                WatchFaceBottomFadeOverlay(
                                    color = watchFaceFadePalette.fadeEdge,
                                    heightDp = honeycombBottomFade,
                                    blurRadiusDp = if (blurEnabled && edgeBlurEnabled) honeycombEdgeBlurRadius else 0f
                                )
                            }
                            WatchFaceStatusIndicatorOverlay(
                                showStatusIndicators = watchFaceStatusIndicators,
                                hasNotifications = showNotification && notificationGroups.isNotEmpty(),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 18.dp)
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (appListLayerRaised) 3f else 0f)
                    .scaleBlurAlpha(
                        targetValues = appListLayerValues(screenState, uiStyle),
                        screenWidth = screenWidthPx,
                        screenHeight = screenHeightPx,
                        blurEnabled = appListLayerBlurEnabled,
                        origin = appListLayerOrigin,
                        scaleSpec = appListMotionSpec,
                        alphaSpec = if (isOpeningAppList) tween(durationMillis = 220) else tween(durationMillis = 400),
                        blurSpec = if (isOpeningAppList) tween(durationMillis = 220) else tween(durationMillis = 400),
                        translationSpec = appListMotionSpec
                    )
            ) {
                when (layoutMode) {
                    LayoutMode.Honeycomb -> HoneycombScreen(
                        apps = apps,
                        blurEnabled = blurEnabled,
                        edgeBlurEnabled = edgeBlurEnabled,
                        suppressHeavyEffects = suppressAppListHeavyEffects,
                        narrowCols = honeycombCols,
                        topBlurRadiusDp = honeycombEdgeBlurRadius,
                        bottomBlurRadiusDp = honeycombEdgeBlurRadius,
                        topFadeRangeDp = honeycombTopFade,
                        bottomFadeRangeDp = honeycombBottomFade,
                        fastScrollOptimizationEnabled = honeycombFastScrollOptimization,
                        rotaryHapticsEnabled = rotaryHapticsEnabled,
                        active = screenState == ScreenState.Apps,
                        leftSafeInsetPercent = appListLeftSafeInsetPercent,
                        appListScalePercent = appListScalePercent,
                        folderOpen = openFolder != null,
                        useWatchFaceColors = useAppListWatchFaceColors,
                        allowFolderCreation = appListFoldersEnabled,
                        fisheyeEnabled = appListFisheyeEnabled,
                        fisheyeRangeRows = appListFisheyeRangeRows,
                        fisheyeStrengthPercent = appListFisheyeStrengthPercent,
                        edgeSpacingCompressionEnabled = appListEdgeSpacingCompressionEnabled,
                        fastFlowAnimationEnabled = fastFlowAnimationEnabled,
                        twoToneIconsEnabled = twoToneIconsEnabled,
                        iconShadowEnabled = effectiveIconShadowEnabled,
                        themeColor = appListThemeColor,
                        uiStyle = uiStyle,
                        darkMode = effectiveDarkMode,
                        entryProgress = appListBackgroundProgress,
                        onAppClick = { appInfo, origin ->
                            launchAppFromLauncher(appInfo, origin, ScreenState.Apps)
                        },
                        onReorder = { from, to -> vm.swapApps(from, to) },
                        onCreateFolder = { from, to -> vm.createFolder(from, to) },
                        onExcludeApp = { appInfo -> vm.setAppHidden(appInfo.componentKey, true) },
                        onRemoveShortcut = vm::removeAppListShortcut,
                        onRenameFolder = vm::renameFolder,
                        onDissolveFolder = vm::dissolveFolder,
                        onScrollToTop = { vm.setState(ScreenState.Face) }
                    )
                    LayoutMode.List -> ListDrawerScreen(
                        apps = apps,
                        blurEnabled = blurEnabled,
                        edgeBlurEnabled = edgeBlurEnabled,
                        suppressHeavyEffects = suppressAppListHeavyEffects,
                        topFadeRangeDp = honeycombTopFade,
                        bottomFadeRangeDp = honeycombBottomFade,
                        topBlurRadiusDp = honeycombEdgeBlurRadius,
                        bottomBlurRadiusDp = honeycombEdgeBlurRadius,
                        rotaryHapticsEnabled = rotaryHapticsEnabled,
                        active = screenState == ScreenState.Apps,
                        initialScrollResetKey = directLaunchInitialScrollResetKey,
                        leftSafeInsetPercent = appListLeftSafeInsetPercent,
                        appListScalePercent = appListScalePercent,
                        folderOpen = openFolder != null,
                        useWatchFaceColors = useAppListWatchFaceColors,
                        rowBorderEnabled = appListRowBorderEnabled,
                        allowFolderCreation = appListFoldersEnabled,
                        fisheyeEnabled = appListFisheyeEnabled,
                        fisheyeRangeRows = appListFisheyeRangeRows,
                        fisheyeStrengthPercent = appListFisheyeStrengthPercent,
                        edgeSpacingCompressionEnabled = appListEdgeSpacingCompressionEnabled,
                        fastFlowAnimationEnabled = fastFlowAnimationEnabled,
                        twoToneIconsEnabled = twoToneIconsEnabled,
                        iconShadowEnabled = effectiveIconShadowEnabled,
                        themeColor = appListThemeColor,
                        uiStyle = uiStyle,
                        darkMode = effectiveDarkMode,
                        entryProgress = appListBackgroundProgress,
                        onAppClick = { appInfo, origin ->
                            launchAppFromLauncher(appInfo, origin, ScreenState.Apps)
                        },
                        onReorder = { from, to -> vm.swapApps(from, to) },
                        onCreateFolder = { from, to -> vm.createFolder(from, to) },
                        onExcludeApp = { appInfo -> vm.setAppHidden(appInfo.componentKey, true) },
                        onRemoveShortcut = vm::removeAppListShortcut,
                        onRenameFolder = vm::renameFolder,
                        onDissolveFolder = vm::dissolveFolder,
                        onScrollToTop = { vm.setState(ScreenState.Face) }
                    )
                }
            }

            val visibleFolder = openFolder
            if (screenState == ScreenState.Apps && visibleFolder != null) {
                val appListScale = appListScalePercent.coerceIn(50, 200) / 100f
                val folderIconShape = when {
                    layoutMode == LayoutMode.List -> RoundedCornerShape((14.dp * appListScale).coerceIn(10.dp, 24.dp))
                    uiStyle == UiStyle.MATERIAL_3 -> RoundedCornerShape((18.dp * appListScale).coerceIn(12.dp, 24.dp))
                    else -> launcherStyle.bubbleShape
                }
                AppFolderOverlay(
                    folder = visibleFolder,
                    items = openFolderItems,
                    availableItems = allSelectableApps,
                    listMode = layoutMode == LayoutMode.List,
                    iconShape = folderIconShape,
                    blurEnabled = blurEnabled,
                    twoToneIconsEnabled = twoToneIconsEnabled,
                    onAppClick = { appInfo, origin ->
                        vm.closeFolder()
                        launchAppFromLauncher(appInfo, origin, ScreenState.Apps)
                    },
                    onReorderItems = { orderedItems -> vm.reorderFolderItems(visibleFolder, orderedItems) },
                    onMoveItemOut = { item -> vm.moveItemOutOfFolder(visibleFolder, item) },
                    onSetFolderItems = { items -> vm.setFolderItems(visibleFolder, items) },
                    onRenameFolder = { name -> vm.renameFolder(visibleFolder, name) },
                    onExcludeApp = { appInfo -> vm.setAppHidden(appInfo.componentKey, true) },
                    onRemoveShortcut = vm::removeAppListShortcut,
                    onDismiss = vm::closeFolder,
                    modifier = Modifier.zIndex(5.4f)
                )
            }

            if (screenState == ScreenState.App) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        }
                )
            }

            AnimatedVisibility(
                visible = showLaunchBackdrop,
                modifier = Modifier.zIndex(6f),
                enter = fadeIn(animationSpec = launcherStyle.launchMaskEnterSpec),
                exit = fadeOut(animationSpec = launcherStyle.launchMaskExitSpec) +
                    slideOutVertically(
                        animationSpec = tween(if (uiStyle == UiStyle.MATERIAL_3) 260 else 220),
                        targetOffsetY = { fullHeight -> if (uiStyle == UiStyle.MATERIAL_3) fullHeight / 10 else fullHeight / 6 }
                    )
            ) {
                LaunchBackdropContent(
                    showSplash = effectiveLaunchSplash,
                    icon = effectiveLaunchIcon,
                    uiStyle = uiStyle,
                    backgroundColor = launchBackdropColor
                )
            }

            if (sideScreenEnabled && sideScreenVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4f)
                        .graphicsLayer {
                            translationX = (sidePageProgress - 1f) * screenWidthPx
                            alpha = sidePageProgress.coerceIn(0f, 1f) *
                                (1f - 0.58f * notificationProgress)
                        }
                ) {
                    SmartStackLayer(
                        apps = allApps,
                        sideScreenShortcuts = sideScreenShortcuts,
                        previewGroups = sideScreenPreviewGroups,
                        showMusicCard = showMusicControls && swapMusicNotificationComponents,
                        showMediaCustomActions = showMediaCustomActions,
                        musicTextSwitchAnimation = musicTextSwitchAnimation,
                        leftSafeInsetPercent = appListLeftSafeInsetPercent,
                        notificationsEnabled = notificationsEnabled,
                        notificationAccessGranted = notificationAccessGranted,
                        notificationsSceneActive = notificationSceneActive,
                        notificationTransitionProgress = notificationProgress,
                        revealedNotificationTarget = revealedNotificationTarget,
                        onRevealTargetChange = vm::setRevealedNotificationTarget,
                        onOpenNotifications = { openNotificationsFrom(ScreenState.Stack) },
                        onNotificationTransitionProgressChange = updateNotificationTransitionProgress,
                        onNotificationTransitionRelease = { open ->
                            if (open) {
                                openNotificationsFrom(ScreenState.Stack)
                            } else {
                                settleNotificationTransition(0f)
                            }
                        },
                        onOpenNotification = { key, origin ->
                            vm.openNotification(
                                key,
                                origin,
                                ScreenState.Stack,
                                BASE_LAUNCH_MASK_DELAY_MS + if (splashIcon) splashDelay.toLong() else 0L
                            )
                        },
                        onLaunchApp = { appInfo, origin ->
                            launchAppFromLauncher(appInfo, origin, ScreenState.Stack)
                        },
                        onSetShortcut = vm::setSideScreenShortcut,
                        onRemoveShortcut = vm::removeSideScreenShortcut,
                        onSwapShortcut = vm::swapSideScreenShortcuts,
                        onSetShowNotification = vm::setShowNotification,
                        onDismissGroup = vm::dismissNotificationGroup,
                        onDismissNotification = vm::dismissNotification,
                        onDismissToFace = { vm.setState(ScreenState.Face) },
                        showSteps = showStepCount,
                        stackCardColor = notificationStackTint
                    )
                }
            }

            if (showWidgetPage && widgetPageVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4.2f)
                        .graphicsLayer {
                            translationX = (1f - widgetPageProgress) * screenWidthPx
                            alpha = widgetPageProgress.coerceIn(0f, 1f)
                        }
                ) {
                    val sideScreenWidgetSlots by vm.sideScreenWidgetSlots.collectAsStateWithLifecycle()
                    WidgetPageLayer(
                        apps = allApps,
                        sideScreenWidgetSlots = sideScreenWidgetSlots,
                        leftSafeInsetPercent = appListLeftSafeInsetPercent,
                        onSetWidget = vm::setWidgetSlot,
                        onSwapWidget = vm::swapWidgetSlots,
                        onRemoveWidget = vm::removeWidgetSlot,
                        onEditModeChange = { widgetPageEditGestureLocked = it }
                    )
                }
            }

            if (showControlCenter && controlCenterVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(4.4f)
                        .graphicsLayer {
                            translationY = (controlCenterProgress - 1f) * screenHeightPx
                            alpha = controlCenterProgress.coerceIn(0f, 1f)
                        }
                ) {
                    ControlCenterLayer(
                        leftSafeInsetPercent = appListLeftSafeInsetPercent,
                        showMusicControls = showMusicControls && !swapMusicNotificationComponents,
                        notificationAccessGranted = notificationAccessGranted,
                        musicTextSwitchAnimation = musicTextSwitchAnimation,
                        showMediaCustomActions = showMediaCustomActions,
                        showNotificationCard = showNotification && swapMusicNotificationComponents,
                        notificationGroups = sideScreenPreviewGroups,
                        onOpenNotifications = { openNotificationsFrom(ScreenState.ControlCenter) },
                        onDismissToFace = { vm.setState(ScreenState.Face) },
                        showPowerButton = powerMenuButtonEnabled,
                        onPowerClick = openPowerDialogWithAccessibility
                    )
                }
            }

            if (notificationsEnabled && keepNotificationLayer) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(5f)
                        .graphicsLayer {
                            alpha = notificationProgress
                            translationY = (1f - notificationProgress) * screenHeightPx
                        }
                ) {
                    NotificationLayer(
                        isActive = notificationSceneActive || notificationGestureActive,
                        transitionProgress = notificationProgress,
                        notificationGroups = notificationGroups,
                        notificationAccessGranted = notificationAccessGranted,
                        revealedNotificationTarget = revealedNotificationTarget,
                        onRevealTargetChange = vm::setRevealedNotificationTarget,
                        onDismissToStack = closeNotificationsToOrigin,
                        onTransitionProgressChange = updateNotificationTransitionProgress,
                        onTransitionRelease = { dismiss ->
                            if (dismiss) {
                                closeNotificationsToOrigin()
                            } else {
                                settleNotificationTransition(1f)
                            }
                        },
                        onToggleGroup = vm::toggleNotificationGroup,
                        onDismissGroup = vm::dismissNotificationGroup,
                        onDismissNotification = vm::dismissNotification,
                        onDismissAllNotifications = vm::dismissAllNotifications,
                        onRunNotificationAction = vm::runNotificationAction,
                        onOpenNotification = { key, origin ->
                            vm.openNotification(
                                key,
                                origin,
                                notificationReturnState,
                                BASE_LAUNCH_MASK_DELAY_MS + if (splashIcon) splashDelay.toLong() else 0L
                            )
                        },
                        stackCardColor = notificationStackTint
                    )
                }
            }
        }
    }
}

@Composable
private fun LaunchBackdropContent(
    showSplash: Boolean,
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    uiStyle: UiStyle,
    backgroundColor: Color
) {
    val launcherStyle = LauncherTheme.style
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = showSplash && icon != null,
            enter = fadeIn() + scaleIn(initialScale = if (uiStyle == UiStyle.MATERIAL_3) 0.82f else 0.5f),
            exit = fadeOut() + scaleOut(targetScale = if (uiStyle == UiStyle.MATERIAL_3) 0.88f else 0.3f)
        ) {
            icon?.let { launchIcon ->
                Image(
                    bitmap = launchIcon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(if (uiStyle == UiStyle.MATERIAL_3) launcherStyle.compactShape else CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}
