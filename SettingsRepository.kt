package com.glowlevel.torch.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class PanelDockSide {
    LEFT, RIGHT
}

data class OverlaySettings(
    val discreteLevels: Int = 8,
    val dockSide: PanelDockSide = PanelDockSide.RIGHT,
    val panelOpacity: Float = 0.88f,
    val verticalOffsetPx: Int = 0,
    val isServiceRunning: Boolean = false
)

/**
 * Persists user settings in SharedPreferences and exposes a reactive StateFlow
 * shared between MainActivity and FloatingTorchService.
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadInitialSettings())
    val settings: StateFlow<OverlaySettings> = _settings.asStateFlow()

    private fun loadInitialSettings(): OverlaySettings {
        val levels = prefs.getInt(KEY_LEVELS, 8).coerceIn(5, 10)
        val sideName = prefs.getString(KEY_DOCK_SIDE, PanelDockSide.RIGHT.name) ?: PanelDockSide.RIGHT.name
        val side = runCatching { PanelDockSide.valueOf(sideName) }.getOrDefault(PanelDockSide.RIGHT)
        val opacity = prefs.getFloat(KEY_OPACITY, 0.88f).coerceIn(0.35f, 1.0f)
        val yOffset = prefs.getInt(KEY_Y_OFFSET, 0)
        return OverlaySettings(
            discreteLevels = levels,
            dockSide = side,
            panelOpacity = opacity,
            verticalOffsetPx = yOffset,
            isServiceRunning = false
        )
    }

    fun setDiscreteLevels(levels: Int) {
        val clamped = levels.coerceIn(5, 10)
        prefs.edit().putInt(KEY_LEVELS, clamped).apply()
        _settings.update { it.copy(discreteLevels = clamped) }
    }

    fun setDockSide(side: PanelDockSide) {
        prefs.edit().putString(KEY_DOCK_SIDE, side.name).apply()
        _settings.update { it.copy(dockSide = side) }
    }

    fun setPanelOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(0.35f, 1.0f)
        prefs.edit().putFloat(KEY_OPACITY, clamped).apply()
        _settings.update { it.copy(panelOpacity = clamped) }
    }

    fun setVerticalOffsetPx(offsetPx: Int) {
        prefs.edit().putInt(KEY_Y_OFFSET, offsetPx).apply()
        _settings.update { it.copy(verticalOffsetPx = offsetPx) }
    }

    fun setServiceRunning(running: Boolean) {
        _settings.update { it.copy(isServiceRunning = running) }
    }

    companion object {
        private const val PREFS_NAME = "glowlevel_prefs"
        private const val KEY_LEVELS = "key_discrete_levels"
        private const val KEY_DOCK_SIDE = "key_dock_side"
        private const val KEY_OPACITY = "key_panel_opacity"
        private const val KEY_Y_OFFSET = "key_vertical_offset_px"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context).also { INSTANCE = it }
            }
        }
    }
}
