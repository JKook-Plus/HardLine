package dev.hardline.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val EGL_RECORDABLE_ANDROID = 0x3142

/** One EGL display/context with a 1x1 pbuffer to stay current when no window exists. */
class EglCore {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    private val context: EGLContext
    private val pbuffer: EGLSurface

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL init failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL config" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        makeCurrent(pbuffer)
    }

    fun createWindowSurface(surface: Any): EGLSurface? {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        return if (s == null || s == EGL14.EGL_NO_SURFACE) null else s
    }

    fun makeCurrent(surface: EGLSurface = pbuffer): Boolean = EGL14.eglMakeCurrent(display, surface, surface, context)
    fun swap(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)
    fun setPresentationTime(surface: EGLSurface, ns: Long) = EGLExt.eglPresentationTimeANDROID(display, surface, ns)
    fun setSwapInterval(interval: Int) = EGL14.eglSwapInterval(display, interval)

    fun destroySurface(surface: EGLSurface) {
        makeCurrent()
        EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, pbuffer)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}

internal fun floatBuffer(vararg v: Float): FloatBuffer =
    ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

internal class Program(vertex: String, fragment: String) {
    val id: Int
    private val locations = HashMap<String, Int>()

    init {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(id, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(id)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: " + GLES20.glGetProgramInfoLog(id) }
    }

    fun attrib(name: String) = locations.getOrPut("a:$name") { GLES20.glGetAttribLocation(id, name) }
    fun uniform(name: String) = locations.getOrPut("u:$name") { GLES20.glGetUniformLocation(id, name) }
}

/** An off-screen colour buffer. */
internal class Fbo(val width: Int, val height: Int) {
    val texture: Int
    private val framebuffer: Int

    init {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        texParams(GLES20.GL_TEXTURE_2D, GLES20.GL_LINEAR)
        GLES20.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, width, height)
    }

    fun release() {
        GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

internal fun texParams(target: Int, filter: Int) {
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, filter)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, filter)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
}

internal fun newTexture(target: Int = GLES20.GL_TEXTURE_2D, filter: Int = GLES20.GL_LINEAR): Int {
    val ids = IntArray(1)
    GLES20.glGenTextures(1, ids, 0)
    GLES20.glBindTexture(target, ids[0])
    texParams(target, filter)
    return ids[0]
}

internal const val OES = GLES11Ext.GL_TEXTURE_EXTERNAL_OES

internal object Shaders {
    const val VERTEX = """
        attribute vec4 aPos; attribute vec2 aTex;
        uniform mat4 uMvp; uniform mat4 uTexM;
        varying vec2 vTex;
        void main() { gl_Position = uMvp * aPos; vTex = (uTexM * vec4(aTex, 0.0, 1.0)).xy; }"""

    /** Plain RGBA, with an optional vertical three-tap blend used for deinterlacing. */
    const val RGBA = """
        precision mediump float;
        varying vec2 vTex; uniform sampler2D uTex; uniform float uAlpha; uniform float uBlendStep;
        void main() {
            vec4 c = texture2D(uTex, vTex);
            if (uBlendStep > 0.0) {
                c = c * 0.5 + 0.25 * (texture2D(uTex, vTex + vec2(0.0, uBlendStep)) + texture2D(uTex, vTex - vec2(0.0, uBlendStep)));
            }
            gl_FragColor = vec4(c.rgb, c.a * uAlpha);
        }"""

    const val EXTERNAL = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTex; uniform samplerExternalOES uTex; uniform float uAlpha;
        void main() { vec4 c = texture2D(uTex, vTex); gl_FragColor = vec4(c.rgb, c.a * uAlpha); }"""

    private const val YUV_TO_RGB = """
        vec3 toRgb(float y, float u, float v) {
            y = 1.164 * (y - 0.0625); u -= 0.5; v -= 0.5;
            return vec3(y + 1.596 * v, y - 0.392 * u - 0.813 * v, y + 2.017 * u);
        }"""

    /** Packed 4:2:2 uploaded as an RGBA texture half as wide as the picture. */
    const val PACKED = """
        precision highp float;
        varying vec2 vTex; uniform sampler2D uTex; uniform float uWidth; uniform float uUyvy;
        $YUV_TO_RGB
        void main() {
            vec4 p = texture2D(uTex, vTex);
            float odd = mod(floor(vTex.x * uWidth), 2.0);
            float y = uUyvy > 0.5 ? mix(p.g, p.a, odd) : mix(p.r, p.b, odd);
            float u = uUyvy > 0.5 ? p.r : p.g;
            float v = uUyvy > 0.5 ? p.b : p.a;
            gl_FragColor = vec4(toRgb(y, u, v), 1.0);
        }"""

    /** Planar and semi-planar 4:2:0, and greyscale. uMode: 0 I420, 1 NV12, 2 NV21, 3 grey. */
    const val PLANAR = """
        precision mediump float;
        varying vec2 vTex; uniform sampler2D uY; uniform sampler2D uU; uniform sampler2D uV; uniform int uMode;
        $YUV_TO_RGB
        void main() {
            float y = texture2D(uY, vTex).r;
            float u = 0.5; float v = 0.5;
            if (uMode == 0) { u = texture2D(uU, vTex).r; v = texture2D(uV, vTex).r; }
            else if (uMode == 1) { vec4 c = texture2D(uU, vTex); u = c.r; v = c.a; }
            else if (uMode == 2) { vec4 c = texture2D(uU, vTex); u = c.a; v = c.r; }
            gl_FragColor = vec4(toRgb(y, u, v), 1.0);
        }"""
}
