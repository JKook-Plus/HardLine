package dev.hardline.ui

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.hardline.App
import dev.hardline.core.Keys
import dev.hardline.service.CameraController
import dev.hardline.ui.settings.SettingsHost
import dev.hardline.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    lateinit var controller: CameraController
        private set
    var inPictureInPicture by mutableStateOf(false)
        private set
    private val backStack = mutableStateListOf<String>()
    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionCallback?.invoke(result.values.all { it })
        permissionCallback = null
        controller.updateAudio()
        controller.applyInsets()
        controller.overlays.updateLocationUse()
    }
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        controller.onProjectionResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = (application as App).controller
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyOrientation()
        if (controller.settings[Keys.fullScreenOnStart]) setFullScreen(true)

        val first = buildList {
            if (!controller.hasPermission(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33 && !controller.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (first.isNotEmpty() && savedInstanceState == null) permissionLauncher.launch(first.toTypedArray())

        lifecycleScope.launch { controller.exitRequests.collect { finishAndRemoveTask() } }

        setContent {
            AppTheme {
                val screen = backStack.lastOrNull()
                BackHandler(enabled = screen != null) { backStack.removeAt(backStack.lastIndex) }
                if (screen == null) MainScreen(this, controller)
                else SettingsHost(this, controller, screen, onBack = { backStack.removeAt(backStack.lastIndex) }, onOpen = { backStack += it })
            }
        }
        if (controller.settings[Keys.pipOnStart] && savedInstanceState == null) window.decorView.post {
            if (controller.settings[Keys.useSystemPip]) enterPip() else FloatingWindow.show(this, controller)
        }
    }

    fun open(screen: String) { backStack += screen }

    fun applyOrientation() {
        requestedOrientation = if (controller.settings[Keys.forceLandscape]) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun setFullScreen(on: Boolean) {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (on) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    /** Asks for runtime permissions if they are missing, then reports whether all are granted. */
    fun withPermissions(vararg permissions: String, then: (Boolean) -> Unit) {
        val missing = permissions.filterNot(controller::hasPermission)
        if (missing.isEmpty()) return then(true)
        permissionCallback = then
        permissionLauncher.launch(missing.toTypedArray())
    }

    /** Shows the system's screen-capture consent, needed for the screen inset and for mixing system audio. */
    fun requestProjection() {
        projectionLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    fun enterPip() {
        val (w, h) = controller.pipeline.outputSize.value.takeIf { it.first > 0 } ?: (16 to 9)
        val ratio = Rational(w, h).let { if (it.toFloat() > 2.39f) Rational(239, 100) else if (it.toFloat() < 0.42f) Rational(100, 239) else it }
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        FloatingWindow.hide(controller)
        controller.onActivityVisible(true)
        controller.refreshDevices()
        controller.openAttached()
    }

    override fun onStop() {
        super.onStop()
        controller.onActivityVisible(false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0 && controller.onMediaKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }
}
