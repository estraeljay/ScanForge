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
    )

    fun update(change: (Preferences) -> Preferences) {
        val p = change(state.value)
        prefs.edit()
            .putString("unit", p.unit.name).putString("preset", p.defaultPreset.name)
            .putBoolean("simple", p.simplifiedMode).putBoolean("tutorial_seen", p.tutorialSeen)
            .putBoolean("haptics", p.haptics).putBoolean("advanced", p.showAdvanced)
            .apply()
        state.value = p
    }
}
