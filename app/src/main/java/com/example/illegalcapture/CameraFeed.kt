package com.example.illegalcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.widget.FrameLayout
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraInfo
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
) : FrameLayout(context) {
    private val preview = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FIT_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
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
    private val recorder = Recorder.Builder()
        .setAspectRatio(AspectRatio.RATIO_16_9)
        .setTargetVideoEncodingBitRate(4_000_000)
        .setQualitySelector(
            QualitySelector.fromOrderedList(
                listOf(Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
            )
        ).build()
    private val video = VideoCapture.withOutput(recorder)
    private var recording: Recording? = null
    private var ready = false
    private var lastFrame = 0L
    private var lastJpeg = 0L
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
        addView(overlay, LayoutParams(-1, -1))
        post { start() }
    }

    private fun start() {
        if (!active.get()) return
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
                            val detected = detector.detect(bitmap, threshold())
                            if (now - lastJpeg >= 1500) {
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
                video.targetRotation = rotation
                cameraInfo?.cameraState?.removeObservers(owner)
                cameraProvider.unbind(cameraPreview, analysis, video)
                val camera = cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA,
                    UseCaseGroup.Builder().setViewPort(viewPort)
                        .addUseCase(cameraPreview).addUseCase(analysis).addUseCase(video).build())
                cameraInfo = camera.cameraInfo
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

    fun record(file: File, seconds: Int, onSeconds: (Int) -> Unit, onComplete: (File?, String?) -> Unit,
        startAtMs: Long = SystemClock.elapsedRealtime() - 10_000): Boolean {
        if (!ready || capturing || joining || !active.get()) return false
        capturing = true; finishRequested = false
        captureDest = file; captureOnSeconds = onSeconds; captureOnComplete = onComplete
        captureStartedAt = SystemClock.elapsedRealtime()
        captureFrom = startAtMs.coerceAtLeast(captureStartedAt - 20_000)
        captureDeadline = minOf(captureStartedAt + seconds.coerceIn(3, 65) * 1000, captureFrom + 80_000)
        captureUntil = Long.MAX_VALUE
        lastClipTiming = null
        if (recording == null) beginSegment()
        return true
    }

    fun stopRecording() {
        if (!capturing || joining || finishRequested) return
        finishRequested = true
        captureUntil = SystemClock.elapsedRealtime()
        recording?.stop() ?: finishJoin()
    }

    fun pauseLoop() {
        looping = false
        if (capturing) stopRecording() else recording?.stop()
    }

    fun resumeLoop() {
        if (!active.get() || !ready) return
        looping = true
        if (recording == null) beginSegment()
    }

    private fun beginSegment() {
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
        if (capturing) stopRecording() else recording?.stop()
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
