package app.scanforge.android.settings

import android.content.Context
import app.scanforge.geometry.LengthUnit
import app.scanforge.reconstruction.QualityPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Preferences(
    val unit: LengthUnit = LengthUnit.MILLIMETER,
    val defaultPreset: QualityPreset = QualityPreset.STANDARD,
    val simplifiedMode: Boolean = true,
    val tutorialSeen: Boolean = false,
    val haptics: Boolean = true,
    val showAdvanced: Boolean = false,
    /** Marker board paper size used for new marker scans (A4, A3, A2). */
    val boardSize: String = "A2",
)

/** Persisted user preferences exposed as a StateFlow. */
class UserSettings(context: Context) {
    private val prefs = context.getSharedPreferences("scanforge_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val flow: StateFlow<Preferences> = state.asStateFlow()
    val value get() = state.value

    private fun load() = Preferences(
        unit = runCatching { LengthUnit.valueOf(prefs.getString("unit", null)!!) }.getOrDefault(LengthUnit.MILLIMETER),
        defaultPreset = runCatching { QualityPreset.valueOf(prefs.getString("preset", null)!!) }.getOrDefault(QualityPreset.STANDARD),
        simplifiedMode = prefs.getBoolean("simple", true),
        tutorialSeen = prefs.getBoolean("tutorial_seen", false),
        haptics = prefs.getBoolean("haptics", true),
        showAdvanced = prefs.getBoolean("advanced", false),
        boardSize = prefs.getString("board_size", "A2") ?: "A2",
    )

    /** Print scale of a board as measured by the user on the 100 mm bar (printed / nominal), 1.0 if never checked. */
    fun boardScale(code: String): Double = prefs.getFloat("board_scale_$code", 1f).toDouble()
    fun boardScaleChecked(code: String): Boolean = prefs.contains("board_scale_$code")
    fun setBoardScale(code: String, scale: Double) { prefs.edit().putFloat("board_scale_$code", scale.toFloat()).apply() }

    fun update(change: (Preferences) -> Preferences) {
        val p = change(state.value)
        prefs.edit()
            .putString("unit", p.unit.name).putString("preset", p.defaultPreset.name)
            .putBoolean("simple", p.simplifiedMode).putBoolean("tutorial_seen", p.tutorialSeen)
            .putBoolean("haptics", p.haptics).putBoolean("advanced", p.showAdvanced).putString("board_size", p.boardSize)
            .apply()
        state.value = p
    }
}
