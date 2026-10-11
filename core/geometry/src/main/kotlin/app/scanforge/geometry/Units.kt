package app.scanforge.geometry

import java.util.Locale

/**
 * Length units supported for display and export. Internally every length is stored in
 * meters; conversion only happens at the presentation/export boundary.
 */
enum class LengthUnit(val symbol: String, val metersPerUnit: Double) {
    MILLIMETER("mm", 0.001),
    CENTIMETER("cm", 0.01),
    METER("m", 1.0),
    INCH("in", 0.0254),
    FOOT("ft", 0.3048);

    fun fromMeters(meters: Double) = meters / metersPerUnit
    fun toMeters(value: Double) = value * metersPerUnit

    /** Formats a length with a precision appropriate for the unit. */
    fun format(meters: Double, decimals: Int = defaultDecimals): String =
        String.format(Locale.ROOT, "%.${decimals}f %s", fromMeters(meters), symbol)

    /** Area units are the square of the length unit. */
    fun areaFromSquareMeters(m2: Double) = m2 / (metersPerUnit * metersPerUnit)
    fun volumeFromCubicMeters(m3: Double) = m3 / (metersPerUnit * metersPerUnit * metersPerUnit)

    val defaultDecimals: Int
        get() = when (this) {
            MILLIMETER -> 1
            CENTIMETER -> 2
            METER -> 4
            INCH -> 3
            FOOT -> 4
        }

    companion object {
        fun fromSymbol(symbol: String): LengthUnit =
            entries.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown length unit '$symbol'")
    }
}
