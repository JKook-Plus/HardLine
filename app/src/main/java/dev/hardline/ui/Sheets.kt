package dev.hardline.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.hardline.core.Keys
import dev.hardline.core.state
import dev.hardline.service.CameraController
import dev.hardline.service.Selection
import dev.hardline.usb.CameraControl
import dev.hardline.usb.ControlRange
import dev.hardline.usb.FrameSize
import dev.hardline.usb.PixelKind
import dev.hardline.usb.UsbSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetFrame(
    title: String, onDismiss: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, overPicture: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Sheets that change the picture stay low over a clear scrim, so the effect can be watched.
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !overPicture),
        scrimColor = if (overPicture) Color.Transparent else BottomSheetDefaults.ScrimColor,
    ) {
        // Capped as well: a sheet that fills up after opening would otherwise grow over the whole picture.
        val cap = if (overPicture) Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 2 / 5).dp) else Modifier
        Column(Modifier.fillMaxWidth().then(cap).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                actions()
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun SwitchLine(title: String, checked: Boolean, enabled: Boolean = true, summary: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled) { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipChoice(options: List<Pair<T, String>>, selected: T?, enabled: Boolean = true, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) }, enabled = enabled)
    }
}

@Composable
private fun IntSlider(label: String, value: Int, range: IntRange, suffix: String = "%", onChange: (Int) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("$value$suffix", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value.toFloat(), { onChange(it.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat())
    }
}

private val corners = listOf<Pair<Int, ImageVector>>(0 to Icons.Default.NorthWest, 1 to Icons.Default.NorthEast, 2 to Icons.Default.SouthWest, 3 to Icons.Default.SouthEast)
private val cornerNames = listOf("Top left", "Top right", "Bottom left", "Bottom right")

@Composable
private fun CornerChoice(selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        corners.forEachIndexed { i, (value, icon) ->
            SegmentedButton(selected == value, { onSelect(value) }, SegmentedButtonDefaults.itemShape(i, corners.size)) { Icon(icon, cornerNames[i]) }
        }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("address", text))
}

private fun share(context: Context, text: String) {
    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)) }
}

@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) {
        runCatching {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
            Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until m.height) for (x in 0 until m.width) setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }.getOrNull()
    } ?: return
    Image(bitmap.asImageBitmap(), "QR code", modifier.clip(RoundedCornerShape(12.dp)), filterQuality = FilterQuality.None)
}

// ---------------------------------------------------------------------- devices

@Composable
fun DevicesSheet(c: CameraController, onDismiss: () -> Unit) {
    val devices by c.devices.collectAsState()
    val session by c.session.collectAsState()
    val waiting by c.waitingForPermission.collectAsState()
    LaunchedEffect(Unit) { c.refreshDevices() }
    SheetFrame("USB devices", onDismiss, actions = { IconButton(onClick = { c.refreshDevices() }) { Icon(Icons.Default.Refresh, "Refresh") } }) {
        if (devices.isEmpty()) Text(
            "Nothing is connected. Plug a camera into the phone's USB port with an OTG adapter.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp),
        )
        for (d in devices) {
            val video = UsbSession.isVideo(d)
            val audio = UsbSession.isAudio(d)
            val open = session?.device?.deviceName == d.deviceName
            val name = d.productName?.takeIf { it.isNotBlank() } ?: "USB device"
            val audioInUse = !video && audio && c.audioDeviceName == name
            val kind = when {
                video && audio -> "Camera with microphone"
                video -> "Camera"
                audio -> "Audio input"
                else -> "Not a camera or audio input"
            }
            ListItem(
                headlineContent = { Text(name) },
                supportingContent = {
                    Text("%04X:%04X · %s".format(d.vendorId, d.productId, if (waiting?.deviceName == d.deviceName) "waiting for permission" else kind))
                },
                leadingContent = { Icon(if (video) Icons.Default.Videocam else if (audio) Icons.Default.Mic else Icons.Default.Usb, null) },
                trailingContent = {
                    when {
                        open -> AssistChip(onClick = { c.close() }, label = { Text("Eject") }, leadingIcon = { Icon(Icons.Default.Eject, null, Modifier.size(18.dp)) })
                        audioInUse -> Text("In use")
                        video || audio -> Icon(Icons.Default.ChevronRight, null)
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = (video || audio) && !open && !audioInUse) {
                    c.open(d)
                    if (video) onDismiss()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------- video format

private fun fpsLabel(interval: Int): String {
    val fps = 1e7 / interval
    return if (kotlin.math.abs(fps - fps.roundToInt()) < 0.01) "${fps.roundToInt()} fps" else "%.2f fps".format(fps)
}

@Composable
fun FormatSheet(c: CameraController, onDismiss: () -> Unit) {
    val session by c.session.collectAsState()
    val current by c.selection.collectAsState()
    val recording by c.recorder.state.collectAsState()
    val streams = session?.description?.streams.orEmpty().filter { s -> s.formats.any { it.kind != PixelKind.UNSUPPORTED && it.frames.isNotEmpty() } }
    var stream by remember(current) { mutableStateOf(current?.stream ?: streams.firstOrNull()) }
    var format by remember(current) { mutableStateOf(current?.format ?: streams.firstOrNull()?.formats?.firstOrNull()) }
    LaunchedEffect(session) { if (session == null) onDismiss() }

    fun apply(frame: FrameSize, interval: Int? = null) {
        val st = stream ?: return
        val f = format ?: return
        val wanted = interval ?: current?.interval
        // Keep the frame rate when the new size offers it; otherwise the fastest rate up to 30 fps.
        val chosen = wanted?.takeIf { it in frame.intervals } ?: frame.intervals.filter { it >= 333333 }.minOrNull() ?: frame.intervals.max()
        c.select(Selection(st, f, frame, chosen))
    }

    SheetFrame("Video format", onDismiss) {
        if (recording != null) Text(
            "Changing the format stops the recording in progress.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
        )
        if (streams.size > 1) {
            Label("Camera")
            ChipChoice(streams.mapIndexed { i, s -> s to "Camera ${i + 1}" }, stream) {
                stream = it
                format = it.formats.firstOrNull { f -> f.kind != PixelKind.UNSUPPORTED && f.frames.isNotEmpty() }
            }
        }
        val formats = stream?.formats.orEmpty().filter { it.kind != PixelKind.UNSUPPORTED && it.frames.isNotEmpty() }
        Label("Format")
        ChipChoice(formats.map { it to it.label }, format) { format = it }
        Text(
            when (format?.kind) {
                PixelKind.MJPEG -> "Compressed in the camera. High resolutions at full frame rate."
                PixelKind.H264, PixelKind.H265 -> "Compressed in the camera. Lowest USB load; can be passed straight to streams."
                null -> ""
                else -> "Uncompressed. Best picture, but high resolutions need USB 3 or run at low frame rates."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Label("Resolution")
        val selectedFrame = current?.takeIf { it.stream == stream && it.format == format }?.frame
        for (frame in format?.frames.orEmpty().sortedByDescending { it.width * it.height }) {
            val selected = frame == selectedFrame
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { apply(frame) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected, onClick = { apply(frame) })
                Text("${frame.width} × ${frame.height}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    "up to " + fpsLabel(frame.intervals.min()), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        if (selectedFrame != null && selectedFrame.intervals.size > 1) {
            Label("Frame rate")
            ChipChoice(selectedFrame.intervals.sorted().map { it to fpsLabel(it) }, current?.interval) { apply(selectedFrame, it) }
        }
    }
}

// ---------------------------------------------------------------------- picture controls

private val menuOptions = mapOf(
    CameraControl.POWER_LINE to listOf(0 to "Off", 1 to "50 Hz", 2 to "60 Hz", 3 to "Auto"),
    CameraControl.EXPOSURE_MODE to listOf(2 to "Auto", 1 to "Manual", 4 to "Shutter priority", 8 to "Aperture priority"),
)

@Composable
fun AdjustSheet(c: CameraController, onDismiss: () -> Unit) {
    val session by c.session.collectAsState()
    val s = session
    LaunchedEffect(s) { if (s == null) onDismiss() }
    if (s == null) return
    var ranges by remember { mutableStateOf<Map<CameraControl, ControlRange>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val values = remember { mutableStateMapOf<CameraControl, Int>() }
    val scope = rememberCoroutineScope()
    // Slider drags produce many values; only the newest one is worth sending to the camera.
    val pending = remember { Channel<Pair<CameraControl, Int>>(Channel.CONFLATED) }
    LaunchedEffect(s) { for ((control, value) in pending) c.setControl(control, value).join() }
    LaunchedEffect(s, reload) {
        val loaded = withContext(Dispatchers.IO) {
            CameraControl.entries.filter(s::supports).mapNotNull { control -> s.range(control)?.let { control to it } }.toMap()
        }
        values.clear()
        loaded.forEach { (control, r) -> values[control] = r.current }
        ranges = loaded
    }

    fun set(control: CameraControl, value: Int, refresh: Boolean = false) {
        values[control] = value
        if (!refresh) pending.trySend(control to value)
        else scope.launch {
            c.setControl(control, value).join()
            delay(150)
            reload++
        }
    }

    SheetFrame("Picture", onDismiss, overPicture = true, actions = {
        TextButton(onClick = { scope.launch { c.resetControls().join(); reload++ } }) {
            Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Reset")
        }
    }) {
        val loaded = ranges
        when {
            loaded == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            loaded.isEmpty() -> Text(
                "This camera has no adjustable picture controls.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp),
            )
            else -> for ((control, r) in loaded) {
                val value = values[control] ?: r.current
                when (control.kind) {
                    CameraControl.Kind.TOGGLE -> SwitchLine(control.label, value != 0) { set(control, if (it) 1 else 0, refresh = true) }
                    CameraControl.Kind.MENU -> {
                        Text(control.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                        ChipChoice(menuOptions.getValue(control), value) { set(control, it, refresh = true) }
                    }
                    CameraControl.Kind.RANGE -> Column(Modifier.padding(top = 8.dp)) {
                        Row {
                            Text(control.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text("$value", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val count = (r.max - r.min) / r.step
                        Slider(
                            value.toFloat().coerceIn(r.min.toFloat(), r.max.toFloat()),
                            onValueChange = { v -> set(control, (r.min + ((v - r.min) / r.step).roundToInt() * r.step).coerceIn(r.min, r.max)) },
                            valueRange = r.min.toFloat()..r.max.toFloat(),
                            steps = if (count in 2..24) count - 1 else 0,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- view

val aspectChoices = listOf(
    0 to "Original", 1 to "16:9", 2 to "4:3", 3 to "1:1", 6 to "21:9", 7 to "9:16", 8 to "3:4", 9 to "9:21", 4 to "Fill screen", 5 to "Stretch",
)

@Composable
fun ViewSheet(activity: MainActivity, c: CameraController, onResetZoom: () -> Unit, onDismiss: () -> Unit) {
    val settings = c.settings
    var rotation by remember { mutableIntStateOf(c.pipeline.rotation) }
    var flipH by remember { mutableStateOf(c.pipeline.flipHorizontal) }
    var flipV by remember { mutableStateOf(c.pipeline.flipVertical) }
    var aspect by settings.state(Keys.aspectRatio)
    var dual by settings.state(Keys.cardboardView)
    var deinterlace by settings.state(Keys.deinterlace)
    var overlay by settings.state(Keys.overlayOnPreview)
    val recording by c.recorder.state.collectAsState()

    SheetFrame("View", onDismiss, overPicture = true) {
        Label("Rotate")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(0, 90, 180, 270).forEachIndexed { i, degrees ->
                SegmentedButton(rotation == degrees, { rotation = degrees; c.setRotation(degrees) }, SegmentedButtonDefaults.itemShape(i, 4)) { Text("$degrees°") }
            }
        }
        if (recording != null) Text(
            "Rotating changes the picture size and stops the recording in progress.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp),
        )
        Label("Mirror")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(flipH, { flipH = !flipH; c.setFlip(flipH, flipV) }, label = { Text("Left ↔ right") })
            FilterChip(flipV, { flipV = !flipV; c.setFlip(flipH, flipV) }, label = { Text("Top ↔ bottom") })
        }
        Label("Shape on screen")
        ChipChoice(aspectChoices, aspect) { aspect = it }
        Spacer(Modifier.height(8.dp))
        SwitchLine("Side-by-side view", dual, summary = "Two copies of the picture for a phone VR headset") { dual = it }
        SwitchLine("Deinterlace", deinterlace == 1, summary = "Smooths the combing of interlaced analogue sources") { deinterlace = if (it) 1 else 0 }
        SwitchLine("Text and watermark on the preview", overlay, summary = "They are always part of recordings and streams") { overlay = it }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onResetZoom) { Text("Reset zoom") }
            OutlinedButton(onClick = { activity.setFullScreen(true); onDismiss() }) { Text("Full screen") }
            OutlinedButton(onClick = { activity.setFullScreen(false) }) { Text("Show bars") }
        }
    }
}

// ---------------------------------------------------------------------- insets

@Composable
fun InsetsSheet(activity: MainActivity, c: CameraController, onDismiss: () -> Unit) {
    val settings = c.settings
    var camera by settings.state(Keys.insetCamera)
    var position by settings.state(Keys.insetPosition)
    var rotation by settings.state(Keys.insetRotation)
    var quality by settings.state(Keys.insetQuality)
    var size by settings.state(Keys.insetSizePercent)
    var padding by settings.state(Keys.insetPaddingPercent)
    var keep by settings.state(Keys.insetKeepInBackground)
    var screenPosition by settings.state(Keys.screenInsetPosition)
    var screenSize by settings.state(Keys.screenInsetSizePercent)
    val screenOn by c.screenInsetActive.collectAsState()
    val cameras = remember { c.phoneCamera.cameras() }

    SheetFrame("Insets", onDismiss, overPicture = true) {
        Text(
            "Add a small second picture on top of the video. It becomes part of recordings and streams.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Label("Phone camera")
        ChipChoice(listOf("" to "Off") + cameras, camera) { id ->
            if (id.isEmpty()) camera = "" else activity.withPermissions(Manifest.permission.CAMERA) { granted ->
                if (granted) camera = id else c.messages.tryEmit("Camera permission is needed for the inset")
            }
        }
        if (camera.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            CornerChoice(position) { position = it }
            IntSlider("Size", size, 10..100) { size = it }
            IntSlider("Distance from the edge", padding, 0..100, suffix = "") { padding = it }
            Text("Turn", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            ChipChoice(listOf(0 to "0°", 1 to "90°", 2 to "180°", 3 to "270°"), rotation) { rotation = it }
            Text("Quality", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            ChipChoice(listOf(0 to "240p", 1 to "480p", 2 to "720p", 3 to "1080p"), quality) { quality = it }
            SwitchLine("Keep in the background", keep, summary = "Leave the phone camera on while the app is not on screen") { keep = it; c.applyInsets() }
        }
        Label("Phone screen")
        SwitchLine("Show this phone's screen", screenOn, summary = "Android asks for permission every time this is switched on") {
            if (c.setScreenInset(it)) activity.requestProjection()
        }
        if (screenOn) {
            CornerChoice(screenPosition) { screenPosition = it }
            IntSlider("Size", screenSize, 10..100) { screenSize = it }
        }
    }
}

// ---------------------------------------------------------------------- web server

@Composable
private fun AddressRow(label: String, value: String, mono: Boolean = true) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
        }
        IconButton(onClick = { copy(context, value) }) { Icon(Icons.Default.ContentCopy, "Copy $label", Modifier.size(20.dp)) }
    }
}

@Composable
fun ServerSheet(activity: MainActivity, c: CameraController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val running by c.web.running.collectAsState()
    val rtspOn by c.rtsp.running.collectAsState()
    val clients by c.web.clients.collectAsState()
    val rtspClients by c.rtsp.clientCount.collectAsState()
    val external by c.upnpAddress.collectAsState()
    val user by c.settings.state(Keys.serverUser)
    val password by c.settings.state(Keys.serverPassword)
    var reveal by remember { mutableStateOf(false) }

    SheetFrame("Web server", onDismiss, actions = {
        IconButton(onClick = { onDismiss(); activity.open("server") }) { Icon(Icons.Default.Settings, "Server settings") }
    }) {
        SwitchLine("Share on the network", running, summary = "Watch and control the camera from a browser or media player") { c.setServer(it) }
        SwitchLine("RTSP stream", rtspOn, enabled = running, summary = "For VLC, NVRs and other players") {
            c.settings[Keys.rtspEnabled] = it
            c.setRtsp(it)
        }
        val url = if (running) c.webUrl() else null
        if (url != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                QrCode(url, Modifier.size(132.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text("Open on another device", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(url, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { copy(context, url) }) { Text("Copy") }
                        OutlinedButton(onClick = { share(context, url) }) { Text("Share") }
                    }
                }
            }
            Label("Sign in")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("User name", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(user, fontFamily = FontFamily.Monospace)
                }
                Column(Modifier.weight(1f)) {
                    Text("Password", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (password.isEmpty()) "none" else if (reveal) password else "•".repeat(password.length), fontFamily = FontFamily.Monospace)
                }
                IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show password") }
            }
            Label("Addresses")
            AddressRow("Video for browsers (Motion-JPEG)", "$url/video")
            AddressRow("Video with sound (HTTP-FLV)", "$url/live.flv")
            AddressRow("Sound only (Ogg Opus)", "$url/audio.opus")
            AddressRow("Still picture", "$url/snapshot.jpg")
            c.rtspUrl()?.takeIf { rtspOn }?.let { AddressRow("RTSP", it) }
            external?.let { AddressRow("From the internet (router port mapping)", "${url.substringBefore("://")}://$it:${c.web.port}") }

            Label(if (clients.isEmpty() && rtspClients == 0) "Nobody is watching" else "Watching now")
            for (client in clients) Text("${client.kind} · ${client.address}", style = MaterialTheme.typography.bodyMedium)
            if (rtspClients > 0) Text("RTSP · $rtspClients ${if (rtspClients == 1) "player" else "players"}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------------------------------------------------------------- live push

@Composable
fun PushSheet(activity: MainActivity, c: CameraController, onDismiss: () -> Unit) {
    val publishers by c.publishers.collectAsState()
    val targetsJson by c.settings.state(Keys.pushTargets)
    val selectedJson by c.settings.state(Keys.selectedPushTargets)
    val targets = remember(targetsJson) { c.pushTargets() }
    val selected = remember(selectedJson) { c.selectedPushIds() }
    val live = publishers.isNotEmpty()

    SheetFrame("Live push", onDismiss, actions = { TextButton(onClick = { onDismiss(); activity.open("push") }) { Text("Manage") } }) {
        if (targets.isEmpty()) {
            Text(
                "Send the video to a streaming service or your own server over RTMP, RTMPS or SRT. Add a destination to begin.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp),
            )
            Button(onClick = { onDismiss(); activity.open("push") }) { Text("Add destination") }
            return@SheetFrame
        }
        for (t in targets) {
            val publisher = publishers.firstOrNull { it.target.id == t.id }
            val status = if (publisher != null) publisher.status.collectAsState().value else null
            ListItem(
                headlineContent = { Text(t.title.ifBlank { t.url }) },
                supportingContent = { Text(status ?: t.url, maxLines = 2) },
                leadingContent = {
                    Checkbox(t.id in selected, { on -> c.setSelectedPushIds(if (on) selected + t.id else selected - t.id) }, enabled = !live)
                },
                trailingContent = {
                    if (status?.startsWith("Live") == true) Badge(containerColor = MaterialTheme.colorScheme.error) { Text("LIVE", Modifier.padding(horizontal = 4.dp)) }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { if (live) c.stopPush() else c.startPush() }, enabled = live || selected.any { id -> targets.any { it.id == id } },
            colors = if (live) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(if (live) Icons.Default.Stop else Icons.Default.Podcasts, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (live) "Stop streaming" else "Go live")
        }
    }
}

// ---------------------------------------------------------------------- device details

@Composable
fun InfoSheet(c: CameraController, onDismiss: () -> Unit) {
    val session by c.session.collectAsState()
    val selection by c.selection.collectAsState()
    val size by c.pipeline.outputSize.collectAsState()
    val s = session
    LaunchedEffect(s) { if (s == null) onDismiss() }
    if (s == null) return
    val d = s.description

    @Composable
    fun line(label: String, value: String) = Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.58f), style = MaterialTheme.typography.bodyMedium)
    }

    SheetFrame("Device details", onDismiss) {
        line("Name", s.displayName)
        s.device.manufacturerName?.takeIf { it.isNotBlank() }?.let { line("Maker", it) }
        line("USB ID", "%04X:%04X".format(d.vendorId, d.productId))
        line("USB version", "%d.%d".format(d.bcdUsb shr 8, (d.bcdUsb shr 4) and 0xF))
        line("Link speed", d.speedLabel)
        if (d.uvcVersion > 0) line("Video class", "UVC %d.%d".format(d.uvcVersion shr 8, (d.uvcVersion shr 4) and 0xF))
        selection?.let {
            line("Now streaming", it.label)
            line("Transfer", if (it.stream.bulk) "Bulk" else "Isochronous")
            c.decoderName()?.let { name -> line("Decoder", name) }
        }
        if (size.first > 0) line("Output picture", "${size.first} × ${size.second}")
        line("Sound", when {
            c.audioDeviceName != null -> "From ${c.audioDeviceName}"
            d.audio.isNotEmpty() -> d.audio.joinToString { "${it.channels} ch, ${it.bits} bit, ${it.rates.joinToString("/") { r -> "${r / 1000} kHz" }}" }
            else -> "No microphone"
        })
        d.streams.forEachIndexed { i, stream ->
            Label(if (d.streams.size > 1) "Formats of camera ${i + 1}" else "Formats")
            for (f in stream.formats) {
                Text(
                    f.label + if (f.kind == PixelKind.UNSUPPORTED) " (not supported)" else "",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    f.frames.joinToString("   ") { "${it.width}×${it.height}" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val controls = CameraControl.entries.filter(s::supports)
        if (controls.isNotEmpty()) {
            Label("Controls")
            Text(controls.joinToString { it.label }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
