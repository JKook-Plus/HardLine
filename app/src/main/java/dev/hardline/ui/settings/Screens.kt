package dev.hardline.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.hardline.core.Keys
import dev.hardline.core.state
import dev.hardline.net.FtpUploader
import dev.hardline.net.Mailer
import dev.hardline.net.PushTarget
import dev.hardline.net.Tls
import dev.hardline.service.CameraController
import dev.hardline.ui.MainActivity
import dev.hardline.ui.QrCode
import dev.hardline.ui.aspectChoices
import dev.hardline.ui.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

private val titles = mapOf(
    "main" to "Settings", "display" to "Display", "startup" to "Connection and start-up", "video" to "USB video", "audio" to "Sound",
    "recording" to "Recording and storage", "overlay" to "Text and watermark", "server" to "Web server and encoders",
    "push" to "Live-push destinations", "mail" to "E-mail alerts", "ftp" to "FTP upload", "buttons" to "Buttons", "about" to "About",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHost(activity: MainActivity, c: CameraController, screen: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val behavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(titles[screen] ?: "Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                scrollBehavior = behavior,
            )
        },
    ) { padding ->
        key(screen) {
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                when (screen) {
                    "display" -> DisplaySettings(activity, c)
                    "startup" -> StartupSettings(c)
                    "video" -> VideoSettings(c)
                    "audio" -> AudioSettings(activity, c)
                    "recording" -> RecordingSettings(activity, c)
                    "overlay" -> OverlaySettings(activity, c)
                    "server" -> ServerSettings(c)
                    "push" -> PushSettings(activity, c)
                    "mail" -> MailSettings(c)
                    "ftp" -> FtpSettings(c)
                    "buttons" -> ButtonSettings(c)
                    "about" -> AboutSettings()
                    else -> MainSettings(onOpen)
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun MainSettings(onOpen: (String) -> Unit) {
    val items = listOf(
        Triple("display", Icons.Default.Smartphone, "Title, shape, full screen, lock screen"),
        Triple("startup", Icons.Default.PowerSettingsNew, "What happens when a camera is plugged in"),
        Triple("video", Icons.Default.Videocam, "Decoder, pass-through, remembered cameras"),
        Triple("audio", Icons.Default.GraphicEq, "Sources, levels, playback"),
        Triple("recording", Icons.Default.SdStorage, "Codec, file splitting, loop recording, folder"),
        Triple("overlay", Icons.Default.TextFields, "Time, position, your own text, a logo"),
        Triple("server", Icons.Default.Wifi, "Password, ports, HTTPS, stream quality"),
        Triple("push", Icons.Default.Podcasts, "RTMP and SRT servers to stream to"),
        Triple("mail", Icons.Default.Email, "Messages when movement is detected"),
        Triple("ftp", Icons.Default.CloudUpload, "Copy finished recordings to a server"),
        Triple("buttons", Icons.Default.SmartButton, "Camera button and media keys"),
        Triple("about", Icons.Default.Info, "Version and open-source licences"),
    )
    for ((id, icon, summary) in items) PrefRow(titles.getValue(id), summary, icon = icon, onClick = { onOpen(id) })
}

// ---------------------------------------------------------------------- display

@Composable
private fun DisplaySettings(activity: MainActivity, c: CameraController) {
    val s = c.settings
    TextPref(s, Keys.appTitle, "Title", empty = "Name of the connected camera")
    ChoicePref(s, Keys.aspectRatio, "Shape on screen", aspectChoices)
    SwitchPref(s, Keys.cardboardView, "Side-by-side view", "Two copies of the picture for a phone VR headset")
    SwitchPref(s, Keys.overlayOnPreview, "Text and watermark on the preview", "They are always part of recordings and streams")
    SwitchPref(s, Keys.showFps, "Format and frame rate", "Show the video format, the measured frame rate and the decoder")
    SwitchPref(s, Keys.audioIndicator, "Sound level meter")
    Section("Screen")
    SwitchPref(s, Keys.forceLandscape, "Always landscape", onChanged = { activity.applyOrientation() })
    SwitchPref(s, Keys.fullScreenOnStart, "Start in full screen", "Hide the status and navigation bars")
    Section("Picture in picture")
    SwitchPref(s, Keys.useSystemPip, "Use Android picture-in-picture", "When off, a floating window that can be moved and resized freely is used")
    SwitchPref(s, Keys.pipOnStart, "Start in picture-in-picture")
    Section("Lock screen")
    SwitchPref(s, Keys.showOnLockScreen, "Show over the lock screen", "When a camera connects while the phone is locked")
    SwitchPref(s, Keys.wakeScreenOnConnect, "Wake the screen when a camera connects")
}

// ---------------------------------------------------------------------- connection and start-up

private fun seconds(n: Int) = if (n == 0) "No delay" else if (n == 1) "1 second" else "$n seconds"

@Composable
private fun StartupSettings(c: CameraController) {
    val s = c.settings
    val record by s.state(Keys.autoRecordOnConnect)
    val motion by s.state(Keys.autoMotionOnConnect)
    val server by s.state(Keys.autoServerOnConnect)
    val boot by s.state(Keys.startOnBoot)
    Section("When a camera is connected")
    SwitchPref(s, Keys.dontOpenOnConnect, "Stay in the background", "Start working without bringing the app to the front")
    SwitchPref(s, Keys.autoMotionOnConnect, "Start motion detection")
    SwitchPref(s, Keys.autoRecordOnConnect, "Start recording", if (motion) "Motion detection decides when to record" else null, enabled = !motion)
    NumberPref(s, Keys.recordDelaySeconds, "Wait before recording", 0..600, enabled = record && !motion, describe = ::seconds)
    SwitchPref(s, Keys.autoServerOnConnect, "Start the web server")
    SwitchPref(s, Keys.autoRtspOnConnect, "Start RTSP as well", enabled = server)
    SwitchPref(s, Keys.autoPushOnConnect, "Start live push", "To the destinations ticked on the live-push panel")
    Section("When the camera is removed")
    SwitchPref(s, Keys.exitOnDisconnect, "Close the app")
    Section("When the phone starts")
    SwitchPref(s, Keys.startOnBoot, "Start in the background", "Ready for a camera without opening the app")
    NumberPref(s, Keys.bootDelaySeconds, "Wait after the phone has started", 0..600, enabled = boot, describe = ::seconds)
}

// ---------------------------------------------------------------------- USB video

@Composable
private fun VideoSettings(c: CameraController) {
    val s = c.settings
    var confirm by remember { mutableStateOf(false) }
    SwitchPref(s, Keys.subDeviceDialog, "Ask which camera to use", "For devices with more than one camera inside")
    SwitchPref(s, Keys.rememberDeviceSettings, "Remember each camera", "Format, picture controls and rotation come back when a camera is connected again")
    PrefRow("Forget remembered cameras", "Clear the saved formats, picture controls and rotation", onClick = { confirm = true })
    Section("Decoding")
    SwitchPref(s, Keys.hardwareDecoder, "Hardware decoder", "For cameras that send H.264 or H.265. Used from the next time the format is set")
    SwitchPref(s, Keys.dropBrokenJpeg, "Skip damaged pictures", "Hide Motion-JPEG frames that arrive incomplete")
    ChoicePref(s, Keys.deinterlace, "Deinterlace", listOf(0 to "Off", 1 to "Blend lines"))
    Section("Pass-through")
    Note("Send the camera's own compressed video to viewers without encoding it again. This saves battery, but text, watermark, insets and rotation are left out.")
    SwitchPref(s, Keys.h264Bypass, "H.264 pass-through", "For web video, RTSP and live push")
    SwitchPref(s, Keys.hevcBypass, "H.265 pass-through", "For web video, RTSP and live push")
    SwitchPref(s, Keys.mjpegBypass, "Motion-JPEG pass-through", "For the browser video")
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Forget remembered cameras?") },
        text = { Text("Every camera starts with its default format and picture controls the next time it is connected.") },
        confirmButton = {
            TextButton(onClick = { s.clearDeviceStates(); confirm = false; c.messages.tryEmit("Remembered cameras cleared") }) { Text("Forget") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------- sound

private fun percent(v: Float) = "${(v * 100).toInt()}%"

@Composable
private fun AudioSettings(activity: MainActivity, c: CameraController) {
    val s = c.settings
    val usb by s.state(Keys.usbAudioInput)
    val mic by s.state(Keys.useMicrophone)
    val playback by s.state(Keys.audioPlayback)
    Section("Sources")
    SwitchPref(s, Keys.usbAudioInput, "USB sound", "The camera's microphone or a USB audio adapter")
    SwitchPref(s, Keys.preferExternalUsbAudio, "Prefer a separate USB audio device", "When both it and the camera have sound", enabled = usb)
    SwitchPref(
        s, Keys.useMicrophone, "Phone microphone", "Used when there is no USB sound",
        intercept = { wanted, apply ->
            if (!wanted) apply(false) else activity.withPermissions(Manifest.permission.RECORD_AUDIO) { granted ->
                if (granted) apply(true) else c.messages.tryEmit("Microphone permission was not granted")
            }
        },
    )
    SwitchPref(s, Keys.mixMicWithUsb, "Mix the microphone with USB sound", enabled = mic && usb)
    SwitchPref(
        s, Keys.mixSystemAudio, "Mix this phone's sound", "Music and other apps. Android asks for screen-capture permission each time",
        intercept = { wanted, apply ->
            if (!wanted) { c.setSystemAudioMix(false); apply(false) } else activity.withPermissions(Manifest.permission.RECORD_AUDIO) { granted ->
                if (!granted) c.messages.tryEmit("Microphone permission was not granted") else {
                    apply(true)
                    if (c.setSystemAudioMix(true)) activity.requestProjection()
                }
            }
        },
    )
    Section("Levels")
    FloatSliderPref(s, Keys.usbGain, "USB sound", 0f..4f, 0.05f, describe = ::percent)
    FloatSliderPref(s, Keys.micGain, "Phone microphone", 0f..4f, 0.05f, describe = ::percent)
    FloatSliderPref(s, Keys.systemGain, "Phone sound", 0f..4f, 0.05f, describe = ::percent)
    Section("Listening")
    SwitchPref(s, Keys.audioPlayback, "Play the sound on this phone")
    SwitchPref(s, Keys.backgroundAudioPlayback, "Keep playing in the background", enabled = playback)
    SwitchPref(s, Keys.volumeLimit, "Quieter while the microphone is on", "Prevents howling between speaker and microphone", enabled = playback)
}

// ---------------------------------------------------------------------- recording and storage

private fun displayName(context: Context, uri: String): String? = runCatching {
    val u = Uri.parse(uri)
    context.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?: u.lastPathSegment?.substringAfterLast(':')
}.getOrNull() ?: Uri.parse(uri).lastPathSegment?.substringAfterLast(':')

private fun minutes(n: Int) = if (n == 0) "Never" else if (n == 1) "Every minute" else "Every $n minutes"
private fun gigabytes(n: Int) = if (n == 0) "All the free space" else "$n GB"

private fun locationGate(activity: MainActivity, c: CameraController): (Boolean, (Boolean) -> Unit) -> Unit = { wanted, apply ->
    if (!wanted) apply(false) else activity.withPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) {
        if (c.location.permitted) apply(true) else c.messages.tryEmit("Location permission was not granted")
        c.overlays.updateLocationUse()
    }
}

@Composable
private fun RecordingSettings(activity: MainActivity, c: CameraController) {
    val s = c.settings
    val context = LocalContext.current
    val custom by s.state(Keys.customRecordBitrate)
    val loop by s.state(Keys.loopRecording)
    val motionLoop by s.state(Keys.motionLoopRecording)
    var tree by s.state(Keys.saveToTree)
    var treeUri by s.state(Keys.saveTreeUri)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            treeUri = uri.toString()
            tree = true
        }
    }
    Section("Video file")
    ChoicePref(s, Keys.recordCodec, "Video codec", listOf(0 to "H.264 (plays everywhere)", 1 to "HEVC (smaller files)", 2 to "AV1 (smallest; newer phones only)"))
    SwitchPref(s, Keys.customRecordBitrate, "Set the bit rate yourself", "Otherwise it follows the picture size")
    FloatSliderPref(s, Keys.recordBitrateMbps, "Bit rate", 0.5f..50f, 0.5f, enabled = custom) { "%.1f Mbit/s".format(it) }
    Section("Splitting")
    NumberPref(s, Keys.segmentMinutes, "Start a new file", 0..720, hint = "Minutes; 0 for never", describe = ::minutes)
    SwitchPref(s, Keys.fourGbLimit, "Keep files under 4 GB", "Starts a new file before the size limit of FAT32 memory cards")
    Section("Loop recording")
    SwitchPref(s, Keys.loopRecording, "Delete the oldest recordings", "When the space below is used up")
    NumberPref(s, Keys.spaceLimitGb, "Space for recordings", 0..2000, enabled = loop, hint = "Gigabytes; 0 for all the free space", describe = ::gigabytes)
    Section("Motion recordings")
    NumberPref(s, Keys.motionTimeoutSeconds, "Stop after no movement for", 3..600) { "$it seconds" }
    NumberPref(s, Keys.motionSegmentMinutes, "Start a new file", 0..720, hint = "Minutes; 0 for never", describe = ::minutes)
    SwitchPref(s, Keys.motionLoopRecording, "Delete the oldest motion recordings", "When the space below is used up")
    NumberPref(s, Keys.motionSpaceLimitGb, "Space for motion recordings", 0..2000, enabled = motionLoop, hint = "Gigabytes; 0 for all the free space", describe = ::gigabytes)
    Section("Where files go")
    PrefRow("Folder", if (tree && treeUri.isNotEmpty()) displayName(context, treeUri) ?: "Chosen folder" else "DCIM/HardLine", onClick = { picker.launch(null) })
    if (tree) PrefRow("Use the standard folder", "DCIM/HardLine, visible in the gallery", onClick = { tree = false })
    SwitchPref(s, Keys.deviceNameInFilename, "Camera name in file names")
    SwitchPref(s, Keys.exifLocation, "Save the position in pictures", intercept = locationGate(activity, c))
    PrefRow("Free space", formatBytes(remember(tree, treeUri) { c.storage.freeBytes() }))
}

// ---------------------------------------------------------------------- text and watermark

@Composable
private fun OverlaySettings(activity: MainActivity, c: CameraController) {
    val s = c.settings
    val context = LocalContext.current
    val timestamp by s.state(Keys.osdTimestamp)
    val speed by s.state(Keys.osdSpeed)
    val custom by s.state(Keys.osdCustomText)
    var font by s.state(Keys.osdFont)
    var fontFile by s.state(Keys.osdFontFile)
    var watermark by s.state(Keys.watermark)
    var watermarkUri by s.state(Keys.watermarkUri)

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val copied = uri?.let {
            runCatching {
                val name = (displayName(context, it.toString()) ?: "font.ttf").replace(Regex("[^A-Za-z0-9._-]"), "_")
                val dir = File(context.filesDir, "fonts").apply { mkdirs() }
                context.contentResolver.openInputStream(it)!!.use { input -> File(dir, name).outputStream().use(input::copyTo) }
                android.graphics.Typeface.createFromFile(File(dir, name))
                "fonts/$name"
            }.getOrNull()
        }
        if (copied != null) { fontFile = copied; font = 3 } else {
            if (uri != null) c.messages.tryEmit("That file is not a font this phone can use")
            if (fontFile.isEmpty()) font = 0
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            watermarkUri = uri.toString()
            watermark = true
        }
    }

    Note("Text and pictures added here are drawn into the video, so they appear in recordings, snapshots and streams.")
    Section("Text")
    SwitchPref(s, Keys.osdTimestamp, "Date and time")
    TextPref(
        s, Keys.osdTimeFormat, "Date and time pattern", enabled = timestamp,
        hint = "y year, M month, d day, E weekday, H hour, m minute, s second",
        validate = { p -> if (p.isBlank() || runCatching { SimpleDateFormat(p, Locale.getDefault()) }.isSuccess) null else "Not a valid pattern" },
    )
    SwitchPref(s, Keys.osdDeviceName, "Camera name")
    SwitchPref(s, Keys.osdBattery, "Battery level")
    SwitchPref(s, Keys.osdLocation, "Position", "Latitude and longitude", intercept = locationGate(activity, c))
    SwitchPref(s, Keys.osdSpeed, "Speed", intercept = locationGate(activity, c))
    ChoicePref(s, Keys.osdSpeedUnit, "Speed unit", listOf(0 to "km/h", 1 to "mph"), enabled = speed)
    SwitchPref(s, Keys.osdCustomText, "Your own text")
    TextPref(s, Keys.osdText, "Text", enabled = custom, lines = 3)
    ChoicePref(s, Keys.osdMarquee, "Scroll your text", listOf(0 to "Off", 1 to "Right to left", 2 to "Left to right"), enabled = custom)
    Section("Look")
    PositionPref(s, Keys.osdPosition, "Position")
    IntSliderPref(s, Keys.osdSizePercent, "Size", 30..300)
    IntSliderPref(s, Keys.osdPaddingPercent, "Distance from the edge", 0..20)
    ChoicePref(
        s, Keys.osdFont, "Font", listOf(0 to "Sans serif", 1 to "Serif", 2 to "Monospace", 3 to if (fontFile.isEmpty()) "Font file…" else fontFile.substringAfter('/')),
        onChanged = { if (it == 3 && fontFile.isEmpty()) fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) },
    )
    if (font == 3) PrefRow("Choose another font file", onClick = { fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) })
    ChoicePref(s, Keys.osdFontStyle, "Style", listOf(0 to "Regular", 1 to "Bold", 2 to "Italic", 3 to "Bold italic"))
    ColorPref(s, Keys.osdTextColor, "Text colour")
    ColorPref(s, Keys.osdBackgroundColor, "Background")
    Section("Watermark")
    SwitchPref(
        s, Keys.watermark, "Show a picture",
        intercept = { wanted, apply -> if (wanted && watermarkUri.isEmpty()) imagePicker.launch(arrayOf("image/*")) else apply(wanted) },
    )
    PrefRow(
        "Picture", if (watermarkUri.isEmpty()) "PNG, JPEG, WebP or animated GIF" else displayName(context, watermarkUri) ?: "Chosen picture",
        onClick = { imagePicker.launch(arrayOf("image/*")) },
    )
    PositionPref(s, Keys.watermarkPosition, "Position", enabled = watermark)
    IntSliderPref(s, Keys.watermarkMaxAreaPercent, "Largest size", 1..100, enabled = watermark) { "$it% of the picture" }
    IntSliderPref(s, Keys.watermarkPaddingPercent, "Distance from the edge", 0..20, enabled = watermark)
}

// ---------------------------------------------------------------------- web server and encoders

private val threeCodecs = listOf(0 to "H.264", 1 to "HEVC", 2 to "AV1")
private val twoCodecs = listOf(0 to "H.264", 1 to "HEVC")
private fun megabits(v: Float) = "%.1f Mbit/s".format(v)
private fun keyframe(n: Int) = if (n == 1) "Every second" else "Every $n seconds"

@Composable
private fun ServerSettings(c: CameraController) {
    val s = c.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by c.web.running.collectAsState()
    val https by s.state(Keys.https)
    var certificate by s.state(Keys.certificateFile)
    var certificatePassword by s.state(Keys.certificatePassword)
    val customH264 by s.state(Keys.customH264Bitrate)
    val customHevc by s.state(Keys.customHevcBitrate)
    val customAv1 by s.state(Keys.customAv1Bitrate)
    var imported by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val name = (displayName(context, uri.toString()) ?: "certificate.p12").replace(Regex("[^A-Za-z0-9._-]"), "_")
            context.contentResolver.openInputStream(uri)!!.use { input -> File(Tls.certDir(context), name).outputStream().use(input::copyTo) }
            imported = name
        }.onFailure { c.messages.tryEmit("The file could not be read") }
    }

    if (running) Note("The web server is running. Changes to access, ports and encryption apply the next time it is started.")
    Section("Access")
    TextPref(s, Keys.serverUser, "User name", validate = { if (':' in it) "A colon is not allowed" else null })
    TextPref(s, Keys.serverPassword, "Password", empty = "None: anyone on the network can watch", secret = true)
    NumberPref(s, Keys.httpPort, "Web port", 1024..65535) { "$it" }
    NumberPref(s, Keys.rtspPort, "RTSP port", 1024..65535) { "$it" }
    SwitchPref(s, Keys.rtspEnabled, "RTSP with the web server", "Start the RTSP stream whenever the web server starts")
    TextPref(s, Keys.pageTitle, "Page title", empty = c.pageTitle())
    TextPref(s, Keys.hostname, "Name on the network", empty = "HardLine", hint = "Shown to devices that look for web servers nearby")
    SwitchPref(s, Keys.upnp, "Open ports on the router", "Asks the router to make the server reachable from the internet (UPnP). Use a strong password")
    Section("Encryption")
    SwitchPref(s, Keys.https, "HTTPS and RTSPS", "A certificate is created on first use. Browsers warn about it because it is self-signed")
    PrefRow("Certificate", certificate.ifEmpty { "Created automatically when needed" }, enabled = https)
    PrefRow("Create a new certificate", "Self-signed, valid for ten years", enabled = https, onClick = {
        scope.launch {
            val name = withContext(Dispatchers.Default) { runCatching { Tls.generate(context, s) }.getOrNull() }
            c.messages.tryEmit(if (name != null) "Certificate created" else "The certificate could not be created")
        }
    })
    PrefRow("Import a certificate", "A PKCS#12 file (.p12 or .pfx) that contains the private key", enabled = https, onClick = {
        picker.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*"))
    })
    Section("Video codec")
    ChoicePref(s, Keys.flvCodec, "Web video with sound", threeCodecs)
    ChoicePref(s, Keys.rtspCodec, "RTSP", twoCodecs)
    ChoicePref(s, Keys.rtmpCodec, "RTMP push", threeCodecs, note = "HEVC and AV1 need a server that accepts enhanced RTMP.")
    ChoicePref(s, Keys.srtCodec, "SRT push", twoCodecs)
    SwitchPref(s, Keys.hardwareEncoder, "Hardware encoder", "Turn off only if streams look wrong on this phone")
    Section("H.264")
    ChoicePref(s, Keys.h264Profile, "Profile", listOf(0 to "Baseline (most compatible)", 1 to "Main", 2 to "High (best quality)"))
    SwitchPref(s, Keys.customH264Bitrate, "Set the bit rate yourself", "Otherwise 2 Mbit/s")
    FloatSliderPref(s, Keys.h264BitrateMbps, "Bit rate", 0.2f..20f, 0.1f, enabled = customH264, describe = ::megabits)
    NumberPref(s, Keys.h264KeyframeSeconds, "Key frame", 1..10, describe = ::keyframe)
    Section("HEVC")
    SwitchPref(s, Keys.customHevcBitrate, "Set the bit rate yourself", "Otherwise 2 Mbit/s")
    FloatSliderPref(s, Keys.hevcBitrateMbps, "Bit rate", 0.2f..20f, 0.1f, enabled = customHevc, describe = ::megabits)
    NumberPref(s, Keys.hevcKeyframeSeconds, "Key frame", 1..10, describe = ::keyframe)
    Section("AV1")
    SwitchPref(s, Keys.customAv1Bitrate, "Set the bit rate yourself", "Otherwise 2 Mbit/s")
    FloatSliderPref(s, Keys.av1BitrateMbps, "Bit rate", 0.2f..20f, 0.1f, enabled = customAv1, describe = ::megabits)
    NumberPref(s, Keys.av1KeyframeSeconds, "Key frame", 1..10, describe = ::keyframe)

    imported?.let { name ->
        TextDialog(
            "Certificate password", "", { imported = null }, secret = true, hint = "Leave empty if the file has none",
            validate = { p ->
                val ok = runCatching { KeyStore.getInstance("PKCS12").apply { File(Tls.certDir(context), name).inputStream().use { load(it, p.toCharArray()) } }.aliases().hasMoreElements() }
                if (ok.getOrDefault(false)) null else "This password does not open the file"
            },
        ) { p ->
            certificate = name
            certificatePassword = p
            c.messages.tryEmit("Certificate imported")
        }
    }
}

// ---------------------------------------------------------------------- live-push destinations

@Composable
private fun PushSettings(activity: MainActivity, c: CameraController) {
    val context = LocalContext.current
    val json by c.settings.state(Keys.pushTargets)
    val targets = remember(json) { c.pushTargets() }
    var editing by remember { mutableStateOf<PushTarget?>(null) }
    var sharing by remember { mutableStateOf<PushTarget?>(null) }
    var scanning by remember { mutableStateOf(false) }

    // Text read from a QR code opens the editor, so nothing is added without being looked at.
    fun received(text: String?) {
        val target = text?.let(QrDecoder::toTarget)
        if (target != null) editing = target else c.messages.tryEmit(if (text == null) "No QR code was found" else "The QR code does not hold a streaming address")
    }
    val picturePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) received(runCatching {
            context.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }?.let(QrDecoder::decode)
        }.getOrNull())
    }

    Note("Servers that receive the live video. Streaming services give you a server address and a stream key; SRT servers need only the address.")
    for (t in targets) ListItem(
        headlineContent = { Text(t.title.ifBlank { t.url }) },
        supportingContent = { Text(t.url, maxLines = 1) },
        leadingContent = { Icon(Icons.Default.Podcasts, null) },
        trailingContent = {
            Row {
                IconButton(onClick = { sharing = t }) { Icon(Icons.Default.QrCode2, "Show as QR code") }
                IconButton(onClick = { editing = t }) { Icon(Icons.Default.Edit, "Edit") }
                IconButton(onClick = {
                    c.savePushTargets(targets.filter { it.id != t.id })
                    c.setSelectedPushIds(c.selectedPushIds() - t.id)
                }) { Icon(Icons.Default.Delete, "Delete") }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    FilledTonalButton(onClick = { editing = PushTarget(UUID.randomUUID().toString(), "", "rtmp://", "") }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Add destination")
    }
    Section("Copy from a QR code")
    PrefRow("Scan with the camera", "Read a code shown on another screen", icon = Icons.Default.QrCodeScanner, onClick = {
        activity.withPermissions(Manifest.permission.CAMERA) { granted ->
            if (granted) scanning = true else c.messages.tryEmit("Camera permission is needed to scan")
        }
    })
    PrefRow("Read from a picture", "A screenshot or photo of the code", icon = Icons.Default.Image, onClick = { picturePicker.launch(arrayOf("image/*")) })
    if (scanning) QrScanDialog(onResult = { scanning = false; received(it) }, onDismiss = { scanning = false })

    editing?.let { original ->
        PushEditor(original, onDismiss = { editing = null }) { saved ->
            val exists = targets.any { it.id == saved.id }
            c.savePushTargets(if (exists) targets.map { if (it.id == saved.id) saved else it } else targets + saved)
            if (!exists) c.setSelectedPushIds(c.selectedPushIds() + saved.id)
            editing = null
        }
    }
    sharing?.let { t ->
        AlertDialog(
            onDismissRequest = { sharing = null },
            title = { Text(t.title.ifBlank { "Destination" }) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    QrCode(t.toJson().toString(), Modifier.size(240.dp))
                    Text(
                        "Scan this with another phone to copy the destination. It contains the stream key.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { sharing = null }) { Text("Done") } },
        )
    }
}

@Composable
private fun PushEditor(original: PushTarget, onDismiss: () -> Unit, onSave: (PushTarget) -> Unit) {
    var title by remember { mutableStateOf(original.title) }
    var url by remember { mutableStateOf(original.url) }
    var key by remember { mutableStateOf(original.key) }
    var listener by remember { mutableStateOf(original.srtMode == "listener") }
    var latency by remember { mutableStateOf(if (original.srtLatencyMs > 0) original.srtLatencyMs.toString() else "") }
    var passphrase by remember { mutableStateOf(original.srtPassphrase) }
    val srt = url.trim().startsWith("srt://", true)
    val urlOk = PushTarget.isValidUrl(url.trim())
    val passOk = passphrase.isEmpty() || passphrase.length in 10..79
    val latencyOk = latency.isEmpty() || latency.toIntOrNull()?.let { it in 20..8000 } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original.title.isNotEmpty() || original.url.length > 7) "Destination" else "New destination") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url, { url = it }, label = { Text("Server address") }, singleLine = true, isError = !urlOk,
                    supportingText = {
                        Text(when {
                            !urlOk -> "Starts with rtmp://, rtmps:// or srt:// and names a server"
                            srt && listener -> "Only the port is used, for example srt://0.0.0.0:9000"
                            else -> "rtmp://, rtmps:// or srt://"
                        })
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
                if (!srt) OutlinedTextField(
                    key, { key = it }, label = { Text("Stream key") }, singleLine = true, supportingText = { Text("Added to the end of the address") },
                    modifier = Modifier.fillMaxWidth(),
                ) else {
                    Row(Modifier.toggleable(listener, role = Role.Switch) { listener = it }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Wait for the receiver to connect")
                            Text("Listener mode: the port of the address is opened on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(listener, null)
                    }
                    OutlinedTextField(
                        latency, { latency = it.filter(Char::isDigit) }, label = { Text("Latency (ms)") }, singleLine = true, isError = !latencyOk,
                        supportingText = { Text("20 to 8000; empty for 120") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        passphrase, { passphrase = it }, label = { Text("Passphrase") }, singleLine = true, isError = !passOk,
                        supportingText = { Text("10 to 79 characters to encrypt the stream; empty for none") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = urlOk && passOk && latencyOk, onClick = {
                onSave(original.copy(
                    title = title.trim(), url = url.trim(), key = if (srt) "" else key.trim(), srtMode = if (listener) "listener" else "caller",
                    srtLatencyMs = latency.toIntOrNull() ?: -1, srtPassphrase = if (srt) passphrase else "",
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------- mail and FTP

/** A row that runs a network check and shows the outcome in a dialog. */
@Composable
private fun CheckRow(title: String, summary: String, success: String, check: suspend () -> String?) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    PrefRow(title, summary, enabled = !busy, onClick = {
        busy = true
        scope.launch {
            val failure = withContext(Dispatchers.IO) { check() }
            result = if (failure == null) true to success else false to failure
            busy = false
        }
    }) { if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
    result?.let { (ok, text) ->
        AlertDialog(
            onDismissRequest = { result = null },
            icon = { Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null) },
            title = { Text(if (ok) "It works" else "It did not work") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { result = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun MailSettings(c: CameraController) {
    val s = c.settings
    val mailTo by s.state(Keys.mailTo)
    Section("Mail account")
    TextPref(s, Keys.mailTo, "Send to", keyboard = KeyboardType.Email, validate = { if (it.isBlank() || '@' in it) null else "Enter an e-mail address" })
    TextPref(s, Keys.smtpServer, "Outgoing mail server (SMTP)", keyboard = KeyboardType.Uri)
    ChoicePref(s, Keys.smtpSecurity, "Security", listOf(0 to "None", 1 to "STARTTLS", 2 to "SSL/TLS"), onChanged = { s[Keys.smtpPort] = listOf(25, 587, 465)[it] })
    NumberPref(s, Keys.smtpPort, "Port", 1..65535) { "$it" }
    TextPref(s, Keys.smtpUser, "User name", keyboard = KeyboardType.Email)
    TextPref(s, Keys.smtpPassword, "Password", secret = true)
    CheckRow("Send a test message", "Checks the account settings above", "A test message is on its way to $mailTo.") {
        Mailer.send(s, "Test message from HardLine", "The mail settings work.")
    }
    Section("Send a message")
    SwitchPref(s, Keys.mailOnMotion, "When movement is detected", "With a picture attached. Motion detection must be on")
    SwitchPref(s, Keys.mailSnapshots, "For every picture taken")
}

@Composable
private fun FtpSettings(c: CameraController) {
    val s = c.settings
    val upload = s.state(Keys.ftpUploadManual).value || s.state(Keys.ftpUploadMotion).value
    Section("Server")
    TextPref(
        s, Keys.ftpUrl, "Address", keyboard = KeyboardType.Uri, hint = "ftp://server/folder or ftps://server:port/folder",
        validate = { if (it.startsWith("ftp://", true) || it.startsWith("ftps://", true)) null else "Starts with ftp:// or ftps://" },
    )
    TextPref(s, Keys.ftpUser, "User name", empty = "anonymous")
    TextPref(s, Keys.ftpPassword, "Password", secret = true)
    CheckRow("Test the connection", "Signs in and opens the folder", "The server accepted the sign-in and the folder exists.") { FtpUploader.verify(s) }
    Section("Upload")
    SwitchPref(s, Keys.ftpUploadManual, "Recordings", "Each finished recording is copied to the server")
    SwitchPref(s, Keys.ftpUploadMotion, "Motion recordings")
    SwitchPref(s, Keys.ftpDeleteAfterUpload, "Delete from the phone after upload", enabled = upload)
}

// ---------------------------------------------------------------------- buttons

@Composable
private fun ButtonSettings(c: CameraController) {
    val s = c.settings
    val actions = listOf(0 to "Nothing", 3 to "Take a picture", 1 to "Start or stop recording", 2 to "Take a burst of pictures")
    Section("What a press does")
    ChoicePref(s, Keys.cameraButtonAction, "Button on the camera", actions)
    ChoicePref(s, Keys.mediaPlayAction, "Play/pause or headset button", actions)
    ChoicePref(s, Keys.mediaPreviousAction, "Previous-track button", actions)
    ChoicePref(s, Keys.mediaNextAction, "Next-track button", actions)
    Section("Burst")
    NumberPref(s, Keys.burstCount, "Pictures per burst", 1..50) { if (it == 1) "1 picture" else "$it pictures in a row" }
    Section("Feedback")
    SwitchPref(s, Keys.vibrate, "Vibrate when a button is used")
}

// ---------------------------------------------------------------------- about

private const val SOURCE_URL = "https://github.com/JKook-Plus/HardLine"

private val licences = listOf(
    "libusb" to "USB device access. GNU Lesser General Public License 2.1. Shipped as the separate library libusb1.so so that it can be replaced.",
    "libuvc" to "USB video class streaming. BSD 3-Clause License. Copyright © 2010–2015 Ken Tossell and contributors.",
    "Apache Commons Net" to "FTP client. Apache License 2.0.",
    "JavaMail for Android" to "SMTP client. Eclipse Public License 2.0, or GPL 2.0 with Classpath Exception.",
    "ZXing" to "QR codes. Apache License 2.0.",
    "AndroidX, Jetpack Compose and Material Components" to "User interface. Apache License 2.0. Copyright © The Android Open Source Project.",
    "Kotlin and kotlinx.coroutines" to "Language and concurrency. Apache License 2.0. Copyright © JetBrains.",
)

@Composable
private fun AboutSettings() {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
    PrefRow("HardLine", "Version $version", icon = Icons.Default.Videocam)
    Note("View, record and stream USB cameras, capture adapters and USB audio from an Android phone, without root.")
    Section("Licence")
    Note("HardLine is free software. You may share and change it under the GNU General Public License, version 3. It comes with no warranty.")
    PrefRow("Source code", SOURCE_URL.removePrefix("https://"), icon = Icons.Default.Code, onClick = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) }
    })
    Section("Open-source software")
    for ((name, text) in licences) PrefRow(name, text)
}
