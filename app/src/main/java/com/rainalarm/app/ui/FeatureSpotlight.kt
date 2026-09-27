package com.rainalarm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt

enum class FeatureTourTarget {
    NOW_PLACE,
    NOW_COMPASS,
    NOW_GRAPH,
    NAV_RADAR,
    RADAR_TIMELINE,
    RADAR_LAYERS,
    RADAR_TRAVEL,
}

/** Compose-independent geometry so placement/state policy remains directly unit-testable. */
data class FeatureTourRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f

    fun expanded(padding: Float): FeatureTourRect = FeatureTourRect(
        left - padding,
        top - padding,
        right + padding,
        bottom + padding,
    )
}

data class FeatureTourTargetBounds(
    val target: FeatureTourTarget,
    val bounds: FeatureTourRect,
    val cornerRadiusPx: Float,
    val paddingPx: Float,
) {
    val cutout: FeatureTourRect get() = bounds.expanded(paddingPx)

    fun ready(rootWidthPx: Float, rootHeightPx: Float): Boolean =
        bounds.width > 1f && bounds.height > 1f && bounds.right > 0f && bounds.bottom > 0f &&
            bounds.left < rootWidthPx && bounds.top < rootHeightPx
}

data class FeatureTourCardPlacement(
    val xPx: Int,
    val yPx: Int,
    val maxHeightPx: Int,
    val belowTarget: Boolean,
)

internal object FeatureTourPlacementPolicy {
    /**
     * The card is initially invisible while Compose reports its measured size.  A one-pixel
     * placeholder makes any non-zero space below a target look sufficient, which can trap the
     * card in a clipped strip beneath targets near the bottom of the screen.  Use a realistic
     * minimum for that first placement; the measured height takes over immediately afterwards.
     */
    const val INITIAL_CARD_HEIGHT_DP = 176

    fun place(
        rootWidthPx: Int,
        rootHeightPx: Int,
        target: FeatureTourRect,
        cardWidthPx: Int,
        cardHeightPx: Int,
        safeTopPx: Int,
        safeBottomPx: Int,
        safeLeftPx: Int,
        safeRightPx: Int,
        marginPx: Int,
        gapPx: Int,
    ): FeatureTourCardPlacement {
        val leftLimit = safeLeftPx + marginPx
        val rightLimit = (rootWidthPx - safeRightPx - marginPx - cardWidthPx)
            .coerceAtLeast(leftLimit)
        val x = (target.centerX - cardWidthPx / 2f).roundToInt().coerceIn(leftLimit, rightLimit)
        val aboveSpace = (target.top.roundToInt() - gapPx - safeTopPx).coerceAtLeast(0)
        val belowTop = target.bottom.roundToInt() + gapPx
        val belowSpace = (rootHeightPx - safeBottomPx - belowTop).coerceAtLeast(0)
        val below = belowSpace >= cardHeightPx || (belowSpace >= aboveSpace && aboveSpace < cardHeightPx)
        val available = if (below) belowSpace else aboveSpace
        val shownHeight = cardHeightPx.coerceAtMost(available.coerceAtLeast(1))
        val y = if (below) belowTop else target.top.roundToInt() - gapPx - shownHeight
        return FeatureTourCardPlacement(
            xPx = x,
            yPx = y.coerceIn(safeTopPx, (rootHeightPx - safeBottomPx - shownHeight).coerceAtLeast(safeTopPx)),
            maxHeightPx = available.coerceAtLeast(1),
            belowTarget = below,
        )
    }
}

fun Modifier.featureTourTarget(
    target: FeatureTourTarget,
    enabled: Boolean,
    cornerRadiusDp: Float,
    paddingDp: Float = 4f,
    onBounds: (FeatureTourTargetBounds) -> Unit,
): Modifier = if (!enabled) this else composed {
    val density = LocalDensity.current
    onGloballyPositioned { coordinates ->
        val measured = coordinates.boundsInRoot()
        onBounds(
            FeatureTourTargetBounds(
                target = target,
                bounds = FeatureTourRect(
                    measured.left,
                    measured.top,
                    measured.right,
                    measured.bottom,
                ),
                cornerRadiusPx = with(density) { cornerRadiusDp.dp.toPx() },
                paddingPx = with(density) { paddingDp.dp.toPx() },
            ),
        )
    }
}

data class FeatureTourCopy(
    val title: String,
    val body: String,
    val action: String,
    val skip: String,
    val progress: String,
    val paneTitle: String,
)

@Composable
fun FeatureSpotlightOverlay(
    target: FeatureTourTargetBounds,
    copy: FeatureTourCopy,
    onAdvance: () -> Unit,
    onSkip: () -> Unit,
) {
    BackHandler(onBack = onSkip)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val interactionSource = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }
    val palette = LocalRainAlarmPalette.current
    val accent = palette.accent
    var cardSize by remember(target.target, copy.title) { mutableStateOf(IntSize.Zero) }

    BoxWithConstraints(
        Modifier.fillMaxSize().semantics {
            paneTitle = copy.paneTitle
            isTraversalGroup = true
        },
    ) {
        val rootWidthPx = with(density) { maxWidth.roundToPx() }
        val rootHeightPx = with(density) { maxHeight.roundToPx() }
        val safeTopPx = WindowInsets.safeDrawing.getTop(density)
        val safeBottomPx = WindowInsets.safeDrawing.getBottom(density)
        val safeLeftPx = WindowInsets.safeDrawing.getLeft(density, layoutDirection)
        val safeRightPx = WindowInsets.safeDrawing.getRight(density, layoutDirection)
        val marginPx = with(density) { 16.dp.roundToPx() }
        val gapPx = with(density) { 12.dp.roundToPx() }
        val cutout = target.cutout
        val radius = target.cornerRadiusPx + target.paddingPx

        Canvas(
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onSkip,
                ),
        ) {
            drawRect(Color.Black.copy(alpha = 0.72f))
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(cutout.left, cutout.top),
                size = Size(cutout.width, cutout.height),
                cornerRadius = CornerRadius(radius, radius),
                blendMode = BlendMode.Clear,
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.95f),
                topLeft = Offset(cutout.left, cutout.top),
                size = Size(cutout.width, cutout.height),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = with(density) { 2.dp.toPx() }),
            )
        }

        val preferredWidthPx = minOf(
            with(density) { 320.dp.roundToPx() },
            rootWidthPx - safeLeftPx - safeRightPx - marginPx * 2,
        ).coerceAtLeast(1)
        val placement = FeatureTourPlacementPolicy.place(
            rootWidthPx = rootWidthPx,
            rootHeightPx = rootHeightPx,
            target = cutout,
            cardWidthPx = preferredWidthPx,
            cardHeightPx = max(
                cardSize.height,
                with(density) { FeatureTourPlacementPolicy.INITIAL_CARD_HEIGHT_DP.dp.roundToPx() },
            ),
            safeTopPx = safeTopPx,
            safeBottomPx = safeBottomPx,
            safeLeftPx = safeLeftPx,
            safeRightPx = safeRightPx,
            marginPx = marginPx,
            gapPx = gapPx,
        )
        val measured = cardSize != IntSize.Zero
        Card(
            colors = CardDefaults.cardColors(
                containerColor = palette.surface,
                contentColor = palette.text,
            ),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier
                .width(with(density) { preferredWidthPx.toDp() })
                .heightIn(max = with(density) { placement.maxHeightPx.toDp() })
                .onSizeChanged { cardSize = it }
                .offset { IntOffset(placement.xPx, placement.yPx) }
                .alpha(if (measured) 1f else 0f)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {},
                )
                .focusRequester(focusRequester)
                .focusable()
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "${copy.title}. ${copy.body}"
                    stateDescription = copy.progress
                },
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(copy.progress, color = palette.muted, fontSize = 12.sp)
                Text(copy.title, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp)
                Text(copy.body, fontSize = 15.sp, lineHeight = 21.sp)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onSkip) { Text(copy.skip) }
                    Button(onClick = onAdvance) { Text(copy.action) }
                }
            }
        }
        LaunchedEffect(target.target, measured) {
            if (measured) runCatching { focusRequester.requestFocus() }
        }
    }
}
