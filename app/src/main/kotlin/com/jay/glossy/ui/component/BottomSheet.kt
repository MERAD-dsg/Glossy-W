/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.jay.glossy.ui.component

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.jay.glossy.constants.NavigationBarAnimationSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow

/**
 * Bottom Sheet
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic)
 */
@Composable
fun BottomSheet(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    background: @Composable (BoxScope.() -> Unit) = { },
    onDismiss: (() -> Unit)? = null,
    collapsedContent: @Composable BoxScope.() -> Unit,
    isExpandable: Boolean = true,
    /**
     * Drawn over [content] while the sheet is not collapsed, so it can follow
     * [BottomSheetState.progress] with the sheet itself and stay in step with
     * the drag. The player's opening bloom lives here — see
     * [com.jay.glossy.ui.player.PlayerOpenBloom].
     */
    openOverlay: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val saveableStateHolder = rememberSaveableStateHolder()
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    Box(
        modifier = modifier
            .graphicsLayer {
                // background fades during about 10%-61% progress
                alpha = (1.4f * (state.progress.coerceAtLeast(0.1f) - 0.1f).pow(0.5f)).coerceIn(0f, 1f)
            }
            .fillMaxSize(),
        content = background,
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            // Use graphicsLayer for offset to ensure hardware acceleration and 120Hz support
            .graphicsLayer {
                val y = (state.expandedBound - state.value)
                    .toPx()
                    .coerceAtLeast(0f)
                translationY = y
            }
            .pointerInput(state, isExpandable) {
                if (!isExpandable) return@pointerInput
                val velocityTracker = VelocityTracker()

                detectVerticalDragGestures(
                    onDragStart = {
                        state.isDragging = true
                    },
                    onVerticalDrag = { change, dragAmount ->
                        velocityTracker.addPointerInputChange(change)
                        state.dispatchRawDelta(dragAmount)
                    },
                    onDragCancel = {
                        state.isDragging = false
                        velocityTracker.resetTracking()
                        state.performFling(0f, currentOnDismiss)
                    },
                    onDragEnd = {
                        state.isDragging = false
                        val velocity = -velocityTracker.calculateVelocity().y
                        velocityTracker.resetTracking()
                        state.performFling(velocity, currentOnDismiss)
                    },
                )
            }
            .graphicsLayer {
                val cornerRadius = if (!state.isExpanded) 16.dp.toPx() else 0f
                shape = RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)
                clip = true
            },
    ) {
        if (!state.isCollapsed && !state.isDismissed) {
            PredictiveBackHandler { progress ->
                val initialValue = state.value
                state.isDragging = true
                try {
                    val range = initialValue - state.collapsedBound
                    progress.collect { event ->
                        state.snapToAndWait(
                            initialValue - range * event.progress.coerceIn(0f, 1f),
                        )
                    }
                    state.isDragging = false
                    state.collapseSoft()
                } catch (_: CancellationException) {
                    state.isDragging = false
                    state.expandSoft()
                }
            }
        }

        // main content
        if (!state.isCollapsed) {
            saveableStateHolder.SaveableStateProvider("bottom_sheet_main_content") {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = ((state.progress - 0.15f) * 4).coerceIn(0f, 1f)
                        },
                    content = content,
                )
            }

            // Drawn last so it lands on top of the content, and inside the same
            // window as it so nothing animates over an empty sheet.
            openOverlay()
        }

        if (!state.isExpanded && (onDismiss == null || !state.isDismissed)) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = 1f - (state.progress * 4).coerceAtMost(1f)
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { if (isExpandable) state.expandSoft() },
                    )
                    .focusable(false)
                    .fillMaxWidth()
                    .height(state.collapsedBound),
                content = collapsedContent,
            )
        }
    }
}

@Stable
class BottomSheetState(
    draggableState: DraggableState,
    private val coroutineScope: CoroutineScope,
    private val animatable: Animatable<Dp, AnimationVector1D>,
    private val onAnchorChanged: (Int) -> Unit,
    initialDismissedBound: Dp,
    initialCollapsedBound: Dp,
    initialExpandedBound: Dp,
    initialTargetAnchor: Int,
) : DraggableState by draggableState {
    var dismissedBound by mutableStateOf(initialDismissedBound)
        internal set

    var collapsedBound by mutableStateOf(initialCollapsedBound)
        internal set

    var expandedBound by mutableStateOf(initialExpandedBound)
        internal set

    var targetAnchor by mutableIntStateOf(initialTargetAnchor)
        internal set

    var isDragging by mutableStateOf(false)
        internal set

    val value by animatable.asState()

    val isRunning: Boolean
        get() = animatable.isRunning

    val isDismissed by derivedStateOf {
        abs((value - dismissedBound).value) <= 0.5f
    }

    val isCollapsed by derivedStateOf {
        abs((value - collapsedBound).value) <= 0.5f && targetAnchor == collapsedAnchor
    }

    val isExpanded by derivedStateOf {
        abs((expandedBound - value).value) <= 0.5f && targetAnchor == expandedAnchor
    }

    val progress by derivedStateOf {
        val span = (expandedBound - collapsedBound).value
        if (span <= 0.001f) {
            if (targetAnchor == expandedAnchor) 1f else 0f
        } else {
            1f - (expandedBound - animatable.value).value / span
        }
    }

    private fun setAnchor(anchor: Int) {
        targetAnchor = anchor
        onAnchorChanged(anchor)
    }

    fun collapse(animationSpec: AnimationSpec<Dp>) {
        setAnchor(collapsedAnchor)
        coroutineScope.launch {
            animatable.animateTo(collapsedBound, animationSpec)
        }
    }

    fun expand(animationSpec: AnimationSpec<Dp>) {
        setAnchor(expandedAnchor)
        coroutineScope.launch {
            animatable.animateTo(expandedBound, animationSpec)
        }
    }

    private fun collapse() {
        collapse(SpringSpec())
    }

    private fun expand() {
        expand(SpringSpec())
    }

    fun collapseSoft() {
        collapse(spring(stiffness = Spring.StiffnessMediumLow))
    }

    fun expandSoft() {
        expand(spring(stiffness = Spring.StiffnessMediumLow))
    }

    fun dismiss() {
        setAnchor(dismissedAnchor)
        coroutineScope.launch {
            animatable.animateTo(dismissedBound)
        }
    }

    suspend fun dismissAndWait() {
        setAnchor(dismissedAnchor)
        animatable.animateTo(dismissedBound)
    }

    fun snapTo(value: Dp) {
        coroutineScope.launch {
            animatable.snapTo(value)
        }
    }

    suspend fun snapToAndWait(value: Dp) {
        animatable.snapTo(value)
    }

    fun performFling(velocity: Float, onDismiss: (() -> Unit)?) {
        if (velocity > 250f) {
            expand()
        } else if (velocity < -250f) {
            if (value < collapsedBound && onDismiss != null) {
                dismiss()
                onDismiss.invoke()
            } else {
                collapse()
            }
        } else {
            val l0 = dismissedBound
            val l1 = (dismissedBound + collapsedBound) / 2
            val l2 = (collapsedBound + expandedBound) / 2
            val l3 = expandedBound

            when {
                value <= l1 -> {
                    if (onDismiss != null) {
                        dismiss()
                        onDismiss.invoke()
                    } else {
                        collapse()
                    }
                }
                value <= l2 -> collapse()
                value <= l3 -> expand()
                else -> expand()
            }
        }
    }

    val preUpPostDownNestedScrollConnection = object : NestedScrollConnection {
        var isTopReached = false

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (isCollapsed || isDismissed) {
                isTopReached = false
                return Offset.Zero
            }
            if (isExpanded && available.y < 0f) {
                isTopReached = false
            }

            return if (isTopReached && available.y < 0f && source == NestedScrollSource.UserInput) {
                isDragging = true
                dispatchRawDelta(available.y)
                available
            } else {
                Offset.Zero
            }
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (isCollapsed || isDismissed) {
                isTopReached = false
                return Offset.Zero
            }
            if (source == NestedScrollSource.UserInput && !isTopReached) {
                isTopReached = consumed.y == 0f && available.y > 0f
            }

            return if (isTopReached && source == NestedScrollSource.UserInput) {
                isDragging = true
                dispatchRawDelta(available.y)
                available
            } else {
                Offset.Zero
            }
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            return if (isTopReached) {
                isDragging = false
                isTopReached = false
                val velocity = -available.y
                performFling(velocity, null)
                available
            } else {
                Velocity.Zero
            }
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            isDragging = false
            isTopReached = false
            return Velocity.Zero
        }
    }
}

const val expandedAnchor = 2
const val collapsedAnchor = 1
const val dismissedAnchor = 0

@Composable
fun rememberBottomSheetState(
    dismissedBound: Dp,
    expandedBound: Dp,
    collapsedBound: Dp = dismissedBound,
    initialAnchor: Int = dismissedAnchor,
): BottomSheetState {
    val density = LocalDensity.current
    val currentDensity by rememberUpdatedState(density)
    val coroutineScope = rememberCoroutineScope()

    var previousAnchor by rememberSaveable {
        mutableIntStateOf(initialAnchor)
    }

    val safeLowerInit = dismissedBound.coerceAtMost(expandedBound)
    val safeUpperInit = expandedBound.coerceAtLeast(safeLowerInit)
    val initialTargetValue = when (previousAnchor) {
        expandedAnchor -> safeUpperInit
        collapsedAnchor -> collapsedBound.coerceIn(safeLowerInit, safeUpperInit)
        dismissedAnchor -> safeLowerInit
        else -> safeLowerInit
    }

    val animatable = remember {
        Animatable(initialTargetValue, Dp.VectorConverter).apply {
            updateBounds(safeLowerInit, safeUpperInit)
        }
    }

    val sheetState = remember(coroutineScope) {
        BottomSheetState(
            draggableState = DraggableState { delta ->
                coroutineScope.launch {
                    val dpDelta = with(currentDensity) { delta.toDp() }
                    animatable.snapTo(animatable.value - dpDelta)
                }
            },
            onAnchorChanged = { previousAnchor = it },
            coroutineScope = coroutineScope,
            animatable = animatable,
            initialDismissedBound = safeLowerInit,
            initialCollapsedBound = collapsedBound.coerceIn(safeLowerInit, safeUpperInit),
            initialExpandedBound = safeUpperInit,
            initialTargetAnchor = previousAnchor,
        )
    }

    LaunchedEffect(dismissedBound, collapsedBound, expandedBound) {
        val lower = dismissedBound.coerceAtMost(expandedBound)
        val upper = expandedBound.coerceAtLeast(lower)
        val collapsed = collapsedBound.coerceIn(lower, upper)

        val prevExpandedBound = sheetState.expandedBound
        val prevCollapsedBound = sheetState.collapsedBound
        val prevDismissedBound = sheetState.dismissedBound

        val boundsChanged =
            prevExpandedBound != upper ||
                prevCollapsedBound != collapsed ||
                prevDismissedBound != lower

        sheetState.dismissedBound = lower
        sheetState.collapsedBound = collapsed
        sheetState.expandedBound = upper
        animatable.updateBounds(lower, upper)

        if (!boundsChanged || sheetState.isDragging) return@LaunchedEffect

        val targetValue = when (sheetState.targetAnchor) {
            expandedAnchor -> upper
            collapsedAnchor -> collapsed
            dismissedAnchor -> lower
            else -> lower
        }

        when (sheetState.targetAnchor) {
            expandedAnchor -> {
                if (prevExpandedBound != upper) {
                    if (animatable.isRunning) {
                        animatable.animateTo(targetValue, spring(stiffness = Spring.StiffnessMediumLow))
                    } else {
                        animatable.snapTo(targetValue)
                    }
                }
            }
            collapsedAnchor -> {
                if (prevCollapsedBound != collapsed) {
                    animatable.animateTo(targetValue, NavigationBarAnimationSpec)
                }
            }
            dismissedAnchor -> {
                if (prevDismissedBound != lower) {
                    animatable.snapTo(targetValue)
                }
            }
        }
    }

    return sheetState
}
