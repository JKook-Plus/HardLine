package dev.hardline.media

import android.graphics.Bitmap
import android.location.Location
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dev.hardline.core.Keys
import dev.hardline.core.Settings
import dev.hardline.gl.Pipeline
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Takes still pictures of the composed frame. */
class Snapshotter(
    private val pipeline: Pipeline,
    private val storage: Storage,
    private val settings: Settings,
    private val deviceName: () -> String?,
    private val location: () -> Location?,
    private val cacheDir: File,
) {
    class Saved(val name: String, val uri: Uri, val jpeg: ByteArray)

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "snapshot") }

    /** Encodes the current picture as JPEG without saving it. */
    fun jpeg(quality: Int, callback: (ByteArray?) -> Unit) {
        pipeline.snapshot { buffer, width, height ->
            if (buffer == null) return@snapshot callback(null)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            executor.execute {
                val out = ByteArrayOutputStream(width * height / 4)
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bmp.recycle()
                callback(out.toByteArray())
            }
        }
    }

    /** Saves the current picture to storage. */
    fun take(callback: (Saved?) -> Unit) {
        jpeg(95) { bytes ->
            if (bytes == null) return@jpeg callback(null)
            val name = storage.fileName("IMG", "jpg", deviceName())
            val out = storage.create(name, "image/jpeg", false) ?: return@jpeg callback(null)
            val tagged = withMetadata(bytes)
            val ok = runCatching { FileOutputStream(out.descriptor.fileDescriptor).use { it.write(tagged) } }.isSuccess
            out.finish(keep = ok)
            callback(if (ok) Saved(name, out.uri, tagged) else null)
        }
    }

    /** Adds the camera name, the time and (when enabled) the position to a JPEG. */
    private fun withMetadata(jpeg: ByteArray): ByteArray = runCatching {
        val file = File.createTempFile("picture", ".jpg", cacheDir)
        try {
            file.writeBytes(jpeg)
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_SOFTWARE, "HardLine")
                deviceName()?.let { setAttribute(ExifInterface.TAG_MODEL, it) }
                val now = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                setAttribute(ExifInterface.TAG_DATETIME, now)
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
                if (settings[Keys.exifLocation]) location()?.let { setGpsInfo(it) }
                saveAttributes()
            }
            file.readBytes()
        } finally {
            file.delete()
        }
    }.getOrDefault(jpeg)
}
