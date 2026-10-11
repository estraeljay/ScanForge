package app.scanforge.android.viewer

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import app.scanforge.android.ar.GlUtil
import app.scanforge.android.ar.OverlayRenderer
import app.scanforge.geometry.Mesh
import app.scanforge.geometry.Vec3
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class DisplayMode { SHADED, COLORS, WIREFRAME, EVIDENCE }

/** Renders a scan mesh with lighting, display modes, grid, axes, bounding box and measurement overlays. */
class MeshRenderer : GLSurfaceView.Renderer {
    val camera = OrbitCamera()
    @Volatile var mode = DisplayMode.COLORS
    @Volatile var showGrid = true
    @Volatile var showBounds = true
    @Volatile var measurementPoints: List<Vec3> = emptyList()
    @Volatile private var pendingMesh: Pair<Mesh, ByteArray?>? = null
    private var width = 1
    private var height = 1

    private var program = 0
    private var aPos = 0; private var aNrm = 0; private var aCol = 0; private var uMvp = 0; private var uLight = 0; private var uLit = 0
    private var pos: FloatBuffer? = null; private var nrm: FloatBuffer? = null
    private var colRgb: FloatBuffer? = null; private var colShaded: FloatBuffer? = null; private var colEvidence: FloatBuffer? = null
    private var tris: IntBuffer? = null; private var edges: IntBuffer? = null
    private var triCount = 0; private var edgeCount = 0
    private var bounds: FloatArray? = null
    private val overlay = OverlayRenderer()

    fun setMesh(mesh: Mesh, evidence: ByteArray?) { pendingMesh = mesh to evidence }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.07f, 0.08f, 0.09f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        program = GlUtil.program(
            """
            uniform mat4 u_Mvp; uniform vec3 u_Light; uniform float u_Lit;
            attribute vec3 a_Position; attribute vec3 a_Normal; attribute vec3 a_Color;
            varying vec3 v_Color;
            void main() {
                gl_Position = u_Mvp * vec4(a_Position, 1.0);
                float d = abs(dot(normalize(a_Normal), normalize(u_Light)));
                v_Color = mix(a_Color, a_Color * (0.25 + 0.75 * d), u_Lit);
            }
            """.trimIndent(),
            "precision mediump float; varying vec3 v_Color; void main() { gl_FragColor = vec4(v_Color, 1.0); }",
        )
        aPos = GLES20.glGetAttribLocation(program, "a_Position"); aNrm = GLES20.glGetAttribLocation(program, "a_Normal")
        aCol = GLES20.glGetAttribLocation(program, "a_Color"); uMvp = GLES20.glGetUniformLocation(program, "u_Mvp")
        uLight = GLES20.glGetUniformLocation(program, "u_Light"); uLit = GLES20.glGetUniformLocation(program, "u_Lit")
        overlay.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { width = w; height = h; GLES20.glViewport(0, 0, w, h) }

    private fun upload(mesh: Mesh, evidence: ByteArray?) {
        pos = GlUtil.floatBuffer(mesh.positions)
        nrm = GlUtil.floatBuffer(mesh.vertexNormals())
        val n = mesh.vertexCount
        colRgb = GlUtil.floatBuffer(FloatArray(n * 3) { i -> mesh.colors?.let { (it[i].toInt() and 0xff) / 255f } ?: 0.75f })
        colShaded = GlUtil.floatBuffer(FloatArray(n * 3) { 0.78f })
        colEvidence = GlUtil.floatBuffer(FloatArray(n * 3) { i ->
            val strong = evidence?.getOrNull(i / 3)?.toInt() == 2
            val c = if (evidence == null) floatArrayOf(0.6f, 0.6f, 0.6f) else if (strong) floatArrayOf(0.18f, 0.77f, 0.95f) else floatArrayOf(0.98f, 0.75f, 0.18f)
            c[i % 3]
        })
        tris = GlUtil.intBuffer(mesh.indices); triCount = mesh.indices.size
        val e = HashSet<Long>()
        val list = ArrayList<Int>()
        for (t in 0 until mesh.triangleCount) for (k in 0 until 3) {
            val a = mesh.indices[t * 3 + k]; val b = mesh.indices[t * 3 + (k + 1) % 3]
            if (e.add(minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong())) { list += a; list += b }
        }
        edges = GlUtil.intBuffer(list.toIntArray()); edgeCount = list.size
        mesh.bounds()?.let { bounds = OverlayRenderer.boxLines(it.min.toFloatArray(), it.max.toFloatArray()); camera.frame(it) }
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingMesh?.let { (m, ev) -> upload(m, ev); pendingMesh = null }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        camera.update(width, height)
        val mvp = camera.viewProj
        if (showGrid) drawGridAndAxes(mvp)
        val p = pos ?: return
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        val e = camera.eye() - camera.target
        GLES20.glUniform3f(uLight, e.x.toFloat(), e.y.toFloat(), e.z.toFloat())
        val colors = when (mode) { DisplayMode.COLORS -> colRgb; DisplayMode.EVIDENCE -> colEvidence; else -> colShaded }!!
        GLES20.glUniform1f(uLit, if (mode == DisplayMode.COLORS) 0.35f else 1f)
        p.position(0); nrm!!.position(0); colors.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, p)
        GLES20.glVertexAttribPointer(aNrm, 3, GLES20.GL_FLOAT, false, 0, nrm)
        GLES20.glVertexAttribPointer(aCol, 3, GLES20.GL_FLOAT, false, 0, colors)
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aNrm); GLES20.glEnableVertexAttribArray(aCol)
        if (mode == DisplayMode.WIREFRAME) {
            edges!!.position(0)
            GLES20.glDrawElements(GLES20.GL_LINES, edgeCount, GLES20.GL_UNSIGNED_INT, edges)
        } else {
            tris!!.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, triCount, GLES20.GL_UNSIGNED_INT, tris)
        }
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aNrm); GLES20.glDisableVertexAttribArray(aCol)
        if (showBounds) bounds?.let { b ->
            overlay.draw(mvp, GlUtil.floatBuffer(b), GlUtil.floatBuffer(OverlayRenderer.solidColors(b.size / 3, 0.6f, 0.65f, 0.7f, 0.6f)), b.size / 3, GLES20.GL_LINES)
        }
        drawMeasurements(mvp)
    }

    private fun drawGridAndAxes(mvp: FloatArray) {
        val c = camera.target
        val step = Math.pow(10.0, Math.floor(Math.log10(camera.distance))) / 5
        val n = 10
        val lines = ArrayList<Float>()
        val y = (bounds?.let { b -> (0 until b.size / 3).minOf { b[it * 3 + 1] } } ?: 0f)
        for (i in -n..n) {
            val o = (i * step).toFloat()
            lines += listOf((c.x + o).toFloat(), y, (c.z - n * step).toFloat(), (c.x + o).toFloat(), y, (c.z + n * step).toFloat())
            lines += listOf((c.x - n * step).toFloat(), y, (c.z + o).toFloat(), (c.x + n * step).toFloat(), y, (c.z + o).toFloat())
        }
        val g = lines.toFloatArray()
        overlay.draw(mvp, GlUtil.floatBuffer(g), GlUtil.floatBuffer(OverlayRenderer.solidColors(g.size / 3, 0.3f, 0.32f, 0.35f, 0.5f)), g.size / 3, GLES20.GL_LINES)
        val a = (step * 2).toFloat()
        val ox = c.x.toFloat(); val oz = c.z.toFloat()
        val axes = floatArrayOf(ox, y, oz, ox + a, y, oz, ox, y, oz, ox, y + a, oz, ox, y, oz, ox, y, oz + a)
        val cols = floatArrayOf(0.9f, 0.3f, 0.3f, 1f, 0.9f, 0.3f, 0.3f, 1f, 0.3f, 0.85f, 0.4f, 1f, 0.3f, 0.85f, 0.4f, 1f, 0.3f, 0.5f, 0.95f, 1f, 0.3f, 0.5f, 0.95f, 1f)
        overlay.draw(mvp, GlUtil.floatBuffer(axes), GlUtil.floatBuffer(cols), 6, GLES20.GL_LINES)
    }

    private fun drawMeasurements(mvp: FloatArray) {
        val pts = measurementPoints
        if (pts.isEmpty()) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST) // always visible, high contrast
        val xyz = FloatArray(pts.size * 3) { pts[it / 3].toFloatArray()[it % 3] }
        overlay.draw(mvp, GlUtil.floatBuffer(xyz), GlUtil.floatBuffer(OverlayRenderer.solidColors(pts.size, 1f, 1f, 1f, 1f)), pts.size, GLES20.GL_POINTS, 14f)
        if (pts.size >= 2) overlay.draw(mvp, GlUtil.floatBuffer(xyz), GlUtil.floatBuffer(OverlayRenderer.solidColors(pts.size, 0.18f, 0.77f, 0.95f, 1f)), pts.size, GLES20.GL_LINE_STRIP)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    fun viewSize() = width to height
}
