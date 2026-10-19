package com.glowlevel.torch.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Immutable snapshot of hardware capabilities and live torch state.
 */
data class TorchHardwareState(
    val cameraId: String? = null,
    val hasFlashUnit: Boolean = false,
    val supportsStrengthControl: Boolean = false,
    val maxHardwareStrengthLevel: Int = 1,
    val defaultHardwareStrengthLevel: Int = 1,
    val isTorchOn: Boolean = false,
    val currentUiStep: Int = 3,
    val lastRememberedUiStep: Int = 3,
    val discreteStepCount: Int = 5,
    val currentHardwareStrength: Int = 1,
    val isCameraInUseOrUnavailable: Boolean = false,
    val errorMessage: String? = null,
    val batteryPercent: Int = 100,
    val batteryTempCelsius: Float = 31.0f,
    val isThermalThrottlingWarning: Boolean = false
)

/**
 * Single controller responsible for all CameraManager torch operations,
 * strength level mapping, external TorchCallback synchronization, and thermal/battery checks.
 */
class TorchController private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(TorchHardwareState())
    val state: StateFlow<TorchHardwareState> = _state.asStateFlow()

    /**
     * Suppresses feedback loops when our own calls trigger TorchCallback.
     */
    @Volatile
    private var internalCommandInFlight = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId != _state.value.cameraId) return
            // Triggered when another app opens the camera and claims the flash unit
            _state.update {
                it.copy(
                    isTorchOn = false,
                    isCameraInUseOrUnavailable = true,
                    errorMessage = "Camera is currently in use by another app."
                )
            }
        }

        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != _state.value.cameraId) return
            _state.update { current ->
                current.copy(
                    isTorchOn = enabled,
                    isCameraInUseOrUnavailable = false,
                    errorMessage = if (enabled) null else current.errorMessage
                )
            }
        }

        override fun onTorchStrengthLevelChanged(cameraId: String, newStrengthLevel: Int) {
            if (cameraId != _state.value.cameraId) return
            if (internalCommandInFlight) return

            _state.update { current ->
                val mappedUiStep = mapHardwareToUiStep(
                    hardwareLevel = newStrengthLevel,
                    discreteSteps = current.discreteStepCount,
                    maxHardware = current.maxHardwareStrengthLevel
                )
                current.copy(
                    currentHardwareStrength = newStrengthLevel,
                    currentUiStep = mappedUiStep,
                    lastRememberedUiStep = mappedUiStep
                )
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val tenthsOfDegree = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 310)

            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
            val tempC = tenthsOfDegree / 10f

            val thermalSevere = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
            } else {
                false
            }

            _state.update {
                it.copy(
                    batteryPercent = pct,
                    batteryTempCelsius = tempC,
                    isThermalThrottlingWarning = thermalSevere || tempC >= 41.0f
                )
            }
        }
    }

    init {
        probeRearFlashCamera()
        cameraManager.registerTorchCallback(torchCallback, mainHandler)
        appContext.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    /**
     * Queries CameraCharacteristics to locate the rear flash unit and inspect
     * FLASH_INFO_STRENGTH_MAXIMUM_LEVEL on Android 13+ (API 33+).
     */
    private fun probeRearFlashCamera() {
        try {
            val selectedId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }

            if (selectedId == null) {
                _state.update {
                    it.copy(
                        hasFlashUnit = false,
                        errorMessage = "No hardware flash unit detected on this device."
                    )
                }
                return
            }

            val characteristics = cameraManager.getCameraCharacteristics(selectedId)
            var maxStrength = 1
            var defaultStrength = 1

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                maxStrength = characteristics.get(
                    CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL
                ) ?: 1
                defaultStrength = characteristics.get(
                    CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL
                ) ?: 1
            }

            val supportsStrength = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxStrength > 1
            val initialSteps = if (supportsStrength) min(8, max(5, maxStrength)) else 5
            val defaultUiStep = if (supportsStrength) {
                mapHardwareToUiStep(defaultStrength, initialSteps, maxStrength)
            } else {
                initialSteps
            }

            _state.update {
                it.copy(
                    cameraId = selectedId,
                    hasFlashUnit = true,
                    supportsStrengthControl = supportsStrength,
                    maxHardwareStrengthLevel = maxStrength,
                    defaultHardwareStrengthLevel = defaultStrength,
                    discreteStepCount = initialSteps,
                    currentUiStep = defaultUiStep,
                    lastRememberedUiStep = defaultUiStep,
                    currentHardwareStrength = defaultStrength,
                    errorMessage = null
                )
            }
        } catch (e: CameraAccessException) {
            _state.update {
                it.copy(errorMessage = "Unable to query camera characteristics: ${e.localizedMessage}")
            }
        } catch (e: SecurityException) {
            _state.update {
                it.copy(errorMessage = "Camera permission required by OEM firmware to query flash.")
            }
        }
    }

    /**
     * Toggles the torch ON or OFF. When turning ON, restores the last remembered UI step.
     */
    fun toggleTorch() {
        val current = _state.value
        if (current.isTorchOn) {
            turnOffTorch()
        } else {
            setTorchStep(current.lastRememberedUiStep, turnOnIfOff = true)
        }
    }

    /**
     * Sets the discrete UI step (1..discreteStepCount) and applies the corresponding
     * hardware torch strength on Android 13+, or standard setTorchMode(true) on legacy devices.
     */
    fun setTorchStep(targetStep: Int, turnOnIfOff: Boolean = false) {
        val current = _state.value
        val cameraId = current.cameraId ?: return
        val clampedStep = targetStep.coerceIn(1, current.discreteStepCount)
        val mappedHwStrength = mapUiStepToHardware(
            uiStep = clampedStep,
            discreteSteps = current.discreteStepCount,
            maxHardware = current.maxHardwareStrengthLevel
        )

        val shouldDriveHardware = current.isTorchOn || turnOnIfOff
        if (!shouldDriveHardware) {
            // Update remembered step without turning on the physical LED
            _state.update {
                it.copy(
                    currentUiStep = clampedStep,
                    lastRememberedUiStep = clampedStep,
                    currentHardwareStrength = mappedHwStrength
                )
            }
            return
        }

        try {
            internalCommandInFlight = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && current.supportsStrengthControl) {
                cameraManager.turnOnTorchWithStrengthLevel(cameraId, mappedHwStrength)
            } else {
                cameraManager.setTorchMode(cameraId, true)
            }

            _state.update {
                it.copy(
                    isTorchOn = true,
                    currentUiStep = clampedStep,
                    lastRememberedUiStep = clampedStep,
                    currentHardwareStrength = mappedHwStrength,
                    isCameraInUseOrUnavailable = false,
                    errorMessage = null
                )
            }
        } catch (e: CameraAccessException) {
            _state.update {
                it.copy(
                    isTorchOn = false,
                    isCameraInUseOrUnavailable = true,
                    errorMessage = "Flashlight unavailable: Camera is in use by another app."
                )
            }
        } catch (e: IllegalArgumentException) {
            _state.update {
                it.copy(errorMessage = "Invalid strength level requested: $mappedHwStrength")
            }
        } finally {
            internalCommandInFlight = false
        }
    }

    /**
     * Configures the number of discrete snapping levels (5 to 10) on the vertical slider.
     */
    fun setDiscreteStepCount(requestedCount: Int) {
        val current = _state.value
        val safeCount = requestedCount.coerceIn(5, 10)
        val newUiStep = ((current.currentUiStep.toFloat() / current.discreteStepCount) * safeCount)
            .roundToInt()
            .coerceIn(1, safeCount)

        _state.update {
            it.copy(
                discreteStepCount = safeCount,
                currentUiStep = newUiStep,
                lastRememberedUiStep = newUiStep
            )
        }

        if (current.isTorchOn) {
            setTorchStep(newUiStep, turnOnIfOff = true)
        }
    }

    /**
     * Turns off the physical flashlight while preserving lastRememberedUiStep.
     */
    fun turnOffTorch() {
        val current = _state.value
        val cameraId = current.cameraId ?: return
        try {
            internalCommandInFlight = true
            cameraManager.setTorchMode(cameraId, false)
            _state.update {
                it.copy(isTorchOn = false, errorMessage = null)
            }
        } catch (e: CameraAccessException) {
            _state.update {
                it.copy(
                    isTorchOn = false,
                    errorMessage = "Could not release torch cleanly: ${e.localizedMessage}"
                )
            }
        } finally {
            internalCommandInFlight = false
        }
    }

    /**
     * Called when FloatingTorchService stops or is killed to guarantee the LED is turned off.
     */
    fun releaseAndTurnOff() {
        if (_state.value.isTorchOn) {
            turnOffTorch()
        }
    }

    private fun mapUiStepToHardware(uiStep: Int, discreteSteps: Int, maxHardware: Int): Int {
        if (maxHardware <= 1 || discreteSteps <= 1) return 1
        val fraction = (uiStep - 1).toFloat() / (discreteSteps - 1).toFloat()
        return (1f + fraction * (maxHardware - 1)).roundToInt().coerceIn(1, maxHardware)
    }

    private fun mapHardwareToUiStep(hardwareLevel: Int, discreteSteps: Int, maxHardware: Int): Int {
        if (maxHardware <= 1 || discreteSteps <= 1) return discreteSteps
        val fraction = (hardwareLevel - 1).toFloat() / (maxHardware - 1).toFloat()
        return (1f + fraction * (discreteSteps - 1)).roundToInt().coerceIn(1, discreteSteps)
    }

    companion object {
        @Volatile
        private var INSTANCE: TorchController? = null

        fun getInstance(context: Context): TorchController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TorchController(context).also { INSTANCE = it }
            }
        }
    }
}
