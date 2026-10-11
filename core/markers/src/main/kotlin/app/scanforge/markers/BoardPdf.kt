package app.scanforge.markers

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Writes a board as a single-page vector PDF at true size (1 mm = 72/25.4 pt), including a 100 mm scale bar so the
 * printed scale can be checked with a ruler. Uses only standard PDF operators and the built-in Helvetica font.
 */
object BoardPdf {
    private const val PT_PER_MM = 72.0 / 25.4

    fun render(layout: BoardLayout): ByteArray {
        val spec = layout.spec
        val h = spec.paper.heightMm
        fun f(x: Double) = String.format(Locale.ROOT, "%.3f", x * PT_PER_MM)
        // PDF origin is bottom-left; page y grows down, so y_pdf = h - y_page.
        fun rect(x: Double, y: Double, w: Double, hh: Double) = "${f(x)} ${f(h - y - hh)} ${f(w)} ${f(hh)} re f\n"
        val c = StringBuilder()
        c.append("0 g\n")
        val module = spec.markerMm / (ArucoDictionary.BITS + 2)
        for (m in layout.markers) {
            c.append(rect(m.xMm, m.yMm, spec.markerMm, spec.markerMm))
            c.append("1 g\n")
            for (r in 0 until ArucoDictionary.BITS) for (col in 0 until ArucoDictionary.BITS) {
                if (ArucoDictionary.bit(m.id, r, col)) c.append(rect(m.xMm + (col + 1) * module, m.yMm + (r + 1) * module, module, module))
            }
            c.append("0 g\n")
        }
        // Scale bar: 100 mm with 10 mm ticks.
        val barY = h - spec.marginMm - 6.0
        val barX = spec.marginMm
        c.append(rect(barX, barY, 100.0, 0.6))
        for (i in 0..10) c.append(rect(barX + i * 10.0 - 0.2, barY - (if (i % 5 == 0) 4.0 else 2.0), 0.4, if (i % 5 == 0) 4.0 else 2.0))
        fun text(x: Double, y: Double, size: Double, s: String) {
            val esc = s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            c.append("BT /F1 ${String.format(Locale.ROOT, "%.1f", size)} Tf ${f(x)} ${f(h - y)} Td ($esc) Tj ET\n")
        }
        text(barX + 104.0, barY + 0.5, 7.0, "100 mm - measure this bar to verify the print scale")
        text(barX, barY - 7.5, 7.0, "ScanForge marker board ${spec.code}. Print at 100% / Actual size (no 'Fit to page'). Keep it flat; place the object in the middle.")
        val content = c.toString().toByteArray(Charsets.US_ASCII)

        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(spec.paper.widthMm)} ${f(h)}] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            null, // content stream, written separately
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        w("%PDF-1.4\n")
        val offsets = IntArray(objects.size)
        objects.forEachIndexed { i, o ->
            offsets[i] = out.size()
            w("${i + 1} 0 obj\n")
            if (o != null) w("$o\n") else {
                w("<< /Length ${content.size} >>\nstream\n"); out.write(content); w("\nendstream\n")
            }
            w("endobj\n")
        }
        val xref = out.size()
        w("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { w(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        w("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }
}
