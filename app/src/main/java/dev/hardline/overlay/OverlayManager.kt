package dev.hardline.overlay

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.util.Log
import dev.hardline.core.Keys
import dev.hardline.core.LocationTracker
import dev.hardline.core.Settings
import dev.hardline.gl.ImageLayer
import dev.hardline.gl.Pipeline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws the on-screen display (time, device name, battery, position, speed, free text) and the
 * watermark image into layers of the pipeline. Layout is recomputed for the current frame size.
 */
class OverlayManager(
    private val context: Context,
    private val settings: Settings,
    private val pipeline: Pipeline,
    private val deviceName: () -> String?,
    private val location: LocationTracker,
) {
    private val text = ImageLayer(osd = true).apply { visible = false }
    private val marquee = ImageLayer(osd = true).apply { visible = false }
    private val watermark = ImageLayer(osd = true).apply { visible = false }
    private val loader = Executors.newSingleThreadExecutor { Thread(it, "watermark-loader") }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val background = Paint()

    @Volatile private var dirty = true
    private var lastSecond = -1L
    private var lastWidth = 0
    private var lastHeight = 0
    @Volatile private var watermarkSource: Bitmap? = null
    @Volatile private var movie: Movie? = null
    private var movieFrameAt = 0L
    private var marqueeText = ""
    private var blockTop = 0f
    private var blockHeight = 0f

    /** Extra per-frame layout work (insets) run on the pipeline thread. */
    var onLayout: ((width: Int, height: Int) -> Unit)? = null

    init {
        pipeline.imageLayers += listOf(watermark, text, marquee)
        pipeline.beforeCompose = ::update
        settings.onChange { key ->
            dirty = true
            if (key == Keys.watermarkUri.key || key == Keys.watermark.key) loadWatermark()
            if (key == Keys.osdLocation.key || key == Keys.osdSpeed.key || key == Keys.exifLocation.key) updateLocationUse()
        }.also { keepListener = it }
        loadWatermark()
        updateLocationUse()
    }

    private var keepListener: Any? = null

    fun updateLocationUse() {
        location.setActive(settings[Keys.osdLocation] || settings[Keys.osdSpeed] || settings[Keys.exifLocation])
    }

    private fun update(width: Int, height: Int, timeNs: Long) {
        val second = System.currentTimeMillis() / 1000
        if (dirty || second != lastSecond || width != lastWidth || height != lastHeight) {
            dirty = false
            lastSecond = second
            lastWidth = width
            lastHeight = height
            runCatching { renderText(width, height) }.onFailure { Log.w("Overlay", "text", it) }
            layoutWatermark(width, height)
        }
        animateMarquee(width, timeNs)
        animateWatermark(timeNs)
        onLayout?.invoke(width, height)
    }

    // ------------------------------------------------------------------ text

    private fun typeface(): Typeface {
        val base = when (settings[Keys.osdFont]) {
            1 -> Typeface.SERIF
            2 -> Typeface.MONOSPACE
            3 -> runCatching { Typeface.createFromFile(File(context.filesDir, settings[Keys.osdFontFile])) }.getOrDefault(Typeface.SANS_SERIF)
            else -> Typeface.SANS_SERIF
        }
        return Typeface.create(base, settings[Keys.osdFontStyle].coerceIn(0, 3))
    }

    private fun lines(): List<String> {
        val out = ArrayList<String>()
        if (settings[Keys.osdTimestamp]) {
            val pattern = settings[Keys.osdTimeFormat].ifBlank { Keys.osdTimeFormat.default }
            out += runCatching { SimpleDateFormat(pattern, Locale.getDefault()).format(Date()) }
                .getOrElse { SimpleDateFormat(Keys.osdTimeFormat.default, Locale.getDefault()).format(Date()) }
        }
        if (settings[Keys.osdDeviceName]) deviceName()?.let { out += it }
        if (settings[Keys.osdBattery]) {
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val pct = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                out += "Battery $pct%" + if (charging) " (charging)" else ""
            }
        }
        val loc = location.last
        if (settings[Keys.osdLocation]) out += if (loc != null) "%.5f, %.5f".format(Locale.US, loc.latitude, loc.longitude) else "Waiting for position"
        if (settings[Keys.osdSpeed]) {
            val metresPerSecond = loc?.takeIf { it.hasSpeed() }?.speed ?: 0f
            out += if (settings[Keys.osdSpeedUnit] == 1) "%.1f mph".format(Locale.US, metresPerSecond * 2.23694f) else "%.1f km/h".format(Locale.US, metresPerSecond * 3.6f)
        }
        return out
    }

    private fun renderText(width: Int, height: Int) {
        val size = (min(width, height) * 0.05f * settings[Keys.osdSizePercent] / 100f).coerceAtLeast(8f)
        paint.typeface = typeface()
        paint.textSize = size
        paint.color = settings[Keys.osdTextColor]
        paint.setShadowLayer(size / 12f, 0f, size / 24f, Color.argb(160, 0, 0, 0))
        background.color = settings[Keys.osdBackgroundColor]
        val pad = (height * settings[Keys.osdPaddingPercent] / 100f)
        val position = settings[Keys.osdPosition]

        val all = lines().toMutableList()
        val custom = settings[Keys.osdText].takeIf { settings[Keys.osdCustomText] && it.isNotBlank() }
        marqueeText = ""
        if (custom != null) {
            if (settings[Keys.osdMarquee] != 0) marqueeText = custom.replace('\n', ' ') else all += custom
        }
        val metrics = paint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val inner = size * 0.3f

        if (all.isEmpty()) {
            text.visible = false
            blockHeight = 0f
        } else {
            val w = (all.maxOf { paint.measureText(it) } + inner * 2).toInt().coerceIn(2, width)
            val h = (lineHeight * all.size + inner * 2).toInt().coerceIn(2, height)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), background)
            val centre = position in listOf(4, 7, 8)
            val right = position in listOf(1, 3, 6)
            all.forEachIndexed { i, line ->
                val lw = paint.measureText(line)
                val x = if (centre) (w - lw) / 2 else if (right) w - lw - inner else inner
                canvas.drawText(line, x, inner - metrics.ascent + i * lineHeight, paint)
            }
            place(text, w, h, width, height, position, pad)
            text.bitmap?.recycle()
            text.bitmap = bmp
            text.version++
            text.visible = true
            blockTop = text.top
            blockHeight = text.height
        }

        if (marqueeText.isEmpty()) {
            marquee.visible = false
        } else {
            val w = (paint.measureText(marqueeText) + inner * 2).toInt().coerceAtMost(8192)
            val h = (lineHeight + inner * 2).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), background)
            canvas.drawText(marqueeText, inner, inner - metrics.ascent, paint)
            marquee.width = w.toFloat() / width
            marquee.height = h.toFloat() / height
            val bottom = position in listOf(2, 3, 7)
            val margin = pad / height
            marquee.top = when {
                blockHeight == 0f -> if (bottom) 1f - marquee.height - margin else margin
                bottom -> blockTop - marquee.height
                else -> blockTop + blockHeight
            }
            marquee.bitmap?.recycle()
            marquee.bitmap = bmp
            marquee.version++
            marquee.visible = true
        }
    }

    private fun animateMarquee(width: Int, timeNs: Long) {
        if (!marquee.visible) return
        val travel = 1f + marquee.width
        val phase = ((timeNs / 1e9) * 0.12 % travel).toFloat()         // about eight seconds to cross the frame
        marquee.left = if (settings[Keys.osdMarquee] == 2) -marquee.width + phase else 1f - phase
    }

    /** Positions a layer of [w] x [h] pixels at one of nine anchors inside a frame of [fw] x [fh]. */
    private fun place(layer: ImageLayer, w: Int, h: Int, fw: Int, fh: Int, position: Int, pad: Float) {
        val lw = w.toFloat() / fw
        val lh = h.toFloat() / fh
        val mx = pad / fw
        val my = pad / fh
        layer.width = lw
        layer.height = lh
        layer.left = when (position) { 1, 3, 6 -> 1f - lw - mx; 4, 7, 8 -> (1f - lw) / 2; else -> mx }
        layer.top = when (position) { 2, 3, 7 -> 1f - lh - my; 5, 6, 8 -> (1f - lh) / 2; else -> my }
    }

    // ------------------------------------------------------------------ watermark

    private fun loadWatermark() {
        val uri = settings[Keys.watermarkUri]
        if (!settings[Keys.watermark] || uri.isEmpty()) {
            watermarkSource = null
            movie = null
            watermark.visible = false
            dirty = true
            return
        }
        loader.execute {
            runCatching {
                val bytes = context.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                val gif = bytes.size > 6 && String(bytes, 0, 3) == "GIF"
                @Suppress("DEPRECATION")
                movie = if (gif) Movie.decodeByteArray(bytes, 0, bytes.size)?.takeIf { it.duration() > 0 } else null
                watermarkSource = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                dirty = true
            }.onFailure {
                Log.w("Overlay", "watermark", it)
                watermarkSource = null
                movie = null
                dirty = true
            }
        }
    }

    private fun layoutWatermark(width: Int, height: Int) {
        val src = watermarkSource
        if (src == null || !settings[Keys.watermark]) {
            watermark.visible = false
            return
        }
        val pad = height * settings[Keys.watermarkPaddingPercent] / 100f
        val area = settings[Keys.watermarkMaxAreaPercent].coerceIn(1, 100) / 100f
        var scale = sqrt(area * width * height / (src.width.toFloat() * src.height))
        scale = min(scale, min((width - 2 * pad) / src.width, (height - 2 * pad) / src.height)).coerceAtMost(1f)
        place(watermark, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), width, height, settings[Keys.watermarkPosition], pad)
        if (watermark.bitmap !== src && movie == null) {
            watermark.bitmap = src
            watermark.version++
        }
        watermark.visible = true
    }

    /** Animated GIF watermarks are redrawn about fifteen times a second. */
    @Suppress("DEPRECATION")
    private fun animateWatermark(timeNs: Long) {
        val m = movie ?: return
        if (!watermark.visible || timeNs - movieFrameAt < 66_000_000L) return
        movieFrameAt = timeNs
        val bmp = watermark.bitmap?.takeIf { it.isMutable && it.width == m.width() && it.height == m.height() }
            ?: Bitmap.createBitmap(m.width().coerceAtLeast(1), m.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        m.setTime(((timeNs / 1_000_000) % m.duration()).toInt())
        m.draw(Canvas(bmp), 0f, 0f)
        watermark.bitmap = bmp
        watermark.version++
    }
}
