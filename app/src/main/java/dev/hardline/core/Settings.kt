package dev.hardline.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import org.json.JSONObject
import java.security.SecureRandom

/** A typed preference key with its default value. */
class Pref<T : Any>(val key: String, val default: T)

/** Every user setting of the app, grouped the way the settings screens present them. */
object Keys {
    // Display
    val appTitle = Pref("app_title", "")
    val aspectRatio = Pref("aspect_ratio", 0)
    val cardboardView = Pref("cardboard_view", false)
    val overlayOnPreview = Pref("overlay_on_preview", true)
    val audioIndicator = Pref("audio_indicator", true)
    val showFps = Pref("show_fps", false)
    val forceLandscape = Pref("force_landscape", false)
    val fullScreenOnStart = Pref("full_screen_on_start", false)
    val useSystemPip = Pref("use_system_pip", true)
    val pipOnStart = Pref("pip_on_start", false)
    val showOnLockScreen = Pref("show_on_lock_screen", false)
    val wakeScreenOnConnect = Pref("wake_screen_on_connect", true)

    // Behaviour on connect / start
    val autoMotionOnConnect = Pref("auto_motion_on_connect", false)
    val autoRecordOnConnect = Pref("auto_record_on_connect", false)
    val recordDelaySeconds = Pref("record_delay_seconds", 0)
    val autoPushOnConnect = Pref("auto_push_on_connect", false)
    val autoServerOnConnect = Pref("auto_server_on_connect", false)
    val autoRtspOnConnect = Pref("auto_rtsp_on_connect", false)
    val startOnBoot = Pref("start_on_boot", false)
    val bootDelaySeconds = Pref("boot_delay_seconds", 0)
    val dontOpenOnConnect = Pref("dont_open_on_connect", false)
    val exitOnDisconnect = Pref("exit_on_disconnect", false)

    // Storage and recording
    val fourGbLimit = Pref("four_gb_limit", true)
    val segmentMinutes = Pref("segment_minutes", 0)
    val loopRecording = Pref("loop_recording", false)
    val spaceLimitGb = Pref("space_limit_gb", 0)
    val motionSegmentMinutes = Pref("motion_segment_minutes", 0)
    val motionLoopRecording = Pref("motion_loop_recording", false)
    val motionSpaceLimitGb = Pref("motion_space_limit_gb", 0)
    val saveToTree = Pref("save_to_tree", false)
    val saveTreeUri = Pref("save_tree_uri", "")
    val deviceNameInFilename = Pref("device_name_in_filename", false)
    val exifLocation = Pref("exif_location", false)
    val recordCodec = Pref("record_codec", 0)              // 0 H.264, 1 HEVC, 2 AV1
    val customRecordBitrate = Pref("custom_record_bitrate", false)
    val recordBitrateMbps = Pref("record_bitrate_mbps", 10f)
    val motionTimeoutSeconds = Pref("motion_timeout_seconds", 15)

    // Audio
    val useMicrophone = Pref("use_microphone", false)
    val mixMicWithUsb = Pref("mix_mic_with_usb", false)
    val mixSystemAudio = Pref("mix_system_audio", false)
    val preferExternalUsbAudio = Pref("prefer_external_usb_audio", true)
    val backgroundAudioPlayback = Pref("background_audio_playback", true)
    val usbAudioInput = Pref("usb_audio_input", true)
    val audioPlayback = Pref("audio_playback", true)
    val volumeLimit = Pref("volume_limit", true)
    val usbGain = Pref("usb_gain", 1f)
    val micGain = Pref("mic_gain", 1f)
    val systemGain = Pref("system_gain", 1f)

    // USB video
    val subDeviceDialog = Pref("sub_device_dialog", true)
    val hardwareDecoder = Pref("hardware_decoder", true)
    val h264Bypass = Pref("h264_bypass", false)
    val hevcBypass = Pref("hevc_bypass", false)
    val mjpegBypass = Pref("mjpeg_bypass", false)
    val dropBrokenJpeg = Pref("drop_broken_jpeg", false)
    val deinterlace = Pref("deinterlace", 0)
    val rememberDeviceSettings = Pref("remember_device_settings", true)

    // Servers
    val rtspCodec = Pref("rtsp_codec", 0)
    val srtCodec = Pref("srt_codec", 0)
    val flvCodec = Pref("flv_codec", 0)
    val rtmpCodec = Pref("rtmp_codec", 0)
    val hardwareEncoder = Pref("hardware_encoder", true)
    val h264Profile = Pref("h264_profile", 0)              // 0 Baseline, 1 Main, 2 High
    val customH264Bitrate = Pref("custom_h264_bitrate", false)
    val h264BitrateMbps = Pref("h264_bitrate_mbps", 2f)
    val h264KeyframeSeconds = Pref("h264_keyframe_seconds", 2)
    val customHevcBitrate = Pref("custom_hevc_bitrate", false)
    val hevcBitrateMbps = Pref("hevc_bitrate_mbps", 2f)
    val hevcKeyframeSeconds = Pref("hevc_keyframe_seconds", 2)
    val customAv1Bitrate = Pref("custom_av1_bitrate", false)
    val av1BitrateMbps = Pref("av1_bitrate_mbps", 2f)
    val av1KeyframeSeconds = Pref("av1_keyframe_seconds", 2)
    val upnp = Pref("upnp", false)
    val hostname = Pref("hostname", "")
    val pageTitle = Pref("page_title", "")
    val httpPort = Pref("http_port", 8081)
    val rtspPort = Pref("rtsp_port", 8554)
    val serverUser = Pref("server_user", "admin")
    val serverPassword = Pref("server_password", "")
    val https = Pref("https", false)
    val certificateFile = Pref("certificate_file", "")
    val certificatePassword = Pref("certificate_password", "")
    val pushTargets = Pref("push_targets", "[]")
    val selectedPushTargets = Pref("selected_push_targets", "[]")
    val rtspEnabled = Pref("rtsp_enabled", false)

    // Mail
    val mailTo = Pref("mail_to", "")
    val smtpServer = Pref("smtp_server", "")
    val smtpPort = Pref("smtp_port", 25)
    val smtpSecurity = Pref("smtp_security", 0)            // 0 none, 1 STARTTLS, 2 TLS
    val smtpUser = Pref("smtp_user", "")
    val smtpPassword = Pref("smtp_password", "")
    val mailOnMotion = Pref("mail_on_motion", false)
    val mailSnapshots = Pref("mail_snapshots", false)

    // FTP
    val ftpUrl = Pref("ftp_url", "ftp://")
    val ftpUser = Pref("ftp_user", "")
    val ftpPassword = Pref("ftp_password", "")
    val ftpUploadMotion = Pref("ftp_upload_motion", false)
    val ftpUploadManual = Pref("ftp_upload_manual", false)
    val ftpDeleteAfterUpload = Pref("ftp_delete_after_upload", false)

    // Buttons
    val cameraButtonAction = Pref("camera_button_action", 3)   // 0 none, 1 record, 2 burst, 3 one picture
    val mediaPlayAction = Pref("media_play_action", 0)
    val mediaPreviousAction = Pref("media_previous_action", 0)
    val mediaNextAction = Pref("media_next_action", 0)
    val burstCount = Pref("burst_count", 1)
    val vibrate = Pref("vibrate", true)

    // Text overlay and watermark
    val osdTimestamp = Pref("osd_timestamp", false)
    val osdTimeFormat = Pref("osd_time_format", "yyyy-MM-dd E HH:mm:ss")
    val osdDeviceName = Pref("osd_device_name", false)
    val osdBattery = Pref("osd_battery", false)
    val osdLocation = Pref("osd_location", false)
    val osdSpeed = Pref("osd_speed", false)
    val osdSpeedUnit = Pref("osd_speed_unit", 0)           // 0 km/h, 1 mph
    val osdCustomText = Pref("osd_custom_text_enabled", true)
    val osdText = Pref("osd_text", "")
    val osdMarquee = Pref("osd_marquee", 0)                // 0 none, 1 right-to-left, 2 left-to-right
    val osdSizePercent = Pref("osd_size_percent", 100)
    val osdPaddingPercent = Pref("osd_padding_percent", 1)
    val osdPosition = Pref("osd_position", 0)
    val osdFont = Pref("osd_font", 0)                      // 0 sans, 1 serif, 2 monospace, 3 custom file
    val osdFontFile = Pref("osd_font_file", "")
    val osdFontStyle = Pref("osd_font_style", 0)
    val osdTextColor = Pref("osd_text_color", 0xFFFFFFFF.toInt())
    val osdBackgroundColor = Pref("osd_background_color", 0x00000000)
    val watermark = Pref("watermark", false)
    val watermarkUri = Pref("watermark_uri", "")
    val watermarkPaddingPercent = Pref("watermark_padding_percent", 0)
    val watermarkMaxAreaPercent = Pref("watermark_max_area_percent", 36)
    val watermarkPosition = Pref("watermark_position", 0)

    // Phone-camera and screen insets
    val insetCamera = Pref("inset_camera", "")             // camera id, empty = off
    val insetPosition = Pref("inset_position", 0)
    val insetRotation = Pref("inset_rotation", 0)
    val insetQuality = Pref("inset_quality", 0)
    val insetSizePercent = Pref("inset_size_percent", 66)
    val insetPaddingPercent = Pref("inset_padding_percent", 16)
    val insetKeepInBackground = Pref("inset_keep_in_background", true)
    val screenInset = Pref("screen_inset", false)
    val screenInsetPosition = Pref("screen_inset_position", 1)
    val screenInsetSizePercent = Pref("screen_inset_size_percent", 40)

    // One-time notices
    val audioNoticeHidden = Pref("audio_notice_hidden", false)
}

class Settings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // A fresh install gets its own server password instead of a well-known default.
        if (!prefs.contains(Keys.serverPassword.key)) {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            set(Keys.serverPassword, (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString(""))
        }
    }

    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(p: Pref<T>): T = when (val d = p.default) {
        is Boolean -> prefs.getBoolean(p.key, d)
        is Int -> prefs.getInt(p.key, d)
        is Long -> prefs.getLong(p.key, d)
        is Float -> prefs.getFloat(p.key, d)
        is String -> prefs.getString(p.key, d) ?: d
        else -> error("unsupported preference type")
    } as T

    operator fun <T : Any> set(p: Pref<T>, value: T) {
        prefs.edit().apply {
            when (value) {
                is Boolean -> putBoolean(p.key, value)
                is Int -> putInt(p.key, value)
                is Long -> putLong(p.key, value)
                is Float -> putFloat(p.key, value)
                is String -> putString(p.key, value)
                else -> error("unsupported preference type")
            }
        }.apply()
    }

    fun onChange(listener: (String?) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> listener(key) }
        prefs.registerOnSharedPreferenceChangeListener(l)
        return l
    }

    /** Per-device saved state (format, control values, view orientation), keyed by USB identity. */
    fun deviceState(deviceKey: String): JSONObject =
        runCatching { JSONObject(prefs.getString("device:$deviceKey", "{}")!!) }.getOrDefault(JSONObject())

    fun saveDeviceState(deviceKey: String, state: JSONObject) {
        prefs.edit().putString("device:$deviceKey", state.toString()).apply()
    }

    fun clearDeviceStates() {
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith("device:") }.forEach { remove(it) } }.apply()
    }
}

/** Compose binding: a state that follows the preference and writes back to it. */
@Composable
fun <T : Any> Settings.state(p: Pref<T>): MutableState<T> {
    val state = remember(p.key) { mutableStateOf(get(p)) }
    DisposableEffect(p.key) {
        val l = onChange { if (it == p.key) state.value = get(p) }
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
    return remember(p.key) {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(v) {
                    state.value = v
                    this@state[p] = v
                }

            override fun component1() = value
            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}
