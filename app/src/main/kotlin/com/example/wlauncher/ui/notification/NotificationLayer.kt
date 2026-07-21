package com.flue.launcher.ui.notification

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.ui.common.instantPressGesture
import com.flue.launcher.ui.common.rememberPressedState
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.WatchColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue

private const val NOTIFICATION_CARD_WIDTH_RATIO = 0.88f
private const val NOTIFICATION_TOP_SAFE_PADDING_DP = 22
private const val NOTIFICATION_TOP_OVERSCROLL_LIMIT = 72f
private const val NOTIFICATION_BOTTOM_OVERSCROLL_LIMIT = -96f
private const val NOTIFICATION_OVERSCROLL_RESISTANCE = 0.35f
private const val NOTIFICATION_DISMISS_DRAG_RANGE_RATIO = 0.78f
private const val NOTIFICATION_DISMISS_DRAG_RANGE_MIN = 300f
private const val NOTIFICATION_DISMISS_RELEASE_PROGRESS = 0.6f
private const val NOTIFICATION_DISMISS_FLING_VELOCITY = 1350f
private const val COLLAPSED_STACK_TRANSLATION_DP = 14
private const val COLLAPSED_STACK_HORIZONTAL_INSET_DP = 5
private val NOTIFICATION_TIME_FORMAT = ThreadLocal.withInitial {
    SimpleDateFormat("HH:mm", Locale.getDefault())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationLayer(
    isActive: Boolean,
    transitionProgress: Float,
    notificationGroups: List<NotificationGroupUi>,
    notificationAccessGranted: Boolean,
    revealedNotificationTarget: NotificationRevealTarget?,
    onRevealTargetChange: (NotificationRevealTarget?) -> Unit,
    onDismissToStack: () -> Unit,
    onTransitionProgressChange: (Float) -> Unit,
    onTransitionRelease: (Boolean) -> Unit,
    onToggleGroup: (String) -> Unit,
    onDismissGroup: (String) -> Unit,
    onDismissNotification: (String) -> Unit,
    onDismissAllNotifications: () -> Unit,
    onRunNotificationAction: (String) -> Boolean,
    onOpenNotification: (String, Offset) -> Boolean,
    stackCardColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val listState = rememberLazyListState()
    val dismissDragRangePx = remember(configuration.screenHeightDp, density) {
        maxOf(
            with(density) { configuration.screenHeightDp.dp.toPx() } * NOTIFICATION_DISMISS_DRAG_RANGE_RATIO,
            NOTIFICATION_DISMISS_DRAG_RANGE_MIN
        )
    }
    val dismissThresholdPx = dismissDragRangePx * NOTIFICATION_DISMISS_RELEASE_PROGRESS
    val overscroll = remember { androidx.compose.animation.core.Animatable(0f) }
    var overscrollTarget by remember { mutableFloatStateOf(0f) }
    var overscrollAnimating by remember { mutableStateOf(false) }

    // Single LaunchedEffect applies overscroll target changes synchronously,
    // avoiding per-call scope.launch overhead on low-end hardware.
    LaunchedEffect(overscrollTarget) {
        if (!overscrollAnimating) {
            overscroll.snapTo(overscrollTarget)
        }
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val launcherStyle = LauncherTheme.style
    var bottomOverlayHeightPx by remember { mutableIntStateOf(0) }
    val hasClearableNotifications = notificationGroups.any { group ->
        group.entries.any(NotificationEntryUi::isClearable)
    }
    var dismissTransitionInFlight by remember { mutableStateOf(false) }
    var dismissDragDistance by remember { mutableFloatStateOf(0f) }
    var dismissDragVelocityY by remember { mutableFloatStateOf(0f) }
    var lastDismissDragEventUptime by remember { mutableStateOf(0L) }

    fun applyOverscroll(delta: Float): Float {
        val next = when {
            overscroll.value > 0f && delta < 0f -> (overscroll.value + delta).coerceAtLeast(0f)
            overscroll.value < 0f && delta > 0f -> (overscroll.value + delta).coerceAtMost(0f)
            delta > 0f -> (overscroll.value + delta * NOTIFICATION_OVERSCROLL_RESISTANCE)
                .coerceIn(0f, NOTIFICATION_TOP_OVERSCROLL_LIMIT)
            delta < 0f -> (overscroll.value + delta * NOTIFICATION_OVERSCROLL_RESISTANCE)
                .coerceIn(NOTIFICATION_BOTTOM_OVERSCROLL_LIMIT, 0f)
            else -> overscroll.value
        }
        overscrollTarget = next
        return next
    }

    fun releaseOverscroll() {
        val startValue = overscroll.value
        if (startValue != 0f) {
            overscrollAnimating = true
            overscrollTarget = 0f
            scope.launch {
                try {
                    overscroll.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                } finally {
                    overscrollAnimating = false
                    overscrollTarget = 0f
                }
            }
        }
    }

    LaunchedEffect(isActive) {
        if (!isActive) {
            dismissTransitionInFlight = false
            dismissDragDistance = 0f
            dismissDragVelocityY = 0f
            lastDismissDragEventUptime = 0L
            overscrollTarget = 0f
            overscroll.snapTo(0f)
        }
    }
    val listAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
    val updateDismissDrag: (Float) -> Unit = { delta ->
        dismissDragDistance = (dismissDragDistance + delta).coerceIn(0f, dismissDragRangePx)
        onTransitionProgressChange(
            (1f - dismissDragDistance / dismissDragRangePx).coerceIn(0f, 1f)
        )
    }
    val releaseDismissDrag: () -> Unit = {
        val shouldDismiss = dismissDragDistance > dismissThresholdPx ||
            dismissDragVelocityY > NOTIFICATION_DISMISS_FLING_VELOCITY
        dismissDragDistance = 0f
        dismissDragVelocityY = 0f
        lastDismissDragEventUptime = 0L
        releaseOverscroll()
        onTransitionRelease(shouldDismiss)
    }
    val closeIfPulled: suspend () -> Unit = {
        if (!dismissTransitionInFlight) {
            if (dismissDragDistance > dismissThresholdPx) {
                dismissTransitionInFlight = true
                onRevealTargetChange(null)
                releaseDismissDrag()
            } else if (overscroll.value != 0f) {
                releaseOverscroll()
            }
        }
    }
    val staticContentDismissModifier = Modifier.pointerInput(notificationAccessGranted, notificationGroups.size, dismissTransitionInFlight) {
        if (dismissTransitionInFlight) return@pointerInput
        detectVerticalDragGestures(
            onDragStart = {
                dismissDragVelocityY = 0f
                lastDismissDragEventUptime = 0L
            },
            onVerticalDrag = { change, dragAmount ->
                if (lastDismissDragEventUptime != 0L) {
                    val deltaMs = (change.uptimeMillis - lastDismissDragEventUptime).coerceAtLeast(1L)
                    val instantVelocityY = dragAmount / deltaMs * 1000f
                    dismissDragVelocityY = if (dismissDragVelocityY == 0f) {
                        instantVelocityY
                    } else {
                        dismissDragVelocityY * 0.35f + instantVelocityY * 0.65f
                    }
                }
                lastDismissDragEventUptime = change.uptimeMillis
                if ((dragAmount > 0f && listAtTop) || dismissDragDistance > 0f) {
                    change.consume()
                    if (dismissDragDistance > 0f) {
                        val currentDrag = dismissDragDistance
                        updateDismissDrag(dragAmount)
                        if (dragAmount < 0f && dragAmount.absoluteValue > currentDrag) {
                            applyOverscroll(dragAmount + currentDrag)
                        }
                    } else {
                        applyOverscroll(dragAmount)
                        updateDismissDrag(dragAmount)
                    }
                } else if (dragAmount < 0f || overscroll.value != 0f) {
                    change.consume()
                    applyOverscroll(dragAmount)
                }
            },
            onDragEnd = {
                if (dismissDragDistance > 0f) {
                    releaseDismissDrag()
                } else {
                    scope.launch { closeIfPulled() }
                }
            },
            onDragCancel = {
                if (dismissDragDistance > 0f || transitionProgress < 1f) {
                    dismissDragDistance = 0f
                    dismissDragVelocityY = 0f
                    lastDismissDragEventUptime = 0L
                    onTransitionRelease(false)
                }
                releaseOverscroll()
            }
        )
    }
    val nestedScroll = remember(listState, dismissTransitionInFlight) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (dismissTransitionInFlight) return androidx.compose.ui.geometry.Offset.Zero
                if (source != NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                val layoutInfo = listState.layoutInfo
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                val atBottom = layoutInfo.totalItemsCount > 0 &&
                    lastVisibleItem != null &&
                    lastVisibleItem.index == layoutInfo.totalItemsCount - 1 &&
                    lastVisibleItem.offset + lastVisibleItem.size <= layoutInfo.viewportEndOffset
                if (available.y > 0f && atTop) {
                    if (dismissDragDistance > 0f) {
                        updateDismissDrag(available.y)
                    } else {
                        applyOverscroll(available.y)
                        updateDismissDrag(available.y)
                    }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                if (available.y < 0f && atBottom) {
                    applyOverscroll(available.y)
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                if (dismissDragDistance > 0f && available.y < 0f) {
                    val currentDrag = dismissDragDistance
                    updateDismissDrag(available.y)
                    if (available.y.absoluteValue > currentDrag) {
                        applyOverscroll(available.y + currentDrag)
                    }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                if (overscroll.value != 0f && ((overscroll.value > 0f && available.y < 0f) || (overscroll.value < 0f && available.y > 0f))) {
                    overscrollTarget = when {
                        overscroll.value > 0f -> (overscroll.value + available.y).coerceAtLeast(0f)
                        else -> (overscroll.value + available.y).coerceAtMost(0f)
                    }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: NestedScrollSource
            ): androidx.compose.ui.geometry.Offset {
                if (dismissTransitionInFlight) return androidx.compose.ui.geometry.Offset.Zero
                if (source != NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (available.y > 0f && atTop) {
                    if (dismissDragDistance > 0f) {
                        updateDismissDrag(available.y)
                    } else {
                        applyOverscroll(available.y)
                        updateDismissDrag(available.y)
                    }
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dismissTransitionInFlight) return available
                if (dismissDragDistance > 0f) {
                    dismissDragVelocityY = available.y
                    releaseDismissDrag()
                    return Velocity.Zero
                }
                if (overscroll.value != 0f) {
                    overscroll.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(40.dp))
            .background(launcherStyle.screenBackground)
            .nestedScroll(nestedScroll)
    ) {
        val contentWidth = maxWidth * NOTIFICATION_CARD_WIDTH_RATIO
        val bottomOverlayHeight = with(density) { bottomOverlayHeightPx.toDp() }
        val bottomScrollPadding = (bottomOverlayHeight + 24.dp).coerceAtLeast(96.dp)
        val bottomSpacerHeight = (maxHeight / 2).coerceAtLeast(124.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = overscroll.value }
                .padding(top = NOTIFICATION_TOP_SAFE_PADDING_DP.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                !notificationAccessGranted -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(staticContentDismissModifier),
                    contentAlignment = Alignment.Center
                ) {
                    PermissionCard(contentWidth) {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    }
                }

                notificationGroups.isEmpty() -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(staticContentDismissModifier),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyCard(contentWidth)
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = bottomScrollPadding)
                ) {
                    notificationGroups.forEach { group ->
                        item(
                            key = "header_${group.packageName}",
                            contentType = "group_header"
                        ) {
                            NotificationRowContainer {
                                Box(Modifier.width(contentWidth)) {
                                    GroupHeader(
                                        title = group.headerTitle,
                                        count = group.entries.size,
                                        showMeta = group.entries.size > 1,
                                        expanded = group.expanded,
                                        onClick = { onToggleGroup(group.packageName) }
                                    )
                                }
                            }
                        }
                        if (group.entries.size > 1) {
                            item(
                                key = "body_${group.packageName}",
                                contentType = "group_body"
                            ) {
                                NotificationRowContainer {
                                    AnimatedContent(
                                        targetState = group.expanded,
                                        transitionSpec = {
                                            fadeIn(tween(durationMillis = 160)) togetherWith
                                                fadeOut(tween(durationMillis = 120))
                                        },
                                        label = "notification_group_body"
                                    ) { expanded ->
                                        if (expanded) {
                                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                group.entries.forEach { entry ->
                                                    SwipeRevealDeleteContainer(
                                                        target = NotificationRevealTarget.Entry(entry.key),
                                                        revealedTarget = revealedNotificationTarget,
                                                        onRevealTargetChange = onRevealTargetChange,
                                                        enabled = entry.isClearable,
                                                        onDelete = { onDismissNotification(entry.key) },
                                                        modifier = Modifier.width(contentWidth)
                                                    ) {
                                                        NotificationCard(
                                                            width = contentWidth,
                                                            entry = entry,
                                                            subtitle = entry.text.ifBlank { entry.title.ifBlank { entry.appLabel } },
                                                            trailing = formatNotificationTime(entry.time),
                                                            onClick = { origin -> onOpenNotification(entry.key, origin) },
                                                            onRunNotificationAction = onRunNotificationAction
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            SwipeRevealDeleteContainer(
                                                target = NotificationRevealTarget.Group(group.packageName),
                                                revealedTarget = revealedNotificationTarget,
                                                onRevealTargetChange = onRevealTargetChange,
                                                enabled = group.entries.any(NotificationEntryUi::isClearable),
                                                onDelete = { onDismissGroup(group.packageName) },
                                                modifier = Modifier.width(contentWidth),
                                                actionHeight = 72.dp
                                            ) {
                                                CollapsedStackCard(
                                                    width = contentWidth,
                                                    leadEntry = group.entries.first(),
                                                    hiddenCount = group.entries.size - 1,
                                                    stackCardColor = stackCardColor,
                                                    onClick = { onToggleGroup(group.packageName) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            val entry = group.entries.first()
                            item(
                                key = "entry_${entry.key}",
                                contentType = "entry"
                            ) {
                                NotificationRowContainer {
                                    SwipeRevealDeleteContainer(
                                        target = NotificationRevealTarget.Entry(entry.key),
                                        revealedTarget = revealedNotificationTarget,
                                        onRevealTargetChange = onRevealTargetChange,
                                        enabled = entry.isClearable,
                                        onDelete = { onDismissNotification(entry.key) },
                                        modifier = Modifier.width(contentWidth)
                                    ) {
                                        NotificationCard(
                                            width = contentWidth,
                                            entry = entry,
                                            subtitle = entry.text.ifBlank { entry.title.ifBlank { entry.appLabel } },
                                            trailing = formatNotificationTime(entry.time),
                                            onClick = { origin -> onOpenNotification(entry.key, origin) },
                                            onRunNotificationAction = onRunNotificationAction
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item("notifications_bottom_spacer") {
                        Spacer(modifier = Modifier.height(bottomSpacerHeight))
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onGloballyPositioned { bottomOverlayHeightPx = it.size.height }
                .padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (hasClearableNotifications) {
                ActionDock {
                    DockActionButton(text = "全部清除", onClick = onDismissAllNotifications)
                }
            }
        }
    }
}

@Composable
private fun ActionDock(content: @Composable () -> Unit) {
    val launcherStyle = LauncherTheme.style
    val dockColor = if (launcherStyle.cardColor.alpha < 0.35f) {
        if (launcherStyle.titleColor == Color.White) {
            Color(0xFF151515).copy(alpha = 0.82f)
        } else {
            Color(0xFFF2F2F6).copy(alpha = 0.88f)
        }
    } else {
        launcherStyle.cardColor.copy(alpha = launcherStyle.cardColor.alpha.coerceAtLeast(0.72f))
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(dockColor)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun NotificationRowContainer(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun PermissionCard(width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val fake = NotificationEntryUi("perm", "", null, "开启通知访问", "开启通知访问", "完成授权后这里会显示真实通知", 0L, null, false, false, false, false)
    NotificationCard(width, fake, "完成授权后这里会显示真实通知", "", onClick = { onClick() })
}

@Composable
private fun EmptyCard(width: androidx.compose.ui.unit.Dp) {
    val fake = NotificationEntryUi("empty", "", null, "暂无通知", "暂无通知", "有新消息时会显示在这里", 0L, null, false, false, false, false)
    NotificationCard(width, fake, "有新消息时会显示在这里", "", onClick = {})
}

@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    showMeta: Boolean,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val pressed = rememberPressedState()
    val isPressed by pressed
    val launcherStyle = LauncherTheme.style
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, spring(stiffness = 840f, dampingRatio = 0.74f), label = "group_header")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (showMeta) {
                    Modifier.instantPressGesture(pressed, onClick = onClick)
                } else {
                    Modifier
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = launcherStyle.titleColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (showMeta) {
            Spacer(Modifier.weight(1f))
            Text("${count}条通知", color = launcherStyle.titleColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(28.dp).clip(CircleShape).background(launcherStyle.topBarChipColor), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = launcherStyle.topBarTextColor, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f })
            }
        }
    }
}

@Composable
private fun CollapsedStackCard(
    width: androidx.compose.ui.unit.Dp,
    leadEntry: NotificationEntryUi,
    hiddenCount: Int,
    stackCardColor: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    val stackStrength by animateFloatAsState(
        targetValue = if (hiddenCount > 0) 1f else 0f,
        animationSpec = spring(stiffness = 620f, dampingRatio = 0.82f),
        label = "collapsed_stack_strength"
    )
    var frontCardHeight by remember { mutableIntStateOf(72) }
    val density = LocalDensity.current
    val stackCardHeight: androidx.compose.ui.unit.Dp = with(density) { frontCardHeight.toDp() }
    Box(Modifier.width(width)) {
        repeat(hiddenCount.coerceIn(0, 2)) { index ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(stackCardHeight)
                    .graphicsLayer {
                        translationY = with(density) { (COLLAPSED_STACK_TRANSLATION_DP * (index + 1)).dp.toPx() } * stackStrength
                        scaleX = 1f - (index + 1) * 0.012f * stackStrength
                        scaleY = 1f - (index + 1) * 0.012f * stackStrength
                        alpha = 0.42f + (0.18f / (index + 1))
                    }
                    .padding(horizontal = COLLAPSED_STACK_HORIZONTAL_INSET_DP.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(notificationStackBackColor(stackCardColor, index))
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 2.dp)) {
            NotificationCard(
                width = width,
                entry = leadEntry,
                subtitle = leadEntry.text.ifBlank { leadEntry.title.ifBlank { leadEntry.appLabel } },
                trailing = formatNotificationTime(leadEntry.time),
                onClick = { onClick() },
                onMeasuredHeight = { heightPx ->
                    if (heightPx > 0 && frontCardHeight != heightPx) {
                        frontCardHeight = heightPx
                    }
                }
            )
            Text("+${hiddenCount}条新消息", color = WatchColors.TextTertiary, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun NotificationCard(
    width: androidx.compose.ui.unit.Dp,
    entry: NotificationEntryUi,
    subtitle: String,
    trailing: String,
    onClick: (Offset) -> Unit,
    onRunNotificationAction: (String) -> Boolean = { false },
    onMeasuredHeight: ((Int) -> Unit)? = null
) {
    val pressed = rememberPressedState()
    val isPressed by pressed
    val launcherStyle = LauncherTheme.style
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }.coerceAtLeast(1f)
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }.coerceAtLeast(1f)
    var launchOrigin by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    val cardColor = if (launcherStyle.cardColor.alpha < 0.35f) {
        if (launcherStyle.titleColor == Color.White) Color(0xFF353535) else Color(0xFFE8E8ED)
    } else {
        launcherStyle.cardColor
    }
    val scale by animateFloatAsState(if (isPressed) 0.968f else 1f, spring(stiffness = 860f, dampingRatio = 0.72f), label = "notif_card")
    Box(
        Modifier
            .width(width)
            .onGloballyPositioned { coordinates ->
                onMeasuredHeight?.invoke(coordinates.size.height)
                val position = coordinates.positionInRoot()
                launchOrigin = Offset(
                    x = ((position.x + coordinates.size.width / 2f) / screenWidthPx).coerceIn(0f, 1f),
                    y = ((position.y + coordinates.size.height / 2f) / screenHeightPx).coerceIn(0f, 1f)
                )
            }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(28.dp))
            .background(cardColor)
            .instantPressGesture(
                pressed,
                enabled = entry.key == "perm" || entry.key == "empty" || entry.contentIntentAvailable,
                onClick = { onClick(launchOrigin) }
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NotificationIcon(entry.icon)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title.ifBlank { entry.appLabel }, color = launcherStyle.titleColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, color = WatchColors.TextSecondary, fontSize = 13.sp, maxLines = 2)
                }
                if (trailing.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(trailing, color = launcherStyle.bodyColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (entry.actions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    entry.actions.take(3).forEach { action ->
                        NotificationActionButton(
                            title = action.title,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onRunNotificationAction(action.key)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationActionButton(
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val launcherStyle = LauncherTheme.style
    val pressed = rememberPressedState()
    val isPressed by pressed
    val scale by animateFloatAsState(if (isPressed) 0.955f else 1f, spring(stiffness = 820f, dampingRatio = 0.76f), label = "notif_action")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(999.dp))
            .background(launcherStyle.topBarChipColor)
            .instantPressGesture(pressed, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = launcherStyle.topBarTextColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DockActionButton(
    modifier: Modifier = Modifier,
    text: String,
    onClick: () -> Unit
) {
    val launcherStyle = LauncherTheme.style
    val pressed = rememberPressedState()
    val isPressed by pressed
    val scale by animateFloatAsState(if (isPressed) 0.955f else 1f, spring(stiffness = 820f, dampingRatio = 0.76f), label = "notif_clear_all")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(999.dp))
            .background(launcherStyle.topBarChipColor)
            .instantPressGesture(pressed, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = launcherStyle.topBarTextColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun NotificationIcon(icon: ImageBitmap?) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFFD9D9D9)), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, null, modifier = Modifier.fillMaxSize().clip(CircleShape), filterQuality = FilterQuality.Low, contentScale = ContentScale.Crop)
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

private data class BatteryStatus(
    val level: Int,
    val charging: Boolean
)

@Composable
private fun rememberBatteryStatus(): BatteryStatus {
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
    return BatteryStatus(level = level, charging = charging)
}


private fun formatNotificationTime(timestamp: Long): String {
    val formatter = NOTIFICATION_TIME_FORMAT.get() ?: SimpleDateFormat("HH:mm", Locale.getDefault()).also {
        NOTIFICATION_TIME_FORMAT.set(it)
    }
    return formatter.format(Date(timestamp))
}
