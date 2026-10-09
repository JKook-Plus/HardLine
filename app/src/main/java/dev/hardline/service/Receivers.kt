package dev.hardline.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.hardline.App
import dev.hardline.core.Keys

/** Starts the background service after the device boots, when the user asked for it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val controller = (context.applicationContext as App).controller
        if (controller.settings[Keys.startOnBoot]) controller.startAfterBoot()
    }
}
