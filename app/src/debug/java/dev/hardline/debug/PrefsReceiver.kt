package dev.hardline.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.hardline.App

/**
 * Lets tests change a setting of the running app:
 * `adb shell am broadcast -n dev.hardline/.debug.PrefsReceiver --es key K --es type int --es value 3`
 */
class PrefsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val value = intent.getStringExtra("value") ?: return
        val editor = (context.applicationContext as App).controller.settings.prefs.edit()
        when (intent.getStringExtra("type")) {
            "bool" -> editor.putBoolean(key, value.toBoolean())
            "int" -> editor.putInt(key, value.toInt())
            "float" -> editor.putFloat(key, value.toFloat())
            else -> editor.putString(key, value)
        }
        editor.apply()
    }
}
