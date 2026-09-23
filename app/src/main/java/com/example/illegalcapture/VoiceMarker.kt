package com.example.illegalcapture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.concurrent.Executors

/** Foreground-only offline recognition. Audio is neither saved nor sent to a server. */
class VoiceMarker(private val context: Context, private val onState: (String) -> Unit, private val onMark: () -> Unit) : RecognitionListener {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    @Volatile private var active = false
    @Volatile private var closed = false
    private var lastTrigger = -10_000L

    fun start() {
        if (active || closed) return
        active = true
        onState("正在准备离线语音…")
        executor.execute {
            try {
                if (model == null) {
                    val root = File(context.noBackupFilesDir, "model-cn-0.22")
                    val ready = File(root, ".ready")
                    if (!ready.exists()) {
                        copyAssets("model-cn", root)
                        ready.writeText("vosk-small-cn-0.22")
                    }
                    model = Model(root.absolutePath)
                }
                main.post {
                    if (active && !closed && service == null) {
                        try {
                            recognizer = Recognizer(model, 16000f)
                            service = SpeechService(recognizer, 16000f).also { it.startListening(this) }
                            onState("语音已开启 · 说“开始标记”")
                        } catch (error: Exception) { onError(error) }
                    }
                }
            } catch (error: Exception) { main.post { onError(error) } }
        }
    }
    private fun copyAssets(asset: String, destination: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(asset).use { input -> destination.outputStream().use { input.copyTo(it) } }
        } else {
            destination.mkdirs()
            children.forEach { copyAssets("$asset/$it", File(destination, it)) }
        }
    }
    private fun accept(json: String, field: String) {
        if (!active) return
        val text = try { JSONObject(json).optString(field) } catch (_: Exception) { return }
        val now = SystemClock.elapsedRealtime()
        if (matches(text) && now - lastTrigger > 5000) {
            lastTrigger = now
            onState("已听到“开始标记”")
            onMark()
        }
    }
    override fun onPartialResult(hypothesis: String) = accept(hypothesis, "partial")
    override fun onResult(hypothesis: String) = accept(hypothesis, "text")
    override fun onFinalResult(hypothesis: String) = accept(hypothesis, "text")
    override fun onError(exception: Exception) { if (!closed) { stop(); onState("语音不可用：${exception.message}") } }
    override fun onTimeout() { if (active) onState("语音等待中 · 说“开始标记”") }
    fun stop() {
        active = false
        service?.stop(); service?.shutdown(); service = null
        recognizer?.close(); recognizer = null
    }
    fun close() {
        closed = true; stop()
        executor.execute { model?.close(); model = null }
        executor.shutdown()
    }
    companion object {
        fun matches(text: String) = text.replace(Regex("[\\s，。！？,.!?]"), "").contains("开始标记")
    }
}
