package com.example.vrupsidedown

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Dessine la source (image/caméra = texture 2D, vidéo = texture externe OES)
 * deux fois côte à côte, avec :
 *  - correction de distorsion en barillet (pour les lentilles de casque)
 *  - réglage d'écart entre les images, zoom
 *  - retournement : 0 = 180° (à l'envers), 1 = miroir vertical, 2 = normal
 */
class StereoRenderer(private val onVideoSurface: (Surface) -> Unit) : GLSurfaceView.Renderer {

    enum class Source { BITMAP, VIDEO }

    @Volatile var source = Source.BITMAP
    @Volatile var flipMode = 0
    @Volatile var distortion = 0.15f
    @Volatile var eyeShift = 0f
    @Volatile var zoom = 1f
    @Volatile var videoAspect = 16f / 9f
    @Volatile private var videoReady = false

    private val pendingBitmap = AtomicReference<Bitmap?>(null)
    private val frameAvailable = AtomicBoolean(false)
    private var lastBitmap: Bitmap? = null
    private var bitmapAspect = 4f / 3f
    private var hasBitmap = false

    private var progBitmap = 0
    private var progOes = 0
    private var texBitmap = 0
    private var texOes = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var viewW = 1
    private var viewH = 1

    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    /** Appelable depuis n'importe quel thread (caméra, décodage d'image). */
    fun submitBitmap(bmp: Bitmap) {
        pendingBitmap.set(bmp)
    }

    fun resetVideo() {
        videoReady = false
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        progBitmap = buildProgram(false)
        progOes = buildProgram(true)

        val t = IntArray(2)
        GLES20.glGenTextures(2, t, 0)
        texBitmap = t[0]
        texOes = t[1]
        setupTexture(GLES20.GL_TEXTURE_2D, texBitmap)
        setupTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes)

        surfaceTexture?.release()
        surface?.release()
        val st = SurfaceTexture(texOes)
        st.setOnFrameAvailableListener { frameAvailable.set(true) }
        surfaceTexture = st
        surface = Surface(st)
        videoReady = false
        onVideoSurface(surface!!)

        // Le contexte GL a été recréé : on renvoie la dernière image si besoin
        hasBitmap = false
        lastBitmap?.let { pendingBitmap.compareAndSet(null, it) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        pendingBitmap.getAndSet(null)?.let { bmp ->
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBitmap)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bitmapAspect = bmp.width.toFloat() / bmp.height.toFloat()
            lastBitmap = bmp
            hasBitmap = true
        }

        if (frameAvailable.getAndSet(false)) {
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(texMatrix)
                videoReady = true
            } catch (e: Exception) {
            }
        }

        val useVideo = source == Source.VIDEO
        if (useVideo && !videoReady) return
        if (!useVideo && !hasBitmap) return

        val prog = if (useVideo) progOes else progBitmap
        GLES20.glUseProgram(prog)

        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (useVideo) GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes)
        else GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBitmap)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTexMatrix"), 1, false, texMatrix, 0)

        val half = viewW / 2
        val ae = half.toFloat() / viewH.toFloat()
        val asp = if (useVideo) videoAspect else bitmapAspect
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAe"), ae)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAs"), asp)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uK1"), distortion)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uK2"), distortion * 0.35f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uZoom"), zoom)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uFlip"), flipMode)
        val uShift = GLES20.glGetUniformLocation(prog, "uShift")

        for (eye in 0..1) {
            GLES20.glViewport(eye * half, 0, half, viewH)
            // œil gauche : -shift, œil droit : +shift (positif = images rapprochées du centre)
            GLES20.glUniform1f(uShift, if (eye == 0) -eyeShift else eyeShift)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun setupTexture(target: Int, tex: Int) {
        GLES20.glBindTexture(target, tex)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun buildProgram(oes: Boolean): Int {
        val ext = if (oes) "#extension GL_OES_EGL_image_external : require\n" else ""
        val decl = if (oes) "uniform samplerExternalOES uTex;" else "uniform sampler2D uTex;"
        val sample = if (oes)
            "gl_FragColor = texture2D(uTex, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy);"
        else
            "gl_FragColor = texture2D(uTex, vec2(uv.x, 1.0 - uv.y));"
        val frag = ext + FRAG.replace("%DECL%", decl).replace("%SAMPLE%", sample)

        val vs = compile(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, frag)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Link: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    companion object {
        private const val VERT = """
attribute vec2 aPos;
varying vec2 vP;
void main() {
    vP = aPos;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        private const val FRAG = """
precision highp float;
varying vec2 vP;
uniform float uAe;     // ratio d'aspect d'un oeil
uniform float uAs;     // ratio d'aspect de la source
uniform float uK1;
uniform float uK2;
uniform float uZoom;
uniform float uShift;
uniform int uFlip;
uniform mat4 uTexMatrix;
%DECL%
void main() {
    float m = max(uAe, 1.0);
    vec2 pc = vP * vec2(uAe, 1.0) / m;
    float r2 = dot(pc, pc);
    vec2 pd = pc * (1.0 + uK1 * r2 + uK2 * r2 * r2);   // barillet
    vec2 ps = pd * m / vec2(uAe, 1.0);
    ps = ps / uZoom;
    ps.x += uShift;

    vec2 s;
    if (uAe > uAs) { s = vec2(ps.x * uAe / uAs, ps.y); }
    else           { s = vec2(ps.x, ps.y * uAs / uAe); }
    vec2 uv = s * 0.5 + 0.5;

    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    if (uFlip == 0) { uv = vec2(1.0) - uv; }
    else if (uFlip == 1) { uv.y = 1.0 - uv.y; }
    %SAMPLE%
}
"""
    }
}
