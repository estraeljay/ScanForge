package app.scanforge.markers

import app.scanforge.geometry.Vec3
import kotlin.math.floor

/** ArUco DICT_4X4_250 (exported from OpenCV, see resources). 1 = white cell. */
object ArucoDictionary {
    const val BITS = 4
    const val SIZE = 250

    private val codes: IntArray by lazy {
        val text = ArucoDictionary::class.java.getResourceAsStream("aruco_4x4_250.txt")!!.bufferedReader().readText()
        val out = IntArray(SIZE)
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val (id, hex) = line.trim().split(" ")
            out[id.toInt()] = hex.toInt(16)
        }
        out
    }

    /** Inner bit at (row, col), row 0 = top of the printed marker. */
    fun bit(id: Int, row: Int, col: Int): Boolean {
        require(id in 0 until SIZE && row in 0 until BITS && col in 0 until BITS)
        return (codes[id] shr (15 - (row * BITS + col))) and 1 == 1
    }
}

enum class PaperSize(val widthMm: Double, val heightMm: Double) {
    A4(210.0, 297.0), A3(297.0, 420.0), A2(420.0, 594.0)
}

/**
 * A printable board: a grid of ArUco markers with exactly known positions. [code] is printed on the board
 * and stored with captures, so a scan always knows which layout (and scale) it was made with.
 */
data class BoardSpec(
    val paper: PaperSize,
    val markerMm: Double,
    val gapMm: Double,
    val marginMm: Double = 12.0,
    /** Strip at the bottom of the page for the scale bar and instructions. */
    val footerMm: Double = 24.0,
) {
    val code: String get() = "SFB1-${paper.name}-${fmt(markerMm)}-${fmt(gapMm)}"

    companion object {
        /** Recommended boards. A2 fits objects with an A3-sized footprint. */
        val A4 = BoardSpec(PaperSize.A4, 30.0, 8.0)
        val A3 = BoardSpec(PaperSize.A3, 35.0, 9.0)
        val A2 = BoardSpec(PaperSize.A2, 40.0, 10.0)
        val PRESETS = listOf(A4, A3, A2)

        /** Preset by paper name ("A4", "A3", "A2", case-insensitive). */
        fun preset(name: String): BoardSpec = PRESETS.firstOrNull { it.paper.name.equals(name, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown board size '$name' (use A4, A3 or A2)")

        fun fromCode(code: String): BoardSpec {
            val p = code.split("-")
            require(p.size == 4 && p[0] == "SFB1") { "Unknown board code '$code'" }
            return BoardSpec(PaperSize.valueOf(p[1]), p[2].toDouble(), p[3].toDouble())
        }

        private fun fmt(x: Double) = if (x == floor(x)) x.toInt().toString() else x.toString()
    }
}

/** One marker on the page; (xMm, yMm) is its top-left corner, page y grows downward. */
data class PlacedMarker(val id: Int, val xMm: Double, val yMm: Double)

/**
 * Geometry of a board in two frames:
 * - page: millimeters, origin top-left of the paper, y down (for printing);
 * - world: meters, origin at the center of the marker grid, board in the plane Y = 0, +X = page right,
 *   +Z = page down, +Y = up out of the paper (right-handed, matches the ScanForge scan space).
 */
class BoardLayout(val spec: BoardSpec, val scale: Double = 1.0) {
    val cols: Int
    val rows: Int
    val markers: List<PlacedMarker>
    private val gridX0: Double
    private val gridY0: Double
    val gridWidthMm: Double
    val gridHeightMm: Double

    init {
        val pitch = spec.markerMm + spec.gapMm
        val usableW = spec.paper.widthMm - 2 * spec.marginMm
        val usableH = spec.paper.heightMm - 2 * spec.marginMm - spec.footerMm
        cols = floor((usableW + spec.gapMm) / pitch).toInt()
        rows = floor((usableH + spec.gapMm) / pitch).toInt()
        require(cols * rows in 4..ArucoDictionary.SIZE) { "Layout needs ${cols * rows} markers" }
        gridWidthMm = cols * pitch - spec.gapMm
        gridHeightMm = rows * pitch - spec.gapMm
        gridX0 = (spec.paper.widthMm - gridWidthMm) / 2
        gridY0 = spec.marginMm + (usableH - gridHeightMm) / 2
        markers = (0 until rows).flatMap { r -> (0 until cols).map { c -> PlacedMarker(r * cols + c, gridX0 + c * pitch, gridY0 + r * pitch) } }
    }

    private val byId = markers.associateBy { it.id }
    fun contains(id: Int) = id in byId

    /** Grid center in page millimeters (= world origin). */
    val centerMm get() = Pair(gridX0 + gridWidthMm / 2, gridY0 + gridHeightMm / 2)

    fun pageToWorld(xMm: Double, yMm: Double): Vec3 {
        val (cx, cy) = centerMm
        return Vec3((xMm - cx) / 1000.0 * scale, 0.0, (yMm - cy) / 1000.0 * scale)
    }

    /** World corners in OpenCV's ArUco order: top-left, top-right, bottom-right, bottom-left of the printed marker. */
    fun cornersWorld(id: Int): List<Vec3> {
        val m = byId[id] ?: throw IllegalArgumentException("Marker $id is not on board ${spec.code}")
        val s = spec.markerMm
        return listOf(pageToWorld(m.xMm, m.yMm), pageToWorld(m.xMm + s, m.yMm), pageToWorld(m.xMm + s, m.yMm + s), pageToWorld(m.xMm, m.yMm + s))
    }

    /** Printed color at a page position: true = black. Used for synthetic renders and tests. */
    fun isBlackAtPage(xMm: Double, yMm: Double): Boolean {
        val pitch = spec.markerMm + spec.gapMm
        val c = floor((xMm - gridX0) / pitch).toInt(); val r = floor((yMm - gridY0) / pitch).toInt()
        if (c !in 0 until cols || r !in 0 until rows) return false
        val lx = xMm - (gridX0 + c * pitch); val ly = yMm - (gridY0 + r * pitch)
        if (lx >= spec.markerMm || ly >= spec.markerMm) return false
        val module = spec.markerMm / (ArucoDictionary.BITS + 2)
        val mc = floor(lx / module).toInt(); val mr = floor(ly / module).toInt()
        if (mc == 0 || mr == 0 || mc == ArucoDictionary.BITS + 1 || mr == ArucoDictionary.BITS + 1) return true
        return !ArucoDictionary.bit(r * cols + c, mr - 1, mc - 1)
    }

    fun isBlackAtWorld(x: Double, z: Double): Boolean {
        val (cx, cy) = centerMm
        return isBlackAtPage(x * 1000.0 / scale + cx, z * 1000.0 / scale + cy)
    }
}
