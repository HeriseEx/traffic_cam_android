package com.example.illegalcapture

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.illegalcapture.ui.theme.IllegalCaptureTheme
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    companion object {
        const val SHUTTER_DOWN = "com.example.illegalcapture.action.SHUTTER_DOWN"
        const val SHUTTER_UP = "com.example.illegalcapture.action.SHUTTER_UP"
    }
    private val shutterActions = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SHUTTER_DOWN -> onShutterDown()
                SHUTTER_UP -> onShutterUp()
            }
        }
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val destroyed = AtomicBoolean(false)
    private val frameBusy = AtomicBoolean(false)
    private var workerDetector: VehicleDetector? = null
    private var detector by mutableStateOf<VehicleDetector?>(null)
    private var status by mutableStateOf("正在加载本地模型…")
    private var result by mutableStateOf<DetectionResult?>(null)
    private var cameraPermission by mutableStateOf(false)
    private var live by mutableStateOf(true)
    private var running by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private var photo by mutableStateOf<Bitmap?>(null)
    private var confidence by mutableFloatStateOf(.4f)
    @Volatile private var inferenceThreshold = .4f
    private var generation = 0
    private var feed: CameraFeed? = null
    private var menu by mutableStateOf(false)
    private var showBoxes by mutableStateOf(true)
    private var voiceEnabled by mutableStateOf(false)
    private var voiceStatus by mutableStateOf("语音未开启")
    private var recording by mutableStateOf(false)
    private var recordedSeconds by mutableIntStateOf(0)
    private var eventClip = false
    private val tracker = TrafficTracker()
    private val incidents = IncidentWatch()
    private var currentIncidents by mutableStateOf<List<LiveIncident>>(emptyList())
    private val captureEvents = linkedMapOf<String, LiveIncident>()
    private val savedEvents = mutableMapOf<String, Long>()
    private var violationMode by mutableStateOf("AUTO")
    private var resolutionId by mutableStateOf("1080")
    private var offeredTiers by mutableStateOf(RecordingTier.recordable())
    private var fingerDown = false
    private var wantSpoken = false
    private var parkingTarget by mutableStateOf<String?>(null)
    private var spotPlates by mutableStateOf<Map<String, Float>>(emptyMap())
    private var frontPlates by mutableStateOf<Map<String, Float>>(emptyMap())
    private var spotFile: File? = null
    private var frontFile: File? = null
    private var cacheSeconds by mutableIntStateOf(0)
    private var lastSignalAt = 0L
    private var lastLocalSignalAt = 0L
    private var clockTick by mutableLongStateOf(0L)
    private var captureFinalizing = false
    private fun incidentKey(it: LiveIncident) = "${it.trackId}:${it.kind}:${it.startAt}"
    private var plateHits by mutableStateOf<List<PlateHit>>(emptyList())
    private var clipSeconds by mutableIntStateOf(15)
    private val plateVoter = PlateVoter()
    private val signalHold = SignalHold()
    private var livePlateAt by mutableLongStateOf(0L)
    private var livePlateEnabled by mutableStateOf(true)
    private var liveSignal by mutableStateOf("UNKNOWN")
    private var liveSignalStable by mutableStateOf(false)
    private val sync by lazy { TaskSync.get(this) }
    private val voice by lazy { VoiceMarker(this, { voiceStatus = it }, { if (!recording) startSpoken() }) }
    private var orientationPreference = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shutterFilter = IntentFilter().apply { addAction(SHUTTER_DOWN); addAction(SHUTTER_UP) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(shutterActions, shutterFilter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(shutterActions, shutterFilter)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val prefs = getPreferences(MODE_PRIVATE)
        confidence = prefs.getFloat("threshold", .4f).coerceIn(.2f,.8f)
        inferenceThreshold = confidence
        voiceEnabled = prefs.getBoolean("voiceEnabled", false)
        livePlateEnabled = prefs.getBoolean("livePlate", true)
        clipSeconds = prefs.getInt("clipSeconds", 15).let { if (it in listOf(10, 15, 30)) it else 15 }
        showBoxes = prefs.getBoolean("showBoxes", true)
        violationMode = ViolationPolicy.mode(prefs.getString("violationMode", "AUTO") ?: "AUTO").id
        resolutionId = RecordingTier.recordable().firstOrNull { it.id == prefs.getString("resolution", "1080") }?.id ?: "1080"
        tracker.occlusionMs = prefs.getLong("occlusionMs", 3000).coerceIn(1500, 5000)
        incidents.motionThreshold = prefs.getFloat("motionThreshold", .045f).coerceIn(.03f, .12f)
        incidents.tailMs = prefs.getLong("tailMs", 2500).coerceIn(1500, 5000)
        orientationPreference = prefs.getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
        requestedOrientation = orientationPreference
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        worker.execute {
            try {
                val loaded = VehicleDetector(applicationContext); workerDetector = loaded
                runOnUiThread { if (!destroyed.get()) { detector = loaded; status = "监看中" } }
            } catch (error: Exception) { runOnUiThread { status = "模型加载失败：${error.message}" } }
        }
        setContent { IllegalCaptureTheme(darkTheme = true, dynamicColor = false) { Screen() } }
        if (intent.getBooleanExtra("simulate_recording", false)) {
            startActivity(Intent(this, BackendActivity::class.java).putExtra("simulate_recording", true))
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { sync.tick(); delay(3000) }
        } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                clockTick = SystemClock.elapsedRealtime()
                cacheSeconds = feed?.cachedSeconds ?: 0
                if (clockTick - lastSignalAt > 3500) { signalHold.clear(); liveSignal = "UNKNOWN"; liveSignalStable = false }
                maybeFinishClip(clockTick)
                delay(250)
            }
        } }
    }

    override fun onResume() {
        super.onResume()
        cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (voiceEnabled && microphoneGranted()) voice.start()
        feed?.resumeLoop()
    }
    override fun onStop() {
        generation++; voice.stop(); feed?.pauseLoop(); resetTracking()
        super.onStop()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!recording) { generation++; resetTracking() }
        feed?.updateRotation()
    }
    private fun microphoneGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun recognizeFrame(jpeg: ByteArray, frameAt: Long = SystemClock.elapsedRealtime()) {
        if (!sync.configured() || !frameBusy.compareAndSet(false, true)) return
        val request = generation
        val wasLive = live
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { sync.client().recognizeFrame(jpeg) }
                if (request != generation || wasLive != live || destroyed.get()) return@launch
                val now = SystemClock.elapsedRealtime()
                if (now - frameAt > 6000) return@launch
                val parsed = parseTrackPlates(response)
                if (livePlateEnabled) {
                    if (live) tracker.acceptPlates(parsed, frameAt, now)
                    else setPlates(parsed.map { PlateHit(it.text, it.confidence, RectF(it.box.left, it.box.top, it.box.right, it.box.bottom)) })
                    livePlateAt = now
                }
                if (now - frameAt <= 3000 && now - lastLocalSignalAt > 1500) {
                    liveSignal = signalHold.update(response.optString("signal_observed", "OFF"))
                    liveSignalStable = signalHold.stable
                    lastSignalAt = frameAt
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == generation) status = "车牌暂未更新 · 本地跟踪和缓存继续"
            } finally { frameBusy.set(false) }
        }
    }

    private fun setPlates(plates: List<PlateHit>) { plateHits = plates }
    private fun resetTracking() {
        tracker.clear(); incidents.clear(); currentIncidents = emptyList(); savedEvents.clear()
        plateVoter.clear(); setPlates(emptyList()); signalHold.clear()
        liveSignal = "UNKNOWN"; liveSignalStable = false; lastSignalAt = 0; lastLocalSignalAt = 0
    }

    private fun onVehicles(detection: DetectionResult, at: Long): DetectionResult {
        val tracks = tracker.update(detection.observations(), at)
        val labeled = detection.withTracks(tracks)
        result = labeled
        val observed = observeLamps(detection.lamps, detection.width.toFloat(), detection.height.toFloat())
        if (observed != "OFF") {
            liveSignal = signalHold.update(observed); liveSignalStable = signalHold.stable
            lastSignalAt = at; lastLocalSignalAt = at
        } else if (at - lastSignalAt > 1500) {
            signalHold.clear(); liveSignal = "UNKNOWN"; liveSignalStable = false
        }
        currentIncidents = incidents.update(tracks, at, liveSignalStable && liveSignal == "RED", detection.road)
        if (recording && !captureFinalizing) currentIncidents.forEach { event ->
            if (captureEvents.size < 16 || incidentKey(event) in captureEvents) captureEvents[incidentKey(event)] = event.copy()
        }
        val mode = ViolationPolicy.mode(violationMode)
        if (!recording && !mode.parking && live && running) {
            val fresh = currentIncidents.filter {
                at - it.endAt < incidents.tailMs + 3_000 && at - (savedEvents[incidentKey(it)] ?: -10000) > 3000 &&
                    ViolationPolicy.autoReady(violationMode, it.kind, it.plate, it.plateConfirmed)
            }
            if (fresh.isNotEmpty()) mark("automatic", fresh)
        }
        maybeFinishClip(at)
        return labeled
    }

    private fun maybeFinishClip(at: Long) {
        if (recording && eventClip && !captureFinalizing && incidents.readyToFinish(at, captureEvents.values.toList())) {
            captureFinalizing = true; feed?.stopRecording()
        }
    }

    private fun mark(trigger: String, events: List<LiveIncident> = emptyList()) {
        if (recording) { status = "重点片段正在录制"; return }
        if (!live || !running || !cameraPermission) { menu = true; status = "请先开启相机再标记"; return }
        if (sync.clips.usableSpace < 150L * 1024 * 1024) { status = "空间不足，请先处理待上传视频"; return }
        val camera = feed ?: return
        val now = SystemClock.elapsedRealtime()
        val file = File(sync.clips, "${UUID.randomUUID()}.mp4")
        captureEvents.clear(); events.take(16).forEach { captureEvents[incidentKey(it)] = it.copy() }
        recording = true; recordedSeconds = 0; captureFinalizing = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        eventClip = trigger == "automatic"
        val note = when (trigger) { "voice" -> "开始标记"; "automatic" -> "行车轨迹疑似事件，待服务器复核"; else -> "手动标记" }
        val from = if (events.isEmpty()) now - 10_000 else events.minOf { it.startAt } - ViolationPolicy.LEAD_MS
        val started = camera.record(file, if (eventClip) 65 else clipSeconds, { recordedSeconds = it }, { completed, error ->
            val timing = camera.lastClipTiming
            val evidence = JSONArray()
            captureEvents.values.forEach { event ->
                savedEvents[incidentKey(event)] = SystemClock.elapsedRealtime()
                if (timing != null) evidence.put(JSONObject().put("track_id", event.trackId).put("kind", event.kind)
                    .put("start_ms", (event.startAt - timing.startMs).coerceIn(0, timing.endMs - timing.startMs))
                    .put("end_ms", (event.endAt - timing.startMs).coerceIn(0, timing.endMs - timing.startMs))
                    .put("plate", event.plate ?: JSONObject.NULL).put("plate_confirmed", event.plateConfirmed))
            }
            val details = timing?.let { JSONObject().put("camera_mode", "moving").put("incidents", evidence)
                .put("captured_at", (System.currentTimeMillis() - (SystemClock.elapsedRealtime() - it.startMs)) / 1000.0)
                .put("duration_ms", it.endMs - it.startMs).put("recording_gaps_ms", it.gapsMs)
                .put("prebuffer_truncated", it.startMs > from + 500) }
            val candidate = captureEvents.values.firstOrNull { it.kind in ViolationPolicy.drivingKinds }?.kind ?: "UNKNOWN"
            val plated = captureEvents.values.any { ViolationPolicy.autoReady(violationMode, it.kind, it.plate, it.plateConfirmed) }
            recording = false; eventClip = false; captureFinalizing = false; requestedOrientation = orientationPreference
            if (completed != null && plated) {
                keepClip(completed, trigger, note, candidate, details, "片段已保存 · 自动上传与复核中")
            } else {
                completed?.delete()
                status = if (completed == null) error ?: "录像失败" else "未识别车牌，已丢弃"
            }
            if (wantSpoken && fingerDown) startSpoken()
        }, startAtMs = from)
        if (!started) { recording = false; eventClip = false; requestedOrientation = orientationPreference; status = "缓存正在准备，请稍后标记" }
        else { status = if (trigger == "automatic") "疑似事件取证中 · 持续跟踪并动态延长" else "正在录制重点片段"; menu = false }
    }

    private fun onShutterDown() {
        if (ViolationPolicy.mode(violationMode).parking) return
        fingerDown = true
        if (recording && eventClip) { wantSpoken = true; captureFinalizing = true; feed?.stopRecording() }
        else if (!recording) startSpoken()
    }

    private fun onShutterUp() {
        fingerDown = false
        if (recording && !eventClip) { captureFinalizing = true; feed?.stopRecording() }
        else wantSpoken = false
    }

    private fun startSpoken() {
        if (recording || !live || !running || !cameraPermission) return
        val camera = feed ?: return
        if (sync.clips.usableSpace < 150L * 1024 * 1024) { status = "空间不足，请先处理待上传视频"; return }
        val file = File(sync.clips, "${UUID.randomUUID()}.mp4")
        recording = true; recordedSeconds = 0; captureFinalizing = false; eventClip = false; wantSpoken = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        val started = camera.record(file, 65, { recordedSeconds = it }, { completed, error ->
            val timing = camera.lastClipTiming
            val duration = timing?.let { it.endMs - it.startMs } ?: 0L
            recording = false; captureFinalizing = false; requestedOrientation = orientationPreference
            if (completed != null && ViolationPolicy.holdKept(duration)) {
                val details = JSONObject().put("camera_mode", "moving").put("incidents", JSONArray())
                    .put("captured_at", System.currentTimeMillis() / 1000.0).put("duration_ms", duration).put("recording_gaps_ms", 0)
                keepClip(completed, "manual", "人工口述", "UNKNOWN", details, "人工片段已保存")
            } else {
                completed?.delete()
                status = if (completed == null) error ?: "录像失败" else "不足 3 秒，已丢弃"
            }
        }, spokenCapture = true)
        if (!started) { recording = false; requestedOrientation = orientationPreference; status = "缓存正在准备，请稍后标记" }
        else { status = "正在录音录像，松开后提交"; menu = false }
    }

    private fun keepClip(file: File, trigger: String, note: String, candidate: String, details: JSONObject?, done: String) {
        val repository = sync
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val saved = repository.enqueue(file, trigger, note, candidate, details, place(), "")
                withContext(Dispatchers.Main) {
                    if (!destroyed.get()) status = if (saved.optString("status") == "NEED_NOTE") "没有定位，请在记录里填写备注后再上传" else done
                }
                repository.tick()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { if (!destroyed.get()) status = "视频已保留，稍后恢复入队：${e.message}" }
            }
        }
    }

    private fun place(): JSONObject? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = getSystemService(LocationManager::class.java)
        val fix = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstNotNullOfOrNull { provider ->
            try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
        } ?: return null
        val address = try {
            if (!Geocoder.isPresent()) null
            else Geocoder(this).getFromLocation(fix.latitude, fix.longitude, 1)?.firstOrNull()?.getAddressLine(0)
        } catch (_: Exception) { null } ?: String.format(java.util.Locale.US, "%.5f, %.5f", fix.latitude, fix.longitude)
        return JSONObject().put("latitude", fix.latitude).put("longitude", fix.longitude).put("address", address)
    }

    private fun shootParking(target: String) {
        parkingTarget = target
        feed?.takeStill()
        status = if (target == "spot") "请保持车尾画面" else "请保持车头画面"
    }

    private fun onParkingStill(jpeg: ByteArray) {
        val target = parkingTarget ?: return
        parkingTarget = null
        val plates = mutableMapOf<String, Float>()
        for (vehicle in result?.vehicles.orEmpty()) {
            val plate = vehicle.plate?.takeIf { vehicle.plateConfirmed && it.isNotBlank() } ?: continue
            val area = vehicle.box.width() * vehicle.box.height()
            if (area > (plates[plate] ?: 0f)) plates[plate] = area
        }
        if (plates.isEmpty()) { status = "没有识别到车牌，请重拍"; return }
        val file = File(cacheDir, "park-$target.jpg")
        file.writeBytes(jpeg)
        if (target == "spot") { spotFile = file; spotPlates = plates.toMap() } else { frontFile = file; frontPlates = plates.toMap() }
        status = ViolationPolicy.parkingBlock(spotPlates, frontPlates) ?: "车牌 ${ViolationPolicy.sharedPlate(spotPlates, frontPlates)} 一致，可以提交"
    }

    private fun submitParking() {
        val block = ViolationPolicy.parkingBlock(spotPlates, frontPlates)
        val spot = spotFile; val front = frontFile
        val plate = ViolationPolicy.sharedPlate(spotPlates, frontPlates)
        if (block != null || spot == null || front == null || plate == null) { status = block ?: "请先拍两张原片"; return }
        val zip = File(sync.clips, "${UUID.randomUUID()}.mp4")
        ZipOutputStream(zip.outputStream()).use { out ->
            for ((name, file) in listOf("spot.jpg" to spot, "front.jpg" to front)) {
                out.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(out) }; out.closeEntry()
            }
        }
        val incidents = JSONArray()
        listOf(0, 1).forEach { index ->
            incidents.put(JSONObject().put("track_id", 1).put("kind", "ILLEGAL_PARKING")
                .put("start_ms", index).put("end_ms", index + 1).put("plate", plate).put("plate_confirmed", true))
        }
        val details = JSONObject().put("camera_mode", "moving").put("incidents", incidents)
            .put("captured_at", System.currentTimeMillis() / 1000.0).put("duration_ms", 2).put("recording_gaps_ms", 0)
        keepClip(zip, "manual", "乱停乱放", "ILLEGAL_PARKING", details, "两张原片已保存")
        spotPlates = emptyMap(); frontPlates = emptyMap(); spotFile = null; frontFile = null
    }

    @Composable private fun HoldButton(modifier: Modifier = Modifier) {
        val label = when {
            ViolationPolicy.mode(violationMode).parking -> "拍照模式"
            recording && !eventClip -> "松开提交"
            recording -> "自动片段录制中"
            else -> "按住取证"
        }
        Box(modifier.background(if (recording && !eventClip) Color(0xFFB71C1C) else Color.White.copy(alpha = .35f), CircleShape)
            .pointerInput(violationMode) {
                awaitEachGesture {
                    awaitFirstDown()
                    onShutterDown()
                    waitForUpOrCancellation()
                    onShutterUp()
                }
            }.padding(horizontal = 18.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White)
        }
    }

    @Composable private fun ParkingShots() {
        val ready = ViolationPolicy.parkingBlock(spotPlates, frontPlates) == null
        val shared = ViolationPolicy.sharedPlate(spotPlates, frontPlates)
        val hint = when {
            spotPlates.isEmpty() && frontPlates.isEmpty() -> "先拍车尾，再拍车头。拍到车牌后按钮变绿，两张都有同一车牌再点确认提交。"
            shared != null -> "车牌 $shared 两张都有。请点绿色的确认提交。"
            spotPlates.isNotEmpty() && frontPlates.isNotEmpty() -> ViolationPolicy.parkingBlock(spotPlates, frontPlates) ?: "请继续拍摄"
            spotPlates.isEmpty() -> "车头 ${frontPlates.keys.joinToString("·")}。请再拍车尾。"
            else -> "车尾 ${spotPlates.keys.joinToString("·")}。请再拍车头。"
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(hint, Modifier.widthIn(max = 320.dp), color = if (ready) Color(0xFF9CE7CE) else Color.White, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ParkingShotButton("车尾", spotPlates) { shootParking("spot") }
                ParkingShotButton("车头", frontPlates) { shootParking("front") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val plates = spotPlates; val file = spotFile
                    spotPlates = frontPlates; spotFile = frontFile; frontPlates = plates; frontFile = file
                    status = ViolationPolicy.parkingBlock(spotPlates, frontPlates) ?: "已调换顺序"
                }, enabled = spotFile != null && frontFile != null) { Text("调换顺序") }
                Button(onClick = { submitParking() }, enabled = ready, colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1B8A4A), disabledContainerColor = Color(0xFF455A64),
                    contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = .7f),
                )) { Text(if (ready) "确认提交" else "等待两张一致") }
            }
        }
    }

    @Composable private fun ParkingShotButton(name: String, plates: Map<String, Float>, onClick: () -> Unit) {
        val taken = plates.isNotEmpty()
        val label = when (plates.size) {
            0 -> "拍$name"
            1 -> "$name ${plates.keys.first()}"
            else -> "$name ${plates.keys.joinToString("·")}"
        }
        Button(onClick = onClick, enabled = live && running && cameraPermission, colors = ButtonDefaults.buttonColors(
            containerColor = if (taken) Color(0xFF1B8A4A) else Color(0xFF546E7A),
            contentColor = Color.White,
        )) { Text(label) }
    }

    @Composable private fun Screen() {
        val connection by sync.state.collectAsState()
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val captureMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (!microphoneGranted()) captureMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            cameraPermission = it
            if (it) locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            else status = "未获得相机权限，可用图片识别"
        }
        val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            voiceEnabled = it; getPreferences(MODE_PRIVATE).edit().putBoolean("voiceEnabled",it).apply()
            if (it) voice.start() else voiceStatus = "未获得麦克风权限"
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) showPhoto {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver,uri)) { decoder,info,_ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val factor = minOf(1f,1600f/maxOf(info.size.width,info.size.height))
                    decoder.setTargetSize((info.size.width*factor).toInt().coerceAtLeast(1),(info.size.height*factor).toInt().coerceAtLeast(1))
                }
            }
        }
        BackHandler(menu) { menu=false }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val ready = detector
            if (live && running && cameraPermission && ready != null) {
                AndroidView(modifier=Modifier.fillMaxSize(),factory={ context ->
                    CameraFeed(context,this@MainActivity,worker,ready,{inferenceThreshold},{ detection, at ->
                        onVehicles(detection, at)
                    },{status=it},{ jpeg, at -> onParkingStill(jpeg); recognizeFrame(jpeg, at) }, resolutionId, { id ->
                        resolutionId = id
                        offeredTiers = feed?.supportedTiers()?.ifEmpty { RecordingTier.recordable() } ?: RecordingTier.recordable()
                        getPreferences(MODE_PRIVATE).edit().putString("resolution", id).apply()
                    }).also { feed=it }
                },update={it.boxes(showBoxes)})
                DisposableEffect(Unit) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { feed?.release();feed=null;window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
            } else if (!live && photo != null) {
                val image = photo!!
                PhotoPreview(image,if(showBoxes)
                    attachPlates(result?.vehicles.orEmpty(),plateHits,image.width,image.height) else emptyList())
            }
            // A dedicated tap surface leaves buttons accessible while the normal view stays unobstructed.
            Box(Modifier.fillMaxSize().testTag("playerTap").semantics { contentDescription = "轻触显示或隐藏菜单" }
                .clickable { menu=!menu })
            if (!cameraPermission && live) {
                Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("开启相机，开始车辆筛查",color=Color.White)
                    Button(onClick={permission.launch(Manifest.permission.CAMERA)}) { Text("允许使用相机") }
                }
            } else if (!running && live) Text("相机已暂停",Modifier.align(Alignment.Center),color=Color.White)
            Column(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(if(landscape) 16.dp else 12.dp)
                .background(Color.Black.copy(alpha=.58f)).padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(if(connection.connected) "● 服务器已连接" else "○ 离线保存 / 正在连接",color=if(connection.connected) Color(0xFF9CE7CE) else Color(0xFFFFCF91),style=MaterialTheme.typography.labelMedium)
                val latest=connection.records.firstOrNull()
                val queued = connection.records.count { it.optString("status") == "PENDING_UPLOAD" }
                val activity = if(recording) "● ${if (eventClip) "动态取证" else "重点片段"} ${recordedSeconds}s" else "缓存 ${cacheSeconds}s · 待上传 $queued"
                Text(ViolationPolicy.mode(violationMode).label, color=Color(0xFFA8C2C9),style=MaterialTheme.typography.labelSmall)
                Text(activity,color=if(recording) Color(0xFFFFCF91) else Color.White,style=MaterialTheme.typography.labelMedium)
            }
            if (!menu) {
                Column(Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(start=16.dp, end=156.dp, bottom=16.dp).fillMaxWidth()
                    .background(Color.Black.copy(alpha=.58f)).padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    val types = result?.vehicles?.groupingBy { it.label }?.eachCount()
                        ?.entries?.joinToString(" / ") { "${it.key} ${it.value}" }.orEmpty()
                    Text("车辆：${types.ifBlank{"未检出"}}",color=Color.White,style=MaterialTheme.typography.bodySmall)
                    val targets = result?.vehicles.orEmpty()
                    val livePlates = if (live) targets.filter { it.plate != null }.take(3).joinToString(" · ") {
                        "${it.plate}${if (it.plateConfirmed) " ✓" else " ?"}"
                    } else plateHits.joinToString(" · ") { it.text }
                    Text("车牌：${livePlates.ifBlank { if (livePlateEnabled) "等待清晰车牌" else "实时车牌已关闭" }}", color=Color.White,style=MaterialTheme.typography.bodySmall)
                    Text("信号灯：${signalLabel(liveSignal, liveSignalStable)}",color=if(liveSignal=="RED" && liveSignalStable) Color(0xFFFF8A80) else Color.White,style=MaterialTheme.typography.bodySmall)
                    if ((violationMode == "AUTO" || violationMode == "RED_LIGHT") && liveSignal == "RED" && liveSignalStable &&
                        currentIncidents.none { it.kind == "RED_LIGHT" })
                        Text("红灯已稳定。上传还需确认车牌，且车底越过停住的停止线。", color = Color(0xFFFFCF91), style = MaterialTheme.typography.bodySmall)
                    val activeEvents = currentIncidents.filter { clockTick - it.endAt < 5500 }
                    if (activeEvents.isNotEmpty()) Text(
                        activeEvents.take(2).joinToString(" · ") { it.reason },
                        color=Color(0xFFECDCA9),style=MaterialTheme.typography.bodySmall)

                }
            }
            if (!menu) {
                Column(Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp), horizontalAlignment=Alignment.End,
                    verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick={menu=true}) { Text("操作菜单") }
                    if (ViolationPolicy.mode(violationMode).parking) ParkingShots() else HoldButton()
                }
            }
            if (menu) CaptureMenu(landscape,
                onVoice = { if (voiceEnabled) { voiceEnabled = false; voice.stop(); voiceStatus = "语音未开启"; getPreferences(MODE_PRIVATE).edit().putBoolean("voiceEnabled", false).apply() } else microphone.launch(Manifest.permission.RECORD_AUDIO) },
                onServer = { startActivity(Intent(this@MainActivity, BackendActivity::class.java)) },
                onPhoto = { picker.launch("image/*") },
                onSample = { showPhoto { assets.open("traffic.jpg").use { BitmapFactory.decodeStream(it) } } },
                onCamera = {
                    generation++; result = null; photo = null; resetTracking()
                    running = if (live && cameraPermission) !running else true; live = true
                    status = if (running) "等待相机画面…" else "相机已暂停"
                    if (running && !cameraPermission) permission.launch(Manifest.permission.CAMERA)
                })
        }
    }

    @Composable private fun BoxScope.CaptureMenu(
        landscape: Boolean,
        onVoice: () -> Unit,
        onServer: () -> Unit,
        onPhoto: () -> Unit,
        onSample: () -> Unit,
        onCamera: () -> Unit,
    ) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .then(if (landscape) Modifier.fillMaxHeight() else Modifier.fillMaxHeight(.72f))
            .clipToBounds()
            .background(Color(0xFF101820))
            .border(2.dp, Color(0xFF9CE7CE))) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("监看菜单", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    TextButton(onClick = { menu = false }) { Text("隐藏菜单") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(status, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Text(voiceStatus, color = Color(0xFFA8C2C9), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ViolationPolicy.mode(violationMode).parking) ParkingShots() else HoldButton()
                        OutlinedButton(onClick = onVoice) { Text(if (voiceEnabled) "关闭语音" else "语音标记") }
                        OutlinedButton(onClick = onServer, enabled = !recording) { Text("服务器与记录") }
                    }
                    if (landscape) {
                        Row(Modifier.fillMaxWidth().drawBehind {
                            val x = size.width * 1.15f / 2.15f
                            drawLine(Color(0xFF9CE7CE), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                        }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                MenuBlock("违法模式") { ModeChips() }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                MenuBlock("分辨率") { ResolutionChips() }
                                MenuBlock("画面") { DirectionChips(); PictureToggles() }
                            }
                        }
                    } else {
                        MenuBlock("违法模式") { ModeChips() }
                        MenuBlock("分辨率") { ResolutionChips() }
                        MenuBlock("画面") { DirectionChips(); PictureToggles() }
                    }
                    MenuBlock("跟踪") { TrackingControls(onCamera, onPhoto, onSample) }
                }
            }
        }
    }

    @Composable private fun MenuBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color(0xFF9CE7CE), style = MaterialTheme.typography.titleSmall)
            HorizontalDivider(thickness = 2.dp, color = Color(0xFF9CE7CE))
            content()
        }
    }

    @Composable private fun ModeChips() {
        Text("Auto 不含乱停乱放。没有车牌的自动线索不会上传。", color = Color(0xFFD5E4E8), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ViolationPolicy.modes.forEach { mode ->
                FilterChip(selected = violationMode == mode.id, enabled = !recording, onClick = {
                    if (ViolationPolicy.mode(violationMode).parking && !mode.parking) status = "监看中"
                    violationMode = mode.id
                    getPreferences(MODE_PRIVATE).edit().putString("violationMode", mode.id).apply()
                }, label = { Text(mode.label) })
            }
        }
    }

    @Composable private fun ResolutionChips() {
        Text("旁边是 50MB 内大约还能录的秒数。违法模式不随分辨率改变。", color = Color(0xFFD5E4E8), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            offeredTiers.forEach { tier ->
                FilterChip(selected = resolutionId == tier.id, enabled = !recording, onClick = {
                    resolutionId = tier.id
                    getPreferences(MODE_PRIVATE).edit().putString("resolution", tier.id).apply()
                    feed?.setTier(tier.id)
                }, label = { Text("${tier.label} · 约${RecordingTier.maxSeconds(tier)}秒") })
            }
        }
    }

    @Composable private fun DirectionChips() {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("横屏" to ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, "竖屏" to ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, "自动" to ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR).forEach { (label, value) ->
                FilterChip(selected = orientationPreference == value, enabled = !recording, onClick = {
                    orientationPreference = value; requestedOrientation = value
                    getPreferences(MODE_PRIVATE).edit().putInt("orientation", value).apply()
                }, label = { Text(label) })
            }
        }
    }

    @Composable private fun PictureToggles() {
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(showBoxes, { showBoxes = it; getPreferences(MODE_PRIVATE).edit().putBoolean("showBoxes", it).apply() }); Text("显示识别框和车牌", color = Color.White) }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(livePlateEnabled, { livePlateEnabled = it; if (!it) { tracker.clearPlates(); setPlates(emptyList()) }; getPreferences(MODE_PRIVATE).edit().putBoolean("livePlate", it).apply() }); Text("实时识别车牌", color = Color.White) }
    }

    @Composable private fun TrackingControls(onCamera: () -> Unit, onPhoto: () -> Unit, onSample: () -> Unit) {
        Text("丢帧保留 ${(tracker.occlusionMs / 1000f)} 秒", color = Color.White, style = MaterialTheme.typography.bodySmall)
        Slider(tracker.occlusionMs.toFloat(), { tracker.occlusionMs = it.toLong(); clockTick++ }, valueRange = 1500f..5000f,
            onValueChangeFinished = { getPreferences(MODE_PRIVATE).edit().putLong("occlusionMs", tracker.occlusionMs).apply() })
        Text("事件尾部补录 ${incidents.tailMs / 1000f} 秒（另保留 3 秒消失容忍）", color = Color.White, style = MaterialTheme.typography.bodySmall)
        Slider(incidents.tailMs.toFloat(), { incidents.tailMs = it.toLong(); clockTick++ }, valueRange = 1500f..5000f,
            onValueChangeFinished = { getPreferences(MODE_PRIVATE).edit().putLong("tailMs", incidents.tailMs).apply() })
        Text("运动阈值 ${(incidents.motionThreshold * 100).toInt()}% 画幅", color = Color.White, style = MaterialTheme.typography.bodySmall)
        Slider(incidents.motionThreshold, { incidents.motionThreshold = it; clockTick++ }, valueRange = .03f.. .12f,
            onValueChangeFinished = { getPreferences(MODE_PRIVATE).edit().putFloat("motionThreshold", incidents.motionThreshold).apply() })
        Text("置信度阈值 ${(confidence * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.bodySmall)
        Slider(confidence, { confidence = it; inferenceThreshold = it }, valueRange = .2f.. .8f, onValueChangeFinished = { getPreferences(MODE_PRIVATE).edit().putFloat("threshold", confidence).apply() })
        Text(result?.let { "处理 ${it.elapsedMs} ms · ${if (live) "相机最多 8 次/秒" else "图片识别"}" } ?: "EfficientDet-Lite0", color = Color(0xFFA8C2C9), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(enabled = !recording && !busy && detector != null, onClick = onCamera) { Text(if (live && running) "暂停" else "相机") }
            OutlinedButton(enabled = !recording && !busy && detector != null, onClick = onPhoto) { Text("选图片") }
            OutlinedButton(enabled = !recording && !busy && detector != null, onClick = onSample) { Text("测试图") }
        }
    }

    private fun showPhoto(load: () -> Bitmap) {
        val ready=detector ?: return
        feed?.release();live=false;busy=true;result=null;photo=null;resetTracking();status="正在识别图片…"
        val request=++generation;val threshold=inferenceThreshold
        worker.execute {
            try {
                val image=load();val detection=ready.detect(image,threshold)
                // 图片测试也送一帧车牌识别，便于用样例图验证“车辆 + 车牌”叠加。
                val jpeg=if(detection.vehicles.isEmpty()) null else
                    java.io.ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.JPEG,95,it)}.toByteArray()
                runOnUiThread { if(!destroyed.get()&&request==generation){photo=image;result=detection;busy=false;status=if(detection.vehicles.isEmpty()) "此图片未检测到车辆" else "检测到 ${detection.vehicles.size} 辆车："+detection.vehicles.groupingBy{it.label}.eachCount().entries.joinToString{"${it.key} ${it.value}"};if(jpeg!=null)recognizeFrame(jpeg)} }
            }catch(error:Exception){runOnUiThread{if(!destroyed.get()){busy=false;status="图片识别失败：${error.message}"}}}
        }
    }
    override fun onDestroy() {
        unregisterReceiver(shutterActions)
        destroyed.set(true);voice.close();feed?.release()
        worker.execute { workerDetector?.close() };worker.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun PhotoPreview(bitmap: Bitmap, vehicles: List<Vehicle>) {
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
        FrameLayout(context).apply {
            addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                FrameLayout.LayoutParams(-1, -1))
            addView(DetectionOverlay(context), FrameLayout.LayoutParams(-1, -1))
        }
    }, update = { frame ->
        (frame.getChildAt(0) as ImageView).setImageBitmap(bitmap)
        frame.post {
            val scale = minOf(frame.width.toFloat() / bitmap.width, frame.height.toFloat() / bitmap.height)
            val dx = (frame.width - bitmap.width * scale) / 2f
            val dy = (frame.height - bitmap.height * scale) / 2f
            (frame.getChildAt(1) as DetectionOverlay).vehicles = vehicles.map { vehicle ->
                val b = vehicle.box
                vehicle.copy(box = RectF(b.left * scale + dx, b.top * scale + dy,
                    b.right * scale + dx, b.bottom * scale + dy))
            }
        }
    })
}
