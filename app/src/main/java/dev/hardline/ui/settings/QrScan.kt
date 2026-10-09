package dev.hardline.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dev.hardline.net.PushTarget
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Reads QR codes from pictures and camera frames, and turns their text into a live-push destination. */
object QrDecoder {
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    private fun decode(source: LuminanceSource): String? {
        for (candidate in listOf(source, source.invert())) {
            runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text }.getOrNull()?.let { return it }
        }
        return null
    }

    fun decode(bitmap: Bitmap): String? {
        // Large pictures are reduced first: the reader is slow and a QR code does not need the detail.
        val scale = 1200f / maxOf(bitmap.width, bitmap.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        return decode(RGBLuminanceSource(small.width, small.height, pixels))
    }

    fun decode(luma: ByteArray, stride: Int, width: Int, height: Int): String? =
        decode(PlanarYUVLuminanceSource(luma, stride, height, 0, 0, width, height, false))

    /** Accepts what this app shows as a QR code, or just an address such as rtmp://server/app/key. */
    fun toTarget(text: String): PushTarget? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            val one = runCatching { JSONObject(trimmed).put("id", UUID.randomUUID().toString()) }.getOrNull() ?: return null
            return PushTarget.list(JSONArray().put(one).toString()).firstOrNull()?.takeIf { PushTarget.isValidUrl(it.url) }
        }
        return if (PushTarget.isValidUrl(trimmed)) PushTarget(UUID.randomUUID().toString(), "", trimmed, "") else null
    }
}

/** Opens the phone's back camera and reports the first QR code it sees. */
private class QrCamera(context: Context, private val onText: (String) -> Unit) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("qr-scan").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(context.mainLooper)
    private val reader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 2)
    private var device: CameraDevice? = null
    @Volatile private var done = false

    @SuppressLint("MissingPermission")
    fun start(preview: SurfaceTexture) {
        val id = runCatching {
            manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: manager.cameraIdList.firstOrNull()
        }.getOrNull() ?: return
        preview.setDefaultBufferSize(1280, 720)
        val surface = Surface(preview)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (done) return@setOnImageAvailableListener
                val plane = image.planes[0]
                val luma = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
                QrDecoder.decode(luma, plane.rowStride, image.width, image.height)?.let { text ->
                    done = true
                    main.post { onText(text) }
                }
            } finally {
                image.close()
            }
        }, handler)
        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    runCatching {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface); addTarget(reader.surface) }
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(surface, reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                runCatching { session.setRepeatingRequest(request.build(), null, handler) }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                        }, handler)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) = camera.close()
                override fun onError(camera: CameraDevice, error: Int) = camera.close()
            }, handler)
        }
    }

    fun stop() {
        done = true
        runCatching { device?.close() }
        device = null
        runCatching { reader.close() }
        thread.quitSafely()
    }
}

/** A full-screen camera view that closes itself with the text of the first QR code found. */
@Composable
fun QrScanDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally) {
            var camera: QrCamera? = remember { null }
            DisposableEffect(Unit) { onDispose { camera?.stop() } }
            Text(
                "Point the camera at a QR code that holds a streaming address", color = Color.White, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.statusBarsPadding().padding(24.dp),
            )
            AndroidView(
                modifier = Modifier.weight(1f).aspectRatio(9f / 16f, matchHeightConstraintsFirst = true),
                factory = { context ->
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                camera = QrCamera(context.applicationContext, onResult).also { it.start(texture) }
                            }

                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { camera?.stop(); camera = null; return true }
                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                },
            )
            FilledTonalButton(onClick = onDismiss, modifier = Modifier.navigationBarsPadding().padding(24.dp)) { Text("Cancel") }
        }
    }
}
