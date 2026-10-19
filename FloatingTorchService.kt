package com.glowlevel.torch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import com.glowlevel.torch.data.PanelDockSide
import com.glowlevel.torch.data.SettingsRepository
import com.glowlevel.torch.hardware.TorchController
import com.glowlevel.torch.ui.MainActivity
import com.glowlevel.torch.ui.components.FloatingPanelOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground service that manages the persistent notification and attaches
 * the Jetpack Compose floating side panel via WindowManager (TYPE_APPLICATION_OVERLAY).
 */
class FloatingTorchService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var torchController: TorchController
    private lateinit var settingsRepository: SettingsRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var overlayView: ComposeView? = null
    private var overlayLifecycleOwner: ComposeOverlayLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isCollapsed by mutableStateOf(false)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        torchController = TorchController.getInstance(this)
        settingsRepository = SettingsRepository.getInstance(this)

        createNotificationChannel()
        startForegroundWithSpecialUse()

        if (Settings.canDrawOverlays(this)) {
            showFloatingOverlay()
            settingsRepository.setServiceRunning(true)
        } else {
            stopSelf()
            return
        }

        // Observe state changes to keep the notification subtitle & dock gravity synchronized
        serviceScope.launch {
            combine(
                torchController.state,
                settingsRepository.settings
            ) { hwState, prefs -> Pair(hwState, prefs) }
                .collect { (hwState, prefs) ->
                    updateNotification(hwState.isTorchOn, hwState.currentUiStep, hwState.discreteStepCount)
                    updateOverlayGravity(prefs.dockSide, prefs.verticalOffsetPx)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TURN_OFF_TORCH -> {
                torchController.turnOffTorch()
            }
            ACTION_STOP_OVERLAY_SERVICE -> {
                torchController.releaseAndTurnOff()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithSpecialUse() {
        val notification = buildNotification(
            isTorchOn = torchController.state.value.isTorchOn,
            step = torchController.state.value.currentUiStep,
            totalSteps = torchController.state.value.discreteStepCount
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showFloatingOverlay() {
        if (overlayView != null) return

        val initialSettings = settingsRepository.settings.value
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or
                if (initialSettings.dockSide == PanelDockSide.RIGHT) Gravity.END else Gravity.START
            x = 0
            y = initialSettings.verticalOffsetPx
        }
        layoutParams = params

        val lifecycleOwner = ComposeOverlayLifecycleOwner().also {
            it.onCreate()
            it.onResume()
        }
        overlayLifecycleOwner = lifecycleOwner

        val composeView = ComposeView(this).apply {
            lifecycleOwner.attachToDecorView(this)
            setContent {
                val hwState by torchController.state.collectAsState()
                val settings by settingsRepository.settings.collectAsState()

                FloatingPanelOverlay(
                    hardwareState = hwState,
                    dockSide = settings.dockSide,
                    panelOpacity = settings.panelOpacity,
                    isCollapsed = isCollapsed,
                    onToggleCollapse = { isCollapsed = !isCollapsed },
                    onPowerToggle = { torchController.toggleTorch() },
                    onStepChange = { step -> torchController.setTorchStep(step) },
                    onDragWindowByY = { deltaY ->
                        val lp = layoutParams ?: return@FloatingPanelOverlay
                        lp.y = (lp.y + deltaY.roundToInt()).coerceIn(-600, 600)
                        settingsRepository.setVerticalOffsetPx(lp.y)
                        overlayView?.let { view ->
                            runCatching { windowManager.updateViewLayout(view, lp) }
                        }
                    }
                )
            }
        }

        overlayView = composeView
        windowManager.addView(composeView, params)
    }

    private fun updateOverlayGravity(dockSide: PanelDockSide, yOffsetPx: Int) {
        val view = overlayView ?: return
        val lp = layoutParams ?: return
        val targetGravity = Gravity.CENTER_VERTICAL or
            if (dockSide == PanelDockSide.RIGHT) Gravity.END else Gravity.START
        if (lp.gravity != targetGravity || lp.y != yOffsetPx) {
            lp.gravity = targetGravity
            lp.y = yOffsetPx
            runCatching { windowManager.updateViewLayout(view, lp) }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GlowLevel Floating Torch",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent controls for the GlowLevel floating flashlight slider"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isTorchOn: Boolean, step: Int, totalSteps: Int): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val turnOffIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingTorchService::class.java).apply { action = ACTION_TURN_OFF_TORCH },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopServiceIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, FloatingTorchService::class.java).apply { action = ACTION_STOP_OVERLAY_SERVICE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pct = ((step.toFloat() / totalSteps.coerceAtLeast(1)) * 100).roundToInt()
        val statusText = if (isTorchOn) {
            "Torch ON · Level $step/$totalSteps ($pct%)"
        } else {
            "Floating side panel active · Torch Standby"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle("GlowLevel Torch Overlay")
            .setContentText(statusText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Quick "Off" action required by specification
            .addAction(
                android.R.drawable.ic_lock_power_off,
                "Off",
                turnOffIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Close Panel",
                stopServiceIntent
            )
            .build()
    }

    private fun updateNotification(isTorchOn: Boolean, step: Int, totalSteps: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(isTorchOn, step, totalSteps))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Ensure torch hardware is released if task is swiped away and service stops
        torchController.releaseAndTurnOff()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Always release the physical torch when the service stops
        torchController.releaseAndTurnOff()
        settingsRepository.setServiceRunning(false)
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        overlayView = null
        overlayLifecycleOwner?.onDestroy()
        overlayLifecycleOwner = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "glowlevel_overlay_channel"
        private const val NOTIFICATION_ID = 4012
        const val ACTION_TURN_OFF_TORCH = "com.glowlevel.torch.action.TURN_OFF_TORCH"
        const val ACTION_STOP_OVERLAY_SERVICE = "com.glowlevel.torch.action.STOP_OVERLAY_SERVICE"
    }
}
