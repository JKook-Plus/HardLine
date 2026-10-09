package dev.hardline.service

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import dev.hardline.core.Keys
import dev.hardline.core.LocationTracker
import dev.hardline.core.Settings
import dev.hardline.gl.Pipeline
import dev.hardline.media.AudioBroadcast
import dev.hardline.media.AudioEngine
import dev.hardline.media.EncoderSpec
import dev.hardline.media.MotionDetector
import dev.hardline.media.Recorder
import dev.hardline.media.Snapshotter
import dev.hardline.media.Storage
import dev.hardline.media.VideoBroadcast
import dev.hardline.media.VideoCodec
import dev.hardline.media.VideoEncoder
import dev.hardline.media.VideoIngest
import dev.hardline.net.FtpUploader
import dev.hardline.net.Mailer
import dev.hardline.net.Publisher
import dev.hardline.net.PushTarget
import dev.hardline.net.RtmpPublisher
import dev.hardline.net.RtspServer
import dev.hardline.net.SrtPublisher
import dev.hardline.net.Upnp
import dev.hardline.net.WebServer
import dev.hardline.overlay.OverlayManager
import dev.hardline.overlay.PhoneCameraInset
import dev.hardline.overlay.ScreenInset
import dev.hardline.ui.LockScreenActivity
import dev.hardline.usb.CameraControl
import dev.hardline.usb.accepts
import dev.hardline.usb.FrameSize
import dev.hardline.usb.PixelKind
import dev.hardline.usb.StreamInterface
import dev.hardline.usb.UsbSession
import dev.hardline.usb.VideoFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList

/** The format a camera is currently streaming in. */
data class Selection(val stream: StreamInterface, val format: VideoFormat, val frame: FrameSize, val interval: Int) {
    val fps: Float get() = 1e7f / interval
    val label: String get() = "${format.label} ${frame.width}x${frame.height} ${"%.0f".format(fps)} fps"
}

/**
 * Owns the camera, the frame pipeline and everything that consumes it. It lives for the whole
 * process; activities observe its state flows and the foreground service keeps it alive.
 */
class CameraController(val app: Application) {
    val settings = Settings(app)
    val pipeline = Pipeline()
    val usb: UsbManager = app.getSystemService(UsbManager::class.java)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()

    // --- camera state
    val devices = MutableStateFlow<List<UsbDevice>>(emptyList())
    val session = MutableStateFlow<UsbSession?>(null)
    val selection = MutableStateFlow<Selection?>(null)
    val waitingForPermission = MutableStateFlow<UsbDevice?>(null)
    val opening = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val audioNotice = MutableStateFlow(false)

    /** Emitted when the whole app should go away: Exit, the notification's Stop, or "close on unplug". */
    val exitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // --- media
    val audio = AudioEngine()
    val storage = Storage(app, settings)
    val location = LocationTracker(app)
    val overlays = OverlayManager(app, settings, pipeline, { session.value?.displayName }, location)
    val phoneCamera = PhoneCameraInset(app, settings, pipeline)
    val screenInset = ScreenInset(app, settings, pipeline)
    val recordBroadcast = VideoBroadcast(pipeline) { recordSpec() }
    val streams: Map<VideoCodec, VideoBroadcast> = VideoCodec.entries.associateWith { codec -> VideoBroadcast(pipeline) { streamSpec(codec) } }
    val aac = AudioBroadcast(audio, MediaFormat.MIMETYPE_AUDIO_AAC, 128_000)
    val opus = AudioBroadcast(audio, MediaFormat.MIMETYPE_AUDIO_OPUS, 64_000)
    val recorder = Recorder(
        storage, settings, recordBroadcast, aac, { audio.hasInput }, { session.value?.displayName },
        onFileFinished = ::onRecordingFinished, onError = { error.value = it },
    )
    val snapshots = Snapshotter(pipeline, storage, settings, { session.value?.displayName }, { location.last }, app.cacheDir)
    private val motion = MotionDetector(pipeline, ::onMotion)
    val motionEnabled = MutableStateFlow(false)

    // --- network
    val web = WebServer(this)
    val rtsp = RtspServer(this)
    private val upnp = Upnp()
    val upnpAddress = MutableStateFlow<String?>(null)
    val publishers = MutableStateFlow<List<Publisher>>(emptyList())
    private var nsdListener: NsdManager.RegistrationListener? = null

    // --- misc state
    val activityVisible = MutableStateFlow(false)
    private var projection: MediaProjection? = null
    private var pendingProjection: Pair<Int, Intent>? = null
    val projectionActive = MutableStateFlow(false)
    val screenInsetActive = MutableStateFlow(false)
    private var audioSession: UsbSession? = null
    private var ingest: VideoIngest? = null
    private val compressedListeners = CopyOnWriteArrayList<(PixelKind, ByteArray, Int, Long) -> Unit>()
    private var lastMotionAt = 0L
    private var motionWatch: Job? = null
    private var talkPlayer: MediaPlayer? = null
    private var settingsListener: Any? = null
    private val ejected = HashSet<String>()
    private var cameraButtonDown = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    refreshDevices()
                    if (device != null && session.value == null && usb.hasPermission(device) && UsbSession.isVideo(device)) open(device)
                    else if (device != null && UsbSession.isAudio(device) && !UsbSession.isVideo(device)) updateAudio()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshDevices()
                    device?.let { ejected -= it.deviceName }
                    if (device != null && device.deviceName == audioSession?.device?.deviceName) closeAudioDevice()
                    if (device != null && device.deviceName == session.value?.device?.deviceName) {
                        close()
                        messages.tryEmit("Camera disconnected")
                        if (settings[Keys.exitOnDisconnect]) shutdown()
                    }
                    if (device?.deviceName == waitingForPermission.value?.deviceName) waitingForPermission.value = null
                }
                ACTION_PERMISSION -> {
                    val wanted = waitingForPermission.value ?: return
                    waitingForPermission.value = null
                    if (usb.hasPermission(wanted)) open(wanted) else messages.tryEmit("USB access was not granted")
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refreshDevices()
        applyViewState(JSONObject())
        overlays.onLayout = { w, h -> phoneCamera.layout(w, h); screenInset.layout(w, h) }
        scope.launch { pipeline.outputSize.collect { refreshEncoders() } }
        scope.launch { recorder.state.collect { updateNotification() } }
        settingsListener = settings.onChange { key -> scope.launch { onSettingChanged(key) } }
    }

    private fun onSettingChanged(key: String?) {
        when (key) {
            Keys.deinterlace.key -> { pipeline.deinterlace = settings[Keys.deinterlace] == 1; pipeline.refresh() }
            Keys.insetCamera.key, Keys.insetQuality.key -> applyInsets()
            Keys.usbAudioInput.key, Keys.useMicrophone.key, Keys.mixMicWithUsb.key, Keys.mixSystemAudio.key, Keys.preferExternalUsbAudio.key,
            Keys.audioPlayback.key, Keys.backgroundAudioPlayback.key, Keys.volumeLimit.key, Keys.usbGain.key, Keys.micGain.key, Keys.systemGain.key -> updateAudio()
            Keys.recordCodec.key, Keys.customRecordBitrate.key, Keys.recordBitrateMbps.key, Keys.hardwareEncoder.key, Keys.h264Profile.key,
            Keys.customH264Bitrate.key, Keys.h264BitrateMbps.key, Keys.h264KeyframeSeconds.key, Keys.customHevcBitrate.key, Keys.hevcBitrateMbps.key,
            Keys.hevcKeyframeSeconds.key, Keys.customAv1Bitrate.key, Keys.av1BitrateMbps.key, Keys.av1KeyframeSeconds.key,
            Keys.h264Bypass.key, Keys.hevcBypass.key -> refreshEncoders()
        }
    }

    // ------------------------------------------------------------------ devices

    fun refreshDevices() {
        devices.value = usb.deviceList.values.sortedBy { it.deviceName }
    }

    fun isSupported(d: UsbDevice) = UsbSession.isVideo(d) || UsbSession.isAudio(d)
    fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    /** Opens [device], asking the user for USB access first when needed. */
    fun open(device: UsbDevice) {
        if (!usb.hasPermission(device)) {
            waitingForPermission.value = device
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            runCatching {
                usb.requestPermission(device, PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), flags))
            }.onFailure {
                waitingForPermission.value = null
                error.value = "Could not ask for USB access: ${it.message}"
            }
            return
        }
        if (!UsbSession.isVideo(device)) return openAudioDevice(device)
        ejected -= device.deviceName
        scope.launch {
            lock.withLock {
                if (session.value?.device?.deviceName == device.deviceName) return@withLock
                closeLocked()
                opening.value = true
                val result = withContext(Dispatchers.IO) { UsbSession.open(usb, device) }
                opening.value = false
                val s = result.getOrElse {
                    error.value = "Could not open ${device.productName ?: "the device"}: ${it.message}.\nTry re-plugging it."
                    return@withLock
                }
                session.value = s
                CameraService.start(app)
                if (s.hasVideo) {
                    val saved = savedState(s)
                    applyViewState(saved)
                    startStreamLocked(s, restoreSelection(s, saved) ?: defaultSelection(s))
                    restoreControls(s, saved)
                }
                updateAudio()
                if (!s.hasAudio && audioSession == null && !settings[Keys.useMicrophone] && !settings[Keys.audioNoticeHidden]) audioNotice.value = true
                onConnected()
            }
        }
    }

    /** Closes the camera at the user's request; it stays closed until it is plugged in again or picked by hand. */
    fun close() = scope.launch {
        session.value?.let { ejected += it.device.deviceName }
        lock.withLock { closeLocked() }
    }

    /** Opens a camera that is already plugged in and allowed, unless the user ejected it. */
    fun openAttached() {
        if (session.value != null || opening.value) return
        devices.value.firstOrNull { UsbSession.isVideo(it) && usb.hasPermission(it) && it.deviceName !in ejected }?.let(::open)
    }

    private suspend fun closeLocked() {
        val s = session.value ?: return
        if (recorder.isRecording) recorder.stop()
        setMotionDetection(false)
        stopPush()
        setServer(false)
        session.value = null
        selection.value = null
        audioNotice.value = false
        cameraButtonDown = false
        if (audioSession == null) audio.setUsbActive(false)
        withContext(Dispatchers.IO) {
            s.stopAudio()
            s.stopVideo()
            ingest?.close()
            ingest = null
            s.close()
        }
        pipeline.clearSource()
        updateAudio()
        updateNotification()
    }

    /** What to do automatically once a camera is streaming, according to the user's settings. */
    private fun onConnected() {
        if (settings[Keys.wakeScreenOnConnect]) wakeScreen()
        if (settings[Keys.showOnLockScreen] && !activityVisible.value && app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            runCatching { app.startActivity(Intent(app, LockScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        if (settings[Keys.autoServerOnConnect]) setServer(true)
        if (settings[Keys.autoServerOnConnect] && settings[Keys.autoRtspOnConnect]) setRtsp(true)
        if (settings[Keys.autoPushOnConnect]) startPush()
        if (settings[Keys.autoMotionOnConnect]) setMotionDetection(true)
        else if (settings[Keys.autoRecordOnConnect]) scope.launch {
            delay(settings[Keys.recordDelaySeconds] * 1000L)
            if (session.value != null) setRecording(true)
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() = runCatching {
        val pm = app.getSystemService(PowerManager::class.java)
        if (!pm.isInteractive) {
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "hardline:connect").acquire(3000)
        }
    }

    // ------------------------------------------------------------------ formats

    /** Every format, size and default rate the connected camera offers, in a stable order. */
    fun formatChoices(): List<Selection> = session.value?.description?.streams.orEmpty().flatMap { stream ->
        stream.formats.filter { it.kind != PixelKind.UNSUPPORTED }.flatMap { f -> f.frames.map { Selection(stream, f, it, preferredInterval(it)) } }
    }

    private fun defaultSelection(s: UsbSession): Selection? {
        val stream = s.description.streams.firstOrNull { it.formats.isNotEmpty() } ?: return null
        val order = listOf(PixelKind.MJPEG, PixelKind.YUYV, PixelKind.UYVY, PixelKind.NV12, PixelKind.I420, PixelKind.H264, PixelKind.H265, PixelKind.GRAY8)
        val format = order.firstNotNullOfOrNull { k -> stream.formats.firstOrNull { it.kind == k && it.frames.isNotEmpty() } } ?: return null
        // Start modestly: the largest size up to 720p, which every phone can decode and encode.
        val frame = format.frames.filter { it.width <= 1280 && it.height <= 720 }.maxByOrNull { it.width * it.height } ?: format.frames.first()
        return Selection(stream, format, frame, preferredInterval(frame))
    }

    private fun preferredInterval(frame: FrameSize): Int =
        frame.intervals.filter { it >= 333333 }.minOrNull() ?: frame.intervals.maxOrNull() ?: frame.defaultInterval

    private fun restoreSelection(s: UsbSession, saved: JSONObject): Selection? {
        if (!settings[Keys.rememberDeviceSettings] || !saved.has("fourcc")) return null
        val stream = s.description.streams.firstOrNull { it.number == saved.optInt("stream", -1) } ?: s.description.streams.firstOrNull() ?: return null
        val format = stream.formats.firstOrNull { it.fourcc == saved.optString("fourcc") } ?: return null
        val frame = format.frames.firstOrNull { it.width == saved.optInt("width") && it.height == saved.optInt("height") } ?: return null
        val interval = saved.optInt("interval").takeIf { it in frame.intervals } ?: preferredInterval(frame)
        return Selection(stream, format, frame, interval)
    }

    /** Switches the running camera to another format, size or frame rate. */
    fun select(sel: Selection) = scope.launch {
        lock.withLock {
            val s = session.value ?: return@withLock
            if (recorder.isRecording) {
                recorder.stop()
                messages.tryEmit("Recording stopped to change the video format")
            }
            startStreamLocked(s, sel)
            restoreControls(s, savedState(s))
        }
    }

    private suspend fun startStreamLocked(s: UsbSession, sel: Selection?) {
        if (sel == null) {
            error.value = "${s.displayName} does not offer a video format this app can show."
            return
        }
        val failure = withContext(Dispatchers.IO) {
            s.stopVideo()
            ingest?.close()
            val kind = sel.format.kind
            val next = VideoIngest(
                pipeline, kind, sel.frame.width, sel.frame.height, settings[Keys.hardwareDecoder],
                dropBrokenJpeg = { settings[Keys.dropBrokenJpeg] },
                onCompressed = { data, length, pts ->
                    for (l in compressedListeners) l(kind, data, length, pts)
                    if (kind == PixelKind.H264) streams.getValue(VideoCodec.H264).feedCameraStream(data, length, pts)
                    if (kind == PixelKind.H265) streams.getValue(VideoCodec.HEVC).feedCameraStream(data, length, pts)
                },
                onButton = { pressed -> scope.launch { onCameraButton(pressed) } },
            )
            ingest = next
            s.startVideo(sel.stream, sel.format, sel.frame, sel.interval, next)
        }
        if (failure != null) {
            selection.value = null
            pipeline.clearSource()
            error.value = "The camera refused ${sel.label}: $failure.\nTry a lower resolution or frame rate."
            return
        }
        selection.value = sel
        saveState(s) {
            put("stream", sel.stream.number); put("fourcc", sel.format.fourcc)
            put("width", sel.frame.width); put("height", sel.frame.height); put("interval", sel.interval)
        }
        refreshEncoders()
        updateNotification()
    }

    fun decoderName(): String? = ingest?.decoderName
    fun addCompressedListener(l: (PixelKind, ByteArray, Int, Long) -> Unit) { compressedListeners += l }
    fun removeCompressedListener(l: (PixelKind, ByteArray, Int, Long) -> Unit) { compressedListeners -= l }
    fun mjpegPassthrough(): Boolean = selection.value?.format?.kind == PixelKind.MJPEG && settings[Keys.mjpegBypass]

    // ------------------------------------------------------------------ encoders

    private fun frameRate(): Int = (selection.value?.fps ?: 30f).toInt().coerceIn(1, 120)

    private fun recordSpec(): EncoderSpec? {
        val (w, h) = pipeline.outputSize.value.takeIf { it.first > 0 } ?: return null
        var codec = VideoCodec.of(settings[Keys.recordCodec])
        if (!VideoEncoder.isSupported(codec, w, h)) codec = VideoCodec.H264
        val bitrate = if (settings[Keys.customRecordBitrate]) (settings[Keys.recordBitrateMbps] * 1_000_000).toInt()
        else (w * h * 7.5f * (if (codec == VideoCodec.H264) 1f else 0.6f)).toInt()
        return EncoderSpec(codec, w, h, frameRate(), bitrate.coerceAtLeast(200_000), 2, hardware = true)
    }

    private fun streamSpec(codec: VideoCodec): EncoderSpec? {
        val (w, h) = pipeline.outputSize.value.takeIf { it.first > 0 } ?: return null
        val kind = selection.value?.format?.kind
        val passthrough = (codec == VideoCodec.H264 && kind == PixelKind.H264 && settings[Keys.h264Bypass]) ||
            (codec == VideoCodec.HEVC && kind == PixelKind.H265 && settings[Keys.hevcBypass])
        val (custom, mbps, keyframe) = when (codec) {
            VideoCodec.H264 -> Triple(settings[Keys.customH264Bitrate], settings[Keys.h264BitrateMbps], settings[Keys.h264KeyframeSeconds])
            VideoCodec.HEVC -> Triple(settings[Keys.customHevcBitrate], settings[Keys.hevcBitrateMbps], settings[Keys.hevcKeyframeSeconds])
            VideoCodec.AV1 -> Triple(settings[Keys.customAv1Bitrate], settings[Keys.av1BitrateMbps], settings[Keys.av1KeyframeSeconds])
        }
        val bitrate = ((if (custom) mbps else 2f) * 1_000_000).toInt().coerceAtLeast(100_000)
        return EncoderSpec(codec, w, h, frameRate(), bitrate, keyframe, settings[Keys.hardwareEncoder], settings[Keys.h264Profile], passthrough)
    }

    private fun refreshEncoders() {
        recordBroadcast.refresh()
        streams.values.forEach { it.refresh() }
    }

    // ------------------------------------------------------------------ audio

    /** Starts and stops audio inputs so they match the settings, permissions and attached devices. */
    fun updateAudio() {
        val s = session.value
        val external = devices.value.firstOrNull { UsbSession.isAudio(it) && !UsbSession.isVideo(it) && usb.hasPermission(it) }
        if (settings[Keys.usbAudioInput] && settings[Keys.preferExternalUsbAudio] && external != null && audioSession == null) openAudioDevice(external)
        val cameraAudio = settings[Keys.usbAudioInput] && s?.hasAudio == true && audioSession == null
        if (cameraAudio && !audio.usbActive) {
            scope.launch(Dispatchers.IO) {
                if (s!!.startAudio(AudioEngine.RATE, audio.usbListener) != null) audio.setUsbActive(true)
                aac.refresh(); opus.refresh()
            }
        } else if (!settings[Keys.usbAudioInput] && audio.usbActive) {
            s?.stopAudio()
            closeAudioDevice()
            audio.setUsbActive(false)
        }
        val wantMic = settings[Keys.useMicrophone] && hasPermission(Manifest.permission.RECORD_AUDIO) &&
            (settings[Keys.mixMicWithUsb] || !(audio.usbActive || cameraAudio)) && (s != null || audioSession != null)
        if (wantMic && !audio.micActive) {
            if (!audio.startMicrophone()) messages.tryEmit("The microphone is not available")
        } else if (!wantMic && audio.micActive) audio.stopMicrophone()

        val p = projection
        val wantSystem = settings[Keys.mixSystemAudio] && p != null && hasPermission(Manifest.permission.RECORD_AUDIO)
        if (wantSystem && !audio.systemActive) audio.startSystemCapture(p!!) else if (!wantSystem && audio.systemActive) audio.stopSystemCapture()

        audio.usbGain = settings[Keys.usbGain]
        audio.micGain = settings[Keys.micGain]
        audio.systemGain = settings[Keys.systemGain]
        audio.limitMonitorVolume = settings[Keys.volumeLimit]
        audio.monitor = settings[Keys.audioPlayback] && (activityVisible.value || settings[Keys.backgroundAudioPlayback])
        aac.refresh()
        opus.refresh()
    }

    private fun openAudioDevice(device: UsbDevice) {
        if (audioSession != null) return
        scope.launch(Dispatchers.IO) {
            val s = UsbSession.open(usb, device).getOrNull()
            if (s == null || !s.hasAudio) {
                s?.close()
                messages.tryEmit("${device.productName ?: "The USB audio device"} could not be opened")
                return@launch
            }
            session.value?.stopAudio()
            audio.setUsbActive(false)
            if (s.startAudio(AudioEngine.RATE, audio.usbListener) != null) {
                audioSession = s
                audio.setUsbActive(true)
                CameraService.start(app)
                messages.tryEmit("Using ${s.displayName} for audio")
            } else s.close()
            withContext(Dispatchers.Main) { updateAudio() }
        }
    }

    private fun closeAudioDevice() {
        val s = audioSession ?: return
        audioSession = null
        audio.setUsbActive(false)
        scope.launch(Dispatchers.IO) { s.stopAudio(); s.close() }
        updateAudio()
    }

    val audioDeviceName: String? get() = audioSession?.displayName

    // ------------------------------------------------------------------ recording, snapshots, motion

    fun setRecording(on: Boolean) {
        if (on == recorder.isRecording) return
        if (!on) return recorder.stop()
        if (selection.value == null) {
            messages.tryEmit("Connect a camera first")
            return
        }
        val wanted = VideoCodec.of(settings[Keys.recordCodec])
        if (recordSpec()?.codec?.let { it != wanted } == true) {
            messages.tryEmit("This phone cannot encode ${wanted.label} at this picture size. Recording in H.264 instead.")
        }
        recorder.start(motion = false)
    }

    fun takeSnapshot(done: (name: String?) -> Unit = {}) {
        snapshots.take { saved ->
            if (saved == null) messages.tryEmit("No picture to save") else {
                messages.tryEmit("Saved ${saved.name}")
                if (settings[Keys.mailSnapshots]) scope.launch(Dispatchers.IO) {
                    Mailer.send(settings, "Snapshot from ${pageTitle()}", saved.name, saved.jpeg, saved.name)?.let { messages.tryEmit("Mail failed: $it") }
                }
            }
            done(saved?.name)
        }
    }

    /** Takes the configured number of pictures in a row. */
    fun burst() = scope.launch {
        repeat(settings[Keys.burstCount].coerceIn(1, 50)) {
            snapshots.take { }
            delay(300)
        }
        messages.tryEmit("Burst saved")
    }

    fun setMotionDetection(on: Boolean) {
        if (on == motionEnabled.value) return
        if (on && selection.value == null) {
            messages.tryEmit("Connect a camera first")
            return
        }
        motionEnabled.value = on
        if (on) {
            motion.start()
            motionWatch = scope.launch {
                while (true) {
                    delay(1000)
                    val quietFor = System.currentTimeMillis() - lastMotionAt
                    if (recorder.isMotionRecording && quietFor > settings[Keys.motionTimeoutSeconds] * 1000L) recorder.stop()
                }
            }
        } else {
            motion.stop()
            motionWatch?.cancel()
            if (recorder.isMotionRecording) recorder.stop()
        }
        updateNotification()
    }

    private fun onMotion() {
        lastMotionAt = System.currentTimeMillis()
        if (!motionEnabled.value || recorder.isRecording) return
        scope.launch {
            if (recorder.isRecording || !motionEnabled.value) return@launch
            recorder.start(motion = true)
            if (settings[Keys.mailOnMotion]) snapshots.jpeg(85) { jpeg ->
                scope.launch(Dispatchers.IO) {
                    Mailer.send(settings, "Motion detected by ${pageTitle()}", "Movement was detected. A snapshot is attached.", jpeg)
                        ?.let { messages.tryEmit("Mail failed: $it") }
                }
            }
        }
    }

    private fun onRecordingFinished(name: String, uri: Uri, wasMotion: Boolean) {
        messages.tryEmit("Saved $name")
        val upload = if (wasMotion) settings[Keys.ftpUploadMotion] else settings[Keys.ftpUploadManual]
        if (!upload) return
        scope.launch(Dispatchers.IO) {
            val entry = storage.list().firstOrNull { it.name == name } ?: return@launch
            val descriptor = storage.open(entry)
            val failure = if (descriptor == null) "file not found"
            else descriptor.use { pfd -> FileInputStream(pfd.fileDescriptor).use { FtpUploader.upload(settings, name, it) } }
            when {
                failure != null -> messages.tryEmit("FTP upload failed: $failure")
                settings[Keys.ftpDeleteAfterUpload] -> storage.delete(entry)
                else -> messages.tryEmit("Uploaded $name")
            }
        }
    }

    // ------------------------------------------------------------------ servers and live push

    fun pageTitle(): String = settings[Keys.pageTitle].ifBlank { "HardLine on ${Build.MODEL}" }
    fun localAddress(): String = upnp.localAddress() ?: "127.0.0.1"

    fun webUrl(): String? = if (web.running.value) "${if (web.secure) "https" else "http"}://${localAddress()}:${web.port}" else null
    fun rtspUrl(): String? = if (rtsp.running.value) "${if (rtsp.secure) "rtsps" else "rtsp"}://${localAddress()}:${rtsp.port}/live" else null

    fun setServer(on: Boolean) {
        if (on == web.running.value) return
        if (!on) {
            setRtsp(false)
            web.stop()
            unregisterDiscovery()
            scope.launch(Dispatchers.IO) { upnp.unmapAll(); upnpAddress.value = null }
            updateNotification()
            return
        }
        if (selection.value == null) {
            messages.tryEmit("Connect a camera first")
            return
        }
        web.start()?.let { error.value = it; return }
        registerDiscovery()
        if (settings[Keys.rtspEnabled]) setRtsp(true)
        if (settings[Keys.upnp]) scope.launch(Dispatchers.IO) {
            upnp.map(listOf(settings[Keys.httpPort], settings[Keys.rtspPort]), "HardLine")
            upnpAddress.value = upnp.externalAddress
        }
        updateNotification()
    }

    fun setRtsp(on: Boolean) {
        if (on == rtsp.running.value) return
        if (on) rtsp.start()?.let { error.value = it } else rtsp.stop()
    }

    private fun registerDiscovery() = runCatching {
        val info = NsdServiceInfo().apply {
            serviceName = settings[Keys.hostname].ifBlank { "HardLine" }
            serviceType = "_http._tcp."
            port = web.port
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) = Unit
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) = Unit
            override fun onServiceRegistered(i: NsdServiceInfo) = Unit
            override fun onServiceUnregistered(i: NsdServiceInfo) = Unit
        }
        app.getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        nsdListener = l
    }

    private fun unregisterDiscovery() = runCatching {
        nsdListener?.let { app.getSystemService(NsdManager::class.java).unregisterService(it) }
        nsdListener = null
    }

    fun pushTargets(): List<PushTarget> = PushTarget.list(settings[Keys.pushTargets])
    fun savePushTargets(list: List<PushTarget>) { settings[Keys.pushTargets] = PushTarget.json(list) }

    fun selectedPushIds(): Set<String> = runCatching {
        JSONArray(settings[Keys.selectedPushTargets]).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    }.getOrDefault(emptySet())

    fun setSelectedPushIds(ids: Set<String>) { settings[Keys.selectedPushTargets] = JSONArray(ids.toList()).toString() }

    /** Starts pushing to every selected destination. */
    fun startPush() {
        if (publishers.value.isNotEmpty()) return
        if (selection.value == null) {
            messages.tryEmit("Connect a camera first")
            return
        }
        val selected = selectedPushIds()
        val targets = pushTargets().filter { it.id in selected }
        if (targets.isEmpty()) {
            messages.tryEmit("Choose at least one live-push destination")
            return
        }
        publishers.value = targets.map { t ->
            if (t.isSrt) SrtPublisher(this, t, if (settings[Keys.srtCodec] == 1) VideoCodec.HEVC else VideoCodec.H264)
            else RtmpPublisher(this, t, VideoCodec.of(settings[Keys.rtmpCodec]))
        }.onEach { it.start() }
        updateNotification()
    }

    fun stopPush() {
        publishers.value.forEach { it.stop() }
        publishers.value = emptyList()
        updateNotification()
    }

    fun playTalkBack(file: File) = scope.launch {
        runCatching {
            talkPlayer?.release()
            talkPlayer = MediaPlayer.create(app, Uri.fromFile(file))?.apply {
                setOnCompletionListener { it.release(); file.delete(); if (talkPlayer === it) talkPlayer = null }
                start()
            }
        }.onFailure { Log.w(TAG, "talk-back", it) }
    }

    fun batteryPercent(): Int = app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    // ------------------------------------------------------------------ insets and screen capture

    fun applyInsets() {
        if (settings[Keys.insetCamera].isNotEmpty() && !hasPermission(Manifest.permission.CAMERA)) return
        val keep = activityVisible.value || settings[Keys.insetKeepInBackground]
        if (keep) phoneCamera.apply() else phoneCamera.close()
    }

    /** Called with the result of the system's screen-capture consent dialog. */
    fun onProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            settings[Keys.screenInset] = false
            settings[Keys.mixSystemAudio] = false
            return
        }
        pendingProjection = resultCode to data
        CameraService.start(app, withProjection = true)
    }

    /** The service is now in the foreground with the screen-capture type: the projection may start. */
    fun onServicePromoted(withProjection: Boolean) {
        val (code, data) = pendingProjection ?: return
        if (!withProjection) return
        pendingProjection = null
        val p = runCatching { app.getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) }.getOrNull() ?: return
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { scope.launch { if (projection === p) stopProjection() } }
        }, null)
        projection = p
        projectionActive.value = true
        if (settings[Keys.screenInset]) screenInset.start(p)
        screenInsetActive.value = screenInset.active
        updateAudio()
    }

    fun stopProjection() {
        val p = projection
        projection = null
        screenInset.stop()
        screenInsetActive.value = false
        audio.stopSystemCapture()
        runCatching { p?.stop() }
        projectionActive.value = false
    }

    /** Turns the screen inset on or off. Returns true when the user must first consent to screen capture. */
    fun setScreenInset(on: Boolean): Boolean {
        settings[Keys.screenInset] = on
        if (on) {
            // One consent allows one capture, so switching the inset on always starts from a fresh consent.
            stopProjection()
            return true
        }
        screenInset.stop()
        screenInsetActive.value = false
        if (!settings[Keys.mixSystemAudio]) stopProjection()
        return false
    }

    /** Turns mixing of the phone's own sound on or off. Returns true when screen-capture consent is needed first. */
    fun setSystemAudioMix(on: Boolean): Boolean {
        settings[Keys.mixSystemAudio] = on
        if (on && projection == null) return true
        if (!on && !screenInset.active) stopProjection()
        updateAudio()
        return false
    }

    // ------------------------------------------------------------------ buttons

    /** 1 toggles recording, 2 takes a burst of pictures, 3 takes one picture. */
    private fun runAction(action: Int) {
        when (action) {
            1 -> setRecording(!recorder.isRecording)
            2 -> burst()
            3 -> takeSnapshot()
            else -> return
        }
        if (settings[Keys.vibrate]) runCatching {
            @Suppress("DEPRECATION") val v = app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /**
     * The button on the camera. A click counts once: on the press, or on the release when no press
     * came before it, because some cameras report only one of the two.
     */
    private fun onCameraButton(pressed: Boolean) {
        val click = pressed || !cameraButtonDown
        cameraButtonDown = pressed
        if (click) runAction(settings[Keys.cameraButtonAction])
    }

    fun onMediaKey(keyCode: Int): Boolean {
        val action = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> settings[Keys.mediaPlayAction]
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> settings[Keys.mediaPreviousAction]
            KeyEvent.KEYCODE_MEDIA_NEXT -> settings[Keys.mediaNextAction]
            else -> 0
        }
        if (action == 0) return false
        runAction(action)
        return true
    }

    // ------------------------------------------------------------------ per-device state

    private fun savedState(s: UsbSession): JSONObject = settings.deviceState(s.key)

    private fun saveState(s: UsbSession, edit: JSONObject.() -> Unit) {
        if (!settings[Keys.rememberDeviceSettings]) return
        settings.saveDeviceState(s.key, savedState(s).apply(edit))
    }

    private fun applyViewState(saved: JSONObject) {
        pipeline.rotation = saved.optInt("rotation", 0)
        pipeline.flipHorizontal = saved.optBoolean("flipH", false)
        pipeline.flipVertical = saved.optBoolean("flipV", false)
        pipeline.deinterlace = settings[Keys.deinterlace] == 1
    }

    fun setRotation(degrees: Int) {
        if (recorder.isRecording) recorder.stop()
        pipeline.rotation = degrees
        session.value?.let { saveState(it) { put("rotation", degrees) } }
    }

    fun setFlip(horizontal: Boolean, vertical: Boolean) {
        pipeline.flipHorizontal = horizontal
        pipeline.flipVertical = vertical
        session.value?.let { saveState(it) { put("flipH", horizontal); put("flipV", vertical) } }
    }

    fun setControl(control: CameraControl, value: Int) = scope.launch(Dispatchers.IO) {
        val s = session.value ?: return@launch
        if (s.set(control, value)) saveState(s) { put("ctl:${control.name}", value) }
    }

    private suspend fun restoreControls(s: UsbSession, saved: JSONObject) = withContext(Dispatchers.IO) {
        if (!settings[Keys.rememberDeviceSettings]) return@withContext
        // Automatic modes first, so manual values that follow are not overridden.
        for (c in CameraControl.entries.sortedBy { it.kind == CameraControl.Kind.RANGE }) {
            val key = "ctl:${c.name}"
            if (saved.has(key) && s.supports(c)) runCatching { s.set(c, saved.getInt(key)) }.onFailure { Log.w(TAG, "restore $c", it) }
        }
    }

    /** Returns the connected camera's controls to their defaults and forgets saved values. */
    fun resetControls() = scope.launch(Dispatchers.IO) {
        val s = session.value ?: return@launch
        for (c in CameraControl.entries) {
            if (!s.supports(c)) continue
            val r = s.range(c) ?: continue
            if (c.accepts(r.default, r)) s.set(c, r.default)
        }
        settings.saveDeviceState(s.key, savedState(s).apply { keys().asSequence().filter { it.startsWith("ctl:") }.toList().forEach { remove(it) } })
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * The text of the status notification. It deliberately carries no camera name or video format:
     * Android treats code-like text such as "640x480" in a notification as a one-time password and
     * then blanks the whole app in screen recordings and casts.
     */
    fun statusLine(): String {
        val parts = ArrayList<String>()
        if (selection.value != null) parts += "Camera connected"
        if (recorder.isRecording) parts += "recording"
        if (web.running.value) parts += "web server on"
        if (publishers.value.isNotEmpty()) parts += "live"
        if (motionEnabled.value) parts += "watching for motion"
        return parts.joinToString(" · ").ifEmpty { "Waiting for a camera" }
    }

    private fun updateNotification() {
        CameraService.instance?.updateNotification(statusLine())
    }

    fun onActivityVisible(visible: Boolean) {
        activityVisible.value = visible
        applyInsets()
        updateAudio()
    }

    val busy: Boolean
        get() = recorder.isRecording || web.running.value || publishers.value.isNotEmpty() || motionEnabled.value

    /** Stops everything and closes the camera (the notification's Stop action, or Exit). */
    fun shutdown() {
        exitRequests.tryEmit(Unit)
        scope.launch {
            lock.withLock { closeLocked() }
            closeAudioDevice()
            stopProjection()
            phoneCamera.close()
            audio.shutdown()
            CameraService.stop(app)
        }
    }

    fun startAfterBoot() {
        scope.launch {
            delay(settings[Keys.bootDelaySeconds] * 1000L)
            CameraService.start(app)
            refreshDevices()
            devices.value.firstOrNull { UsbSession.isVideo(it) && usb.hasPermission(it) }?.let(::open)
        }
    }

    companion object {
        private const val TAG = "CameraController"
        const val ACTION_PERMISSION = "dev.hardline.USB_PERMISSION"
    }
}
