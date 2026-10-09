package dev.hardline.ui

import android.Manifest
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.hardline.R
import dev.hardline.core.Keys
import dev.hardline.core.state
import dev.hardline.gl.PreviewParams
import dev.hardline.media.RecordingState
import dev.hardline.service.CameraController
import dev.hardline.ui.theme.Signal
import kotlinx.coroutines.delay

enum class Sheet { DEVICES, FORMAT, ADJUST, VIEW, INSETS, SERVER, PUSH, INFO }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(activity: MainActivity, c: CameraController) {
    val settings = c.settings
    val session by c.session.collectAsState()
    val selection by c.selection.collectAsState()
    val waiting by c.waitingForPermission.collectAsState()
    val opening by c.opening.collectAsState()
    val error by c.error.collectAsState()
    val recording by c.recorder.state.collectAsState()
    val motion by c.motionEnabled.collectAsState()
    val serverOn by c.web.running.collectAsState()
    val publishers by c.publishers.collectAsState()
    val audioNotice by c.audioNotice.collectAsState()
    val aspect by settings.state(Keys.aspectRatio)
    val cardboard by settings.state(Keys.cardboardView)
    val overlayOnPreview by settings.state(Keys.overlayOnPreview)
    val appTitle by settings.state(Keys.appTitle)

    var chrome by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var menu by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize(1, 1)) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { c.messages.collect { snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Short) } }
    LaunchedEffect(session) {
        if (session == null) { zoom = 1f; pan = Offset.Zero }
        // A device with several cameras inside: let the user say which one to show.
        val cameras = session?.description?.streams.orEmpty().count { it.formats.isNotEmpty() }
        if (cameras > 1 && settings[Keys.subDeviceDialog]) sheet = Sheet.FORMAT
    }

    val params = PreviewParams(aspect, zoom, pan.x, pan.y, cardboard, overlayOnPreview)

    if (activity.inPictureInPicture) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { PreviewSurface(c.pipeline, PreviewParams(showOsd = overlayOnPreview), Modifier.fillMaxSize()) }
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { viewSize = it }) {
        PreviewSurface(c.pipeline, params, Modifier.fillMaxSize())
        // Gestures are taken by a transparent layer so the surface below never sees touches.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(1f, 8f)
                        val limit = zoom - 1f
                        pan = Offset(
                            (pan.x + panChange.x * 2f / viewSize.width).coerceIn(-limit, limit),
                            (pan.y - panChange.y * 2f / viewSize.height).coerceIn(-limit, limit),
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { chrome = !chrome }, onDoubleTap = { zoom = 1f; pan = Offset.Zero })
                },
        )

        if (session == null) EmptyState(
            waitingFor = waiting?.productName, opening = opening,
            onDevices = { sheet = Sheet.DEVICES }, modifier = Modifier.align(Alignment.Center),
        )

        AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Column {
                TopAppBar(
                    title = {
                        val title = appTitle.ifBlank { session?.displayName.orEmpty() }
                        if (title.isEmpty()) Wordmark() else Text(title, maxLines = 1)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)),
                    actions = {
                        IconButton(onClick = { sheet = Sheet.DEVICES }) { Icon(Icons.Default.Usb, "Devices") }
                        if (session?.hasVideo == true) IconButton(onClick = { sheet = Sheet.ADJUST }) { Icon(Icons.Default.Tune, "Adjust picture") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            MainMenu(menu, session != null, onDismiss = { menu = false }, onSheet = { sheet = it }, activity = activity, c = c)
                        }
                    },
                )
                StatusRow(c, recording, motion, serverOn, publishers.isNotEmpty(), selection?.label)
            }
        }

        AnimatedVisibility(chrome && session != null, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            ControlBar(
                recording = recording, motion = motion, serverOn = serverOn, live = publishers.isNotEmpty(),
                onSnapshot = { c.takeSnapshot() },
                onRecord = { c.setRecording(recording == null) },
                onPause = { c.recorder.setPaused(recording?.paused != true) },
                onMotion = { c.setMotionDetection(!motion) },
                onServer = { sheet = Sheet.SERVER },
                onPush = { sheet = Sheet.PUSH },
                modifier = Modifier.navigationBarsPadding().padding(bottom = 20.dp),
            )
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 108.dp))
    }

    when (sheet) {
        Sheet.DEVICES -> DevicesSheet(c) { sheet = null }
        Sheet.FORMAT -> FormatSheet(c) { sheet = null }
        Sheet.ADJUST -> AdjustSheet(c) { sheet = null }
        Sheet.VIEW -> ViewSheet(activity, c, onResetZoom = { zoom = 1f; pan = Offset.Zero }) { sheet = null }
        Sheet.INSETS -> InsetsSheet(activity, c) { sheet = null }
        Sheet.SERVER -> ServerSheet(activity, c) { sheet = null }
        Sheet.PUSH -> PushSheet(activity, c) { sheet = null }
        Sheet.INFO -> InfoSheet(c) { sheet = null }
        null -> Unit
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { c.error.value = null },
            icon = { Icon(Icons.Default.ErrorOutline, null) },
            title = { Text("Something went wrong") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { c.error.value = null }) { Text("OK") } },
        )
    }

    if (audioNotice) {
        var hide by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { c.audioNotice.value = false },
            icon = { Icon(Icons.Default.MicOff, null) },
            title = { Text("No sound from this camera") },
            text = {
                Column {
                    Text("This camera has no microphone. You can plug in a USB audio adapter, or use the phone's microphone instead.")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Checkbox(hide, { hide = it })
                        Text("Don't show this again")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    c.audioNotice.value = false
                    if (hide) settings[Keys.audioNoticeHidden] = true
                    activity.withPermissions(Manifest.permission.RECORD_AUDIO) { if (it) settings[Keys.useMicrophone] = true }
                }) { Text("Use phone microphone") }
            },
            dismissButton = {
                TextButton(onClick = { c.audioNotice.value = false; if (hide) settings[Keys.audioNoticeHidden] = true }) { Text("Not now") }
            },
        )
    }
}

@Composable
private fun EmptyState(waitingFor: String?, opening: Boolean, onDevices: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Usb, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                opening -> "Opening camera…"
                waitingFor != null -> "Waiting for permission to use $waitingFor"
                else -> "Connect a USB camera"
            },
            color = Color.White, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Webcams, endoscopes, HDMI capture adapters and USB audio inputs are supported. Use a short cable, or a powered hub for long runs.",
            color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        if (opening) CircularProgressIndicator() else FilledTonalButton(onClick = onDevices) {
            Icon(Icons.Default.Usb, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Choose device")
        }
    }
}

/** The app's name as its two-tone wordmark: HARD in the bar's text colour, LINE in the brand's orange. */
@Composable
private fun Wordmark() = Box {
    Icon(painterResource(R.drawable.wordmark_hard), "HardLine")
    Icon(painterResource(R.drawable.wordmark_line), null, tint = Signal)
}

@Composable
private fun MainMenu(expanded: Boolean, connected: Boolean, onDismiss: () -> Unit, onSheet: (Sheet) -> Unit, activity: MainActivity, c: CameraController) {
    @Composable
    fun item(label: String, icon: ImageVector, enabled: Boolean = true, action: () -> Unit) = DropdownMenuItem(
        text = { Text(label) }, leadingIcon = { Icon(icon, null) }, enabled = enabled, onClick = { onDismiss(); action() },
    )
    DropdownMenu(expanded, onDismiss) {
        item("Video format", Icons.Default.HighQuality, connected) { onSheet(Sheet.FORMAT) }
        item("View", Icons.Default.ScreenRotation, connected) { onSheet(Sheet.VIEW) }
        item("Insets", Icons.Default.PictureInPictureAlt, connected) { onSheet(Sheet.INSETS) }
        item("Text and watermark", Icons.Default.TextFields) { activity.open("overlay") }
        item("Device details", Icons.Default.Info, connected) { onSheet(Sheet.INFO) }
        HorizontalDivider()
        item("Picture in picture", Icons.Default.PictureInPicture, connected) {
            if (c.settings[Keys.useSystemPip]) activity.enterPip() else FloatingWindow.show(activity, c)
        }
        item("Run in background", Icons.Default.VisibilityOff) { activity.moveTaskToBack(true) }
        item("Safely eject", Icons.Default.Eject, connected) { c.close() }
        HorizontalDivider()
        item("Settings", Icons.Default.Settings) { activity.open("main") }
        item("Exit", Icons.AutoMirrored.Filled.ExitToApp) { c.shutdown(); activity.finishAndRemoveTask() }
    }
}

@Composable
private fun StatusRow(c: CameraController, recording: RecordingState?, motion: Boolean, serverOn: Boolean, live: Boolean, format: String?) {
    val showFps by c.settings.state(Keys.showFps)
    val showAudio by c.settings.state(Keys.audioIndicator)
    val fps by c.pipeline.fps.collectAsState()
    val level by c.audio.level.collectAsState()
    val hasAudio by c.audio.active.collectAsState()
    val clients by c.web.clients.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(recording != null) { while (recording != null) { now = SystemClock.elapsedRealtime(); delay(500) } }

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (recording != null) {
            val seconds = ((now - recording.startedAt - recording.pausedMs) / 1000).coerceAtLeast(0)
            Pill(
                "%s %02d:%02d:%02d · %s".format(
                    if (recording.paused) "PAUSED" else if (recording.motion) "MOTION REC" else "REC",
                    seconds / 3600, seconds / 60 % 60, seconds % 60, formatBytes(recording.bytes),
                ),
                MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        if (live) Pill("LIVE", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        if (serverOn) Pill("Server · ${clients.size} watching", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        if (motion && recording?.motion != true) Pill("Watching for motion", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        if (showFps && format != null) {
            val decoder = c.decoderName()?.let { " · $it" } ?: ""
            Pill("$format · %.1f fps$decoder".format(fps), Color.Black.copy(alpha = 0.55f), Color.White)
        }
        if (showAudio && hasAudio) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.GraphicEq, "Audio level", tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                LinearProgressIndicator(
                    progress = { level.coerceIn(0f, 1f) }, modifier = Modifier.width(72.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (level > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.25f), drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
private fun Pill(text: String, container: Color, content: Color) {
    Text(
        text, color = content, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(container).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun ControlBar(
    recording: RecordingState?, motion: Boolean, serverOn: Boolean, live: Boolean,
    onSnapshot: () -> Unit, onRecord: () -> Unit, onPause: () -> Unit, onMotion: () -> Unit, onServer: () -> Unit, onPush: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier, shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 3.dp, shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledIconButton(
                onClick = onRecord,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) {
                Icon(if (recording != null) Icons.Default.Stop else Icons.Default.FiberManualRecord, if (recording != null) "Stop recording" else "Record")
            }
            if (recording != null) IconButton(onClick = onPause) {
                Icon(if (recording.paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (recording.paused) "Resume" else "Pause")
            }
            FilledTonalIconToggleButton(checked = motion, onCheckedChange = { onMotion() }) { Icon(Icons.AutoMirrored.Filled.DirectionsRun, "Motion detection") }
            Spacer(Modifier.width(4.dp))
            FilledIconButton(onClick = onSnapshot, modifier = Modifier.size(60.dp), shape = CircleShape) {
                Icon(Icons.Default.PhotoCamera, "Take picture", Modifier.size(30.dp))
            }
            Spacer(Modifier.width(4.dp))
            FilledTonalIconToggleButton(checked = serverOn, onCheckedChange = { onServer() }) { Icon(Icons.Default.Wifi, "Web server") }
            FilledTonalIconToggleButton(checked = live, onCheckedChange = { onPush() }) { Icon(Icons.Default.Podcasts, "Live push") }
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1e9)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1e6)
    else -> "%d kB".format(bytes / 1000)
}
