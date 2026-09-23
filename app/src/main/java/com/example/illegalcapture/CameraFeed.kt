package com.example.illegalcapture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.io.ByteArrayOutputStream
import java.io.File

@androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)
class CameraFeed(
    context: Context,
    private val owner: LifecycleOwner,
    private val executor: ExecutorService,
    private val detector: VehicleDetector,
    private val threshold: () -> Float,
    private val onResult: (DetectionResult, Long) -> DetectionResult,
    private val onError: (String) -> Unit,
    private val onJpeg: (ByteArray, Long) -> Unit = { _, _ -> },
    tier: String = "1080",
    private val onTier: (String) -> Unit = {},
) : FrameLayout(context) {
    private val preview = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FIT_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    private val qhdView = TextureView(context).apply { visibility = View.GONE }
    private val overlay = DetectionOverlay(context)
    private var showBoxes = true
    private val active = AtomicBoolean(true)
    private var provider: ProcessCameraProvider? = null
    private var cameraInfo: CameraInfo? = null
    private val sixteenNine = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
        ).build()
    private val cameraPreview = Preview.Builder().setResolutionSelector(sixteenNine).build()
    private val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .setResolutionSelector(sixteenNine)
        .build()
    private var tierId = tier
    private var tierChecked = false
    private var recorder = buildRecorder()
    private var video = VideoCapture.withOutput(recorder)
    private var recording: Recording? = null
    private var qhd: QhdCamera? = null
    private var qhdBusy = false
    private var ready = false
    private var lastFrame = 0L
    private var lastJpeg = 0L
    private var wantStill = false
    private var spoken = false
    private val ringDir = File(context.filesDir, "ring").apply { mkdirs() }
    private val ring = ArrayDeque<VideoSegment>()
    private val exporting = mutableSetOf<File>()
    private var session = 0
    private var looping = false
    private var capturing = false
    private var joining = false
    private var finishRequested = false
    private var rebind = false
    private var captureDest: File? = null
    private var captureOnSeconds: (Int) -> Unit = {}
    private var captureOnComplete: (File?, String?) -> Unit = { _, _ -> }
    private var captureStartedAt = 0L
    private var captureFrom = 0L
    private var captureUntil = Long.MAX_VALUE
    private var captureDeadline = 0L
    var lastClipTiming: ClipTiming? = null
        private set
    val cachedSeconds: Int get() = (ring.sumOf { it.endMs - it.startMs } / 1000).toInt()

    init {
        addView(preview, LayoutParams(-1, -1))
        addView(qhdView, LayoutParams(-1, -1))
        addView(overlay, LayoutParams(-1, -1))
        post { start() }
    }

    private fun buildRecorder(): Recorder {
        val quality = when (RecordingTier.qualityName(tierId)) {
            "HD" -> Quality.HD
            "UHD" -> Quality.UHD
            else -> Quality.FHD
        }
        return Recorder.Builder()
            .setAspectRatio(AspectRatio.RATIO_16_9)
            .setTargetVideoEncodingBitRate(RecordingTier.bitrate(RecordingTier.byId(tierId)).toInt())
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
            .build()
    }

    fun supportedTiers(): List<RecordingTier.Tier> {
        val info = cameraInfo ?: return emptyList()
        val names = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR).mapNotNull {
            when (it) {
                Quality.HD -> "HD"
                Quality.FHD -> "FHD"
                Quality.UHD -> "UHD"
                else -> null
            }
        }.toSet()
        val tiers = RecordingTier.offered(names).toMutableList()
        if (QhdCamera.supported(context) && tiers.none { it.id == "1440" }) {
            val at = tiers.indexOfFirst { it.height > 1440 }.let { if (it < 0) tiers.size else it }
            tiers.add(at, RecordingTier.byId("1440"))
        }
        return tiers
    }

    fun setTier(id: String) {
        val allowed = id == "1440" && QhdCamera.supported(context) || RecordingTier.qualityName(id) != null
        if (capturing || !allowed || id == tierId) return
        tierId = id
        tierChecked = true
        onTier(id)
        if (ready) updateRotation()
    }

    private fun start() {
        if (!active.get()) return
        if (tierId == "1440" && QhdCamera.supported(context)) {
            startQhd()
            return
        }
        if (tierId == "1440") {
            tierId = "1080"
            onTier("1080")
        }
        closeQhd()
        session++
        val mine = session
        looping = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!active.get() || mine != session) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                cameraPreview.setSurfaceProvider(preview.surfaceProvider)
                analysis.setAnalyzer(executor) { image ->
                    try {
                        val now = SystemClock.elapsedRealtime()
                        // ponytail: cap CPU analysis at 8 fps; tune after sustained road/thermal tests.
                        if (!active.get() || now - lastFrame < 125) return@setAnalyzer
                        lastFrame = now
                        val source = ImageProxyTransformFactory().apply {
                            isUsingRotationDegrees = true
                        }.getOutputTransform(image)
                        val raw = image.toBitmap()
                        val rotation = image.imageInfo.rotationDegrees
                        val bitmap = if (rotation == 0) raw else Bitmap.createBitmap(
                            raw, 0, 0, raw.width, raw.height,
                            Matrix().apply { postRotate(rotation.toFloat()) }, true)
                        val frameWidth = bitmap.width
                        val frameHeight = bitmap.height
                        var jpeg: ByteArray? = null
                        val result = try {
                            val detected = detector.detect(bitmap, threshold()).let { raw ->
                                raw.copy(road = RoadScan.marks(bitmap), vehicles = raw.vehicles.map { vehicle ->
                                    vehicle.copy(signalOff = RoadScan.lampOff(bitmap, vehicle.box))
                                })
                            }
                            if (wantStill || now - lastJpeg >= 1500) {
                                wantStill = false
                                lastJpeg = now
                                val bytes = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes)
                                jpeg = bytes.toByteArray().takeIf { it.size <= 2 * 1024 * 1024 }
                            }
                            detected
                        } finally {
                            if (bitmap !== raw) bitmap.recycle()
                            raw.recycle()
                        }
                        post {
                            if (active.get() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                val labeled = onResult(result, now)
                                jpeg?.let { onJpeg(it, now) }
                                val target = preview.outputTransform
                                if (target == null || !showBoxes) {
                                    overlay.vehicles = emptyList()
                                    overlay.lamps = emptyList()
                                    overlay.plates = emptyList()
                                } else {
                                    val transform = CoordinateTransform(source, target)
                                    overlay.vehicles = labeled.vehicles.map { vehicle ->
                                        vehicle.copy(box = RectF(vehicle.box).also { transform.mapRect(it) }, path = vehicle.path.map { point ->
                                            val xy = floatArrayOf(point.x, point.y)
                                            transform.mapPoints(xy)
                                            point.copy(x = xy[0], y = xy[1])
                                        })
                                    }
                                    overlay.lamps = labeled.lamps.map { lamp ->
                                        lamp.copy(box = RectF(lamp.box).also { transform.mapRect(it) })
                                    }
                                    overlay.plates = emptyList()
                                }
                            }
                        }
                    } catch (error: Exception) {
                        if (active.compareAndSet(true, false)) post {
                            analysis.clearAnalyzer()
                            provider?.unbind(cameraPreview, analysis, video)
                            overlay.vehicles = emptyList()
                            overlay.lamps = emptyList()
                            overlay.plates = emptyList()
                            onError("识别失败：${error.message}")
                        }
                    } finally {
                        image.close()
                    }
                }
                val rotation = display?.rotation ?: Surface.ROTATION_0
                val viewPort = ViewPort.Builder(Rational(16, 9), rotation)
                    .setScaleType(ViewPort.FIT)
                    .build()
                cameraPreview.targetRotation = rotation
                analysis.targetRotation = rotation
                cameraInfo?.cameraState?.removeObservers(owner)
                cameraProvider.unbindAll()
                recorder = buildRecorder()
                video = VideoCapture.withOutput(recorder)
                video.targetRotation = rotation
                val camera = cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA,
                    UseCaseGroup.Builder().setViewPort(viewPort)
                        .addUseCase(cameraPreview).addUseCase(analysis).addUseCase(video).build())
                cameraInfo = camera.cameraInfo
                if (!tierChecked) {
                    tierChecked = true
                    val offered = supportedTiers()
                    val chosen = RecordingTier.choose(offered, tierId)
                    if (offered.isNotEmpty() && chosen.id != tierId) {
                        tierId = chosen.id
                        onTier(chosen.id)
                        cameraProvider.unbindAll()
                        post { start() }
                        return@addListener
                    }
                    onTier(tierId)
                }
                ready = true
                looping = true
                if (recording == null) beginSegment()
                camera.cameraInfo.cameraState.observe(owner) { state ->
                    if (active.get() && state.error != null) onError("相机暂不可用，请暂停后重试")
                }
            } catch (error: Exception) {
                onError("无法打开后置相机：${error.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun boxes(visible: Boolean) {
        showBoxes = visible
        if (!visible) { overlay.vehicles = emptyList(); overlay.lamps = emptyList(); overlay.plates = emptyList() }
    }
    fun updateRotation() {
        if (capturing) return
        rebind = true
        looping = false
        recording?.stop() ?: post { start() }
    }

    fun takeStill() { wantStill = true }

    fun record(file: File, seconds: Int, onSeconds: (Int) -> Unit, onComplete: (File?, String?) -> Unit,
        startAtMs: Long = SystemClock.elapsedRealtime() - 10_000, spokenCapture: Boolean = false): Boolean {
        if (!ready || capturing || joining || !active.get()) return false
        capturing = true; finishRequested = false; spoken = spokenCapture
        captureDest = file; captureOnSeconds = onSeconds; captureOnComplete = onComplete
        captureStartedAt = SystemClock.elapsedRealtime()
        captureFrom = if (spokenCapture) captureStartedAt else startAtMs.coerceAtLeast(captureStartedAt - 20_000)
        captureDeadline = minOf(captureStartedAt + seconds.coerceIn(3, 65) * 1000, captureFrom + 80_000)
        captureUntil = Long.MAX_VALUE
        lastClipTiming = null
        if (spokenCapture) {
            if (recording != null) recording?.stop()
            else if (tierId == "1440" && qhdBusy) qhd?.stopSegment()
            else startSpoken()
        } else if (recording == null) beginSegment()
        return true
    }

    private fun closeQhd() {
        qhd?.close()
        qhd = null
        qhdBusy = false
        qhdView.visibility = View.GONE
        preview.visibility = View.VISIBLE
    }

    private fun startQhd() {
        if (qhd != null) return
        provider?.unbindAll()
        preview.visibility = View.GONE
        qhdView.visibility = View.VISIBLE
        val camera = QhdCamera(context, qhdView, { bitmap -> executor.execute { deliverQhd(bitmap) } }, onError)
        qhd = camera
        camera.open {
            if (!active.get() || tierId != "1440") return@open
            ready = true
            tierChecked = true
            looping = true
            onTier("1440")
            if (!qhdBusy) beginQhdSegment()
        }
    }

    private fun deliverQhd(bitmap: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        val detected = try {
            detector.detect(bitmap, threshold()).let { raw ->
                raw.copy(road = RoadScan.marks(bitmap), vehicles = raw.vehicles.map { vehicle ->
                    vehicle.copy(signalOff = RoadScan.lampOff(bitmap, vehicle.box))
                })
            }
        } catch (error: Exception) {
            bitmap.recycle()
            onError("识别失败：${error.message}")
            return
        }
        var jpeg: ByteArray? = null
        if (wantStill || now - lastJpeg >= 1500) {
            wantStill = false
            lastJpeg = now
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes)
            jpeg = bytes.toByteArray().takeIf { it.size <= 2 * 1024 * 1024 }
        }
        bitmap.recycle()
        post {
            if (!active.get()) return@post
            val labeled = onResult(detected, now)
            jpeg?.let { onJpeg(it, now) }
            val viewW = qhdView.width.coerceAtLeast(1)
            val viewH = qhdView.height.coerceAtLeast(1)
            val scale = minOf(viewW / detected.width.toFloat(), viewH / detected.height.toFloat())
            val dx = (viewW - detected.width * scale) / 2
            val dy = (viewH - detected.height * scale) / 2
            fun map(box: RectF) = RectF(box.left * scale + dx, box.top * scale + dy, box.right * scale + dx, box.bottom * scale + dy)
            overlay.vehicles = if (!showBoxes) emptyList() else labeled.vehicles.map { it.copy(box = map(it.box)) }
            overlay.lamps = if (!showBoxes) emptyList() else labeled.lamps.map { it.copy(box = map(it.box)) }
            overlay.plates = emptyList()
        }
    }

    private fun beginQhdSegment() {
        val camera = qhd ?: return
        if (!ready || qhdBusy || !active.get()) return
        if (ringDir.usableSpace < 150L * 1024 * 1024) {
            looping = false
            onError("空间不足，已停止缓存，请处理本地片段")
            return
        }
        val file = File(ringDir, "seg-${System.nanoTime()}.mp4")
        qhdBusy = true
        val started = camera.startSegment(file, false) { ok, duration ->
            qhdBusy = false
            val start = SystemClock.elapsedRealtime() - duration
            onSegmentFinal(file, start, duration, ok && file.length() > 0)
        }
        if (!started) {
            qhdBusy = false
            file.delete()
        }
    }

    private fun startQhdSpoken() {
        val dest = captureDest ?: return
        val camera = qhd ?: return
        val audio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        qhdBusy = true
        captureStartedAt = SystemClock.elapsedRealtime()
        tickSpoken()
        val started = camera.startSegment(dest, audio) { ok, duration ->
            qhdBusy = false
            spoken = false
            capturing = false
            finishRequested = false
            lastClipTiming = ClipTiming(captureStartedAt, captureStartedAt + duration, 0)
            val kept = ok && duration >= 3_000 && dest.length() > 0
            if (!kept) dest.delete()
            val done = captureOnComplete
            captureOnComplete = { _, _ -> }
            captureDest = null
            done(if (kept) dest else null, if (kept) null else if (duration < 3_000) "不足 3 秒，已丢弃" else "录音录像失败")
            if (looping && active.get() && !qhdBusy) beginQhdSegment()
        }
        if (!started) {
            qhdBusy = false
            spoken = false
            capturing = false
            val done = captureOnComplete
            captureOnComplete = { _, _ -> }
            captureDest = null
            done(null, "无法开始录音录像")
        }
    }

    private fun onSegmentFinal(file: File, segmentStart: Long, duration: Long, usable: Boolean) {
        if (spoken && capturing) {
            if (usable) ring.addLast(VideoSegment(file, segmentStart, segmentStart + duration)) else file.delete()
            if (finishRequested || !active.get()) {
                spoken = false; capturing = false; finishRequested = false
                captureDest?.delete(); captureDest = null
                val done = captureOnComplete
                captureOnComplete = { _, _ -> }
                done(null, "不足 3 秒，已丢弃")
                if (looping && active.get()) beginSegment()
            } else startSpoken()
        } else {
            if (usable) ring.addLast(VideoSegment(file, segmentStart, segmentStart + duration)) else file.delete()
            if (capturing && !joining && (finishRequested || !active.get() || !usable)) {
                captureUntil = minOf(captureUntil, SystemClock.elapsedRealtime())
                finishJoin()
            }
            if (rebind && !capturing && active.get()) {
                clearRing(); rebind = false; start()
            } else if (looping && active.get()) beginSegment()
            evict()
        }
    }

    private fun tickSpoken() {
        if (!spoken || !capturing) return
        captureOnSeconds(((SystemClock.elapsedRealtime() - captureStartedAt) / 1000).toInt())
        postDelayed({ tickSpoken() }, 500)
    }

    private fun startSpoken() {
        if (tierId == "1440") { startQhdSpoken(); return }
        val dest = captureDest ?: return
        try {
            val options = FileOutputOptions.Builder(dest).build()
            var pending = recorder.prepareRecording(context, options)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                pending = pending.withAudioEnabled()
            captureStartedAt = SystemClock.elapsedRealtime()
            captureFrom = captureStartedAt
            recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        captureStartedAt = SystemClock.elapsedRealtime()
                        captureFrom = captureStartedAt
                    }
                    is VideoRecordEvent.Status -> {
                        val now = SystemClock.elapsedRealtime()
                        captureOnSeconds(((now - captureStartedAt) / 1000).toInt())
                        if (now - captureStartedAt >= 65_000 || event.recordingStats.numBytesRecorded > 42L * 1024 * 1024) stopRecording()
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        val duration = event.recordingStats.recordedDurationNanos / 1_000_000
                        val ok = event.error in setOf(
                            VideoRecordEvent.Finalize.ERROR_NONE,
                            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
                        ) && dest.length() > 0 && duration > 0
                        spoken = false
                        capturing = false
                        finishRequested = false
                        lastClipTiming = ClipTiming(captureStartedAt, captureStartedAt + duration, 0)
                        val done = captureOnComplete
                        captureOnComplete = { _, _ -> }
                        captureDest = null
                        done(if (ok) dest else null, if (ok) null else "录音录像失败")
                        if (looping && active.get() && recording == null) beginSegment()
                    }
                    else -> Unit
                }
            }
        } catch (error: Exception) {
            spoken = false
            capturing = false
            recording = null
            val done = captureOnComplete
            captureOnComplete = { _, _ -> }
            captureDest = null
            done(null, "无法开始录音录像：${error.message}")
            if (looping && active.get()) beginSegment()
        }
    }

    fun stopRecording() {
        if (!capturing || joining || finishRequested) return
        finishRequested = true
        captureUntil = SystemClock.elapsedRealtime()
        if (tierId == "1440") qhd?.stopSegment() else recording?.stop() ?: finishJoin()
    }

    fun pauseLoop() {
        looping = false
        if (capturing) stopRecording() else if (tierId == "1440") qhd?.stopSegment() else recording?.stop()
    }

    fun resumeLoop() {
        if (!active.get() || !ready) return
        looping = true
        if (recording == null) beginSegment()
    }

    private fun beginSegment() {
        if (tierId == "1440") { beginQhdSegment(); return }
        if (!ready || recording != null || !active.get()) return
        if (ringDir.usableSpace < 150L * 1024 * 1024) {
            looping = false
            if (capturing) { captureUntil = SystemClock.elapsedRealtime(); finishJoin() }
            onError("空间不足，已停止缓存，请处理本地片段")
            return
        }
        val file = File(ringDir, "seg-${System.nanoTime()}.mp4")
        var segmentStart = SystemClock.elapsedRealtime()
        try {
            val options = FileOutputOptions.Builder(file).setDurationLimitMillis(5000)
                .setFileSizeLimit(6L * 1024 * 1024).build()
            recording = recorder.prepareRecording(context, options).start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> segmentStart = SystemClock.elapsedRealtime()
                    is VideoRecordEvent.Status -> {
                        if (capturing && !joining) {
                            val now = SystemClock.elapsedRealtime()
                            captureOnSeconds(((now - captureStartedAt) / 1000).toInt())
                            val bytes = ring.filter { it.endMs > captureFrom }.sumOf { it.file.length() } + event.recordingStats.numBytesRecorded
                            if (now >= captureDeadline || bytes > 42L * 1024 * 1024) stopRecording()
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        val duration = event.recordingStats.recordedDurationNanos / 1_000_000
                        val usable = event.error in setOf(VideoRecordEvent.Finalize.ERROR_NONE,
                            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
                            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
                            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) && file.length() > 0 && duration > 0
                        onSegmentFinal(file, segmentStart, duration, usable)
                    }
                }
            }
        } catch (error: Exception) {
            recording = null; looping = false
            if (capturing) { captureUntil = SystemClock.elapsedRealtime(); finishJoin() }
            onError("无法录像：${error.message}")
        }
    }

    private fun finishJoin() {
        if (joining) return
        val dest = captureDest ?: return
        joining = true
        val parts = ring.filter { it.endMs > captureFrom && it.startMs < captureUntil }
        exporting.addAll(parts.map { it.file })
        val from = captureFrom
        val until = captureUntil
        Thread {
            var failure: String? = null
            var timing: ClipTiming? = null
            try { timing = Mp4Join.window(parts, dest, from, until) }
            catch (error: Exception) {
                // Failed exports keep source evidence for recovery instead of deleting it.
                val recovery = File(context.filesDir, "recovery/${dest.nameWithoutExtension}").apply { mkdirs() }
                parts.forEach { part -> try { part.file.copyTo(File(recovery, part.file.name), overwrite = true) } catch (_: Exception) { } }
                failure = "导出失败，缓存已保留：${error.message}"
            }
            ContextCompat.getMainExecutor(context).execute {
                lastClipTiming = timing
                exporting.removeAll(parts.map { it.file }.toSet())
                capturing = false; joining = false; finishRequested = false; captureDest = null
                val done = captureOnComplete
                captureOnComplete = { _, _ -> }
                done(if (failure == null) dest else null, failure)
                evict()
                if (!active.get()) clearRing()
            }
        }.apply { name = "evidence-export" }.start()
    }

    fun release() {
        if (!active.getAndSet(false)) return
        looping = false; ready = false
        if (capturing) stopRecording() else if (tierId == "1440") qhd?.stopSegment() else recording?.stop()
        closeQhd()
        analysis.clearAnalyzer()
        cameraInfo?.cameraState?.removeObservers(owner)
        provider?.unbind(cameraPreview, analysis, video)
        overlay.vehicles = emptyList(); overlay.lamps = emptyList(); overlay.plates = emptyList()
        if (!capturing && recording == null) clearRing()
    }

    private fun clearRing() {
        ring.removeAll { part -> if (part.file in exporting) false else { part.file.delete(); true } }
    }

    private fun evict() {
        if (!active.get() && !capturing) { clearRing(); return }
        val cutoff = if (capturing && !joining) captureFrom else SystemClock.elapsedRealtime() - 20_000
        ring.removeAll { part -> if (part.endMs < cutoff && part.file !in exporting) { part.file.delete(); true } else false }
    }
}
