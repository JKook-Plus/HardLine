package dev.hardline.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.SizeF
import dev.hardline.core.Keys
import dev.hardline.core.Settings
import dev.hardline.gl.Pipeline
import dev.hardline.gl.SurfaceLayer
import kotlin.math.atan

/** Places an inset of the given aspect ratio in a corner of the frame. */
private fun SurfaceLayer.placeInset(frameW: Int, frameH: Int, aspect: Float, widthFraction: Float, padFraction: Float, corner: Int) {
    val w = widthFraction.coerceIn(0.05f, 1f)
    val h = (w * frameW / frameH / aspect).coerceAtMost(1f)
    val px = padFraction
    val py = padFraction * frameW / frameH
    width = w
    height = h
    left = if (corner == 1 || corner == 3) 1f - w - px else px
    top = if (corner == 2 || corner == 3) 1f - h - py else py
}

/** Shows one of the phone's own cameras as an inset on the video. */
class PhoneCameraInset(private val context: Context, private val settings: Settings, private val pipeline: Pipeline) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("phone-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var layer: SurfaceLayer? = null
    private var device: CameraDevice? = null
    private var openId = ""
    private var aspect = 4f / 3f

    /** Camera ids with a readable label such as "Back 78°". */
    fun cameras(): List<Pair<String, String>> = runCatching {
        manager.cameraIdList.map { id ->
            val ch = manager.getCameraCharacteristics(id)
            val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                CameraCharacteristics.LENS_FACING_BACK -> "Back"
                else -> "External"
            }
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val sensor: SizeF? = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val fov = if (focal != null && sensor != null && focal > 0) Math.toDegrees(2.0 * atan(sensor.width / (2.0 * focal))) else null
            id to (if (fov != null) "$facing %.0f°".format(fov) else "$facing camera $id")
        }
    }.getOrDefault(emptyList())

    /** Starts, stops or switches the inset to match the settings. */
    @SuppressLint("MissingPermission")
    fun apply() {
        val wanted = settings[Keys.insetCamera]
        if (wanted == openId) return
        close()
        if (wanted.isEmpty()) return
        val (w, h) = when (settings[Keys.insetQuality]) { 1 -> 640 to 480; 2 -> 1280 to 720; 3 -> 1920 to 1080; else -> 320 to 240 }
        aspect = w.toFloat() / h
        val l = pipeline.createSurfaceLayer(w, h)
        layer = l
        openId = wanted
        runCatching {
            manager.openCamera(wanted, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    runCatching {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(l.surface) }
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(l.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                runCatching { session.setRepeatingRequest(request.build(), null, handler) }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                        }, handler)
                    }.onFailure { Log.w("Inset", "session", it) }
                }

                override fun onDisconnected(camera: CameraDevice) = camera.close()
                override fun onError(camera: CameraDevice, error: Int) = camera.close()
            }, handler)
        }.onFailure {
            Log.w("Inset", "open", it)
            close()
        }
    }

    fun layout(frameW: Int, frameH: Int) {
        val l = layer ?: return
        l.rotation = settings[Keys.insetRotation] * 90
        val turned = settings[Keys.insetRotation] % 2 == 1
        l.placeInset(frameW, frameH, if (turned) 1f / aspect else aspect, settings[Keys.insetSizePercent] / 200f,
            settings[Keys.insetPaddingPercent] / 1000f, settings[Keys.insetPosition])
    }

    fun close() {
        runCatching { device?.close() }
        device = null
        layer?.let(pipeline::removeSurfaceLayer)
        layer = null
        openId = ""
    }
}

/** Shows the phone's screen as an inset on the video. */
class ScreenInset(private val context: Context, private val settings: Settings, private val pipeline: Pipeline) {
    private var layer: SurfaceLayer? = null
    private var display: VirtualDisplay? = null
    private var aspect = 9f / 16f
    val active: Boolean get() = display != null

    fun start(projection: MediaProjection) {
        if (display != null) return
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels / 2 and 1.inv()
        val h = metrics.heightPixels / 2 and 1.inv()
        aspect = w.toFloat() / h
        val l = pipeline.createSurfaceLayer(w, h)
        layer = l
        display = runCatching {
            projection.createVirtualDisplay("screen-inset", w, h, metrics.densityDpi / 2, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, l.surface, null, null)
        }.onFailure { Log.w("Inset", "virtual display", it) }.getOrNull()
        if (display == null) stop()
    }

    fun layout(frameW: Int, frameH: Int) {
        layer?.placeInset(frameW, frameH, aspect, settings[Keys.screenInsetSizePercent] / 200f, 0.016f, settings[Keys.screenInsetPosition])
    }

    fun stop() {
        display?.release()
        display = null
        layer?.let(pipeline::removeSurfaceLayer)
        layer = null
    }
}
