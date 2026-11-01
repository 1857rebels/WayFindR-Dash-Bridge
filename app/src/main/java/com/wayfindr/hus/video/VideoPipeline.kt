package com.wayfindr.hus.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.wayfindr.hus.Config
import com.wayfindr.hus.Point
import com.wayfindr.hus.Rect
import com.wayfindr.hus.log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The phone version of the Pi's ffmpeg filter graph, on the GPU:
 *   Android Auto H.264 (800x480) -> hardware decoder -> SurfaceTexture -> OpenGL ES composite
 *   -> hardware H.264 encoder (526x300, Baseline 4.1, 4 fps, ~200 kbps CBR, 1 s IDR).
 * The composite is the fill layout: a blurred, dimmed copy of the map behind the map crop placed
 * at Config.layout(), plus AA's clock / signal / battery strip top centre (Config.statusStrip).
 * While Android Auto isn't connected it encodes a "Waiting for Android Auto" card so the dash
 * keeps getting frames. Twice a second the Re-centre strip (Config.probe) is read back to find
 * Maps' teal "Re-centre" label for the joystick.
 */
class VideoPipeline(
    private val configSource: () -> Config,
    /** Whether anyone wants frames (a dash session); with none, nothing is drawn or encoded. */
    private val wanted: () -> Boolean,
    private val onEncoded: (ByteArray, Boolean) -> Unit,
) {
    /** Read on every use, so the view chosen on the app screen applies at once. */
    private val config get() = configSource()

    companion object {
        const val WIDTH = 526
        const val HEIGHT = 300
        private const val SRC_W = 800
        private const val SRC_H = 480
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val MAX_PENDING = 30

        private const val VS = """
            attribute vec2 aPos; attribute vec2 aTex; uniform mat4 uTexM; varying vec2 vTex;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); vTex = (uTexM * vec4(aTex, 0.0, 1.0)).xy; }"""
        private const val FS_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 vTex; uniform samplerExternalOES uTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }"""
        // 3x3 taps uStep apart (0 = plain copy), then a brightness offset (eq=brightness).
        private const val FS_2D = """
            precision mediump float; varying vec2 vTex; uniform sampler2D uTex; uniform vec2 uStep; uniform float uBright;
            void main() {
                vec4 c = vec4(0.0);
                for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++)
                    c += texture2D(uTex, vTex + vec2(float(i), float(j)) * uStep);
                gl_FragColor = vec4(c.rgb / 9.0 + uBright, 1.0);
            }"""
    }

    private val thread = HandlerThread("hus-gl").apply { start() }
    private val gl = Handler(thread.looper)
    private val codecThread = HandlerThread("hus-codec").apply { start() }
    private val codecHandler = Handler(codecThread.looper)

    private var encoder: MediaCodec? = null
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesProg = 0
    private var texProg = 0
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var decoderSurface: Surface? = null
    private val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val bgW = WIDTH / 4
    private val bgH = HEIGHT / 4
    private var bgFbo = 0
    private var bgTex = 0
    private var probeFbo = 0
    private var probeTex = 0
    private lateinit var probeBuf: ByteBuffer
    private var waitingTex = 0

    // Decoder (one per Android Auto session), fed from the AA reader thread.
    private var decoder: MediaCodec? = null
    private val decoderLock = Any()
    private val freeInputs = ArrayDeque<Int>()
    private val pending = ArrayDeque<ByteArray>()
    @Volatile private var showAa = false
    @Volatile var hasFrame = false
        private set

    @Volatile private var recentre: Pair<Long, Point?> = 0L to null
    private var tick = 0L
    private var nextTickAt = 0L
    @Volatile var encodedFrames = 0L
        private set
    @Volatile var decodedFrames = 0L
        private set

    fun start() = gl.post {
        try {
            setupEncoder()
            setupEgl()
            setupGl()
            nextTickAt = SystemClock.uptimeMillis()
            gl.post(::onTick)
            log("video: encoder ${encoder?.name} ${WIDTH}x$HEIGHT, ${config.fps} fps, ${config.bitrate / 1000} kbps")
        } catch (e: Exception) {
            log("video: setup failed: $e")
        }
    }

    fun stop() {
        stopDecoder()
        gl.post {
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }; encoder = null
            surfaceTexture?.release(); decoderSurface?.release()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            thread.quitSafely()
            codecThread.quitSafely()
        }
    }

    /** Asks the encoder for a keyframe now (a newly attached dash starts from one). */
    fun requestKeyFrame() {
        runCatching { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    /** AA frame point of Maps' "Re-centre" button if it showed in the last second or so. */
    fun findRecentre(): Point? = recentre.let { (at, p) -> if (SystemClock.uptimeMillis() - at < 1500) p else null }

    private var captureFbo = 0
    private var captureTex = 0

    /** Counts pixels of AA's latest frame inside [r] (800x480 frame px) that pass [match] (r, g, b),
     *  on the GL thread; [done] gets -1 if no frame has arrived. */
    fun countPixels(r: Rect, match: (Int, Int, Int) -> Boolean, done: (Int) -> Unit) = gl.post {
        if (!hasFrame) { done(-1); return@post }
        if (captureFbo == 0) fbo(SRC_W, SRC_H).let { (fb, tex) -> captureFbo = fb; captureTex = tex }
        target(captureFbo, SRC_W, SRC_H)
        drawOes(0f, 0f, SRC_W.toFloat(), SRC_H.toFloat(), Rect(0, 0, SRC_W, SRC_H), SRC_W, SRC_H)
        val buf = ByteBuffer.allocateDirect(r.w * r.h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(r.x, SRC_H - r.y - r.h, r.w, r.h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        target(0, WIDTH, HEIGHT)
        var n = 0
        for (i in 0 until r.w * r.h) {
            if (match(buf.get(i * 4).toInt() and 0xFF, buf.get(i * 4 + 1).toInt() and 0xFF, buf.get(i * 4 + 2).toInt() and 0xFF)) n++
        }
        done(n)
    }

    /** Saves Android Auto's whole latest 800x480 frame (before any crop) as a PNG, for measuring
     *  where an app draws its buttons. [done] gets false if no frame has arrived yet. */
    fun capture(file: java.io.File, done: (Boolean) -> Unit) = gl.post {
        if (!hasFrame) { done(false); return@post }
        try {
            if (captureFbo == 0) fbo(SRC_W, SRC_H).let { (fb, tex) -> captureFbo = fb; captureTex = tex }
            target(captureFbo, SRC_W, SRC_H)
            drawOes(0f, 0f, SRC_W.toFloat(), SRC_H.toFloat(), Rect(0, 0, SRC_W, SRC_H), SRC_W, SRC_H)
            val buf = ByteBuffer.allocateDirect(SRC_W * SRC_H * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, SRC_W, SRC_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            target(0, WIDTH, HEIGHT)
            val raw = Bitmap.createBitmap(SRC_W, SRC_H, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buf) }
            // glReadPixels rows start at the bottom
            val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
            val bmp = Bitmap.createBitmap(raw, 0, 0, SRC_W, SRC_H, flip, false)
            file.parentFile?.mkdirs()
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            raw.recycle(); bmp.recycle()
            done(true)
        } catch (e: Exception) {
            log("video: capture failed: $e")
            target(0, WIDTH, HEIGHT)
            done(false)
        }
    }

    // ── Decoder ─────────────────────────────────────────────────────────────────

    /** New Android Auto session: a fresh decoder onto the compositor's SurfaceTexture. */
    fun startDecoder() {
        stopDecoder()
        val target = decoderSurface ?: run { log("video: no decoder surface"); return }
        val format = MediaFormat.createVideoFormat(MIME, SRC_W, SRC_H)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        val codec = MediaCodec.createDecoderByType(MIME)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
                synchronized(decoderLock) {
                    if (decoder !== c) return
                    val data = pending.removeFirstOrNull()
                    if (data != null) queue(c, index, data) else freeInputs.addLast(index)
                }
            }
            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                runCatching { c.releaseOutputBuffer(index, info.size > 0) }
                if (info.size > 0) decodedFrames++
            }
            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) = log("video: decoder error: ${e.diagnosticInfo}")
            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) = log("video: AA video format $format")
        }, codecHandler)
        try {
            codec.configure(format, target, null, 0)
        } catch (e: Exception) {
            log("video: low-latency decode rejected, using defaults")
            codec.reset()
            codec.configure(MediaFormat.createVideoFormat(MIME, SRC_W, SRC_H), target, null, 0)
        }
        synchronized(decoderLock) {
            freeInputs.clear(); pending.clear()
            decoder = codec
        }
        codec.start()
        log("video: decoder ${codec.name} started")
    }

    fun stopDecoder() {
        val codec = synchronized(decoderLock) { decoder.also { decoder = null; freeInputs.clear(); pending.clear() } }
        showAa = false
        hasFrame = false
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
    }

    /** One H.264 access unit from Android Auto. */
    fun feed(data: ByteArray, offset: Int, length: Int) {
        val unit = data.copyOfRange(offset, offset + length)
        synchronized(decoderLock) {
            val codec = decoder ?: return
            val index = freeInputs.removeFirstOrNull()
            if (index != null) { queue(codec, index, unit); return }
            if (pending.size >= MAX_PENDING) {
                log("video: decoder behind, dropping ${pending.size} queued AA frames")
                pending.clear()
            }
            pending.addLast(unit)
        }
    }

    private fun queue(codec: MediaCodec, index: Int, unit: ByteArray) {
        try {
            val buf = codec.getInputBuffer(index) ?: return
            buf.clear()
            buf.put(unit, 0, minOf(unit.size, buf.capacity()))
            val flags = if (onlyParameterSets(unit)) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            codec.queueInputBuffer(index, 0, minOf(unit.size, buf.capacity()), System.nanoTime() / 1000, flags)
        } catch (e: Exception) {
            log("video: decoder input failed: ${e.message}")
        }
    }

    /** True if the unit holds only SPS/PPS NALs (Android Auto's first message). */
    private fun onlyParameterSets(unit: ByteArray): Boolean {
        var i = 0
        var seen = false
        while (i + 3 < unit.size) {
            if (unit[i].toInt() == 0 && unit[i + 1].toInt() == 0 && unit[i + 2].toInt() == 1) {
                val type = unit[i + 3].toInt() and 0x1F
                if (type != 7 && type != 8) return false
                seen = true
                i += 3
            } else i++
        }
        return seen
    }

    // ── Encoder and GL setup ────────────────────────────────────────────────────

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        // A hardware encoder, as Wayfindr's DashEncoder: software encoding is what heats phones.
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val name = list.codecInfos.firstOrNull {
            it.isEncoder && it.isHardwareAccelerated && !it.isSoftwareOnly && it.supportedTypes.any { t -> t.equals(MIME, true) }
        }?.name
        val codec = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(MIME)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) = Unit
            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    if (info.size > 0) {
                        val buf = c.getOutputBuffer(index)
                        if (buf != null) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size).also { buf.get(it) }
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) encodedFrames++
                            onEncoded(bytes, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        }
                    }
                } catch (e: Exception) {
                    log("video: encoder output failed: $e")
                } finally {
                    runCatching { c.releaseOutputBuffer(index, false) }
                }
            }
            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) = log("video: encoder error: ${e.diagnosticInfo}")
            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) = Unit
        }, codecHandler)
        // Low latency first: at 4 fps every frame the encoder holds back costs 250 ms.
        val lowLatency = MediaFormat(format).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger("vendor.qti-ext-enc-low-latency.enable", 1)
        }
        try {
            codec.configure(lowLatency, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            log("video: low-latency encode rejected (${e.message}), using defaults")
            codec.reset()
            format.removeKey(MediaFormat.KEY_BITRATE_MODE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        encoderSurface = codec.createInputSurface()
        codec.start()
        encoder = codec
    }

    private var encoderSurface: Surface? = null

    private fun setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL config" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
    }

    private fun setupGl() {
        oesProg = program(VS, FS_OES)
        texProg = program(VS, FS_2D)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTex = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(oesTex).apply {
            setDefaultBufferSize(SRC_W, SRC_H)
            setOnFrameAvailableListener({ st ->
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
                hasFrame = true
                showAa = true
            }, gl)
        }
        decoderSurface = Surface(surfaceTexture)

        val (fb, ft) = fbo(bgW, bgH); bgFbo = fb; bgTex = ft
        val p = config.probe
        val (pb, pt) = fbo(p.w, p.h); probeFbo = pb; probeTex = pt
        probeBuf = ByteBuffer.allocateDirect(p.w * p.h * 4).order(ByteOrder.nativeOrder())
        waitingTex = waitingCard()
    }

    private fun fbo(w: Int, h: Int): Pair<Int, Int> {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        texParams()
        GLES20.glGenFramebuffers(1, ids, 0)
        val fb = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return fb to tex
    }

    private fun texParams() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /** The card shown while Android Auto isn't connected (the Pi's filler stream). */
    private fun waitingCard(): Int {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.rgb(0x10, 0x18, 0x20))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; textAlign = Paint.Align.CENTER }
            drawText("Waiting for Android Auto", WIDTH / 2f, HEIGHT / 2f + 8f, paint)
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        texParams()
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return ids[0]
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: ${GLES20.glGetShaderInfoLog(it)}" }
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
        }
    }

    // ── Per output frame ────────────────────────────────────────────────────────

    private fun onTick() {
        val period = 1000L / config.fps
        try {
            if (wanted()) render()
        } catch (e: Exception) {
            log("video: render failed: $e")
        }
        // Fixed rate; after a stall, carry on from now rather than catching up.
        nextTickAt = maxOf(nextTickAt + period, SystemClock.uptimeMillis() + 5)
        gl.postAtTime(::onTick, nextTickAt)
    }

    private fun render() {
        tick++
        if (showAa && hasFrame) {
            composite()
            if (tick % 2 == 0L) probe()
        } else {
            target(0, WIDTH, HEIGHT)
            draw2d(waitingTex, Rect(0, 0, WIDTH, HEIGHT), WIDTH, HEIGHT, flipV = true)
        }
        EGLExt.eglPresentationTimeANDROID(display, surface, System.nanoTime())
        EGL14.eglSwapBuffers(display, surface)
    }

    private fun composite() {
        val crop = config.crop
        val (k, mx, my) = config.layout()
        // Background: the crop scaled to cover the frame, shrunk to a quarter, blurred, dimmed.
        val s = maxOf(WIDTH.toFloat() / crop.w, HEIGHT.toFloat() / crop.h)
        val vw = WIDTH / s; val vh = HEIGHT / s
        target(bgFbo, bgW, bgH)
        drawOes(crop.x + (crop.w - vw) / 2, crop.y + (crop.h - vh) / 2, vw, vh, Rect(0, 0, bgW, bgH), bgW, bgH)
        target(0, WIDTH, HEIGHT)
        draw2d(bgTex, Rect(0, 0, WIDTH, HEIGHT), WIDTH, HEIGHT, step = 1.5f, bright = -0.08f)
        // The map itself.
        drawOes(crop.x.toFloat(), crop.y.toFloat(), crop.w.toFloat(), crop.h.toFloat(),
            Rect(mx, my, (crop.w * k).toInt(), (crop.h * k).toInt()), WIDTH, HEIGHT)
        if (config.statusStrip) statusStrip()
    }

    /** AA's signal/battery icons and clock side by side on black, at Config.strip() (top centre
     *  by default; the Pi's status_strip(): icons padded 6 px left / 2 right, clock 2 px down +
     *  4 px right), everything scaled by the strip's size. */
    private fun statusStrip() {
        val ic = config.railIcons
        val ck = config.railClock
        val place = config.strip()
        val k = place.scale
        fun s(v: Int) = Math.round(v * k)
        val h = s(ck.h + 2)
        val w = s(ic.w + 8 + ck.w + 4)
        val x = place.x.coerceIn(0, WIDTH - w)
        val y = place.y.coerceIn(0, HEIGHT - h)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(x, HEIGHT - y - h, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        drawOes(ic.x.toFloat(), ic.y.toFloat(), ic.w.toFloat(), ic.h.toFloat(),
            Rect(x + s(6), y + s((ck.h + 2 - ic.h) / 2 / 2 * 2), s(ic.w), s(ic.h)), WIDTH, HEIGHT)
        drawOes(ck.x.toFloat(), ck.y.toFloat(), ck.w.toFloat(), ck.h.toFloat(),
            Rect(x + s(ic.w + 8), y + s(2), s(ck.w), s(ck.h)), WIDTH, HEIGHT)
    }

    /** Reads the Re-centre strip back and looks for Maps' teal "Re-centre" label (the Pi's
     *  ScreenProbe: green and blue both > red + 50 and within 30 of each other, >= 150 px). */
    private fun probe() {
        val p = config.probe
        target(probeFbo, p.w, p.h)
        drawOes(p.x.toFloat(), p.y.toFloat(), p.w.toFloat(), p.h.toFloat(), Rect(0, 0, p.w, p.h), p.w, p.h)
        probeBuf.clear()
        GLES20.glReadPixels(0, 0, p.w, p.h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, probeBuf)
        target(0, WIDTH, HEIGHT)
        var n = 0; var sx = 0L; var sy = 0L
        for (row in 0 until p.h) {
            val y = p.h - 1 - row   // glReadPixels rows start at the bottom
            var i = row * p.w * 4
            for (x in 0 until p.w) {
                val r = probeBuf.get(i).toInt() and 0xFF
                val g = probeBuf.get(i + 1).toInt() and 0xFF
                val b = probeBuf.get(i + 2).toInt() and 0xFF
                if (g - r > 50 && b - r > 50 && kotlin.math.abs(g - b) < 30) { n++; sx += x; sy += y }
                i += 4
            }
        }
        // The label is ~280 matching pixels; scattered map colours reached 41.
        recentre = SystemClock.uptimeMillis() to (if (n >= 150) Point(p.x + (sx / n).toInt(), p.y + (sy / n).toInt()) else null)
    }

    private fun target(fbo: Int, w: Int, h: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
    }

    /** Draws AA frame rect (sx, sy, sw, sh) into [dst] (pixels, top-left origin) of a w x h target. */
    private fun drawOes(sx: Float, sy: Float, sw: Float, sh: Float, dst: Rect, w: Int, h: Int) {
        GLES20.glUseProgram(oesProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(oesProg, "uTex"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(oesProg, "uTexM"), 1, false, stMatrix, 0)
        drawQuad(oesProg, dst, w, h, sx / SRC_W, 1f - sy / SRC_H, (sx + sw) / SRC_W, 1f - (sy + sh) / SRC_H)
    }

    /** Draws a whole 2D texture into [dst]. flipV for bitmaps (row 0 at the top). */
    private fun draw2d(tex: Int, dst: Rect, w: Int, h: Int, step: Float = 0f, bright: Float = 0f, flipV: Boolean = false) {
        GLES20.glUseProgram(texProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(texProg, "uTex"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(texProg, "uTexM"), 1, false, identity, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(texProg, "uStep"), step / bgW, step / bgH)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(texProg, "uBright"), bright)
        if (flipV) drawQuad(texProg, dst, w, h, 0f, 0f, 1f, 1f) else drawQuad(texProg, dst, w, h, 0f, 1f, 1f, 0f)
    }

    /** Quad over [dst]; (u0, vTop) at its top-left corner, (u1, vBottom) at its bottom-right. */
    private fun drawQuad(prog: Int, dst: Rect, w: Int, h: Int, u0: Float, vTop: Float, u1: Float, vBottom: Float) {
        val x0 = dst.x * 2f / w - 1f
        val x1 = (dst.x + dst.w) * 2f / w - 1f
        val yTop = 1f - dst.y * 2f / h
        val yBottom = 1f - (dst.y + dst.h) * 2f / h
        quad.clear()
        quad.put(floatArrayOf(
            x0, yTop, u0, vTop,
            x0, yBottom, u0, vBottom,
            x1, yTop, u1, vTop,
            x1, yBottom, u1, vBottom))
        val pos = GLES20.glGetAttribLocation(prog, "aPos")
        val tex = GLES20.glGetAttribLocation(prog, "aTex")
        quad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(pos)
        quad.position(2)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
