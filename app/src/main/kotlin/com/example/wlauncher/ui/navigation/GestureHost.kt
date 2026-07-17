package com.flue.launcher.ui.navigation

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

@Composable
fun GestureHost(
    screenState: ScreenState,
    onStateChange: (ScreenState) -> Unit,
    modifier: Modifier = Modifier,
    sideScreenEnabled: Boolean = false,
    showNotification: Boolean = true,
    showWidgetPage: Boolean = true,
    showControlCenter: Boolean = false,
    widgetsBackGestureLocked: Boolean = false,
    widgetScrollAtTop: Boolean = true,
    gestureSwapWidgetApps: Boolean = false,
    content: @Composable () -> Unit
) {
    var totalDx by remember { mutableFloatStateOf(0f) }
    var totalDy by remember { mutableFloatStateOf(0f) }
    var consumed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .pointerInput(screenState, sideScreenEnabled, showWidgetPage, showControlCenter, widgetsBackGestureLocked, widgetScrollAtTop, gestureSwapWidgetApps) {
                detectDragGestures(
                    onDragStart = {
                        totalDx = 0f
                        totalDy = 0f
                        consumed = false
                    },
                    onDrag = { change, dragAmount ->
                        totalDx += dragAmount.x
                        totalDy += dragAmount.y

                        if (!consumed && (abs(totalDx) > 80 || abs(totalDy) > 80)) {
                            consumed = true
                            val isVertical = abs(totalDy) > abs(totalDx)
                            val isHorizontal = !isVertical

                            when (screenState) {
                                ScreenState.Face -> {
                                    if (gestureSwapWidgetApps) {
                                        // 交换模式：上划→列表，左划→小组件
                                        if (isVertical && totalDy < -80) {
                                            onStateChange(ScreenState.Apps)
                                            change.consume()
                                        } else if (isHorizontal && totalDx < -80 && showWidgetPage) {
                                            onStateChange(ScreenState.Widgets)
                                            change.consume()
                                        } else if (isHorizontal && totalDx > 80 && sideScreenEnabled) {
                                            onStateChange(ScreenState.Stack)
                                            change.consume()
                                        } else if (isVertical && totalDy > 80 && showControlCenter) {
                                            onStateChange(ScreenState.ControlCenter)
                                            change.consume()
                                        }
                                    } else {
                                        // 默认：上划→小组件，左划→列表
                                        if (isVertical && totalDy < -80 && showWidgetPage) {
                                            onStateChange(ScreenState.Widgets)
                                            change.consume()
                                        } else if (isHorizontal && totalDx > 80 && sideScreenEnabled) {
                                            onStateChange(ScreenState.Stack)
                                            change.consume()
                                        } else if (isHorizontal && totalDx < -80) {
                                            onStateChange(ScreenState.Apps)
                                            change.consume()
                                        } else if (isVertical && totalDy > 80 && showControlCenter) {
                                            onStateChange(ScreenState.ControlCenter)
                                            change.consume()
                                        }
                                    }
                                }

                                ScreenState.Notifications -> Unit

                                ScreenState.Widgets -> {
                                    if (gestureSwapWidgetApps) {
                                        // 交换模式：从右打开，右划退出
                                        if (widgetScrollAtTop && isHorizontal && totalDx > 50) {
                                            change.consume()
                                            onStateChange(ScreenState.Face)
                                        }
                                    } else {
                                        // 默认模式：从下滑入，下滑退出
                                        if (widgetScrollAtTop && isVertical && totalDy > 50) {
                                            change.consume()
                                            onStateChange(ScreenState.Face)
                                        }
                                    }
                                }

                                ScreenState.ControlCenter -> Unit

                                ScreenState.Apps -> {
                                    if (isHorizontal && totalDx > 80) {
                                        onStateChange(ScreenState.Face)
                                        change.consume()
                                    } else if (isHorizontal && totalDx < -80) {
                                        onStateChange(ScreenState.Face)
                                        change.consume()
                                    }
                                }
                                ScreenState.App -> Unit
                                ScreenState.Settings -> Unit
                                ScreenState.Stack -> {
                                    if (isHorizontal && totalDx > 80) {
                                        onStateChange(ScreenState.Face)
                                        change.consume()
                                    }
                                }
                            }
                        }
                    },
                    onDragEnd = {}
                )
            }
    ) {
        content()
    }
}
