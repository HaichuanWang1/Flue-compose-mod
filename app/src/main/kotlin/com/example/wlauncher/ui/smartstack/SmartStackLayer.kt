package com.flue.launcher.ui.smartstack

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Outline
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.BatteryManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.data.model.WidgetInfo
import com.flue.launcher.data.model.iconForDisplay
import com.flue.launcher.FlueApplication
import com.flue.launcher.data.repository.WidgetRepository
import com.flue.launcher.ui.common.instantPressGesture
import com.flue.launcher.ui.common.rememberPressedState
import com.flue.launcher.ui.common.WatchBatteryPill
import com.flue.launcher.ui.controlcenter.CompactControlCenterMusicCard
import com.flue.launcher.ui.controlcenter.MusicTextSwitchAnimations
import com.flue.launcher.ui.drawer.AppBubble
import com.flue.launcher.ui.drawer.AppShortcutOverlay
import com.flue.launcher.ui.drawer.vibrateHaptic
import com.flue.launcher.ui.notification.NotificationEntryUi
import com.flue.launcher.ui.notification.NotificationGroupUi
import com.flue.launcher.ui.notification.NotificationRevealTarget
import com.flue.launcher.ui.notification.SwipeRevealDeleteContainer
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.WatchColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

private const val SIDE_SCREEN_CONTENT_WIDTH_RATIO = 0.90f
private const val SIDE_SCREEN_DISMISS_THRESHOLD = 86f
private const val SIDE_SCREEN_NOTIFICATION_DRAG_RANGE_RATIO = 0.78f
private const val SIDE_SCREEN_NOTIFICATION_DRAG_RANGE_MIN = 300f
private const val SIDE_SCREEN_NOTIFICATION_RELEASE_PROGRESS = 0.6f
private const val SIDE_SCREEN_NOTIFICATION_FLING_VELOCITY = 1350f
private const val STACKED_PREVIEW_TRANSLATION_DP = 14
private const val STACKED_PREVIEW_HORIZONTAL_INSET_DP = 5
private const val SIDE_SCREEN_APP_WIDGET_HOST_ID = 1024
private const val SIDE_SCREEN_SHORTCUT_MENU_TRIGGER_MS = 410L
private const val SIDE_SCREEN_OVERSCROLL_LIMIT = 140f
private const val SIDE_SCREEN_OVERSCROLL_RESISTANCE = 0.35f
private const val WIDGET_CARD_MIN_HEIGHT_DP = 64
private const val WIDGET_CARD_MAX_HEIGHT_DP = 360

private sealed interface SideScreenModalState {
    data object None : SideScreenModalState
    data class ShortcutPicker(val slotIndex: Int) : SideScreenModalState
    data class ShortcutActions(val slotIndex: Int, val app: AppInfo) : SideScreenModalState
    data class RemoveShortcut(val slotIndex: Int) : SideScreenModalState
}

private data class SideScreenClockSnapshot(val time: String, val date: String)
private data class BatterySnapshot(val level: Int = 0, val charging: Boolean = false)
private data class StepSnapshot(val steps: Int? = null)
private data class ShortcutPickerItem(
    val componentKey: String,
    val label: String,
    val packageName: String,
    val icon: ImageBitmap,
    val source: AppInfo
)

@Composable
private fun rememberPickerOverscrollConnection(
    listState: androidx.compose.foundation.lazy.LazyListState
): Pair<Float, NestedScrollConnection> {
    var overscroll by remember { mutableFloatStateOf(0f) }
    val rebound = remember { Animatable(0f) }
    val connection = remember(listState, rebound) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val layoutInfo = listState.layoutInfo
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                val atBottom = layoutInfo.totalItemsCount > 0 &&
                    lastVisible != null &&
                    lastVisible.index >= layoutInfo.totalItemsCount - 1 &&
                    lastVisible.offset + lastVisible.size <= layoutInfo.viewportEndOffset
                val next = when {
                    available.y > 0f && atTop -> (overscroll + available.y * 0.35f).coerceAtMost(112f)
                    available.y < 0f && atBottom -> (overscroll + available.y * 0.35f).coerceAtLeast(-112f)
                    overscroll > 0f && available.y < 0f -> (overscroll + available.y).coerceAtLeast(0f)
                    overscroll < 0f && available.y > 0f -> (overscroll + available.y).coerceAtMost(0f)
                    else -> overscroll
                }
                if (next != overscroll) {
                    overscroll = next
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                val next = when {
                    overscroll > 0f && available.y < 0f -> (overscroll + available.y).coerceAtLeast(0f)
                    overscroll < 0f && available.y > 0f -> (overscroll + available.y).coerceAtMost(0f)
                    else -> overscroll
                }
                if (next != overscroll) {
                    overscroll = next
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscroll != 0f) {
                    rebound.snapTo(overscroll)
                    rebound.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f)) {
                        overscroll = value
                    }
                    overscroll = 0f
                    return available
                }
                return Velocity.Zero
            }
        }
    }
    return overscroll to connection
}

private data class SideScreenWidgetCardItem(
    val widgetIndex: Int,
    val widget: WidgetInfo,
    val layout: WidgetSlotLayout,
    val keyOverride: String? = null
)

private sealed interface WidgetLayoutSection {
    val cards: List<SideScreenWidgetCardItem>
    val stableKey: String

    data class FullWidth(val card: SideScreenWidgetCardItem) : WidgetLayoutSection {
        override val cards: List<SideScreenWidgetCardItem> = listOf(card)
        override val stableKey: String = "full:${card.stableKey()}"
    }

    data class Columns(
        val left: List<SideScreenWidgetCardItem>,
        val right: List<SideScreenWidgetCardItem>
    ) : WidgetLayoutSection {
        override val cards: List<SideScreenWidgetCardItem> = left + right
        override val stableKey: String =
            "cols:${cards.joinToString(separator = "|") { it.stableKey() }}"
    }
}

private data class WidgetSlotLayout(
    val spanColumns: Int = 2,
    val heightDp: Int? = null
)

private data class ActiveWidgetResize(
    val cardKey: String,
    val startLayout: WidgetSlotLayout,
    val resizeMode: WidgetResizeMode,
    val singleColumnWidthDp: Float,
    val delta: Offset = Offset.Zero
)

private data class WidgetDragPreview(
    val card: SideScreenWidgetCardItem,
    val bounds: Rect,
    val offset: Offset
)

private enum class WidgetResizeMode {
    Horizontal,
    Vertical,
    Both
}

private data class WidgetHostTag(
    val bindTag: String,
    val hostView: AppWidgetHostView
)

private data class PendingWidgetSelection(
    val slotIndex: Int,
    val widget: WidgetInfo,
    val appWidgetId: Int
)

private fun SideScreenWidgetCardItem.stableKey(): String {
    keyOverride?.let { return it }
    return "${baseStableKey()}@slot_$widgetIndex"
}

private fun SideScreenWidgetCardItem.baseStableKey(): String {
    return if (widget.widgetId != -1) {
        "widget_${widget.widgetId}"
    } else {
        "widget_${widget.widgetKey}"
    }
}

private fun parseWidgetSlotLayout(
    raw: String?,
    widget: WidgetInfo,
    widgetRepository: WidgetRepository
): WidgetSlotLayout {
    val defaultLayout = defaultWidgetSlotLayout(widget, widgetRepository)
    val metadata = raw?.substringAfter('#', missingDelimiterValue = "").orEmpty()
    if (metadata.isBlank()) return defaultLayout
    val values = metadata
        .split(';')
        .mapNotNull { part ->
            val key = part.substringBefore('=').trim()
            val value = part.substringAfter('=', missingDelimiterValue = "").trim()
            if (key.isBlank() || value.isBlank()) null else key to value
        }
        .toMap()
    val span = (values["s"] ?: values["span"])
        ?.toIntOrNull()
        ?.coerceIn(1, 2)
        ?: defaultLayout.spanColumns
    val height = (values["h"] ?: values["height"])
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?.coerceIn(WIDGET_CARD_MIN_HEIGHT_DP, WIDGET_CARD_MAX_HEIGHT_DP)
    return WidgetSlotLayout(spanColumns = span, heightDp = height)
}

private fun defaultWidgetSlotLayout(
    widget: WidgetInfo,
    widgetRepository: WidgetRepository
): WidgetSlotLayout {
    val providerInfo = widgetRepository.findProviderInfo(widget.widgetKey)
    val minWidth = providerInfo?.minWidth?.takeIf { it > 0 } ?: 0
    val minHeight = providerInfo?.minHeight?.takeIf { it > 0 } ?: widget.minHeightDp
    val aspect = if (minWidth > 0 && minHeight > 0) minWidth.toFloat() / minHeight.toFloat() else 2f
    val span = if (aspect in 0.75f..1.18f) 1 else 2
    return WidgetSlotLayout(spanColumns = span)
}

private fun serializeWidgetSlotValue(baseValue: String, layout: WidgetSlotLayout): String {
    val height = layout.heightDp ?: 0
    return "$baseValue#s=${layout.spanColumns.coerceIn(1, 2)};h=$height"
}

private fun resizeWidgetSlotLayout(
    startLayout: WidgetSlotLayout,
    delta: Offset,
    density: androidx.compose.ui.unit.Density,
    resizeMode: WidgetResizeMode = WidgetResizeMode.Both,
    singleColumnWidthDp: Float? = null
): WidgetSlotLayout {
    val horizontalThresholdPx = with(density) { 34.dp.toPx() }
    val verticalThresholdPx = with(density) { 14.dp.toPx() }
    val horizontalResize = resizeMode != WidgetResizeMode.Vertical && abs(delta.x) > horizontalThresholdPx
    val verticalResize = resizeMode != WidgetResizeMode.Horizontal && abs(delta.y) > verticalThresholdPx
    val nextSpan = if (horizontalResize) {
        if (startLayout.spanColumns == 1) 2 else 1
    } else {
        startLayout.spanColumns
    }
    val defaultHeight = startLayout.heightDp ?: if (nextSpan == 1) 156 else 170
    val startHeight = startLayout.heightDp ?: defaultHeight
    val nextHeight = if (verticalResize) {
        val rawHeight = startHeight + with(density) { delta.y.toDp().value }
        ((rawHeight / 8f).roundToInt() * 8).coerceIn(
            WIDGET_CARD_MIN_HEIGHT_DP,
            WIDGET_CARD_MAX_HEIGHT_DP
        )
    } else {
        startLayout.heightDp ?: defaultHeight
    }
    return WidgetSlotLayout(spanColumns = nextSpan, heightDp = nextHeight)
}

private fun groupWidgetCardsIntoSections(widgetCards: List<SideScreenWidgetCardItem>): List<WidgetLayoutSection> {
    if (widgetCards.isEmpty()) return emptyList()
    val sections = mutableListOf<WidgetLayoutSection>()
    val leftColumn = mutableListOf<SideScreenWidgetCardItem>()
    val rightColumn = mutableListOf<SideScreenWidgetCardItem>()
    var leftHeight = 0
    var rightHeight = 0

    fun flushColumns() {
        if (leftColumn.isEmpty() && rightColumn.isEmpty()) return
        sections += WidgetLayoutSection.Columns(
            left = leftColumn.toList(),
            right = rightColumn.toList()
        )
        leftColumn.clear()
        rightColumn.clear()
        leftHeight = 0
        rightHeight = 0
    }

    widgetCards.forEach { card ->
        if (card.layout.spanColumns >= 2) {
            flushColumns()
            sections += WidgetLayoutSection.FullWidth(card)
        } else if (leftHeight <= rightHeight) {
            leftColumn += card
            leftHeight += card.estimatedHeightDp()
        } else {
            rightColumn += card
            rightHeight += card.estimatedHeightDp()
        }
    }
    flushColumns()
    return sections
}

private fun SideScreenWidgetCardItem.estimatedHeightDp(): Int {
    return (layout.heightDp ?: if (layout.spanColumns == 1) 156 else 170)
        .coerceIn(WIDGET_CARD_MIN_HEIGHT_DP, WIDGET_CARD_MAX_HEIGHT_DP)
}

private fun widgetInsertionIndexFor(
    point: Offset,
    widgetCards: List<SideScreenWidgetCardItem>,
    boundsByKey: Map<String, Rect>
): Int? {
    val targets = widgetCards.mapNotNull { card ->
        boundsByKey[card.stableKey()]?.let { bounds -> card to bounds }
    }
    if (targets.isEmpty()) return null
    val columnTargets = targets.filter { (_, bounds) ->
        val horizontalSlop = bounds.width * 0.35f
        point.x in (bounds.left - horizontalSlop)..(bounds.right + horizontalSlop)
    }
    val relevantTargets = (columnTargets.ifEmpty { targets })
        .sortedWith(compareBy<Pair<SideScreenWidgetCardItem, Rect>> { it.second.top }.thenBy { it.second.left })
    val beforeTarget = relevantTargets.firstOrNull { (_, bounds) -> point.y < bounds.center.y }
    if (beforeTarget != null) return beforeTarget.first.widgetIndex
    val afterTarget = relevantTargets.last()
    val maxInsertIndex = widgetCards.maxOf { it.widgetIndex } + 1
    return (afterTarget.first.widgetIndex + 1).coerceIn(0, maxInsertIndex)
}

private fun widgetResizeModeAt(
    position: Offset,
    widthPx: Float,
    heightPx: Float,
    hotspotPx: Float
): WidgetResizeMode? {
    val horizontal = position.x <= hotspotPx || position.x >= widthPx - hotspotPx
    val vertical = position.y <= hotspotPx || position.y >= heightPx - hotspotPx
    return when {
        horizontal && vertical -> WidgetResizeMode.Both
        horizontal -> WidgetResizeMode.Horizontal
        vertical -> WidgetResizeMode.Vertical
        else -> null
    }
}

private fun <T> MutableList<T>.moveItem(fromIndex: Int, toIndex: Int) {
    if (fromIndex == toIndex || fromIndex !in indices || toIndex !in indices) return
    val item = removeAt(fromIndex)
    add(toIndex, item)
}

private data class WidgetPickerApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val widgets: List<WidgetInfo>
)
private sealed interface PreviewRow {
    data class Group(val group: NotificationGroupUi, val entries: List<NotificationEntryUi>, val hiddenCount: Int) : PreviewRow
    data class Aggregate(val leadEntry: NotificationEntryUi, val hiddenCount: Int) : PreviewRow
}

private sealed interface PressHoldResult {
    data class LongPress(val change: PointerInputChange) : PressHoldResult
    data object Released : PressHoldResult
    data object Cancelled : PressHoldResult
}

@Composable
fun SmartStackLayer(
    apps: List<AppInfo>,
    sideScreenShortcuts: List<String?>,
    previewGroups: List<NotificationGroupUi>,
    showMusicCard: Boolean = false,
    showMediaCustomActions: Boolean = true,
    musicTextSwitchAnimation: String = MusicTextSwitchAnimations.DEFAULT_ID,
    leftSafeInsetPercent: Int,
    notificationsEnabled: Boolean,
    notificationAccessGranted: Boolean,
    notificationsSceneActive: Boolean,
    notificationTransitionProgress: Float,
    revealedNotificationTarget: NotificationRevealTarget?,
    onRevealTargetChange: (NotificationRevealTarget?) -> Unit,
    onOpenNotifications: () -> Unit,
    onNotificationTransitionProgressChange: (Float) -> Unit,
    onNotificationTransitionRelease: (Boolean) -> Unit,
    onOpenNotification: (String, Offset) -> Unit,
    onLaunchApp: (AppInfo, Offset) -> Unit,
    onSetShortcut: (Int, String?) -> Unit,
    onRemoveShortcut: (Int) -> Unit,
    onSwapShortcut: (Int, Int) -> Unit,
    onSetShowNotification: (Boolean) -> Unit,
    onDismissGroup: (String) -> Unit,
    onDismissNotification: (String) -> Unit,
    onDismissToFace: () -> Unit,
    showSteps: Boolean,
    stackCardColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val clock = rememberClockSnapshot()
    val battery = rememberBatterySnapshot()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val contentScrollState = rememberScrollState()
    val stepPermissionGranted = rememberActivityRecognitionPermission(showSteps)
    val stepSnapshot = rememberStepSnapshot(showSteps, stepPermissionGranted)
    val slotCenters = remember { mutableStateMapOf<Int, Offset>() }
    val showNotificationPreview = notificationsEnabled && !showMusicCard
    val canOpenNotificationsFromSide = showNotificationPreview
    val hasSecondaryComponent = showNotificationPreview || showMusicCard
    val visibleShortcutCount = if (hasSecondaryComponent) 6 else 9
    val context = LocalContext.current

    val shortcutItems = remember(apps, sideScreenShortcuts, visibleShortcutCount) {
        List(visibleShortcutCount) { i ->
            val appKey = sideScreenShortcuts.getOrNull(i)
            appKey?.let { key -> apps.firstOrNull { it.componentKey == key } }
        }
    }
    val previewRows = remember(previewGroups, showNotificationPreview) {
        if (showNotificationPreview) buildPreviewRows(previewGroups, maxRows = 1) else emptyList()
    }
    val notificationDragRangePx = remember(configuration.screenHeightDp, density) {
        maxOf(
            with(density) { configuration.screenHeightDp.dp.toPx() } * SIDE_SCREEN_NOTIFICATION_DRAG_RANGE_RATIO,
            SIDE_SCREEN_NOTIFICATION_DRAG_RANGE_MIN
        )
    }
    val notificationReleaseThresholdPx = notificationDragRangePx * SIDE_SCREEN_NOTIFICATION_RELEASE_PROGRESS
    var modalState by remember { mutableStateOf<SideScreenModalState>(SideScreenModalState.None) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var dragVelocityY by remember { mutableFloatStateOf(0f) }
    var lastDragEventUptime by remember { mutableStateOf(0L) }
    var transitionInFlight by remember { mutableStateOf(false) }
    val sideOverscroll = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val contentTranslationY = if (notificationsSceneActive || transitionInFlight) 0f else sideOverscroll.value

    fun releaseSideOverscroll() {
        scope.launch {
            if (sideOverscroll.value != 0f) {
                sideOverscroll.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
            }
        }
    }

    fun applySideOverscroll(delta: Float) {
        val next = when {
            sideOverscroll.value > 0f && delta < 0f -> (sideOverscroll.value + delta).coerceAtLeast(0f)
            sideOverscroll.value < 0f && delta > 0f -> (sideOverscroll.value + delta).coerceAtMost(0f)
            delta > 0f -> (sideOverscroll.value + delta * SIDE_SCREEN_OVERSCROLL_RESISTANCE)
                .coerceIn(0f, SIDE_SCREEN_OVERSCROLL_LIMIT)
            delta < 0f -> (sideOverscroll.value + delta * SIDE_SCREEN_OVERSCROLL_RESISTANCE)
                .coerceIn(-SIDE_SCREEN_OVERSCROLL_LIMIT, 0f)
            else -> sideOverscroll.value
        }
        scope.launch {
            sideOverscroll.stop()
            sideOverscroll.snapTo(next)
        }
    }

    LaunchedEffect(notificationsSceneActive) {
        sideOverscroll.snapTo(0f)
        dragDx = 0f
        dragDy = 0f
        dragVelocityY = 0f
        lastDragEventUptime = 0L
        transitionInFlight = false
    }
    val launcherStyle = LauncherTheme.style

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(40.dp))
            .background(launcherStyle.screenBackground)
            .pointerInput(notificationAccessGranted, canOpenNotificationsFromSide, modalState, notificationsSceneActive, transitionInFlight) {
                if (modalState != SideScreenModalState.None || notificationsSceneActive || transitionInFlight) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        dragDx = 0f
                        dragDy = 0f
                        dragVelocityY = 0f
                        lastDragEventUptime = 0L
                    },
                    onDrag = { change, dragAmount ->
                        val listAtTop = true
                        val listAtBottom = true
                        if (lastDragEventUptime != 0L) {
                            val deltaMs = (change.uptimeMillis - lastDragEventUptime).coerceAtLeast(1L)
                            val instantVelocityY = dragAmount.y / deltaMs * 1000f
                            dragVelocityY = if (dragVelocityY == 0f) {
                                instantVelocityY
                            } else {
                                dragVelocityY * 0.35f + instantVelocityY * 0.65f
                            }
                        }
                        lastDragEventUptime = change.uptimeMillis
                        dragDx += dragAmount.x
                        dragDy += dragAmount.y
                        if (abs(dragDx) > abs(dragDy)) {
                            if (dragDx < 0f) change.consume()
                        } else if (sideOverscroll.value != 0f) {
                            change.consume()
                            applySideOverscroll(dragAmount.y)
                        } else if (!canOpenNotificationsFromSide && ((dragAmount.y > 0f && listAtTop) || (dragAmount.y < 0f && listAtBottom))) {
                            change.consume()
                            applySideOverscroll(dragAmount.y)
                        } else if (canOpenNotificationsFromSide && (dragDy < 0f || notificationTransitionProgress > 0f)) {
                            change.consume()
                            onNotificationTransitionProgressChange(
                                (-dragDy / notificationDragRangePx).coerceIn(0f, 1f)
                            )
                        } else if (
                            canOpenNotificationsFromSide &&
                            dragDy > 0f &&
                            listAtTop
                        ) {
                            change.consume()
                            applySideOverscroll(dragAmount.y)
                        }
                    },
                    onDragEnd = {
                        val verticalIntent = abs(dragDy) > abs(dragDx) ||
                            abs(dragVelocityY) > SIDE_SCREEN_NOTIFICATION_FLING_VELOCITY
                        val dismissToFace = dragDx < -SIDE_SCREEN_DISMISS_THRESHOLD && abs(dragDx) > abs(dragDy)
                        val openNotifications = canOpenNotificationsFromSide &&
                            verticalIntent &&
                            (
                                dragDy < -notificationReleaseThresholdPx ||
                                    notificationTransitionProgress > SIDE_SCREEN_NOTIFICATION_RELEASE_PROGRESS ||
                                    dragVelocityY < -SIDE_SCREEN_NOTIFICATION_FLING_VELOCITY
                                )

                        when {
                            dismissToFace -> {
                                dragDx = 0f
                                dragDy = 0f
                                dragVelocityY = 0f
                                lastDragEventUptime = 0L
                                onNotificationTransitionRelease(false)
                                onRevealTargetChange(null)
                                releaseSideOverscroll()
                                onDismissToFace()
                            }
                            openNotifications -> {
                                dragDx = 0f
                                dragDy = 0f
                                dragVelocityY = 0f
                                lastDragEventUptime = 0L
                                onRevealTargetChange(null)
                                releaseSideOverscroll()
                                onNotificationTransitionRelease(true)
                            }
                            else -> {
                                dragDx = 0f
                                dragDy = 0f
                                dragVelocityY = 0f
                                lastDragEventUptime = 0L
                                releaseSideOverscroll()
                                onNotificationTransitionRelease(false)
                            }
                        }
                    },
                    onDragCancel = {
                        dragDx = 0f
                        dragDy = 0f
                        dragVelocityY = 0f
                        lastDragEventUptime = 0L
                        releaseSideOverscroll()
                        onNotificationTransitionRelease(false)
                    }
                )
            }
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val leftSafeInset = maxWidth * (leftSafeInsetPercent.coerceIn(0, 50) / 100f)
        val availableWidth = maxWidth - leftSafeInset
        val contentWidth = availableWidth * SIDE_SCREEN_CONTENT_WIDTH_RATIO
        val quickHeight = when {
            showMusicCard -> (contentWidth * 0.64f).coerceAtLeast(168.dp)
            hasSecondaryComponent -> (contentWidth * 0.64f).coerceAtLeast(168.dp)
            else -> (contentWidth * 0.88f).coerceAtLeast(226.dp)
        }
        val verticalScrollEnabled = false
        val bottomSafePadding = 18.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = contentTranslationY }
                .padding(start = leftSafeInset)
                .padding(top = 10.dp, bottom = bottomSafePadding)
                .then(
                    if (verticalScrollEnabled) {
                        Modifier.verticalScroll(contentScrollState)
                    } else {
                        Modifier
                    }
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                clock.time,
                color = launcherStyle.titleColor,
                fontSize = 31.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                clock.date,
                color = WatchColors.TextSecondary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            QuickPanel(
                width = contentWidth,
                height = quickHeight,
                items = shortcutItems,
                slotCenters = slotCenters,
                onAdd = { onRevealTargetChange(null); modalState = SideScreenModalState.ShortcutPicker(it) },
                onOpenActions = { slot, app ->
                    onRevealTargetChange(null)
                    modalState = SideScreenModalState.ShortcutActions(slot, app)
                },
                onCloseActions = {
                    if (modalState is SideScreenModalState.ShortcutActions) {
                        modalState = SideScreenModalState.None
                    }
                },
                onSwap = onSwapShortcut,
                onClickApp = { slot, app ->
                    val center = slotCenters[slot] ?: Offset(widthPx / 2f, heightPx / 2f)
                    onRevealTargetChange(null)
                    onLaunchApp(app, Offset(center.x / widthPx, center.y / heightPx))
                }
            )
            if (showMusicCard) {
                Spacer(Modifier.height(16.dp))
                CompactControlCenterMusicCard(
                    notificationAccessGranted = notificationAccessGranted,
                    musicTextSwitchAnimation = musicTextSwitchAnimation,
                    showMediaCustomActions = showMediaCustomActions,
                    modifier = Modifier.width(contentWidth)
                )
                Spacer(Modifier.height(20.dp))
            }
            if (showNotificationPreview) {
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier
                        .width(contentWidth)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when {
                        !notificationAccessGranted -> PreviewInfoCard("开启通知访问", "副一屏上滑进入通知中心后完成授权", onOpenNotifications)
                        previewGroups.isEmpty() -> PreviewInfoCard("暂无通知", "副一屏上滑可打开通知中心", onOpenNotifications)
                        else -> previewRows.forEach { row ->
                            when (row) {
                                is PreviewRow.Aggregate -> StackedPreviewCard(
                                    row.leadEntry,
                                    row.hiddenCount,
                                    stackCardColor,
                                    onOpenNotifications
                                )
                                is PreviewRow.Group -> {
                                    val entry = row.entries.firstOrNull()
                                    if (entry != null) {
                                        SwipeRevealDeleteContainer(
                                            target = if (row.hiddenCount > 0) {
                                                NotificationRevealTarget.Group(row.group.packageName)
                                            } else {
                                                NotificationRevealTarget.Entry(entry.key)
                                            },
                                            revealedTarget = revealedNotificationTarget,
                                            onRevealTargetChange = onRevealTargetChange,
                                            enabled = if (row.hiddenCount > 0) {
                                                row.group.entries.any(NotificationEntryUi::isClearable)
                                            } else {
                                                entry.isClearable
                                            },
                                            onDelete = {
                                                if (row.hiddenCount > 0) {
                                                    onDismissGroup(row.group.packageName)
                                                } else {
                                                    onDismissNotification(entry.key)
                                                }
                                            },
                                            actionHeight = 72.dp
                                        ) {
                                            if (row.hiddenCount > 0) {
                                                StackedPreviewCard(entry, row.hiddenCount, stackCardColor, onOpenNotifications)
                                            } else {
                                                PreviewPill(
                                                    entry = entry,
                                                    onClick = onOpenNotifications
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(52.dp))
            }
        }
        WatchBatteryAndStepsPill(
            level = battery.level,
            charging = battery.charging,
            steps = stepSnapshot.steps,
            showSteps = showSteps,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
        )
    }

    when (val state = modalState) {
        is SideScreenModalState.ShortcutPicker -> ShortcutPickerOverlay(
            apps = apps,
            onSelect = { onSetShortcut(state.slotIndex, it.componentKey) },
            onDismiss = { modalState = SideScreenModalState.None }
        )
        is SideScreenModalState.ShortcutActions -> AppShortcutOverlay(
            app = state.app,
            onExcludeApp = null,
            onRemoveShortcut = { onRemoveShortcut(state.slotIndex) },
            onDismiss = { modalState = SideScreenModalState.None }
        )
        is SideScreenModalState.RemoveShortcut -> {
            val app = shortcutItems.getOrNull(state.slotIndex)
            if (app != null) {
                RemoveOverlay(
                    app = app,
                    widget = null,
                    onRemove = { onRemoveShortcut(state.slotIndex) },
                    onDismiss = { modalState = SideScreenModalState.None }
                )
            } else {
                LaunchedEffect(state) { modalState = SideScreenModalState.None }
            }
        }
        SideScreenModalState.None -> Unit
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WidgetPageLayer(
    apps: List<AppInfo>,
    sideScreenWidgetSlots: List<String?>,
    leftSafeInsetPercent: Int,
    onSetWidget: (Int, String?) -> Unit,
    onSwapWidget: (Int, Int) -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onEditModeChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val density = LocalDensity.current
    val launcherStyle = LauncherTheme.style
    val widgetRepository = remember(context) { FlueApplication.repositories(context).widgetRepository }
    val appWidgetManager = remember(context) { AppWidgetManager.getInstance(context) }
    val appWidgetHost = remember(context) { AppWidgetHost(context, SIDE_SCREEN_APP_WIDGET_HOST_ID) }
    val listState = rememberLazyListState()
    val overscroll = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val widgetOverscrollLimitPx = 150f
    val widgetCards = remember(sideScreenWidgetSlots, widgetRepository) {
        val parsedCards = sideScreenWidgetSlots.mapIndexedNotNull { index, raw ->
            widgetRepository.parseSlotValue(raw)?.let { widget ->
                SideScreenWidgetCardItem(
                    widgetIndex = index,
                    widget = widget,
                    layout = parseWidgetSlotLayout(raw, widget, widgetRepository)
                )
            }
        }
        val duplicateBaseKeys = parsedCards
            .groupingBy { it.baseStableKey() }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        parsedCards.map { card ->
            if (card.baseStableKey() in duplicateBaseKeys) {
                card.copy(keyOverride = "${card.baseStableKey()}_${card.widgetIndex}")
            } else {
                card
            }
        }
    }
    val widgetSections = remember(widgetCards) { groupWidgetCardsIntoSections(widgetCards) }
    val widgetCardCenters = remember { mutableStateMapOf<String, Offset>() }
    val widgetCardBounds = remember { mutableStateMapOf<String, Rect>() }
    var editMode by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var pendingWidgetSelection by remember { mutableStateOf<PendingWidgetSelection?>(null) }
    var draggingWidgetKey by remember { mutableStateOf<String?>(null) }
    var draggingWidgetOffset by remember { mutableStateOf(Offset.Zero) }
    var activeWidgetResize by remember { mutableStateOf<ActiveWidgetResize?>(null) }
    var editGestureLocked by remember { mutableStateOf(false) }
    var widgetDragAutoScrollPx by remember { mutableFloatStateOf(0f) }
    var pendingWidgetDropIndex by remember { mutableStateOf<Int?>(null) }
    var widgetPageRootOffset by remember { mutableStateOf(Offset.Zero) }
    val removingWidgetKeys = remember { mutableStateListOf<String>() }
    val enteringWidgetKeys = remember { mutableStateListOf<String>() }
    var previousWidgetKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    val widgetBorderHotspotPx = with(density) { 26.dp.toPx() }
    val widgetResizeLongPressMs = 420L
    val autoScrollEdgePx = with(density) { 56.dp.toPx() }
    val autoScrollMaxVelocityPx = with(density) { 1100.dp.toPx() }

    LaunchedEffect(editMode, showPicker) {
        onEditModeChange(editMode && !showPicker)
    }

    DisposableEffect(Unit) {
        onDispose { onEditModeChange(false) }
    }

    fun clearAllocatedWidget(slotIndex: Int) {
        widgetRepository.extractWidgetId(sideScreenWidgetSlots.getOrNull(slotIndex))?.let { widgetId ->
            runCatching { appWidgetHost.deleteAppWidgetId(widgetId) }
        }
    }

    fun persistBoundWidget(slotIndex: Int, widget: WidgetInfo, appWidgetId: Int) {
        clearAllocatedWidget(slotIndex)
        val boundWidget = widget.copy(widgetId = appWidgetId)
        onSetWidget(
            slotIndex,
            serializeWidgetSlotValue(
                baseValue = widgetRepository.serializeSlotValue(boundWidget),
                layout = defaultWidgetSlotLayout(boundWidget, widgetRepository)
            )
        )
    }

    fun persistWidgetLayout(card: SideScreenWidgetCardItem, layout: WidgetSlotLayout) {
        val baseValue = sideScreenWidgetSlots.getOrNull(card.widgetIndex)
            ?.substringBefore('#')
            ?: widgetRepository.serializeSlotValue(card.widget)
        onSetWidget(card.widgetIndex, serializeWidgetSlotValue(baseValue, layout))
    }

    fun discardPendingWidgetSelection() {
        pendingWidgetSelection?.let { selection ->
            runCatching { appWidgetHost.deleteAppWidgetId(selection.appWidgetId) }
        }
        pendingWidgetSelection = null
    }

    fun continueWidgetBinding(
        selection: PendingWidgetSelection,
        configureLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    ) {
        val providerInfo = appWidgetManager.getAppWidgetInfo(selection.appWidgetId)
            ?: widgetRepository.findProviderInfo(selection.widget.widgetKey)
        if (providerInfo?.configure != null) {
            val configureIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = providerInfo.configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, selection.appWidgetId)
            }
            configureLauncher.launch(configureIntent)
        } else {
            persistBoundWidget(selection.slotIndex, selection.widget, selection.appWidgetId)
            pendingWidgetSelection = null
        }
    }

    val configureWidgetLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        val selection = pendingWidgetSelection ?: return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            persistBoundWidget(selection.slotIndex, selection.widget, selection.appWidgetId)
        } else {
            runCatching { appWidgetHost.deleteAppWidgetId(selection.appWidgetId) }
        }
        pendingWidgetSelection = null
    }
    val bindWidgetLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        val selection = pendingWidgetSelection ?: return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            continueWidgetBinding(selection, configureWidgetLauncher)
        } else {
            runCatching { appWidgetHost.deleteAppWidgetId(selection.appWidgetId) }
            pendingWidgetSelection = null
        }
    }

    DisposableEffect(appWidgetHost, widgetCards.isNotEmpty()) {
        if (widgetCards.isNotEmpty()) {
            runCatching { appWidgetHost.startListening() }
        }
        onDispose {
            discardPendingWidgetSelection()
            if (widgetCards.isNotEmpty()) {
                runCatching { appWidgetHost.stopListening() }
            }
        }
    }

    val nestedScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (draggingWidgetKey != null || activeWidgetResize != null) {
                    return available
                }
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                val atBottom = lastVisible != null &&
                    lastVisible.index >= listState.layoutInfo.totalItemsCount - 1 &&
                    lastVisible.offset + lastVisible.size <= listState.layoutInfo.viewportEndOffset
                when {
                    available.y > 0f && atTop -> {
                        scope.launch {
                            overscroll.stop()
                            overscroll.snapTo((overscroll.value + available.y * 0.35f).coerceAtMost(widgetOverscrollLimitPx))
                        }
                        return Offset(0f, available.y)
                    }
                    available.y < 0f && atBottom -> {
                        scope.launch {
                            overscroll.stop()
                            overscroll.snapTo((overscroll.value + available.y * 0.35f).coerceAtLeast(-widgetOverscrollLimitPx))
                        }
                        return Offset(0f, available.y)
                    }
                    overscroll.value > 0f && available.y < 0f -> {
                        scope.launch {
                            overscroll.stop()
                            overscroll.snapTo((overscroll.value + available.y).coerceAtLeast(0f))
                        }
                        return Offset(0f, available.y)
                    }
                    overscroll.value < 0f && available.y > 0f -> {
                        scope.launch {
                            overscroll.stop()
                            overscroll.snapTo((overscroll.value + available.y).coerceAtMost(0f))
                        }
                        return Offset(0f, available.y)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscroll.value != 0f) {
                    overscroll.stop()
                    overscroll.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(widgetCards) {
        if (widgetCards.isEmpty()) {
            editMode = false
        }
        val activeKeys = widgetCards.map { it.stableKey() }.toSet()
        val addedKeys = activeKeys - previousWidgetKeys
        widgetCardCenters.keys.retainAll(activeKeys)
        widgetCardBounds.keys.retainAll(activeKeys)
        removingWidgetKeys.removeAll { key -> key !in activeKeys }
        if (addedKeys.isNotEmpty()) {
            enteringWidgetKeys += addedKeys
            yield()
            enteringWidgetKeys.removeAll(addedKeys)
        }
        previousWidgetKeys = activeKeys
    }

    LaunchedEffect(editMode, showPicker) {
        editGestureLocked = editMode && !showPicker
        if (!editMode || showPicker) {
            draggingWidgetKey = null
            draggingWidgetOffset = Offset.Zero
            activeWidgetResize = null
            widgetDragAutoScrollPx = 0f
            pendingWidgetDropIndex = null
            editGestureLocked = false
        }
    }

    LaunchedEffect(draggingWidgetKey, widgetDragAutoScrollPx) {
        var previousFrameNanos = 0L
        while (draggingWidgetKey != null && widgetDragAutoScrollPx != 0f && isActive) {
            var scrollDelta = 0f
            withFrameNanos { frameTimeNanos ->
                val frameDeltaSeconds = if (previousFrameNanos == 0L) {
                    1f / 60f
                } else {
                    ((frameTimeNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(1f / 144f, 0.05f)
                }
                previousFrameNanos = frameTimeNanos
                scrollDelta = widgetDragAutoScrollPx * frameDeltaSeconds
            }
            val consumed = listState.scrollBy(scrollDelta)
            if (consumed != 0f) {
                draggingWidgetOffset = draggingWidgetOffset.copy(y = draggingWidgetOffset.y + consumed)
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                widgetPageRootOffset = coordinates.positionInRoot()
            }
            .background(launcherStyle.screenBackground, RoundedCornerShape(40.dp))
    ) {
        val leftSafeInset = maxWidth * (leftSafeInsetPercent.coerceIn(0, 50) / 100f)
        val availableWidth = maxWidth - leftSafeInset
        val contentWidth = availableWidth * SIDE_SCREEN_CONTENT_WIDTH_RATIO
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val widgetColumnGap = 10.dp
        val singleColumnWidth = (contentWidth - widgetColumnGap) / 2f
        val singleColumnWidthDp = with(density) { singleColumnWidth.toPx().toDp().value }

        @Composable
        fun EditableWidgetCard(
            card: SideScreenWidgetCardItem,
            targetWidth: Dp
        ) {
            val cardKey = card.stableKey()
            val resizing = activeWidgetResize?.takeIf { it.cardKey == cardKey }
            val displayLayout = resizing
                ?.let {
                    resizeWidgetSlotLayout(
                        it.startLayout,
                        it.delta,
                        density,
                        it.resizeMode,
                        it.singleColumnWidthDp
                    )
                }
                ?: card.layout
            val cardWidth = if (displayLayout.spanColumns >= 2) {
                contentWidth
            } else {
                targetWidth
            }
            val animatedCardWidth by animateDpAsState(
                targetValue = cardWidth,
                animationSpec = spring(stiffness = 620f, dampingRatio = 0.86f),
                label = "widget_card_width"
            )
            val shellShape = RoundedCornerShape(28.dp)
            val jiggleActive = editMode && !showPicker

            key(cardKey) {
                AnimatedVisibility(
                    visible = cardKey !in removingWidgetKeys && cardKey !in enteringWidgetKeys,
                    enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.88f),
                    exit = fadeOut(tween(150)) + scaleOut(tween(170), targetScale = 0.86f)
                ) {
                    val dragging = draggingWidgetKey == cardKey
                    Box(
                        modifier = Modifier
                            .width(animatedCardWidth)
                            .then(
                                if (resizing != null) {
                                    Modifier
                                } else {
                                    Modifier.animateContentSize(
                                        animationSpec = spring(stiffness = 560f, dampingRatio = 0.86f)
                                    )
                                }
                            )
                            .onGloballyPositioned { coordinates ->
                                val position = coordinates.positionInRoot()
                                val width = coordinates.size.width.toFloat()
                                val height = coordinates.size.height.toFloat()
                                widgetCardCenters[cardKey] = Offset(
                                    position.x + width / 2f,
                                    position.y + height / 2f
                                )
                                widgetCardBounds[cardKey] = Rect(
                                    left = position.x,
                                    top = position.y,
                                    right = position.x + width,
                                    bottom = position.y + height
                                )
                            }
                            .zIndex(if (dragging) 50f else 0f)
                            .graphicsLayer {
                                translationX = if (dragging) draggingWidgetOffset.x else 0f
                                translationY = if (dragging) draggingWidgetOffset.y else 0f
                                shadowElevation = if (dragging) 22.dp.toPx() else 0f
                                alpha = 1f
                                scaleX = if (dragging) 1.035f else 1f
                                scaleY = if (dragging) 1.035f else 1f
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shellShape)
                                .background(Color.White.copy(alpha = if (editMode) 0.05f else 0.03f))
                                .padding(4.dp)
                        ) {
                            WidgetCard(
                                widget = card.widget,
                                layout = displayLayout,
                                cardWidth = cardWidth,
                                widgetRepository = widgetRepository,
                                appWidgetHost = appWidgetHost,
                                onLongPress = if (!editMode && !showPicker) {
                                    {
                                        editMode = true
                                        editGestureLocked = true
                                    }
                                } else {
                                    null
                                },
                                onMeasuredHeight = {}
                            )
                        }
                        if (jiggleActive) {
                            val blockerInteraction = remember { MutableInteractionSource() }
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(shellShape)
                                    .clickable(
                                        interactionSource = blockerInteraction,
                                        indication = null,
                                        onClick = {}
                                    )
                                    .pointerInput(cardKey, card.layout, editMode) {
                                        awaitEachGesture {
                                            val down = awaitPrimaryDown()
                                            down.consume()
                                            if (!editMode) return@awaitEachGesture
                                            val resizeMode = widgetResizeModeAt(
                                                position = down.position,
                                                widthPx = size.width.toFloat(),
                                                heightPx = size.height.toFloat(),
                                                hotspotPx = widgetBorderHotspotPx
                                            )
                                            if (resizeMode != null) {
                                                vibrateHaptic(context)
                                                activeWidgetResize = ActiveWidgetResize(
                                                    cardKey = cardKey,
                                                    startLayout = card.layout,
                                                    resizeMode = resizeMode,
                                                    singleColumnWidthDp = singleColumnWidthDp
                                                )
                                                draggingWidgetKey = null
                                                draggingWidgetOffset = Offset.Zero
                                                widgetDragAutoScrollPx = 0f
                                            } else {
                                                val hold = awaitLongPressOrRelease(
                                                    pointerId = down.id,
                                                    downPosition = down.position,
                                                    timeoutMillis = widgetResizeLongPressMs
                                                )
                                                if (hold !is PressHoldResult.LongPress) return@awaitEachGesture
                                                vibrateHaptic(context)
                                                hold.change.consume()
                                                activeWidgetResize = null
                                                draggingWidgetKey = cardKey
                                                draggingWidgetOffset = Offset.Zero
                                                widgetDragAutoScrollPx = 0f
                                                pendingWidgetDropIndex = null
                                            }
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                if (!change.pressed) break
                                                val dragAmount = change.positionChange()
                                                if (dragAmount == Offset.Zero) continue
                                                change.consume()
                                                val resize = activeWidgetResize
                                                if (resize?.cardKey == cardKey) {
                                                    activeWidgetResize = resize.copy(delta = resize.delta + dragAmount)
                                                } else if (draggingWidgetKey == cardKey) {
                                                    draggingWidgetOffset += dragAmount
                                                    val cardCenter = widgetCardCenters[cardKey]
                                                    val pointerScreenY = cardCenter?.y?.let { centerY ->
                                                        centerY - size.height / 2f +
                                                            draggingWidgetOffset.y +
                                                            change.position.y
                                                    }
                                                    when {
                                                        pointerScreenY != null && pointerScreenY < autoScrollEdgePx -> {
                                                            val progress = ((autoScrollEdgePx - pointerScreenY) / autoScrollEdgePx)
                                                                .coerceIn(0f, 1.2f)
                                                            widgetDragAutoScrollPx = -autoScrollMaxVelocityPx * progress
                                                        }
                                                        pointerScreenY != null && pointerScreenY > screenHeightPx - autoScrollEdgePx -> {
                                                            val progress = ((pointerScreenY - (screenHeightPx - autoScrollEdgePx)) / autoScrollEdgePx)
                                                                .coerceIn(0f, 1.2f)
                                                            widgetDragAutoScrollPx = autoScrollMaxVelocityPx * progress
                                                        }
                                                        else -> {
                                                            widgetDragAutoScrollPx = 0f
                                                        }
                                                    }
                                                    val sourceCenter = widgetCardCenters[cardKey]
                                                    pendingWidgetDropIndex = sourceCenter
                                                        ?.plus(draggingWidgetOffset)
                                                        ?.let { point ->
                                                            widgetInsertionIndexFor(
                                                                point = point,
                                                                widgetCards = widgetCards,
                                                                boundsByKey = widgetCardBounds
                                                            )
                                                        }
                                                }
                                            }
                                            if (draggingWidgetKey == cardKey) {
                                                val targetIndex = pendingWidgetDropIndex
                                                if (
                                                    targetIndex != null &&
                                                    targetIndex != card.widgetIndex &&
                                                    targetIndex != card.widgetIndex + 1
                                                ) {
                                                    onSwapWidget(card.widgetIndex, targetIndex)
                                                }
                                            }
                                            activeWidgetResize?.takeIf { it.cardKey == cardKey }?.let { resize ->
                                                persistWidgetLayout(
                                                    card,
                                                    resizeWidgetSlotLayout(
                                                        resize.startLayout,
                                                        resize.delta,
                                                        density,
                                                        resize.resizeMode,
                                                        resize.singleColumnWidthDp
                                                    )
                                                )
                                            }
                                            activeWidgetResize = null
                                            draggingWidgetKey = null
                                            draggingWidgetOffset = Offset.Zero
                                            widgetDragAutoScrollPx = 0f
                                            pendingWidgetDropIndex = null
                                        }
                                    }
                            )
                            WidgetResizeHandles(
                                shape = shellShape,
                                modifier = Modifier.matchParentSize()
                            )
                            WidgetEditIconButton(
                                enabled = true,
                                backgroundColor = Color(0xFFE53935),
                                onClick = {
                                    clearAllocatedWidget(card.widgetIndex)
                                    removingWidgetKeys += cardKey
                                    scope.launch {
                                        delay(170)
                                        onRemoveWidget(card.widgetIndex)
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "-",
                                    color = Color.White,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
                .graphicsLayer { translationY = overscroll.value }
                .padding(start = leftSafeInset),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
        ) {
            item("widget_page_header") {
                Row(
                    modifier = Modifier.width(contentWidth),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "小组件",
                        color = launcherStyle.titleColor,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetPageChip(
                            label = if (editMode) "完成" else "编辑",
                            onClick = { editMode = !editMode },
                            enabled = widgetCards.isNotEmpty()
                        )
                        WidgetPageChip(
                            label = "添加",
                            onClick = {
                                editMode = false
                                showPicker = true
                            }
                        )
                    }
                }
            }
            if (widgetCards.isEmpty()) {
                item("empty_widget_page") {
                    EmptyWidgetCard(width = contentWidth)
                }
            } else {
                items(
                    items = widgetSections,
                    key = { section -> section.stableKey },
                    contentType = { section ->
                        when (section) {
                            is WidgetLayoutSection.Columns -> "widget_page_columns"
                            is WidgetLayoutSection.FullWidth -> "widget_page_full"
                        }
                    }
                ) { section ->
                    val sectionIsResizing = activeWidgetResize?.cardKey?.let { activeKey ->
                        section.cards.any { it.stableKey() == activeKey }
                    } == true
                    val sectionModifier = Modifier
                        .width(contentWidth)
                        .animateItem(
                            fadeInSpec = tween(160),
                            placementSpec = spring(stiffness = 520f, dampingRatio = 0.86f),
                            fadeOutSpec = tween(160)
                        )
                        .then(
                            if (sectionIsResizing) {
                                Modifier
                            } else {
                                Modifier.animateContentSize(
                                    animationSpec = spring(stiffness = 560f, dampingRatio = 0.86f)
                                )
                            }
                        )
                    when (section) {
                        is WidgetLayoutSection.FullWidth -> {
                            Box(modifier = sectionModifier) {
                                EditableWidgetCard(section.card, contentWidth)
                            }
                        }
                        is WidgetLayoutSection.Columns -> {
                            Row(
                                modifier = sectionModifier,
                                horizontalArrangement = Arrangement.spacedBy(widgetColumnGap)
                            ) {
                                Column(
                                    modifier = Modifier.width(singleColumnWidth),
                                    verticalArrangement = Arrangement.spacedBy(widgetColumnGap)
                                ) {
                                    section.left.forEach { card ->
                                        EditableWidgetCard(card, singleColumnWidth)
                                    }
                                }
                                Column(
                                    modifier = Modifier.width(singleColumnWidth),
                                    verticalArrangement = Arrangement.spacedBy(widgetColumnGap)
                                ) {
                                    section.right.forEach { card ->
                                        EditableWidgetCard(card, singleColumnWidth)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showPicker) {
        val repository = requireNotNull(widgetRepository)
        val host = requireNotNull(appWidgetHost)
        val manager = requireNotNull(appWidgetManager)
        WidgetPickerOverlay(
            apps = apps,
            widgetRepository = repository,
            onSelectWidget = { widget ->
                val targetIndex = sideScreenWidgetSlots.size
                val providerInfo = repository.findProviderInfo(widget.widgetKey)
                if (providerInfo != null) {
                    discardPendingWidgetSelection()
                    clearAllocatedWidget(targetIndex)
                    val appWidgetId = runCatching { host.allocateAppWidgetId() }.getOrNull()
                    if (appWidgetId != null) {
                        val selection = PendingWidgetSelection(
                            slotIndex = targetIndex,
                            widget = widget,
                            appWidgetId = appWidgetId
                        )
                        pendingWidgetSelection = selection
                        val bindExtras = buildWidgetBindOptions(providerInfo)
                        val bound = runCatching {
                            manager.bindAppWidgetIdIfAllowed(
                                appWidgetId,
                                providerInfo.provider,
                                bindExtras
                            )
                        }.getOrDefault(false)
                        if (bound) {
                            continueWidgetBinding(selection, configureWidgetLauncher)
                        } else if (activity != null) {
                            val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, providerInfo.provider)
                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, bindExtras)
                            }
                            bindWidgetLauncher.launch(bindIntent)
                        } else {
                            discardPendingWidgetSelection()
                        }
                    }
                }
            },
            onDismiss = { showPicker = false }
        )
    }
}

}

@Composable
private fun WidgetPageChip(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val launcherStyle = LauncherTheme.style
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) launcherStyle.topBarChipColor else launcherStyle.cardColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (enabled) launcherStyle.topBarTextColor else WatchColors.TextTertiary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun WidgetEditIconButton(
    enabled: Boolean,
    backgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val alpha = if (enabled) 1f else 0.34f
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(backgroundColor.copy(alpha = backgroundColor.alpha * alpha))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun WidgetResizeHandles(
    shape: Shape,
    modifier: Modifier = Modifier
) {
    val handleColor = Color.White.copy(alpha = 0.72f)
    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.46f), shape)
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(46.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(handleColor)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .width(46.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(handleColor)
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(5.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(handleColor)
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(5.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(handleColor)
        )
    }
}

@Composable
private fun WidgetDragPreviewCard(
    preview: WidgetDragPreview,
    rootOffset: Offset,
    widgetRepository: WidgetRepository,
    appWidgetHost: AppWidgetHost,
    modifier: Modifier = Modifier
) {
    val width = with(LocalDensity.current) { preview.bounds.width.toDp() }
    val height = with(LocalDensity.current) { preview.bounds.height.toDp() }
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = modifier
            .size(width, height)
            .graphicsLayer {
                translationX = preview.bounds.left - rootOffset.x + preview.offset.x
                translationY = preview.bounds.top - rootOffset.y + preview.offset.y
                shadowElevation = 22.dp.toPx()
                this.shape = shape
                clip = false
                scaleX = 1.035f
                scaleY = 1.035f
            }
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.38f), shape)
    ) {
        WidgetCard(
            widget = preview.card.widget,
            layout = preview.card.layout,
            cardWidth = width,
            widgetRepository = widgetRepository,
            appWidgetHost = appWidgetHost,
            onLongPress = null,
            onMeasuredHeight = {}
        )
    }
}

@Composable private fun QuickPanel(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    items: List<AppInfo?>,
    slotCenters: MutableMap<Int, Offset>,
    onAdd: (Int) -> Unit,
    onOpenActions: (Int, AppInfo) -> Unit,
    onCloseActions: () -> Unit,
    onSwap: (Int, Int) -> Unit,
    onClickApp: (Int, AppInfo) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val dragThresholdPx = with(density) { 10.dp.toPx() }
    val dropThresholdPx = with(density) { 68.dp.toPx() }
    val visibleSlots = remember(items.size) { items.indices.toSet() }
    val bubbleSize = 58.dp
    var draggingSlot by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragMoved by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF353535))
            .padding(14.dp)
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            items.chunked(3).forEachIndexed { rowIndex, row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEachIndexed { colIndex, item ->
                        val slot = rowIndex * 3 + colIndex
                        Box(Modifier.onGloballyPositioned { c -> val p = c.positionInRoot(); slotCenters[slot] = Offset(p.x + c.size.width / 2f, p.y + c.size.height / 2f) }, contentAlignment = Alignment.Center) {
                            when (item) {
                                null -> {
                                    AddBubble(size = bubbleSize) { onAdd(slot) }
                                }
                                else -> {
                                    val dragModifier = Modifier.pointerInput(item.componentKey, slot, slotCenters) {
                                        awaitEachGesture {
                                            val down = awaitPrimaryDown()
                                            when (val hold = awaitLongPressOrRelease(
                                                pointerId = down.id,
                                                downPosition = down.position,
                                                timeoutMillis = SIDE_SCREEN_SHORTCUT_MENU_TRIGGER_MS
                                            )) {
                                                PressHoldResult.Cancelled -> Unit
                                                PressHoldResult.Released -> {
                                                    onClickApp(slot, item)
                                                }
                                                is PressHoldResult.LongPress -> {
                                                    vibrateHaptic(context)
                                                    hold.change.consume()
                                                    dragOffset = Offset.Zero
                                                    dragMoved = false
                                                    onOpenActions(slot, item)

                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                        if (!change.pressed) {
                                                            change.consume()
                                                            break
                                                        }
                                                        val delta = change.positionChange()
                                                        if (delta != Offset.Zero) {
                                                            dragOffset += delta
                                                            if (!dragMoved && dragOffset.getDistance() > dragThresholdPx) {
                                                                dragMoved = true
                                                                draggingSlot = slot
                                                                onCloseActions()
                                                            }
                                                            change.consume()
                                                        }
                                                    }

                                                    val release = slotCenters[slot]?.let { it + dragOffset }
                                                    val target = release?.let { pointer ->
                                                        slotCenters
                                                            .filterKeys { it != slot && it in visibleSlots }
                                                            .minByOrNull { (_, center) -> (center - pointer).getDistance() }
                                                            ?.takeIf { (_, center) -> (center - pointer).getDistance() <= dropThresholdPx }
                                                            ?.key
                                                    }
                                                    if (dragMoved && target != null) {
                                                        onSwap(slot, target)
                                                    }
                                                    draggingSlot = null
                                                    dragOffset = Offset.Zero
                                                    dragMoved = false
                                                }
                                            }
                                        }
                                    }
                                    val itemDragOffset = if (draggingSlot == slot) dragOffset else Offset.Zero
                                    AppBubble(
                                        item.cachedIcon,
                                        bubbleSize,
                                        onClick = {},
                                        onLongClick = null,
                                        forcePressed = draggingSlot == slot,
                                        gesturesEnabled = false,
                                        shape = CircleShape,
                                        modifier = dragModifier
                                            .zIndex(if (draggingSlot == slot) 6f else 0f)
                                            .graphicsLayer {
                                                translationX = itemDragOffset.x
                                                translationY = itemDragOffset.y
                                                shadowElevation = if (draggingSlot == slot) 12.dp.toPx() else 0f
                                            }
                                    )
                                }
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.size(bubbleSize)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WidgetCard(
    widget: WidgetInfo,
    layout: WidgetSlotLayout,
    cardWidth: Dp,
    widgetRepository: WidgetRepository,
    appWidgetHost: AppWidgetHost,
    onLongPress: (() -> Unit)?,
    onMeasuredHeight: (Int) -> Unit
) {
    val context = LocalContext.current
    val providerInfo = remember(widgetRepository, widget.widgetKey) {
        widgetRepository.findProviderInfo(widget.widgetKey)
    }
    val minWidgetHeight = WIDGET_CARD_MIN_HEIGHT_DP.dp
    val widgetAspectRatio = remember(providerInfo) {
        val minWidth = providerInfo?.minWidth?.takeIf { it > 0 } ?: 1
        val minHeight = providerInfo?.minHeight?.takeIf { it > 0 } ?: 1
        (minWidth.toFloat() / minHeight.toFloat()).coerceIn(0.5f, 2.5f)
    }
    val defaultHeight = if (layout.spanColumns == 1) {
        cardWidth.value.roundToInt().coerceIn(WIDGET_CARD_MIN_HEIGHT_DP, WIDGET_CARD_MAX_HEIGHT_DP)
    } else {
        170
    }
    val targetHeight = (layout.heightDp ?: defaultHeight)
        .coerceIn(WIDGET_CARD_MIN_HEIGHT_DP, WIDGET_CARD_MAX_HEIGHT_DP)
        .dp
        .coerceAtLeast(minWidgetHeight)
    val cardShape = RoundedCornerShape(24.dp)
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(targetHeight)
            .onGloballyPositioned { coordinates -> onMeasuredHeight(coordinates.size.height) }
            .graphicsLayer {
                shape = cardShape
                clip = true
            }
            .background(Color(0xFF1F2937))
    ) {
        if (providerInfo != null && widget.widgetId != -1) {
            val widgetBindTag = remember(widget.widgetId, providerInfo) {
                "${widget.widgetId}|${providerInfo.provider.flattenToString()}|${providerInfo.minWidth}x${providerInfo.minHeight}"
            }
            AndroidView(
                factory = { viewContext ->
                    val radiusPx = with(density) { 24.dp.toPx() }
                    val container = FrameLayout(viewContext).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        clipToOutline = true
                        outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: Outline) {
                                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                            }
                        }
                    }
                    val hostView = appWidgetHost.createView(viewContext, widget.widgetId, providerInfo).apply {
                        setAppWidget(widget.widgetId, providerInfo)
                        tag = widgetBindTag
                        clipToOutline = true
                        outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: Outline) {
                                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                            }
                        }
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        if (onLongPress != null) {
                            setOnLongClickListener {
                                vibrateHaptic(context)
                                onLongPress()
                                true
                            }
                        } else {
                            setOnLongClickListener(null)
                        }
                    }
                    container.tag = WidgetHostTag(widgetBindTag, hostView)
                    container.addView(hostView)
                    container
                },
                update = { container ->
                    val tag = container.tag as? WidgetHostTag
                    val hostView = tag?.hostView
                    if (tag?.bindTag != widgetBindTag && hostView != null) {
                        @Suppress("DEPRECATION")
                        hostView.setAppWidget(widget.widgetId, providerInfo)
                        container.tag = WidgetHostTag(widgetBindTag, hostView)
                    }
                    hostView?.let { view ->
                        val widthDp = with(density) { container.width.toDp().value.roundToInt() }
                            .coerceAtLeast(1)
                        val heightDp = with(density) { container.height.toDp().value.roundToInt() }
                            .coerceAtLeast(1)
                        @Suppress("DEPRECATION")
                        view.updateAppWidgetSize(
                            buildWidgetBindOptions(providerInfo),
                            widthDp,
                            heightDp,
                            widthDp,
                            heightDp
                        )
                        if (onLongPress != null) {
                            view.setOnLongClickListener {
                                vibrateHaptic(context)
                                onLongPress()
                                true
                            }
                        } else {
                            view.setOnLongClickListener(null)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(targetHeight)
                    .graphicsLayer {
                        shape = cardShape
                        clip = true
                    }
            )
        } else {
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val scale by animateFloatAsState(
                if (isPressed) 0.988f else 1f,
                spring(stiffness = 860f, dampingRatio = 0.78f),
                label = "widget_unavailable_scale"
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(targetHeight)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(cardShape)
                    .background(Color(0xFF1F2937))
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {},
                        onLongClick = {
                            if (onLongPress != null) {
                                vibrateHaptic(context)
                                onLongPress()
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "小组件不可用",
                    color = WatchColors.TextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun EmptyWidgetCard(width: Dp) {
    val launcherStyle = LauncherTheme.style
    Box(
        modifier = Modifier
            .width(width)
            .height(96.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(launcherStyle.cardColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "还没有小组件，点右上角添加",
            color = WatchColors.TextSecondary,
            fontSize = 13.sp
        )
    }
}

@Composable private fun AddBubble(size: Dp = 58.dp, onClick: () -> Unit) {
    val launcherStyle = LauncherTheme.style
    val pressed = rememberPressedState(); val isPressed by pressed
    val scale by animateFloatAsState(if (isPressed) 0.958f else 1f, spring(stiffness = 860f, dampingRatio = 0.72f), label = "side_add")
    Box(Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(launcherStyle.topBarChipColor).instantPressGesture(pressed, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Add, null, tint = launcherStyle.topBarTextColor, modifier = Modifier.size(24.dp))
    }
}

@Composable private fun PreviewInfoCard(title: String, subtitle: String, onClick: () -> Unit) {
    val fake = NotificationEntryUi("info", "", null, title, title, subtitle, 0L, null, false, false, false, false)
    PreviewPill(fake, onClick)
}

@Composable private fun PreviewPill(
    entry: NotificationEntryUi,
    onClick: () -> Unit,
    onMeasuredHeight: ((Int) -> Unit)? = null
) {
    val launcherStyle = LauncherTheme.style
    val cardColor = if (launcherStyle.cardColor.alpha < 0.35f) {
        if (launcherStyle.titleColor == Color.White) Color(0xFF353535) else Color(0xFFE8E8ED)
    } else {
        launcherStyle.cardColor
    }
    val pressed = rememberPressedState(); val isPressed by pressed
    val scale by animateFloatAsState(if (isPressed) 0.968f else 1f, spring(stiffness = 860f, dampingRatio = 0.72f), label = "preview_scale")
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                onMeasuredHeight?.invoke(coordinates.size.height)
            }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(28.dp))
            .background(cardColor)
            .instantPressGesture(pressed, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NotificationIcon(entry.icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title.ifBlank { entry.appLabel }, color = launcherStyle.titleColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(entry.text.ifBlank { entry.title.ifBlank { entry.appLabel } }, color = WatchColors.TextSecondary, fontSize = 13.sp, maxLines = 2)
            }
            if (entry.time > 0L) {
                Spacer(Modifier.width(10.dp))
                Text(formatClockTime(entry.time), color = launcherStyle.bodyColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable private fun StackedPreviewCard(
    entry: NotificationEntryUi,
    hiddenCount: Int,
    stackCardColor: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    val stackStrength by animateFloatAsState(
        targetValue = if (hiddenCount > 0) 1f else 0f,
        animationSpec = spring(stiffness = 620f, dampingRatio = 0.82f),
        label = "preview_stack_strength"
    )
    var frontCardHeight by remember { mutableIntStateOf(72) }
    val density = LocalDensity.current
    val stackCardHeight: Dp = with(density) { frontCardHeight.toDp() }
    Box(Modifier.fillMaxWidth()) {
        repeat(hiddenCount.coerceIn(0, 2)) { index ->
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        translationY = with(density) { ((STACKED_PREVIEW_TRANSLATION_DP - 6) * (index + 1)).dp.toPx() } * stackStrength
                        scaleX = 1f - (index + 1) * 0.012f * stackStrength
                        scaleY = 1f - (index + 1) * 0.012f * stackStrength
                        alpha = 0.44f + (0.18f / (index + 1))
                    }
                    .padding(horizontal = STACKED_PREVIEW_HORIZONTAL_INSET_DP.dp)
                    .fillMaxWidth()
                    .height(stackCardHeight)
                    .clip(RoundedCornerShape(28.dp))
                    .background(notificationStackBackColor(stackCardColor, index))
            )
        }
        Column(modifier = Modifier.padding(bottom = 2.dp)) {
            PreviewPill(
                entry = entry,
                onClick = onClick,
                onMeasuredHeight = { heightPx ->
                    if (heightPx > 0 && frontCardHeight != heightPx) {
                        frontCardHeight = heightPx
                    }
                }
            )
            Text("+${hiddenCount}条新消息", color = WatchColors.TextTertiary, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
        }
    }
}

@Composable private fun NotificationIcon(icon: ImageBitmap?) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFFD9D9D9)), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, null, modifier = Modifier.fillMaxSize().clip(CircleShape), filterQuality = FilterQuality.Medium, contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.Notifications, null, tint = Color(0xFF2B2B2B), modifier = Modifier.size(24.dp))
    }
}

private fun notificationStackBackColor(base: Color, index: Int): Color {
    return when {
        base != Color.Unspecified && base.alpha > 0f -> base.copy(alpha = (0.40f - index * 0.08f).coerceIn(0.18f, 0.42f))
        index == 0 -> Color(0xFF404040)
        else -> Color(0xFF2E2E2E)
    }
}

@Composable
private fun ShortcutPickerOverlay(
    apps: List<AppInfo>,
    onSelect: (AppInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val appPickerItems = remember(apps) {
        apps.asSequence()
            .filterNot { it.isFolder }
            .map { app ->
                ShortcutPickerItem(
                    componentKey = app.componentKey,
                    label = app.label,
                    packageName = app.packageName,
                    icon = app.cachedIcon,
                    source = app
                )
            }
            .toList()
    }
    val listState = rememberLazyListState()
    val (pickerOverscroll, pickerNestedScroll) = rememberPickerOverscrollConnection(listState)
    ModalShell(onDismiss) { dismiss ->
        Column(Modifier.fillMaxWidth(0.82f).heightIn(max = 420.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1E1E1E)).padding(vertical = 12.dp)) {
            Text("添加快捷启动", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .nestedScroll(pickerNestedScroll)
                    .graphicsLayer { translationY = pickerOverscroll },
                contentPadding = PaddingValues(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items = appPickerItems,
                    key = { it.componentKey },
                    contentType = { "shortcut_picker_app" }
                ) { app ->
                    ShortcutPickerRow(
                        item = app,
                        onClick = {
                            onSelect(app.source)
                            dismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetPickerOverlay(
    apps: List<AppInfo>,
    widgetRepository: WidgetRepository,
    onSelectWidget: (WidgetInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val widgetPickerItems = remember(widgetRepository) { widgetRepository.getAllWidgets() }
    val launcherAppsByPackage = remember(apps) {
        apps.filterNot { it.isBuiltInSettingsEntry }
            .groupBy { it.packageName }
            .mapValues { it.value.minByOrNull { app -> app.label.length } ?: it.value.first() }
    }
    val pickerApps = remember(widgetPickerItems, launcherAppsByPackage) {
        widgetPickerItems
            .groupBy { it.packageName }
            .map { (packageName, widgets) ->
                val launcherApp = launcherAppsByPackage[packageName]
                val representativeWidget = widgets.first()
                WidgetPickerApp(
                    packageName = packageName,
                    label = launcherApp?.label ?: representativeWidget.appLabel,
                    icon = launcherApp?.iconForDisplay(useTwoTone = false, blurred = false),
                    widgets = widgets.sortedBy { it.label.lowercase(Locale.getDefault()) }
                )
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var appListReturnIndex by remember { mutableIntStateOf(0) }
    var appListReturnOffset by remember { mutableIntStateOf(0) }
    val selectedApp = selectedPackage?.let { selected ->
        pickerApps.firstOrNull { it.packageName == selected }
    }
    val listState = rememberLazyListState()
    val (pickerOverscroll, pickerNestedScroll) = rememberPickerOverscrollConnection(listState)
    LaunchedEffect(selectedPackage) {
        if (selectedPackage == null) {
            listState.scrollToItem(appListReturnIndex, appListReturnOffset)
        } else {
            listState.scrollToItem(0)
        }
    }
    BackHandler(enabled = selectedPackage != null) {
        selectedPackage = null
    }
    ModalShell(onDismiss, backHandlerEnabled = selectedPackage == null) { dismiss ->
        Column(Modifier.fillMaxWidth(0.82f).heightIn(max = 420.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1E1E1E)).padding(vertical = 12.dp)) {
            Text(
                selectedApp?.label ?: "添加小组件",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp)
            )
            AnimatedContent(
                targetState = selectedApp,
                transitionSpec = {
                    val forward = initialState == null && targetState != null
                    val enter = slideInHorizontally(
                        animationSpec = tween(220),
                        initialOffsetX = { width -> if (forward) width / 3 else -width / 3 }
                    ) + fadeIn(tween(160))
                    val exit = slideOutHorizontally(
                        animationSpec = tween(220),
                        targetOffsetX = { width -> if (forward) -width / 3 else width / 3 }
                    ) + fadeOut(tween(140))
                    enter togetherWith exit using SizeTransform(clip = false)
                },
                label = "widget_picker_level",
                modifier = Modifier.weight(1f, fill = false)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .nestedScroll(pickerNestedScroll)
                        .graphicsLayer { translationY = pickerOverscroll },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (widgetPickerItems.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                Text("未找到可用的小组件", color = WatchColors.TextTertiary, fontSize = 14.sp)
                            }
                        }
                    } else if (it == null) {
                        items(
                            items = pickerApps,
                            key = { app -> app.packageName },
                            contentType = { "widget_app_picker" }
                        ) { app ->
                            WidgetPickerAppRow(
                                app = app,
                                widgetRepository = widgetRepository,
                                onClick = {
                                    appListReturnIndex = listState.firstVisibleItemIndex
                                    appListReturnOffset = listState.firstVisibleItemScrollOffset
                                    selectedPackage = app.packageName
                                }
                            )
                        }
                    } else {
                        item("back_to_widget_apps") {
                            WidgetPickerBackRow(onClick = { selectedPackage = null })
                        }
                        items(
                            items = it.widgets,
                            key = { widget -> widget.widgetKey },
                            contentType = { "widget_picker" }
                        ) { widget ->
                            WidgetPickerRow(
                                widget = widget,
                                widgetRepository = widgetRepository,
                                onClick = {
                                    onSelectWidget(widget)
                                    dismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetPickerBackRow(onClick: () -> Unit) {
    val pressed = rememberPressedState()
    val isPressed by pressed
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (isPressed) Color(0xFF3A3A3A) else Color(0xFF2B2B2D))
            .instantPressGesture(pressed, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(9.dp))
        Text("返回应用列表", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WidgetPickerAppRow(
    app: WidgetPickerApp,
    widgetRepository: WidgetRepository,
    onClick: () -> Unit
) {
    val pressed = rememberPressedState()
    val isPressed by pressed
    val rowIcon by produceState<ImageBitmap?>(initialValue = app.icon, app.packageName, widgetRepository) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                widgetRepository.loadProviderPackageIcon(app.packageName)
            }
        }
    }
    val scale by animateFloatAsState(
        if (isPressed) 0.985f else 1f,
        spring(stiffness = 880f, dampingRatio = 0.8f),
        label = "widget_app_picker_row"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(18.dp))
            .background(if (isPressed) Color(0xFF303030) else Color.Transparent)
            .instantPressGesture(pressed, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (rowIcon != null) {
            Image(
                bitmap = rowIcon!!,
                contentDescription = app.label,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF007AFF)),
                contentAlignment = Alignment.Center
            ) {
                Text(app.label.take(1), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(app.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("${app.widgets.size} 个小组件", color = WatchColors.TextTertiary, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun WidgetPickerRow(
    widget: WidgetInfo,
    widgetRepository: WidgetRepository,
    onClick: () -> Unit
) {
    val pressed = rememberPressedState()
    val isPressed by pressed
    val preview by produceState<ImageBitmap?>(initialValue = widget.previewImage ?: widget.appIcon, widget.widgetKey, widgetRepository) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                widgetRepository.loadWidgetPreviewImage(widget)
                    ?: widgetRepository.loadProviderPackageIcon(widget.packageName)
            }
        }
    }
    val previewBitmap = preview
    val scale by animateFloatAsState(
        if (isPressed) 0.985f else 1f,
        spring(stiffness = 880f, dampingRatio = 0.8f),
        label = "widget_picker_row"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(18.dp))
            .background(if (isPressed) Color(0xFF303030) else Color.Transparent)
            .instantPressGesture(pressed, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(52.dp, 42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF2A3444)),
            contentAlignment = Alignment.Center
        ) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap,
                    contentDescription = widget.label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(widget.label.take(1), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(widget.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(widget.appLabel, color = WatchColors.TextTertiary, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun TypeTab(
    modifier: Modifier,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val pressed = rememberPressedState()
    val isPressed by pressed
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF007AFF) else Color.Transparent)
            .instantPressGesture(pressed, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color.White else WatchColors.TextSecondary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ShortcutPickerRow(
    item: ShortcutPickerItem,
    onClick: () -> Unit
) {
    val pressed = rememberPressedState()
    val isPressed by pressed
    val scale by animateFloatAsState(
        if (isPressed) 0.985f else 1f,
        spring(stiffness = 880f, dampingRatio = 0.8f),
        label = "picker_row"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(18.dp))
            .background(if (isPressed) Color(0xFF303030) else Color.Transparent)
            .instantPressGesture(pressed, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            item.icon,
            null,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(item.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(item.packageName, color = WatchColors.TextTertiary, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable private fun RemoveOverlay(app: AppInfo?, widget: WidgetInfo?, onRemove: () -> Unit, onDismiss: () -> Unit) {
    ModalShell(onDismiss) { dismiss ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(0.74f).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF2C2C2E))) {
                Box(Modifier.fillMaxWidth().clickable { onRemove(); dismiss() }.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text("移除", color = Color(0xFFFF453A), fontSize = 15.sp, fontWeight = FontWeight.W500)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (app != null) {
                Image(app.cachedIcon, null, modifier = Modifier.size(88.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Spacer(Modifier.height(6.dp))
                Text(app.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.W600)
            } else if (widget != null) {
                Box(
                    Modifier.size(88.dp).clip(CircleShape).background(Color(0xFF007AFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(widget.label.take(1), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text(widget.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.W600)
            }
        }
    }
}

@Composable private fun ModalShell(
    onDismissRequest: () -> Unit,
    backHandlerEnabled: Boolean = true,
    content: @Composable (dismiss: () -> Unit) -> Unit
) {
    val dismissInteraction = remember { MutableInteractionSource() }
    var visible by remember { mutableStateOf(false) }
    fun dismiss() { visible = false }
    LaunchedEffect(Unit) { visible = true }
    BackHandler(enabled = backHandlerEnabled) { dismiss() }
    LaunchedEffect(visible) { if (!visible) { delay(220); onDismissRequest() } }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, spring(stiffness = 720f, dampingRatio = 0.85f), label = "modal_alpha")
    val scale by animateFloatAsState(if (visible) 1f else 0.84f, spring(stiffness = 700f, dampingRatio = 0.8f), label = "modal_scale")
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(indication = null, interactionSource = dismissInteraction) { dismiss() }
        )
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            content(::dismiss)
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitLongPressOrRelease(
    pointerId: PointerId,
    downPosition: Offset,
    timeoutMillis: Long
): PressHoldResult {
    val result = withTimeoutOrNull(timeoutMillis) {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId }
                ?: return@withTimeoutOrNull PressHoldResult.Cancelled
            if (!change.pressed) {
                return@withTimeoutOrNull PressHoldResult.Released
            }
            if ((change.position - downPosition).getDistance() > viewConfiguration.touchSlop) {
                return@withTimeoutOrNull PressHoldResult.Cancelled
            }
        }
        @Suppress("UNREACHABLE_CODE")
        PressHoldResult.Cancelled
    }
    if (result != null) return result
    val current = currentEvent.changes.firstOrNull { it.id == pointerId && it.pressed }
    return current?.let(PressHoldResult::LongPress) ?: PressHoldResult.Cancelled
}

private suspend fun AwaitPointerEventScope.awaitPrimaryDown(): PointerInputChange {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.pressed }
        if (change != null) return change
    }
}

@Composable private fun rememberClockSnapshot(): SideScreenClockSnapshot {
    var snapshot by remember { mutableStateOf(SideScreenClockSnapshot("--:--", "--")) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = Date(); val locale = Locale.getDefault()
            snapshot = SideScreenClockSnapshot(SimpleDateFormat("HH:mm", locale).format(now), if (locale.language.startsWith("zh")) SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now) else SimpleDateFormat("MMM d, EEEE", locale).format(now))
            delay(1000)
        }
    }
    return snapshot
}

@Composable private fun rememberBatterySnapshot(): BatterySnapshot {
    val context = LocalContext.current
    var level by remember(context) { mutableIntStateOf(0) }
    var charging by remember(context) { mutableStateOf(false) }
    DisposableEffect(context) {
        fun readLevel(): Int = (context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0).coerceIn(0, 100)
        fun readCharging(intent: Intent?): Boolean {
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
        level = readLevel()
        val stickyIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        charging = readCharging(stickyIntent)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                level = readLevel()
                charging = readCharging(intent)
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return BatterySnapshot(level = level, charging = charging)
}

@Composable
private fun WatchBatteryAndStepsPill(
    level: Int,
    charging: Boolean,
    steps: Int?,
    showSteps: Boolean,
    modifier: Modifier = Modifier
) {
    val launcherStyle = LauncherTheme.style
    val formattedSteps = remember(steps) {
        steps?.let {
            NumberFormat.getIntegerInstance(Locale.getDefault()).format(it)
        } ?: "--"
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        WatchBatteryPill(
            level = level,
            charging = charging,
            sizeScale = 1.3f
        )
        if (showSteps) {
            Spacer(Modifier.width(24.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                    contentDescription = null,
                    tint = launcherStyle.bodyColor,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = formattedSteps,
                    color = launcherStyle.bodyColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun rememberActivityRecognitionPermission(showSteps: Boolean): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    val context = LocalContext.current
    var granted by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { allowed ->
        granted = allowed
    }
    LaunchedEffect(showSteps, context) {
        val latest = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
        granted = latest
        if (showSteps && !latest) {
            launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }
    return granted
}

@Composable
private fun rememberStepSnapshot(
    showSteps: Boolean,
    permissionGranted: Boolean
): StepSnapshot {
    val context = LocalContext.current
    var steps by remember(showSteps, permissionGranted) { mutableStateOf<Int?>(null) }
    DisposableEffect(context, showSteps, permissionGranted) {
        if (!showSteps || !permissionGranted) {
            steps = null
            return@DisposableEffect onDispose { }
        }
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val stepCounter = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensorManager == null || stepCounter == null) {
            steps = null
            return@DisposableEffect onDispose { }
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val value = event?.values?.firstOrNull() ?: return
                steps = value.toInt().coerceAtLeast(0)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = runCatching {
            sensorManager.registerListener(listener, stepCounter, SensorManager.SENSOR_DELAY_NORMAL)
        }.getOrDefault(false)
        if (!registered) {
            steps = null
        }
        onDispose {
            runCatching { sensorManager.unregisterListener(listener) }
        }
    }
    return StepSnapshot(steps = steps)
}
private fun buildPreviewRows(groups: List<NotificationGroupUi>, maxRows: Int): List<PreviewRow> {
    val safeMaxRows = maxRows.coerceAtLeast(0)
    if (safeMaxRows == 0) return emptyList()

    val totalNotificationCount = groups.sumOf { it.entries.size }
    val distinctGroupCount = groups.size

    if (distinctGroupCount > 1 && totalNotificationCount > 1) {
        val leadEntry = groups.firstOrNull()?.entries?.firstOrNull() ?: return emptyList()
        return listOf(
            PreviewRow.Aggregate(
                leadEntry = leadEntry,
                hiddenCount = (totalNotificationCount - 1).coerceAtLeast(0)
            )
        )
    }

    return groups.take(safeMaxRows).map { group ->
        if (group.entries.size > 1) {
            PreviewRow.Group(group, listOf(group.entries.first()), group.entries.size - 1)
        } else {
            PreviewRow.Group(group, group.entries, 0)
        }
    }
}

private fun buildWidgetBindOptions(providerInfo: AppWidgetProviderInfo): Bundle {
    return Bundle().apply {
        putInt(
            AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
            providerInfo.minWidth.coerceAtLeast(120)
        )
        putInt(
            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
            providerInfo.minHeight.coerceAtLeast(120)
        )
        putInt(
            AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
            providerInfo.minResizeWidth.takeIf { it > 0 } ?: providerInfo.minWidth.coerceAtLeast(120)
        )
        putInt(
            AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
            providerInfo.minResizeHeight.takeIf { it > 0 } ?: providerInfo.minHeight.coerceAtLeast(120)
        )
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun formatClockTime(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WidgetBubble(
    widget: WidgetInfo,
    size: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) 0.958f else 1f,
        spring(stiffness = 860f, dampingRatio = 0.72f),
        label = "widget_bubble"
    )
    Box(
        Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(Color(0xFF007AFF))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = widget.label.take(2),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
