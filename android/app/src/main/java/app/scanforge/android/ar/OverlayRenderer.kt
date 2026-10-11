package app.scanforge.android.ar

import android.opengl.GLES20
import java.nio.FloatBuffer

/** Colored points and lines in world space (live preview points, scan-region box, axes, grid). */
class OverlayRenderer {
    private var program = 0
    private var aPosition = 0
    private var aColor = 0
    private var uMvp = 0
    private var uPointSize = 0

    fun createOnGlThread() {
        program = GlUtil.program(
            """
            uniform mat4 u_Mvp;
            uniform float u_PointSize;
            attribute vec3 a_Position;
            attribute vec4 a_Color;
            varying vec4 v_Color;
            void main() { gl_Position = u_Mvp * vec4(a_Position, 1.0); gl_PointSize = u_PointSize; v_Color = a_Color; }
            """.trimIndent(),
            """
            precision mediump float;
            varying vec4 v_Color;
            void main() { gl_FragColor = v_Color; }
            """.trimIndent(),
        )
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aColor = GLES20.glGetAttribLocation(program, "a_Color")
        uMvp = GLES20.glGetUniformLocation(program, "u_Mvp")
        uPointSize = GLES20.glGetUniformLocation(program, "u_PointSize")
    }

    /** [xyz] positions; [rgba] colors per vertex. [mode] is GL_POINTS or GL_LINES. */
    fun draw(mvp: FloatArray, xyz: FloatBuffer, rgba: FloatBuffer, count: Int, mode: Int, pointSize: Float = 6f) {
        if (count == 0) return
        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform1f(uPointSize, pointSize)
        xyz.position(0); rgba.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, xyz)
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, 0, rgba)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glLineWidth(3f)
        GLES20.glDrawArrays(mode, 0, count)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aColor)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    companion object {
        /** 12 edges of an axis-aligned box as line-list vertices. */
        fun boxLines(min: FloatArray, max: FloatArray): FloatArray {
            val c = Array(8) { i -> floatArrayOf(if (i and 1 != 0) max[0] else min[0], if (i and 2 != 0) max[1] else min[1], if (i and 4 != 0) max[2] else min[2]) }
            val e = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0, 2, 1, 3, 4, 6, 5, 7, 0, 4, 1, 5, 2, 6, 3, 7)
            return FloatArray(e.size * 3) { c[e[it / 3]][it % 3] }
        }

        fun solidColors(count: Int, r: Float, g: Float, b: Float, a: Float) = FloatArray(count * 4) { when (it % 4) { 0 -> r; 1 -> g; 2 -> b; else -> a } }
    }
}
