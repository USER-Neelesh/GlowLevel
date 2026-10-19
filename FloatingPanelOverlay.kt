package com.glowlevel.torch.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glowlevel.torch.data.PanelDockSide
import com.glowlevel.torch.hardware.TorchHardwareState

/**
 * Floating side panel rendered inside WindowManager (TYPE_APPLICATION_OVERLAY).
 * - Expanded dimensions: 56.dp wide by 220.dp tall.
 * - Collapsed tab dimensions: 24.dp wide by 76.dp tall docked to the screen edge.
 * - Uses an isolated top drag handle so dragging the brightness dot NEVER moves the window.
 */
@Composable
fun FloatingPanelOverlay(
    hardwareState: TorchHardwareState,
    dockSide: PanelDockSide,
    panelOpacity: Float,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onPowerToggle: () -> Unit,
    onStepChange: (Int) -> Unit,
    onDragWindowByY: (Float) -> Unit
) {
    val containerColor = Color(0xFF11131A).copy(alpha = panelOpacity.coerceIn(0.35f, 1.0f))
    val borderColor = if (hardwareState.isTorchOn) {
        Color(0xFFF59E0B).copy(alpha = 0.55f)
    } else {
        Color.White.copy(alpha = 0.14f)
    }

    val panelShape = if (isCollapsed) {
        if (dockSide == PanelDockSide.RIGHT) {
            RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
        } else {
            RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
        }
    } else {
        RoundedCornerShape(28.dp)
    }

    AnimatedContent(
        targetState = isCollapsed,
        label = "FloatingOverlayCollapseTransition"
    ) { collapsed ->
        if (collapsed) {
            // Compact edge tab (draggable vertically + tap to expand)
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .height(76.dp)
                    .clip(panelShape)
                    .background(containerColor)
                    .border(1.dp, borderColor, panelShape)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            onDragWindowByY(dragAmount)
                        }
                    }
                    .clickable { onToggleCollapse() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (hardwareState.isTorchOn) Color(0xFFFBBF24)
                            else Color.White.copy(alpha = 0.45f)
                        )
                )
            }
        } else {
            // Full 56dp x 220dp floating torch panel
            Column(
                modifier = Modifier
                    .width(56.dp)
                    .height(220.dp)
                    .clip(panelShape)
                    .background(containerColor)
                    .border(1.dp, borderColor, panelShape)
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. Dedicated Top Drag Handle + Collapse trigger
                // Touching here moves the WindowManager overlay; touching the slider below only adjusts brightness.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { change, dragAmount ->
                                change.consume()
                                onDragWindowByY(dragAmount)
                            }
                        }
                        .clickable { onToggleCollapse() },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.32f))
                    )
                }

                // 2. Power Button at top of panel
                IconButton(
                    onClick = onPowerToggle,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (hardwareState.isTorchOn) Color(0xFFF59E0B)
                            else Color(0xFF1F2430)
                        )
                ) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_lock_power_off),
                        contentDescription = if (hardwareState.isTorchOn) "Turn Torch Off" else "Turn Torch On",
                        tint = if (hardwareState.isTorchOn) Color(0xFF090A0F) else Color(0xFFE5E7EB),
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 3. Vertical Brightness Slider (or binary fallback note if unsupported)
                if (hardwareState.supportsStrengthControl) {
                    VerticalGlowSlider(
                        currentStep = hardwareState.currentUiStep,
                        totalSteps = hardwareState.discreteStepCount,
                        enabled = hardwareState.isTorchOn,
                        showPercentageOnLeft = dockSide == PanelDockSide.RIGHT,
                        onStepChanged = onStepChange,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "ON/OFF\nONLY",
                            color = Color(0xFF9CA3AF),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
