package com.example.illegalcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * CameraX Recorder 1.4 has no 1440p quality. This session is used only for that tier.
 * ponytail: one back camera, preview 720p, record 1440p. Upgrade path is CameraX when it grows a QHD quality.
 */
class QhdCamera(
    private val context: Context,
    private val texture: TextureView,
    private val onFrame: (Bitmap) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val thread = HandlerThread("qhd-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var preview: Surface? = null
    private var sensor = 0
    private var cameraId: String? = null
    private var lastFrame = 0L
    private var segmentDone: ((Boolean, Long) -> Unit)? = null
    var segmentStartedAt = 0L
        private set

    fun open(onReady: () -> Unit) {
        val id = backId() ?: return onError("这台手机不能录 1440p")
        cameraId = id
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        sensor = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        if (texture.isAvailable) openDevice(manager, id, onReady)
        else texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openDevice(manager, id, onReady)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = orientPreview()
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    fun startSegment(file: File, withAudio: Boolean, onDone: (Boolean, Long) -> Unit): Boolean {
        val camera = device ?: return false
        if (recorder != null) return false
        val next = mediaRecorder(file, withAudio) ?: return false
        recorder = next
        segmentDone = onDone
        val recorderSurface = next.surface
        val previewSurface = preview ?: return false
        val analysis = reader?.surface ?: return false
        try {
            camera.createCaptureSession(listOf(previewSurface, analysis, recorderSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(capture: CameraCaptureSession) {
                    session?.close()
                    session = capture
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(previewSurface)
                        addTarget(analysis)
                        addTarget(recorderSurface)
                    }.build()
                    capture.setRepeatingRequest(request, null, handler)
                    next.setOnInfoListener { _, what, _ ->
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finishSegment()
                    }
                    next.start()
                    segmentStartedAt = android.os.SystemClock.elapsedRealtime()
                }
                override fun onConfigureFailed(capture: CameraCaptureSession) {
                    onError("1440p 录像会话失败")
                    val done = segmentDone
                    segmentDone = null
                    recorder = null
                    done?.let { callback -> android.os.Handler(context.mainLooper).post { callback(false, 0) } }
                }
            }, handler)
        } catch (error: Exception) {
            onError("无法开始 1440p：${error.message}")
            return false
        }
        return true
    }

    fun stopSegment() {
        handler.postAtFrontOfQueue { finishSegment() }
    }

    fun close() {
        handler.post {
            finishSegment()
            session?.close()
            session = null
            device?.close()
            device = null
            reader?.close()
            reader = null
            preview?.release()
            preview = null
            thread.quitSafely()
        }
    }

    private fun finishSegment() {
        val rec: MediaRecorder?
        val started: Long
        val done: ((Boolean, Long) -> Unit)?
        synchronized(this) {
            rec = recorder
            recorder = null
            started = segmentStartedAt
            segmentStartedAt = 0L
            done = segmentDone
            segmentDone = null
        }
        if (rec == null) return
        var ok = false
        if (started == 0L) {
            ok = false
        } else try {
            rec.stop()
            ok = true
        } catch (_: Exception) {
            ok = false
        }
        try { rec.reset(); rec.release() } catch (_: Exception) { }
        val duration = if (started == 0L) 0L else android.os.SystemClock.elapsedRealtime() - started
        done?.let { callback ->
            android.os.Handler(context.mainLooper).post { callback(ok && duration > 0, duration) }
        }
    }

    private fun openDevice(manager: CameraManager, id: String, onReady: () -> Unit) {
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    prepareReader()
                    orientPreview()
                    val surfaceTexture = texture.surfaceTexture ?: return
                    surfaceTexture.setDefaultBufferSize(1280, 720)
                    preview = Surface(surfaceTexture)
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(preview!!)
                        reader?.surface?.let { addTarget(it) }
                    }.build()
                    val surfaces = listOfNotNull(preview, reader?.surface)
                    camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(capture: CameraCaptureSession) {
                            session = capture
                            capture.setRepeatingRequest(request, null, handler)
                            android.os.Handler(context.mainLooper).post(onReady)
                        }
                        override fun onConfigureFailed(capture: CameraCaptureSession) {
                            onError("1440p 预览失败")
                        }
                    }, handler)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    onError("1440p 相机错误 $error")
                }
            }, handler)
        } catch (error: Exception) {
            onError("无法打开 1440p 相机：${error.message}")
        }
    }

    private fun prepareReader() {
        reader?.close()
        reader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ images ->
                val now = android.os.SystemClock.elapsedRealtime()
                val image = images.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (now - lastFrame < 125) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                lastFrame = now
                val bitmap = try { toBitmap(image) } catch (_: Exception) { null }
                image.close()
                if (bitmap != null) onFrame(bitmap)
            }, handler)
        }
    }

    private fun orientPreview() {
        if (!texture.isAvailable || texture.width == 0) return
        val matrix = Matrix()
        val view = RectF(0f, 0f, texture.width.toFloat(), texture.height.toFloat())
        val buffer = if (sensor % 180 == 0) RectF(0f, 0f, 1280f, 720f) else RectF(0f, 0f, 720f, 1280f)
        val centerX = view.centerX()
        val centerY = view.centerY()
        buffer.offset(centerX - buffer.centerX(), centerY - buffer.centerY())
        matrix.setRectToRect(view, buffer, Matrix.ScaleToFit.FILL)
        matrix.postRotate((sensor - displayRotation()).toFloat(), centerX, centerY)
        texture.setTransform(matrix)
    }

    private fun displayRotation(): Int = when (texture.display?.rotation) {
        android.view.Surface.ROTATION_90 -> 90
        android.view.Surface.ROTATION_180 -> 180
        android.view.Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun mediaRecorder(file: File, withAudio: Boolean): MediaRecorder? {
        return try {
            @Suppress("DEPRECATION")
            MediaRecorder().apply {
                if (withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                if (withAudio) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(2560, 1440)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(RecordingTier.bitrate(RecordingTier.byId("1440")).toInt())
                setMaxDuration(if (withAudio) 65_000 else 5_000)
                setOrientationHint(sensor)
                setOutputFile(file.absolutePath)
                prepare()
            }
        } catch (error: Exception) {
            onError("1440p 编码器不可用：${error.message}")
            null
        }
    }

    companion object {
        fun supported(context: Context): Boolean {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            return manager.cameraIdList.any { id ->
                val chars = manager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@any false
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@any false
                map.getOutputSizes(MediaRecorder::class.java).orEmpty().any { it.width == 2560 && it.height == 1440 }
                    || (android.os.Build.VERSION.SDK_INT >= 30 && id.toIntOrNull()?.let {
                        android.media.CamcorderProfile.hasProfile(it, android.media.CamcorderProfile.QUALITY_QHD)
                    } == true)
            }
        }
    }

    private fun backId(): String? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return manager.cameraIdList.firstOrNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@firstOrNull false
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@firstOrNull false
            map.getOutputSizes(MediaRecorder::class.java).orEmpty().any { it.width == 2560 && it.height == 1440 }
                || (android.os.Build.VERSION.SDK_INT >= 30 && id.toIntOrNull()?.let {
                    android.media.CamcorderProfile.hasProfile(it, android.media.CamcorderProfile.QUALITY_QHD)
                } == true)
        }
    }

    private fun toBitmap(image: Image): Bitmap? {
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        copyLuma(image, nv21)
        val jpeg = ByteArrayOutputStream()
        android.graphics.YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), 80, jpeg)
        val raw = BitmapFactory.decodeByteArray(jpeg.toByteArray(), 0, jpeg.size()) ?: return null
        if (sensor == 0) return raw
        val turned = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(sensor.toFloat()) }, true)
        if (turned !== raw) raw.recycle()
        return turned
    }

    private fun copyLuma(image: Image, nv21: ByteArray) {
        val width = image.width
        val height = image.height
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        var pos = 0
        for (row in 0 until height) {
            var offset = row * y.rowStride
            for (col in 0 until width) {
                nv21[pos++] = y.buffer.get(offset)
                offset += y.pixelStride
            }
        }
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            var uOffset = row * u.rowStride
            var vOffset = row * v.rowStride
            for (col in 0 until chromaWidth) {
                nv21[pos++] = v.buffer.get(vOffset)
                nv21[pos++] = u.buffer.get(uOffset)
                uOffset += max(1, u.pixelStride)
                vOffset += max(1, v.pixelStride)
            }
        }
    }
}
