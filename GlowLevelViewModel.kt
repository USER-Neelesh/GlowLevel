package com.glowlevel.torch.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glowlevel.torch.data.OverlaySettings
import com.glowlevel.torch.data.PanelDockSide
import com.glowlevel.torch.data.SettingsRepository
import com.glowlevel.torch.hardware.TorchController
import com.glowlevel.torch.hardware.TorchHardwareState
import com.glowlevel.torch.service.FloatingTorchService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class GlowLevelUiState(
    val hardware: TorchHardwareState = TorchHardwareState(),
    val settings: OverlaySettings = OverlaySettings(),
    val hasOverlayPermission: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val overlayPermissionDeniedMessage: String? = null
)

class GlowLevelViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val torchController = TorchController.getInstance(appContext)
    private val settingsRepository = SettingsRepository.getInstance(appContext)

    private val _hasOverlayPermission = MutableStateFlow(Settings.canDrawOverlays(appContext))
    private val _hasNotificationPermission = MutableStateFlow(true)
    private val _overlayDeniedMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<GlowLevelUiState> = combine(
        torchController.state,
        settingsRepository.settings,
        _hasOverlayPermission,
        _hasNotificationPermission,
        _overlayDeniedMessage
    ) { hw, prefs, overlayGranted, notifGranted, deniedMsg ->
        GlowLevelUiState(
            hardware = hw,
            settings = prefs,
            hasOverlayPermission = overlayGranted,
            hasNotificationPermission = notifGranted,
            overlayPermissionDeniedMessage = deniedMsg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GlowLevelUiState()
    )

    fun refreshPermissions(notifGranted: Boolean) {
        val canDraw = Settings.canDrawOverlays(appContext)
        _hasOverlayPermission.value = canDraw
        _hasNotificationPermission.value = notifGranted
        if (canDraw) {
            _overlayDeniedMessage.value = null
        }
    }

    fun onOverlayPermissionResult() {
        val canDraw = Settings.canDrawOverlays(appContext)
        _hasOverlayPermission.value = canDraw
        if (!canDraw) {
            _overlayDeniedMessage.value =
                "Overlay permission was not granted. You can still use the in-app vertical brightness slider below, or enable 'Display over other apps' anytime to launch the floating side panel."
            settingsRepository.setServiceRunning(false)
        } else {
            _overlayDeniedMessage.value = null
        }
    }

    fun toggleTorch() {
        torchController.toggleTorch()
    }

    fun setTorchStep(step: Int) {
        torchController.setTorchStep(step)
    }

    fun setDiscreteLevels(levels: Int) {
        settingsRepository.setDiscreteLevels(levels)
        torchController.setDiscreteStepCount(levels)
    }

    fun setDockSide(side: PanelDockSide) {
        settingsRepository.setDockSide(side)
    }

    fun setPanelOpacity(opacity: Float) {
        settingsRepository.setPanelOpacity(opacity)
    }

    fun toggleFloatingService(): Boolean {
        val canDraw = Settings.canDrawOverlays(appContext)
        _hasOverlayPermission.value = canDraw
        if (!canDraw) {
            _overlayDeniedMessage.value =
                "Overlay permission is required before starting the floating side panel."
            return false
        }

        val currentlyRunning = settingsRepository.settings.value.isServiceRunning
        val serviceIntent = Intent(appContext, FloatingTorchService::class.java)
        if (currentlyRunning) {
            serviceIntent.action = FloatingTorchService.ACTION_STOP_OVERLAY_SERVICE
            appContext.startService(serviceIntent)
            settingsRepository.setServiceRunning(false)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(serviceIntent)
            } else {
                appContext.startService(serviceIntent)
            }
            settingsRepository.setServiceRunning(true)
        }
        return true
    }
}
