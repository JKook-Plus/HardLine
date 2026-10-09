package dev.hardline.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import dev.hardline.R
import dev.hardline.core.Keys
import dev.hardline.gl.PreviewParams
import dev.hardline.service.CameraController

/** A small always-on-top window with the live picture: drag to move, pinch to resize, double tap to return. */
object FloatingWindow {
    private var root: View? = null
    private val key = Any()

    val showing: Boolean get() = root != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(activity: MainActivity, c: CameraController) {
        if (!Settings.canDrawOverlays(activity)) {
            c.messages.tryEmit("Allow \"Display over other apps\" for the floating window, then try again")
            runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))) }
            return
        }
        if (root != null) return
        val context: Context = activity.applicationContext
        val wm = context.getSystemService(WindowManager::class.java)
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val (pw, ph) = c.pipeline.outputSize.value.takeIf { it.first > 0 } ?: (16 to 9)
        val minWidth = (120 * density).toInt()
        val maxWidth = minOf(metrics.widthPixels, metrics.heightPixels)
        val lp = WindowManager.LayoutParams(
            (220 * density).toInt().coerceAtMost(maxWidth), 0, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            height = width * ph / pw
            x = metrics.widthPixels - width - (16 * density).toInt()
            y = (96 * density).toInt()
        }

        val frame = FrameLayout(context)
        val surface = SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    c.pipeline.addWindow(key, holder.surface, width, height, false, PreviewParams(showOsd = c.settings[Keys.overlayOnPreview]))
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) = c.pipeline.removeWindow(key)
            })
        }
        frame.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val close = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Close floating window"
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()) }
            val pad = (6 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { hide(c) }
        }
        val closeSize = (32 * density).toInt()
        frame.addView(close, FrameLayout.LayoutParams(closeSize, closeSize, Gravity.TOP or Gravity.END).apply { setMargins(0, (4 * density).toInt(), (4 * density).toInt(), 0) })

        val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                hide(c)
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                close.visibility = if (close.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
                return true
            }
        })
        val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                lp.width = (lp.width * detector.scaleFactor).toInt().coerceIn(minWidth, maxWidth)
                lp.height = lp.width * ph / pw
                wm.updateViewLayout(frame, lp)
                return true
            }
        })
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        frame.setOnTouchListener { _, e ->
            taps.onTouchEvent(e)
            scale.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y }
                MotionEvent.ACTION_MOVE -> if (!scale.isInProgress && e.pointerCount == 1) {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(frame, lp)
                }
            }
            true
        }

        runCatching { wm.addView(frame, lp) }.onFailure {
            c.messages.tryEmit("The floating window could not be shown")
            return
        }
        root = frame
        activity.moveTaskToBack(true)
    }

    fun hide(c: CameraController) {
        val view = root ?: return
        root = null
        c.pipeline.removeWindow(key)
        runCatching { view.context.getSystemService(WindowManager::class.java).removeView(view) }
    }
}
