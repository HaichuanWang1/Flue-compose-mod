package com.flue.launcher.ui.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.scrollBy
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.data.model.iconForDisplay
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val FOLDER_ITEM_MENU_TRIGGER_MS = 620L
private const val FOLDER_ITEM_DRAG_OUT_MS = 2_000L
private const val FOLDER_GRID_COLUMNS = 3
private const val FOLDER_EDGE_AUTO_SCROLL_DP = 48
private const val FOLDER_EDGE_AUTO_SCROLL_MAX_PX = 14f
private const val FOLDER_LABEL_MARQUEE_DELAY_MS = 700L
private const val FOLDER_LABEL_MARQUEE_SPEED_DP_PER_SECOND = 24f

@Composable
fun AppFolderOverlay(
    folder: AppInfo,
    items: List<AppInfo>,
    availableItems: List<AppInfo>,
    listMode: Boolean,
    iconShape: Shape = if (listMode) RoundedCornerShape(18.dp) else CircleShape,
    blurEnabled: Boolean,
    twoToneIconsEnabled: Boolean,
    onAppClick: (AppInfo, Offset) -> Unit,
    onReorderItems: (List<AppInfo>) -> Unit,
    onMoveItemOut: (AppInfo) -> Unit,
    onSetFolderItems: (List<AppInfo>) -> Unit,
    onRenameFolder: (String) -> Unit,
    onExcludeApp: (AppInfo) -> Unit,
    onRemoveShortcut: (AppInfo) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val folderOverscroll = remember { androidx.compose.animation.core.Animatable(0f) }
    val visibleItems = remember { mutableStateListOf<AppInfo>() }
    val slotBounds = remember { mutableStateMapOf<Int, Rect>() }
    val slotIconBounds = remember { mutableStateMapOf<Int, Rect>() }
    var dragSlotCenters by remember { mutableStateOf<Map<Int, Offset>>(emptyMap()) }
    val itemBounds = remember { mutableStateMapOf<String, Rect>() }
    val iconBounds = remember { mutableStateMapOf<String, Rect>() }
    var showing by remember(folder.componentKey) { mutableStateOf(false) }
    var panelBounds by remember { mutableStateOf(Rect.Zero) }
    var gridBounds by remember { mutableStateOf(Rect.Zero) }
    var menuApp by remember { mutableStateOf<AppInfo?>(null) }
    var renameDialogVisible by remember { mutableStateOf(false) }
    var addDialogVisible by remember { mutableStateOf(false) }
    var selectedAddKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var renameText by remember(folder.componentKey) { mutableStateOf(folder.label) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var dragPointerRoot by remember { mutableStateOf(Offset.Zero) }
    var draggedOutsidePanel by remember { mutableStateOf(false) }
    var dragFromIndex by remember { mutableStateOf<Int?>(null) }
    var dragCurrentIndex by remember { mutableStateOf<Int?>(null) }
    var dragOriginalItems by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var moveOutJob by remember { mutableStateOf<Job?>(null) }
    var dismissJob by remember { mutableStateOf<Job?>(null) }
    val dismissInteraction = remember { MutableInteractionSource() }
    val blockInteraction = remember { MutableInteractionSource() }
    val dragThresholdPx = with(density) { 22.dp.toPx() }
    val dragIconHalfSizePx = with(density) { 29.dp.toPx() }
    val slotSwitchHysteresisPx = with(density) { 10.dp.toPx() }
    val folderOverscrollLimitPx = with(density) { 72.dp.toPx() }
    val folderAutoScrollEdgePx = with(density) { FOLDER_EDGE_AUTO_SCROLL_DP.dp.toPx() }

    fun cancelMoveOutJob() {
        moveOutJob?.cancel()
        moveOutJob = null
    }

    fun requestDismiss() {
        if (dismissJob != null) return
        cancelMoveOutJob()
        menuApp = null
        renameDialogVisible = false
        showing = false
        dismissJob = scope.launch {
            delay(190)
            onDismiss()
        }
    }

    fun finishMoveOut(app: AppInfo) {
        cancelMoveOutJob()
        draggedKey = null
        dragFromIndex = null
        dragCurrentIndex = null
        dragOriginalItems = emptyList()
        dragSlotCenters = emptyMap()
        draggedOutsidePanel = false
        onMoveItemOut(app)
        requestDismiss()
    }

    fun captureDragSlotCenters() {
        dragSlotCenters = buildMap {
            repeat(visibleItems.size) { index ->
                val center = slotIconBounds[index]?.center ?: slotBounds[index]?.center ?: return@repeat
                put(index, center)
            }
        }
    }

    fun persistOrderIfChanged(originalKeys: List<String>) {
        val currentKeys = visibleItems.map { it.componentKey }
        if (currentKeys != originalKeys) {
            onReorderItems(visibleItems.toList())
        }
    }

    fun updateDraggedItemTarget(app: AppInfo, visualCenter: Offset) {
        dragPointerRoot = visualCenter
        val insidePanel = panelBounds.contains(visualCenter)
        draggedOutsidePanel = !insidePanel
        if (insidePanel) {
            cancelMoveOutJob()
            val targetIndex = findNearestFolderSlotIndex(
                pointer = visualCenter,
                slotBounds = slotBounds,
                slotIconBounds = slotIconBounds,
                slotCentersSnapshot = dragSlotCenters,
                currentIndex = dragCurrentIndex,
                hysteresisPx = slotSwitchHysteresisPx,
                itemCount = (dragOriginalItems.ifEmpty { visibleItems }).size
            )
            if (targetIndex != null) {
                dragCurrentIndex = targetIndex
            }
        } else {
            if (moveOutJob == null) {
                moveOutJob = scope.launch {
                    delay(FOLDER_ITEM_DRAG_OUT_MS)
                    if (draggedKey == app.componentKey && draggedOutsidePanel) {
                        finishMoveOut(app)
                    }
                }
            }
        }
    }

    LaunchedEffect(folder.componentKey, items.map { it.componentKey }.joinToString("|")) {
        visibleItems.clear()
        visibleItems.addAll(items)
        slotBounds.clear()
        slotIconBounds.clear()
        dragSlotCenters = emptyMap()
        itemBounds.clear()
        iconBounds.clear()
        dragFromIndex = null
        dragCurrentIndex = null
        dragOriginalItems = emptyList()
    }
    LaunchedEffect(folder.componentKey) {
        dismissJob?.cancel()
        dismissJob = null
        showing = true
    }
    LaunchedEffect(draggedKey) {
        if (draggedKey != null && folderOverscroll.value != 0f) {
            folderOverscroll.snapTo(0f)
        }
    }
    LaunchedEffect(draggedKey, gridBounds, folderAutoScrollEdgePx) {
        while (draggedKey != null) {
            val pointer = dragPointerRoot
            val delta = when {
                !gridBounds.contains(pointer) -> 0f
                pointer.y < gridBounds.top + folderAutoScrollEdgePx -> {
                    val strength = ((gridBounds.top + folderAutoScrollEdgePx - pointer.y) / folderAutoScrollEdgePx)
                        .coerceIn(0f, 1f)
                    -FOLDER_EDGE_AUTO_SCROLL_MAX_PX * strength
                }
                pointer.y > gridBounds.bottom - folderAutoScrollEdgePx -> {
                    val strength = ((pointer.y - (gridBounds.bottom - folderAutoScrollEdgePx)) / folderAutoScrollEdgePx)
                        .coerceIn(0f, 1f)
                    FOLDER_EDGE_AUTO_SCROLL_MAX_PX * strength
                }
                else -> 0f
            }
            if (delta != 0f) {
                gridState.scrollBy(delta)
                draggedKey
                    ?.let { key -> visibleItems.firstOrNull { it.componentKey == key } }
                    ?.let { updateDraggedItemTarget(it, pointer) }
            }
            delay(16)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            cancelMoveOutJob()
            dismissJob?.cancel()
        }
    }
    BackHandler(enabled = true) { requestDismiss() }

    fun gridAtTop(): Boolean {
        return gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
    }

    fun gridAtBottom(): Boolean {
        val info = gridState.layoutInfo
        val last = info.visibleItemsInfo.maxByOrNull { it.index } ?: return true
        return last.index >= info.totalItemsCount - 1 &&
            last.offset.y + last.size.height <= info.viewportEndOffset
    }

    fun consumeFolderOverscroll(availableY: Float): Offset {
        if (draggedKey != null || availableY == 0f) return Offset.Zero
        val current = folderOverscroll.value
        val next = when {
            availableY > 0f && current < 0f ->
                (current + availableY * 0.45f).coerceAtMost(0f)
            availableY < 0f && current > 0f ->
                (current + availableY * 0.45f).coerceAtLeast(0f)
            availableY > 0f && gridAtTop() ->
                (current + availableY * 0.45f).coerceAtMost(folderOverscrollLimitPx)
            availableY < 0f && gridAtBottom() ->
                (current + availableY * 0.45f).coerceAtLeast(-folderOverscrollLimitPx)
            else -> return Offset.Zero
        }
        scope.launch { folderOverscroll.snapTo(next) }
        return Offset(0f, availableY)
    }

    val folderNestedScrollConnection = remember(gridState, draggedKey, folderOverscrollLimitPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                return consumeFolderOverscroll(available.y)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                return consumeFolderOverscroll(available.y)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (folderOverscroll.value != 0f) {
                    folderOverscroll.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 380f))
                }
                return Velocity.Zero
            }
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (showing) 1f else 0f,
        animationSpec = tween(180),
        label = "folder_overlay_alpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (showing) 1f else 0.92f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "folder_overlay_scale"
    )

    val folderItemKeys = visibleItems.mapTo(linkedSetOf()) { it.componentKey }
    val folderItemKeySet = folderItemKeys.toSet()
    val editableCandidates = (visibleItems + availableItems)
        .asSequence()
        .filterNot { it.isFolder }
        .filter { it.componentKey != folder.componentKey }
        .distinctBy { it.componentKey }
        .toList()

    if (renameDialogVisible) {
        AlertDialog(
            onDismissRequest = { renameDialogVisible = false },
            title = { Text("重命名文件夹") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameFolder(renameText)
                        renameDialogVisible = false
                    }
                ) {
                    Text("完成")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogVisible = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (addDialogVisible) {
        AlertDialog(
            onDismissRequest = {
                addDialogVisible = false
                selectedAddKeys = emptySet()
            },
            title = { Text("添加应用") },
            text = {
                if (editableCandidates.isEmpty()) {
                    Text("没有可添加的应用")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(editableCandidates, key = { it.componentKey }) { app ->
                            val selected = app.componentKey in selectedAddKeys
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .clickable {
                                        selectedAddKeys = if (selected) {
                                            selectedAddKeys - app.componentKey
                                        } else {
                                            selectedAddKeys + app.componentKey
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    bitmap = app.iconForDisplay(twoToneIconsEnabled),
                                    contentDescription = app.label,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = app.label,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                    maxLines = 1
                                )
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { checked ->
                                        selectedAddKeys = if (checked) {
                                            selectedAddKeys + app.componentKey
                                        } else {
                                            selectedAddKeys - app.componentKey
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedAddKeys.isNotEmpty(),
                    onClick = {
                        val selectedItems = editableCandidates.filter { it.componentKey in selectedAddKeys }
                        onSetFolderItems(selectedItems)
                        selectedAddKeys = emptySet()
                        addDialogVisible = false
                    }
                ) {
                    Text("完成")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        selectedAddKeys = emptySet()
                        addDialogVisible = false
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .background(Color.Black.copy(alpha = if (blurEnabled) 0.58f else 0.46f))
            .clickable(indication = null, interactionSource = dismissInteraction) { requestDismiss() },
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val panelMaxHeight = (maxHeight * 0.76f).coerceAtLeast(210.dp)
            val gridMaxHeight = (panelMaxHeight - 76.dp).coerceAtLeast(150.dp)
            val panelColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
            val panelTextColor = MaterialTheme.colorScheme.onSurface
            val manageButtonColor = MaterialTheme.colorScheme.primary
            val manageButtonBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .heightIn(max = panelMaxHeight)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .onGloballyPositioned { panelBounds = it.boundsInRoot() }
                    .clip(RoundedCornerShape(28.dp))
                    .background(panelColor)
                    .clickable(indication = null, interactionSource = blockInteraction) { }
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .align(Alignment.Center)
                    .animateContentSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FolderMarqueeLabel(
                        text = folder.label,
                        color = panelTextColor,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                renameText = folder.label
                                renameDialogVisible = true
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.W700,
                        textAlign = TextAlign.Start,
                        contentAlignment = Alignment.CenterStart
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "管理应用",
                        color = if (editableCandidates.isEmpty()) {
                            panelTextColor.copy(alpha = 0.38f)
                        } else {
                            manageButtonColor
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (editableCandidates.isEmpty()) panelTextColor.copy(alpha = 0.06f) else manageButtonBackground)
                            .clickable(enabled = editableCandidates.isNotEmpty()) {
                                selectedAddKeys = folderItemKeySet
                                addDialogVisible = true
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp, max = gridMaxHeight)
                        .nestedScroll(folderNestedScrollConnection)
                        .onGloballyPositioned { gridBounds = it.boundsInRoot() }
                        .clipToBounds()
                        .pointerInput(panelBounds, gridBounds, visibleItems.size) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val downRoot = Offset(
                                    x = gridBounds.left + down.position.x,
                                    y = gridBounds.top + down.position.y
                                )
                                val startIndex = visibleItems.indexOfFirst { app ->
                                    itemBounds[app.componentKey]?.contains(downRoot) == true
                                }
                                if (startIndex !in visibleItems.indices) return@awaitEachGesture

                                val app = visibleItems[startIndex]
                                val originalKeys = visibleItems.map { it.componentKey }
                                val dragStartCenter = iconBounds[app.componentKey]?.center
                                    ?: itemBounds[app.componentKey]?.center
                                    ?: downRoot
                                var dragVisualOffset = Offset.Zero
                                var longPressCancelled = false
                                withTimeoutOrNull(FOLDER_ITEM_MENU_TRIGGER_MS) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: run {
                                                longPressCancelled = true
                                                return@withTimeoutOrNull
                                            }
                                        val rootPointer = Offset(
                                            x = gridBounds.left + change.position.x,
                                            y = gridBounds.top + change.position.y
                                        )
                                        if (!change.pressed) {
                                            longPressCancelled = true
                                            return@withTimeoutOrNull
                                        }
                                        if ((rootPointer - downRoot).getDistance() > dragThresholdPx) {
                                            longPressCancelled = true
                                            return@withTimeoutOrNull
                                        }
                                    }
                                }
                                if (longPressCancelled) return@awaitEachGesture

                                menuApp = app
                                vibrateHaptic(context)
                                var dragActive = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val rootPointer = Offset(
                                        x = gridBounds.left + change.position.x,
                                        y = gridBounds.top + change.position.y
                                    )
                                    if (!change.pressed) break
                                    val movedDistance = (rootPointer - downRoot).getDistance()
                                    if (!dragActive && movedDistance > dragThresholdPx) {
                                        menuApp = null
                                        dragActive = true
                                        dragFromIndex = startIndex
                                        dragCurrentIndex = startIndex
                                        dragOriginalItems = visibleItems.toList()
                                        captureDragSlotCenters()
                                        draggedKey = app.componentKey
                                        draggedOutsidePanel = false
                                        dragVisualOffset = dragStartCenter - rootPointer
                                        updateDraggedItemTarget(app, rootPointer + dragVisualOffset)
                                        vibrateHaptic(context)
                                    }
                                    if (dragActive) {
                                        updateDraggedItemTarget(app, rootPointer + dragVisualOffset)
                                    }
                                    if (change.position != change.previousPosition) {
                                        change.consume()
                                    }
                                }

                                if (dragActive) {
                                    val releasedOutside = draggedOutsidePanel
                                    val finalItems = folderReorderedItems(
                                        items = dragOriginalItems.ifEmpty { visibleItems },
                                        fromIndex = dragFromIndex,
                                        toIndex = dragCurrentIndex
                                    )
                                    draggedKey = null
                                    dragFromIndex = null
                                    dragCurrentIndex = null
                                    dragOriginalItems = emptyList()
                                    dragSlotCenters = emptyMap()
                                    draggedOutsidePanel = false
                                    if (releasedOutside) {
                                        finishMoveOut(app)
                                    } else {
                                        cancelMoveOutJob()
                                        if (finalItems.isNotEmpty()) {
                                            visibleItems.clear()
                                            visibleItems.addAll(finalItems)
                                        }
                                        persistOrderIfChanged(originalKeys)
                                    }
                                }
                            }
                        }
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(FOLDER_GRID_COLUMNS),
                        state = gridState,
                        userScrollEnabled = draggedKey == null && menuApp == null,
                        contentPadding = PaddingValues(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 150.dp, max = gridMaxHeight)
                            .graphicsLayer { translationY = folderOverscroll.value }
                    ) {
                        itemsIndexed(
                            items = dragOriginalItems.ifEmpty { visibleItems },
                            key = { _, app -> app.componentKey },
                            contentType = { _, _ -> "folder_app" }
                        ) { index, app ->
                            val interactionSource = remember(app.componentKey) { MutableInteractionSource() }
                            val isPressed by interactionSource.collectIsPressedAsState()
                            val isDragged = draggedKey == app.componentKey
                            val dragDisplacement = folderDragDisplacement(
                                index = index,
                                fromIndex = dragFromIndex,
                                toIndex = dragCurrentIndex,
                                slotBounds = slotBounds,
                                slotIconBounds = slotIconBounds,
                                slotCentersSnapshot = dragSlotCenters
                            )
                            val animatedDragDisplacementX by animateFloatAsState(
                                targetValue = dragDisplacement.x,
                                animationSpec = spring(stiffness = 520f, dampingRatio = 0.84f),
                                label = "folder_drag_x"
                            )
                            val animatedDragDisplacementY by animateFloatAsState(
                                targetValue = dragDisplacement.y,
                                animationSpec = spring(stiffness = 520f, dampingRatio = 0.84f),
                                label = "folder_drag_y"
                            )
                            val pressedScale by animateFloatAsState(
                                targetValue = when {
                                    isDragged -> 0.92f
                                    isPressed -> 0.95f
                                    else -> 1f
                                },
                                animationSpec = tween(150),
                                label = "folder_item_press"
                            )
                            val iconPressedOverlay by animateFloatAsState(
                                targetValue = when {
                                    isDragged -> 0.18f
                                    isPressed -> 0.14f
                                    else -> 0f
                                },
                                animationSpec = tween(150),
                                label = "folder_item_icon_overlay"
                            )
                            Column(
                                modifier = Modifier
                                    .animateItem(
                                        fadeInSpec = tween(140),
                                        placementSpec = spring(stiffness = 520f, dampingRatio = 0.84f),
                                        fadeOutSpec = tween(120)
                                    )
                                    .onGloballyPositioned { coords ->
                                        val bounds = coords.boundsInRoot()
                                        slotBounds[index] = bounds
                                        itemBounds[app.componentKey] = bounds
                                    }
                                    .graphicsLayer {
                                        translationX = if (isDragged) 0f else animatedDragDisplacementX
                                        translationY = if (isDragged) 0f else animatedDragDisplacementY
                                        scaleX = pressedScale
                                        scaleY = pressedScale
                                        this.alpha = if (isDragged) 0f else 1f
                                    }
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = null
                                    ) {
                                        if (draggedKey == null && menuApp == null) {
                                            onAppClick(app, Offset(0.5f, 0.5f))
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(iconShape)
                                        .drawWithContent {
                                            drawContent()
                                            if (iconPressedOverlay > 0f) {
                                                drawRect(Color.Black.copy(alpha = iconPressedOverlay))
                                            }
                                        }
                                        .onGloballyPositioned { coords ->
                                            val bounds = coords.boundsInRoot()
                                            slotIconBounds[index] = bounds
                                            iconBounds[app.componentKey] = bounds
                                        }
                                ) {
                                    Image(
                                        bitmap = app.iconForDisplay(twoToneIconsEnabled),
                                        contentDescription = app.label,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(modifier = Modifier.height(5.dp))
                                FolderMarqueeLabel(
                                    text = app.label,
                                    color = panelTextColor.copy(alpha = 0.84f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clipToBounds()
                                )
                            }
                        }
                    }
                }
            }
        }

        val draggedApp = draggedKey?.let { key -> visibleItems.firstOrNull { it.componentKey == key } }
        if (draggedApp != null) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .align(Alignment.TopStart)
                    .graphicsLayer {
                        translationX = dragPointerRoot.x - dragIconHalfSizePx
                        translationY = dragPointerRoot.y - dragIconHalfSizePx
                        this.alpha = if (draggedOutsidePanel) 0.58f else 0.94f
                        scaleX = if (draggedOutsidePanel) 0.94f else 1.04f
                        scaleY = if (draggedOutsidePanel) 0.94f else 1.04f
                        shadowElevation = 18.dp.toPx()
                        shape = iconShape
                        clip = true
                    }
                    .clip(iconShape)
                    .drawWithContent {
                        drawContent()
                        drawRect(Color.Black.copy(alpha = if (draggedOutsidePanel) 0.14f else 0.18f))
                    }
            ) {
                Image(
                    bitmap = draggedApp.iconForDisplay(twoToneIconsEnabled),
                    contentDescription = draggedApp.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        menuApp?.let { app ->
            AppShortcutOverlay(
                app = app,
                blurEnabled = blurEnabled,
                onExcludeApp = null,
                onRemoveShortcut = if (app.isAppListShortcut) { { onRemoveShortcut(app) } } else null,
                onDismiss = { menuApp = null }
            )
        }
    }
}

@Composable
private fun FolderMarqueeLabel(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 10.sp,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign = TextAlign.Center,
    contentAlignment: Alignment = Alignment.Center
) {
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    var containerWidthPx by remember { mutableStateOf(0) }
    var textWidthPx by remember(text) { mutableStateOf(0) }
    val spacerWidth = 16.dp
    val spacerWidthPx = with(density) { spacerWidth.toPx() }
    val overflow = containerWidthPx > 0 && textWidthPx > containerWidthPx
    val scrollTarget = if (overflow) {
        (textWidthPx + spacerWidthPx).toInt().coerceAtLeast(0)
    } else {
        0
    }

    LaunchedEffect(text, overflow, scrollTarget, density.density) {
        scrollState.scrollTo(0)
        if (!overflow || scrollTarget <= 0) return@LaunchedEffect
        delay(FOLDER_LABEL_MARQUEE_DELAY_MS)
        while (true) {
            val durationMs = ((scrollTarget / density.density) / FOLDER_LABEL_MARQUEE_SPEED_DP_PER_SECOND * 1000f)
                .toInt()
                .coerceAtLeast(900)
            scrollState.animateScrollTo(
                value = scrollTarget,
                animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
            )
            delay(260)
            scrollState.scrollTo(0)
            delay(FOLDER_LABEL_MARQUEE_DELAY_MS)
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { containerWidthPx = it.width.coerceAtLeast(0) }
            .clipToBounds(),
        contentAlignment = contentAlignment
    ) {
        Row(
            modifier = Modifier.horizontalScroll(scrollState, enabled = false),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                textAlign = textAlign,
                onTextLayout = { textWidthPx = it.size.width.coerceAtLeast(0) }
            )
            if (overflow) {
                Spacer(modifier = Modifier.width(spacerWidth))
                Text(
                    text = text,
                    color = color,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

private fun findNearestFolderSlotIndex(
    pointer: Offset,
    slotBounds: Map<Int, Rect>,
    slotIconBounds: Map<Int, Rect>,
    slotCentersSnapshot: Map<Int, Offset>,
    currentIndex: Int? = null,
    hysteresisPx: Float = 0f,
    itemCount: Int
): Int? {
    var bestIndex: Int? = null
    var bestDistance = Float.MAX_VALUE
    var currentDistance = Float.MAX_VALUE
    repeat(itemCount.coerceAtLeast(0)) { index ->
        val center = slotCentersSnapshot[index]
            ?: slotIconBounds[index]?.center
            ?: slotBounds[index]?.center
            ?: return@repeat
        val distance = (center - pointer).getDistance()
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = index
        }
        if (index == currentIndex) {
            currentDistance = distance
        }
    }
    if (currentIndex != null && bestIndex != null && bestIndex != currentIndex) {
        if (currentDistance <= bestDistance + hysteresisPx) {
            return currentIndex
        }
    }
    return bestIndex
}

private fun folderReorderedItems(
    items: List<AppInfo>,
    fromIndex: Int?,
    toIndex: Int?
): List<AppInfo> {
    if (fromIndex == null || toIndex == null || fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) {
        return items
    }
    return items.toMutableList().apply {
        val item = removeAt(fromIndex)
        add(toIndex.coerceIn(0, size), item)
    }
}

private fun folderDragDisplacement(
    index: Int,
    fromIndex: Int?,
    toIndex: Int?,
    slotBounds: Map<Int, Rect>,
    slotIconBounds: Map<Int, Rect>,
    slotCentersSnapshot: Map<Int, Offset>
): Offset {
    if (fromIndex == null || toIndex == null || fromIndex == toIndex) return Offset.Zero
    val targetIndex = when {
        index == fromIndex -> index
        toIndex > fromIndex && index in (fromIndex + 1)..toIndex -> index - 1
        toIndex < fromIndex && index in toIndex until fromIndex -> index + 1
        else -> index
    }
    if (targetIndex == index) return Offset.Zero
    val currentCenter = slotCentersSnapshot[index]
        ?: slotIconBounds[index]?.center
        ?: slotBounds[index]?.center
        ?: return Offset.Zero
    val targetCenter = slotCentersSnapshot[targetIndex]
        ?: slotIconBounds[targetIndex]?.center
        ?: slotBounds[targetIndex]?.center
        ?: return Offset.Zero
    return targetCenter - currentCenter
}
