package com.glowlevel.torch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.glowlevel.torch.data.PanelDockSide
import com.glowlevel.torch.ui.components.VerticalGlowSlider
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val viewModel: GlowLevelViewModel by viewModels()

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onOverlayPermissionResult()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermissions(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFF59E0B),
                    background = Color(0xFF090A0F),
                    surface = Color(0xFF12151E)
                )
            ) {
                val state by viewModel.uiState.collectAsState()
                var showOverlayExplanationDialog by remember { mutableStateOf(false) }

                GlowLevelMainScreen(
                    state = state,
                    onToggleTorch = viewModel::toggleTorch,
                    onStepChanged = viewModel::setTorchStep,
                    onRequestOverlayExplanation = { showOverlayExplanationDialog = true },
                    onToggleFloatingService = {
                        val started = viewModel.toggleFloatingService()
                        if (!started) showOverlayExplanationDialog = true
                    },
                    onDiscreteLevelsChange = viewModel::setDiscreteLevels,
                    onDockSideChange = viewModel::setDockSide,
                    onOpacityChange = viewModel::setPanelOpacity
                )

                if (showOverlayExplanationDialog) {
                    AlertDialog(
                        onDismissRequest = { showOverlayExplanationDialog = false },
                        containerColor = Color(0xFF161924),
                        title = {
                            Text(
                                text = "Allow Display Over Other Apps",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Text(
                                text = "GlowLevel uses the Android Overlay permission (SYSTEM_ALERT_WINDOW) to dock a compact 56dp × 220dp brightness slider on the edge of your screen so you can adjust flashlight intensity while using other apps.",
                                color = Color(0xFFD1D5DB)
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showOverlayExplanationDialog = false
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:$packageName")
                                    )
                                    overlaySettingsLauncher.launch(intent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                            ) {
                                Text("Open Settings", color = Color(0xFF090A0F), fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    showOverlayExplanationDialog = false
                                    viewModel.onOverlayPermissionResult()
                                }
                            ) {
                                Text("Use In-App Only", color = Color(0xFF9CA3AF))
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        viewModel.refreshPermissions(notifGranted)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
fun GlowLevelMainScreen(
    state: GlowLevelUiState,
    onToggleTorch: () -> Unit,
    onStepChanged: (Int) -> Unit,
    onRequestOverlayExplanation: () -> Unit,
    onToggleFloatingService: () -> Unit,
    onDiscreteLevelsChange: (Int) -> Unit,
    onDockSideChange: (PanelDockSide) -> Unit,
    onOpacityChange: (Float) -> Unit
) {
    val hw = state.hardware
    val prefs = state.settings
    val isMaxLevelActive = hw.isTorchOn && hw.supportsStrengthControl && hw.currentUiStep >= hw.discreteStepCount

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090A0F))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "GlowLevel",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Precision Torch Strength & Floating Edge Slider",
            color = Color(0xFF9CA3AF),
            fontSize = 14.sp
        )

        // Hardware fallback note when brightness control is unsupported
        if (!hw.supportsStrengthControl) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1F2430))
                    .padding(14.dp)
            ) {
                Text(
                    text = "Note: Variable brightness control isn't supported by this device's camera flash hardware (MAX_LEVEL = ${hw.maxHardwareStrengthLevel}). Falling back to standard ON/OFF torch control.",
                    color = Color(0xFFFDE68A),
                    fontSize = 13.sp
                )
            }
        }

        // External camera error / unavailability warning
        hw.errorMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF3B181E))
                    .padding(14.dp)
            ) {
                Text(text = msg, color = Color(0xFFFCA5A5), fontSize = 13.sp)
            }
        }

        // Battery & Overheating Warning at Maximum Level
        if (isMaxLevelActive || hw.isThermalThrottlingWarning) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF2E2112))
                    .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = "Thermal & Battery Caution: Running the LED at 100% strength (${hw.batteryTempCelsius}°C, ${hw.batteryPercent}% battery) increases heat and battery drain.",
                    color = Color(0xFFFDE047),
                    fontSize = 13.sp
                )
            }
        }

        // Interactive In-App Torch & Vertical Slider Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF12151E))
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (hw.isTorchOn) "TORCH ACTIVE" else "TORCH STANDBY",
                    color = if (hw.isTorchOn) Color(0xFFFBBF24) else Color(0xFF9CA3AF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                val pct = ((hw.currentUiStep.toFloat() / hw.discreteStepCount.coerceAtLeast(1)) * 100).roundToInt()
                Text(
                    text = if (hw.supportsStrengthControl) "$pct%" else if (hw.isTorchOn) "ON" else "OFF",
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Step ${hw.currentUiStep} of ${hw.discreteStepCount} · HW Level ${hw.currentHardwareStrength}/${hw.maxHardwareStrengthLevel}",
                    color = Color(0xFF9CA3AF),
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onToggleTorch,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hw.isTorchOn) Color(0xFFF59E0B) else Color(0xFF262B38)
                    )
                ) {
                    Text(
                        text = if (hw.isTorchOn) "Turn Torch Off" else "Turn Torch On",
                        color = if (hw.isTorchOn) Color(0xFF090A0F) else Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(72.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                VerticalGlowSlider(
                    currentStep = hw.currentUiStep,
                    totalSteps = hw.discreteStepCount,
                    enabled = hw.isTorchOn && hw.supportsStrengthControl,
                    showPercentageOnLeft = true,
                    onStepChanged = onStepChanged
                )
            }
        }

        // Overlay Permission & Floating Side Panel Control Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF12151E))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Floating Side Panel", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        text = if (state.hasOverlayPermission) "Overlay permission granted (56dp × 220dp)"
                        else "Requires 'Display over other apps' permission",
                        color = Color(0xFF9CA3AF),
                        fontSize = 13.sp
                    )
                }
                Switch(
                    checked = prefs.isServiceRunning,
                    onCheckedChange = { onToggleFloatingService() }
                )
            }

            if (!state.hasOverlayPermission) {
                OutlinedButton(onClick = onRequestOverlayExplanation) {
                    Text("Configure Overlay Permission", color = Color(0xFFFBBF24))
                }
            }

            state.overlayPermissionDeniedMessage?.let { deniedMsg ->
                Text(text = deniedMsg, color = Color(0xFFFDE68A), fontSize = 12.sp)
            }
        }

        // Settings Card: Discrete Levels (5-10), Panel Side (Left/Right), Opacity
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF12151E))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Panel & Slider Settings", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

            Text(
                text = "Discrete Brightness Levels: ${prefs.discreteLevels}",
                color = Color(0xFFD1D5DB),
                fontSize = 14.sp
            )
            Slider(
                value = prefs.discreteLevels.toFloat(),
                onValueChange = { onDiscreteLevelsChange(it.roundToInt()) },
                valueRange = 5f..10f,
                steps = 4
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Floating Panel Dock Edge", color = Color(0xFFD1D5DB), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onDockSideChange(PanelDockSide.LEFT) }
                    ) {
                        Text(
                            text = "Left",
                            color = if (prefs.dockSide == PanelDockSide.LEFT) Color(0xFFFBBF24) else Color.Gray
                        )
                    }
                    OutlinedButton(
                        onClick = { onDockSideChange(PanelDockSide.RIGHT) }
                    ) {
                        Text(
                            text = "Right",
                            color = if (prefs.dockSide == PanelDockSide.RIGHT) Color(0xFFFBBF24) else Color.Gray
                        )
                    }
                }
            }

            Text(
                text = "Floating Panel Opacity: ${(prefs.panelOpacity * 100).roundToInt()}%",
                color = Color(0xFFD1D5DB),
                fontSize = 14.sp
            )
            Slider(
                value = prefs.panelOpacity,
                onValueChange = onOpacityChange,
                valueRange = 0.35f..1.0f
            )
        }
    }
}
