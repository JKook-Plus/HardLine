package dev.hardline.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.hardline.App
import dev.hardline.core.Keys
import dev.hardline.gl.PreviewParams
import dev.hardline.ui.theme.AppTheme

/** Receives "USB device attached" from the system and hands the device to the app. */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as App).controller
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        controller.refreshDevices()
        if (device != null && controller.session.value == null) controller.open(device)
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        when {
            // Above the lock screen only the picture is shown; the full app needs the phone unlocked.
            locked && controller.settings[Keys.showOnLockScreen] ->
                startActivity(Intent(this, LockScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            !controller.settings[Keys.dontOpenOnConnect] ->
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}

/** Shows the live picture above the lock screen until the camera goes away or the phone is unlocked. */
class LockScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = (application as App).controller
        setContent {
            AppTheme {
                val session by controller.session.collectAsState()
                val mainVisible by controller.activityVisible.collectAsState()
                LaunchedEffect(session, mainVisible) { if (session == null || mainVisible) finish() }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    PreviewSurface(
                        controller.pipeline,
                        PreviewParams(aspectMode = controller.settings[Keys.aspectRatio], showOsd = controller.settings[Keys.overlayOnPreview]),
                        Modifier.fillMaxSize(),
                    )
                    Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        FilledTonalIconButton(onClick = { finish() }) { Icon(Icons.Default.Close, "Close") }
                        FilledTonalIconButton(onClick = { unlockAndOpen() }) { Icon(Icons.Default.LockOpen, "Unlock and open the app") }
                    }
                }
            }
        }
    }

    private fun unlockAndOpen() {
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                startActivity(Intent(this@LockScreenActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        })
    }
}
