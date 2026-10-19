package com.glowlevel.torch.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Slim vertical rounded gradient bar with a spring-snapping thumb and dynamic radial glow.
 * - Dark at the bottom (low light) and warm-white/yellow at the top (high light).
 * - Triggers light haptic feedback on each discrete step transition.
 * - Shows a floating percentage callout next to the thumb while actively dragging.
 */
@Composable
fun VerticalGlowSlider(
    currentStep: Int,
    totalSteps: Int,
    enabled: Boolean,
    showPercentageOnLeft: Boolean,
    onStepChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val density = LocalDensity.current
    var trackHeightPx by remember { mutableStateOf(1f) }
    var isDragging by remember { mutableStateOf(false) }
    var lastHapticStep by remember { mutableStateOf(currentStep) }

    val safeSteps = totalSteps.coerceIn(5, 10)
    val clampedStep = currentStep.coerceIn(1, safeSteps)

    // Normalized intensity (0f at Step 1 -> 1f at max step)
    val targetFraction = if (safeSteps > 1) {
        (clampedStep - 1).toFloat() / (safeSteps - 1).toFloat()
    } else 1f

    // Smooth spring animation on the thumb and gradient intensity
    val animatedFraction by animateFloatAsState(
        targetValue = if (enabled) targetFraction else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "ThumbSpringFraction"
    )

    val animatedGlowAlpha by animateFloatAsState(
        targetValue = if (enabled) (0.25f + 0.75f * targetFraction) else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "GlowIntensityAlpha"
    )

    fun yOffsetToDiscreteStep(yPx: Float): Int {
        val usablePaddingPx = with(density) { 14.dp.toPx() }
        val usableHeight = (trackHeightPx - usablePaddingPx * 2f).coerceAtLeast(1f)
        val normalizedFromTop = ((yPx - usablePaddingPx) / usableHeight).coerceIn(0f, 1f)
        val inverted = 1f - normalizedFromTop
        val stepIndex = (inverted * (safeSteps - 1)).roundToInt()
        return (stepIndex + 1).coerceIn(1, safeSteps)
    }

    fun dispatchStepWithHaptics(newStep: Int) {
        if (newStep != lastHapticStep) {
            lastHapticStep = newStep
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onStepChanged(newStep)
        }
    }

    val percentageValue = ((clampedStep.toFloat() / safeSteps.toFloat()) * 100f).roundToInt()

    Box(
        modifier = modifier
            .width(36.dp)
            .fillMaxHeight()
            .onSizeChanged { trackHeightPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled, safeSteps) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val step = yOffsetToDiscreteStep(offset.y)
                    dispatchStepWithHaptics(step)
                }
            }
            .pointerInput(enabled, safeSteps) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        lastHapticStep = currentStep
                        dispatchStepWithHaptics(yOffsetToDiscreteStep(offset.y))
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val step = yOffsetToDiscreteStep(change.position.y)
                        dispatchStepWithHaptics(step)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .width(18.dp)
                .fillMaxHeight()
        ) {
            val barWidth = 14.dp.toPx()
            val barLeft = (size.width - barWidth) / 2f
            val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            val verticalInset = 14.dp.toPx()
            val usableHeight = (size.height - verticalInset * 2f).coerceAtLeast(1f)
            val thumbY = verticalInset + usableHeight * (1f - animatedFraction)

            // 1. Base recessed vertical track
            drawRoundRect(
                color = Color(0xFF141720),
                topLeft = Offset(barLeft, 0f),
                size = Size(barWidth, size.height),
                cornerRadius = cornerRadius
            )

            // 2. Active gradient fill (Dark warm ember at bottom -> Bright warm-white/yellow at top)
            if (enabled) {
                val topBrightColor = Color(0xFFFFFBEB)
                val midAmberColor = Color(0xFFFBBF24)
                val bottomDarkEmber = Color(0xFF292116)

                val activeTopY = thumbY.coerceIn(0f, size.height)
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            topBrightColor.copy(alpha = 0.55f + 0.45f * animatedFraction),
                            midAmberColor.copy(alpha = 0.45f + 0.50f * animatedFraction),
                            bottomDarkEmber
                        ),
                        startY = 0f,
                        endY = size.height
                    ),
                    topLeft = Offset(barLeft, activeTopY),
                    size = Size(barWidth, (size.height - activeTopY).coerceAtLeast(0f)),
                    cornerRadius = cornerRadius
                )
            }

            // 3. Discrete level tick markers
            for (i in 0 until safeSteps) {
                val stepFrac = i.toFloat() / (safeSteps - 1).toFloat()
                val tickY = verticalInset + usableHeight * (1f - stepFrac)
                val isLit = enabled && stepFrac <= animatedFraction + 0.01f
                drawCircle(
                    color = if (isLit) Color(0xFF18181B).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.22f),
                    radius = 2.dp.toPx(),
                    center = Offset(size.width / 2f, tickY)
                )
            }

            // 4. Soft radial glow around the draggable thumb that grows with intensity
            if (enabled && animatedGlowAlpha > 0.01f) {
                val maxGlowRadius = (16.dp.toPx() + (22.dp.toPx() * animatedFraction))
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFDE047).copy(alpha = 0.65f * animatedGlowAlpha),
                            Color(0xFFF59E0B).copy(alpha = 0.28f * animatedGlowAlpha),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, thumbY),
                        radius = maxGlowRadius
                    ),
                    radius = maxGlowRadius,
                    center = Offset(size.width / 2f, thumbY)
                )
            }

            // 5. Draggable Thumb (Dot)
            val thumbRadius = if (isDragging) 10.5.dp.toPx() else 9.dp.toPx()
            drawCircle(
                color = if (enabled) Color(0xFFFFFBEB) else Color(0xFF4B5563),
                radius = thumbRadius,
                center = Offset(size.width / 2f, thumbY)
            )
            drawCircle(
                color = if (enabled) Color(0xFFF59E0B) else Color(0xFF374151),
                radius = thumbRadius * 0.45f,
                center = Offset(size.width / 2f, thumbY)
            )
        }

        // 6. Floating percentage readout next to the dot while dragging
        AnimatedVisibility(
            visible = isDragging && enabled,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val usablePaddingPx = with(density) { 14.dp.toPx() }
            val usableHeight = (trackHeightPx - usablePaddingPx * 2f).coerceAtLeast(1f)
            val thumbOffsetFromCenterPx =
                (usablePaddingPx + usableHeight * (1f - animatedFraction)) - (trackHeightPx / 2f)
            val horizontalOffsetPx = with(density) {
                (if (showPercentageOnLeft) (-48).dp else 48.dp).roundToPx()
            }

            Surface(
                modifier = Modifier.offset {
                    IntOffset(
                        x = horizontalOffsetPx,
                        y = thumbOffsetFromCenterPx.roundToInt()
                    )
                },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xEE18181B),
                shadowElevation = 6.dp
            ) {
                Text(
                    text = "$percentageValue%",
                    color = Color(0xFFFEF08A),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
    }
}
