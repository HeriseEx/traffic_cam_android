package com.example.illegalcapture

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONArray
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
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
    private var options by mutableStateOf(false)
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
    private var automatic by mutableStateOf(true)
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
    private val voice by lazy { VoiceMarker(this, { voiceStatus = it }, { mark("voice") }) }
    private var orientationPreference = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val prefs = getPreferences(MODE_PRIVATE)
        confidence = prefs.getFloat("threshold", .4f).coerceIn(.2f,.8f)
        inferenceThreshold = confidence
        voiceEnabled = prefs.getBoolean("voiceEnabled", false)
        livePlateEnabled = prefs.getBoolean("livePlate", true)
        clipSeconds = prefs.getInt("clipSeconds", 15).let { if (it in listOf(10, 15, 30)) it else 15 }
        showBoxes = prefs.getBoolean("showBoxes", true)
        automatic = prefs.getBoolean("automatic", true)
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
        currentIncidents = incidents.update(tracks, at, liveSignalStable && liveSignal == "RED")
        if (recording && !captureFinalizing) currentIncidents.forEach { event ->
            if (captureEvents.size < 16 || incidentKey(event) in captureEvents) captureEvents[incidentKey(event)] = event.copy()
        }
        if (!recording && automatic && live && running) {
            val fresh = currentIncidents.filter { at - it.endAt < 1000 && at - (savedEvents[incidentKey(it)] ?: -10000) > 3000 }
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
        val from = if (events.isEmpty()) now - 10_000 else events.minOf { it.startAt } - 3000
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
            val candidate = if (captureEvents.values.any { it.kind == "RED_LIGHT" }) "RED_LIGHT" else "UNKNOWN"
            recording = false; eventClip = false; captureFinalizing = false; requestedOrientation = orientationPreference
            if (completed != null) {
                // Application repository outlives Activity destruction while finalized evidence enters the durable queue.
                val repository = sync
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        repository.enqueue(completed, trigger, note, candidate, details)
                        withContext(Dispatchers.Main) { if (!destroyed.get()) status = "片段已保存 · 自动上传与复核中" }
                        repository.tick()
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { if (!destroyed.get()) status = "视频已保留，稍后恢复入队：${e.message}" }
                    }
                }
            } else status = error ?: "录像失败"
        }, startAtMs = from)
        if (!started) { recording = false; eventClip = false; requestedOrientation = orientationPreference; status = "缓存正在准备，请稍后标记" }
        else { status = if (trigger == "automatic") "疑似事件取证中 · 持续跟踪并动态延长" else "正在录制重点片段"; menu = false }
    }

    @Composable private fun Screen() {
        val connection by sync.state.collectAsState()
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            cameraPermission = it; if (!it) status = "未获得相机权限，可用图片识别"
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
        BackHandler(menu || options) { if (options) options=false else menu=false }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val ready = detector
            if (live && running && cameraPermission && ready != null) {
                AndroidView(modifier=Modifier.fillMaxSize(),factory={ context ->
                    CameraFeed(context,this@MainActivity,worker,ready,{inferenceThreshold},{ detection, at ->
                        onVehicles(detection, at)
                    },{status=it},{ jpeg, at -> recognizeFrame(jpeg, at) }).also { feed=it }
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
                Text(if (automatic) "自动取证已开启" else "手动取证模式", color=Color(0xFFA8C2C9),style=MaterialTheme.typography.labelSmall)
                Text(activity,color=if(recording) Color(0xFFFFCF91) else Color.White,style=MaterialTheme.typography.labelMedium)
            }
            if (!menu) {
                Column(Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(start=16.dp, end=156.dp, bottom=16.dp).fillMaxWidth()
                    .background(Color.Black.copy(alpha=.58f)).padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    val types=result?.vehicles?.map{it.label}?.distinct()?.joinToString(" / ").orEmpty()
                    Text("车辆：${types.ifBlank{"未检出"}}",color=Color.White,style=MaterialTheme.typography.bodySmall)
                    val targets = result?.vehicles.orEmpty()
                    val livePlates = if (live) targets.filter { it.plate != null }.take(3).joinToString(" · ") {
                        "#${it.trackId} ${it.plate}${if (it.plateConfirmed) " ✓" else " 待确认"}"
                    } else plateHits.joinToString(" · ") { it.text }
                    Text("车牌：${livePlates.ifBlank { if (livePlateEnabled) "等待清晰车牌" else "实时车牌已关闭" }}", color=Color.White,style=MaterialTheme.typography.bodySmall)
                    Text("信号灯：${signalLabel(liveSignal, liveSignalStable)}",color=if(liveSignal=="RED" && liveSignalStable) Color(0xFFFF8A80) else Color.White,style=MaterialTheme.typography.bodySmall)
                    val activeEvents = currentIncidents.filter { clockTick - it.endAt < 5500 }
                    Text(if (activeEvents.isEmpty()) "${targets.count { !it.predicted }} 辆跟踪中 · ${targets.count { it.predicted }} 辆遮挡保留" else
                        activeEvents.take(2).joinToString(" · ") { "#${it.trackId} ${if (it.kind == "RED_LIGHT") "红灯期间运动" else "横向移动"} · 待复核" },
                        color=Color(0xFFECDCA9),style=MaterialTheme.typography.bodySmall)

                }
            }
            if (!menu) {
                Column(Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp), horizontalAlignment=Alignment.End,
                    verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick={menu=true}) { Text("操作菜单") }
                    Button(onClick={if(recording) { captureFinalizing=true;feed?.stopRecording() } else mark("manual")},
                        enabled=detector!=null && live && running && cameraPermission) { Text(if(recording) "结束片段" else "标记重点") }
                }
            }
            if(menu) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xEE101820))
                    .safeDrawingPadding().padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        Text("车辆识别",style=MaterialTheme.typography.titleMedium,color=Color.White)
                        TextButton(onClick={menu=false}) { Text("隐藏菜单") }
                    }
                    Text(status,color=Color.White,style=MaterialTheme.typography.bodySmall,maxLines=2)
                    Text(voiceStatus,color=Color(0xFFA8C2C9),style=MaterialTheme.typography.bodySmall)
                    if(landscape) {
                        Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                            Button(onClick={if(recording)feed?.stopRecording() else mark("manual")},enabled=detector!=null) { Text(if(recording) "结束片段" else "标记重点") }
                            OutlinedButton(onClick={if(voiceEnabled){voiceEnabled=false;voice.stop();voiceStatus="语音未开启";getPreferences(MODE_PRIVATE).edit().putBoolean("voiceEnabled",false).apply()} else microphone.launch(Manifest.permission.RECORD_AUDIO)}) { Text(if(voiceEnabled) "关闭语音" else "语音标记") }
                            OutlinedButton(onClick={startActivity(Intent(this@MainActivity,BackendActivity::class.java))},enabled=!recording) { Text("服务器与记录") }
                            OutlinedButton(onClick={options=true}) { Text("更多设置") }
                        }
                    } else {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(modifier=Modifier.weight(1f),enabled=detector!=null,onClick={if(recording)feed?.stopRecording() else mark("manual")}) { Text(if(recording) "结束片段" else "标记重点") }
                            OutlinedButton(modifier=Modifier.weight(1f),onClick={if(voiceEnabled){voiceEnabled=false;voice.stop();voiceStatus="语音未开启";getPreferences(MODE_PRIVATE).edit().putBoolean("voiceEnabled",false).apply()} else microphone.launch(Manifest.permission.RECORD_AUDIO)}) { Text(if(voiceEnabled) "关闭语音" else "语音标记") }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(modifier=Modifier.weight(1f),enabled=!recording,onClick={startActivity(Intent(this@MainActivity,BackendActivity::class.java))}) { Text("服务器与记录") }
                            OutlinedButton(modifier=Modifier.weight(1f),onClick={options=true}) { Text("更多设置") }
                        }
                    }
                }
            }
        }
        if(options) AlertDialog(onDismissRequest={options=false},title={Text("画面与筛查设置")},confirmButton={TextButton(onClick={options=false}){Text("完成")}},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Switch(automatic, { automatic=it;getPreferences(MODE_PRIVATE).edit().putBoolean("automatic",it).apply() })
                    Text("自动发现疑似事件并上传")
                }
                Text("自动片段从动作开始前的缓存提取，随动作延长；短暂遮挡保留身份，事件结束后补录。长事件按容量分段。")
                Text("手动 / 语音标记后补录")
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf(10, 15, 30).forEach { seconds ->
                        FilterChip(selected=clipSeconds==seconds, enabled=!recording, onClick={
                            clipSeconds=seconds
                            getPreferences(MODE_PRIVATE).edit().putInt("clipSeconds", seconds).apply()
                        }, label={Text("${seconds}秒")})
                    }
                }
                Text("画面方向")
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf("横屏" to ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,"竖屏" to ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,"自动" to ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR).forEach { (label,value) ->
                        FilterChip(selected=orientationPreference==value,enabled=!recording,onClick={orientationPreference=value;requestedOrientation=value;getPreferences(MODE_PRIVATE).edit().putInt("orientation",value).apply()},label={Text(label)})
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(showBoxes,{showBoxes=it;getPreferences(MODE_PRIVATE).edit().putBoolean("showBoxes",it).apply()});Text("显示车辆识别框和车牌") }
                Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(livePlateEnabled,{livePlateEnabled=it;if(!it){tracker.clearPlates();setPlates(emptyList())};getPreferences(MODE_PRIVATE).edit().putBoolean("livePlate",it).apply()});Text("连接后实时识别车牌") }
                Text("车牌按轨迹累计确认；网络较慢时自动降低请求频率。离线也会保存疑似片段，恢复连接后自动上传。移动镜头不能仅凭位移认定违法。",style=MaterialTheme.typography.bodySmall)
                Text("遮挡保留 ${(tracker.occlusionMs / 1000f)} 秒")
                Slider(tracker.occlusionMs.toFloat(), { tracker.occlusionMs=it.toLong();clockTick++ }, valueRange=1500f..5000f,
                    onValueChangeFinished={getPreferences(MODE_PRIVATE).edit().putLong("occlusionMs",tracker.occlusionMs).apply()})
                Text("事件尾部补录 ${incidents.tailMs / 1000f} 秒（另保留 3 秒消失容忍）")
                Slider(incidents.tailMs.toFloat(), { incidents.tailMs=it.toLong();clockTick++ }, valueRange=1500f..5000f,
                    onValueChangeFinished={getPreferences(MODE_PRIVATE).edit().putLong("tailMs",incidents.tailMs).apply()})
                Text("运动阈值 ${(incidents.motionThreshold * 100).toInt()}% 画幅")
                Slider(incidents.motionThreshold, { incidents.motionThreshold=it;clockTick++ }, valueRange=.03f.. .12f,
                    onValueChangeFinished={getPreferences(MODE_PRIVATE).edit().putFloat("motionThreshold",incidents.motionThreshold).apply()})
                Text("置信度阈值 ${(confidence*100).toInt()}%")
                Slider(confidence,{confidence=it;inferenceThreshold=it},valueRange=.2f.. .8f,onValueChangeFinished={getPreferences(MODE_PRIVATE).edit().putFloat("threshold",confidence).apply()})
                Text(result?.let{"处理 ${it.elapsedMs} ms · ${if(live) "相机最多 8 次/秒" else "图片识别"}"}?:"EfficientDet-Lite0",style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    OutlinedButton(enabled=!recording&&!busy&&detector!=null,onClick={
                        generation++;result=null;photo=null;resetTracking()
                        running=if(live&&cameraPermission)!running else true;live=true
                        status=if(running)"等待相机画面…" else "相机已暂停"
                        if(running&&!cameraPermission)permission.launch(Manifest.permission.CAMERA)
                        options=false
                    }) { Text(if(live&&running) "暂停" else "相机") }
                    OutlinedButton(enabled=!recording&&!busy&&detector!=null,onClick={picker.launch("image/*");options=false}) { Text("选图片") }
                    OutlinedButton(enabled=!recording&&!busy&&detector!=null,onClick={showPhoto{assets.open("traffic.jpg").use{BitmapFactory.decodeStream(it)}};options=false}) { Text("测试图") }
                }
            }
        })
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
