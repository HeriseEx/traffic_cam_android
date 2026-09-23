package com.example.illegalcapture

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class SyncState(val connected: Boolean = false, val message: String = "服务器未连接", val records: List<JSONObject> = emptyList())

class TaskSync private constructor(private val context: Context) {
    private val directory = File(context.filesDir, "backend").apply { mkdirs() }
    val clips = File(context.filesDir, "clips").apply { mkdirs() }
    val connection = ServerConnection(context)
    private val saved = AtomicFile(File(directory, "events.json"))
    private val mutex = Mutex()
    private var database = try { JSONObject(saved.openRead().use { it.readBytes().toString(Charsets.UTF_8) }) } catch (_: Exception) {
        JSONObject().put("records", JSONObject()).put("cursors", JSONObject())
    }
    private val mutable = MutableStateFlow(SyncState(records = records()))
    val state = mutable.asStateFlow()
    private fun records(): List<JSONObject> {
        val all = database.getJSONObject("records")
        return all.keys().asSequence().map { all.getJSONObject(it) }.sortedByDescending { it.optDouble("created_at", 0.0) }.toList()
    }
    private fun publish(connected: Boolean = mutable.value.connected, message: String = mutable.value.message) {
        mutable.value = SyncState(connected, message, records())
    }
    private fun rememberRemote(task: JSONObject) {
        val all = database.getJSONObject("records")
        val previous = all.optJSONObject(task.getString("event_id"))
        val capture = previous?.optJSONObject("mobile_capture") ?: previous?.optJSONObject("metadata")?.optJSONObject("capture")
        if (capture != null) task.put("mobile_capture", capture)
        // A response lost after acceptance is recovered through /changes; clean up only after a matching checksum.
        val local = previous?.optString("local_file")?.takeIf { it.isNotBlank() }
        if (local != null && task.optString("sha256")==previous.optString("sha256")) task.put("local_file", local)
        all.put(task.getString("event_id"), task)
    }
    private fun persist() {
        val stream = saved.startWrite()
        try { stream.write(database.toString().toByteArray(Charsets.UTF_8)); saved.finishWrite(stream) }
        catch (error: Exception) { saved.failWrite(stream); throw error }
    }
    fun configured() = connection.load().first.isNotBlank()
    fun needsBackgroundWork() = configured() && state.value.records.any {
        it.optString("endpoint") in setOf("", connection.load().first.trimEnd('/')) &&
            it.optString("status") in setOf("PENDING_UPLOAD", "QUEUED", "PROCESSING")
    }
    @Synchronized fun handshake() {
        val (endpoint, token) = connection.load()
        if (endpoint.isBlank() || token.isNotBlank()) return
        val ts = System.currentTimeMillis() / 1000
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val device = connection.deviceId()
        val platform = "android"
        val model = ServerConnection.deviceModel()
        val code = ServerConnection.helloCode(device, platform, ts, nonce)
        val out = BackendClient(endpoint, "").request("POST", "/v1/hello", JSONObject()
            .put("device_id", device).put("platform", platform).put("model", model)
            .put("app_version", "2.0").put("ts", ts).put("nonce", nonce).put("code", code))
        val session = out.getString("session")
        require(session.isNotBlank()) { "握手未返回会话" }
        connection.save(endpoint, session)
    }
    fun client(): BackendClient {
        handshake()
        val (endpoint, token) = connection.load()
        return BackendClient(endpoint, token)
    }

    private val maxUploadBytes = 50L * 1024 * 1024

    private fun copyLimited(open: () -> InputStream, target: File) {
        require(clips.usableSpace > 150L * 1024 * 1024) { "可用空间不足，请先处理待上传片段" }
        open().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= maxUploadBytes) { "视频超过 50 MiB" }
                    output.write(buffer, 0, n)
                }
                output.fd.sync()
            }
        }
    }

    suspend fun importVideo(open: () -> InputStream): JSONObject = withContext(Dispatchers.IO) {
        val target = File(clips, "${UUID.randomUUID()}.mp4")
        try { copyLimited(open, target) } catch (error: Exception) { target.delete(); throw error }
        // Once copying succeeds, a cancellation during enqueue must not delete
        // a file whose queue entry may already have been committed.
        enqueue(target, "import")
    }

    suspend fun simulateRecording(open: () -> InputStream): JSONObject = withContext(Dispatchers.IO) {
        error("模拟录像需走本地缓存检索后按违法时段裁剪，请使用已保存的录像文件")
    }

    suspend fun simulateIncidents(file: File, detector: VehicleDetector, onProgress: ((Long, Long) -> Unit)? = null): Int = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() > 0L) { "录像文件不存在" }
        require(configured()) { "服务器未连接，无法确认车牌" }
        val windows = RecordingCutter.scan(file, detector, { client().recognizeFrame(it) }, onProgress)
        if (windows.isEmpty()) error("未发现持续的疑似动作，未生成片段")
        var count = 0
        for (window in windows) {
            val target = File(clips, "${UUID.randomUUID()}.mp4")
            try {
                val timing = Mp4Join.window(listOf(VideoSegment(file, 0, Mp4Join.durationMs(file))), target, window.startMs, window.endMs)
                require(target.length() in 1..maxUploadBytes) { "裁剪片段超过 50 MiB" }
                val event = window.event
                val capture = JSONObject().put("camera_mode", "moving").put("captured_at", file.lastModified() / 1000.0)
                    .put("duration_ms", timing.endMs - timing.startMs).put("recording_gaps_ms", 0)
                    .put("incidents", JSONArray().put(JSONObject().put("track_id", event.trackId).put("kind", event.kind)
                        .put("start_ms", (event.startAt - timing.startMs).coerceIn(0, timing.endMs - timing.startMs))
                        .put("end_ms", (event.endAt - timing.startMs).coerceIn(0, timing.endMs - timing.startMs))
                        .put("plate", event.plate ?: JSONObject.NULL).put("plate_confirmed", event.plateConfirmed)))
                enqueue(target, "automatic", "录像回放提取 ${window.plate}", window.type, capture)
                count++
            } catch (error: Exception) { throw error } // Finalized evidence remains recoverable if enqueue fails.
        }
        count
    }

    suspend fun enqueue(file: File, trigger: String, text: String = "", candidateType: String = "UNKNOWN", capture: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(file.length() in 1..maxUploadBytes) { "视频为空或超过 50 MiB" }
            val existing = records().firstOrNull { it.optString("local_file") == file.name }
            if (existing != null) return@withLock existing
            val id = try { UUID.fromString(file.nameWithoutExtension).toString() } catch (_: Exception) { UUID.randomUUID().toString() }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            val credentials = connection.load()
            val endpoint = if (credentials.first.isBlank()) "" else credentials.first.trimEnd('/')
            var metadata = JSONObject().put("event_id", id).put("candidate_type", candidateType)
                .put("manual_review", false)
                .put("trigger", trigger).put("trigger_text", text.take(160)).put("app_version", "2.0").put("model_version", "efficientdet-lite0")
            if (capture != null) metadata.put("capture", capture)
            val journal = AtomicFile(File(clips, file.name + ".event.json"))
            var savedEndpoint = endpoint
            if (journal.baseFile.isFile) {
                val prior = JSONObject(journal.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                metadata = prior.getJSONObject("metadata"); savedEndpoint = prior.optString("endpoint", endpoint)
            } else {
                val stream = journal.startWrite()
                try { stream.write(JSONObject().put("metadata", metadata).put("endpoint", endpoint).toString().toByteArray()); journal.finishWrite(stream) }
                catch (error: Exception) { journal.failWrite(stream); throw error }
            }
            val pending = JSONObject().put("event_id", metadata.getString("event_id")).put("status", "PENDING_UPLOAD").put("metadata", metadata)
                .put("local_file", file.name).put("endpoint", savedEndpoint).put("created_at", System.currentTimeMillis() / 1000.0)
                .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
            database.getJSONObject("records").put(pending.getString("event_id"), pending); persist(); publish(message = "片段已保存，等待自动上传")
            UploadJobService.schedule(context)
            pending
        }
    }

    suspend fun retryPending() = withContext(Dispatchers.IO) {
        mutex.withLock {
            records().filter { it.optString("status") in setOf("UPLOAD_ERROR", "PENDING_UPLOAD") }.forEach {
                it.put("status", "PENDING_UPLOAD").put("next_retry_at", 0).put("retry_count", 0).remove("error")
            }
            persist(); publish(message="已安排重新上传")
        }
        tick()
    }

    suspend fun discardLocal(eventId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val row = database.getJSONObject("records").optJSONObject(eventId) ?: return@withLock
            require(row.optString("status") in setOf("PENDING_UPLOAD", "UPLOAD_ERROR", "LOCAL_ERROR")) { "仅可删除未上传的本地片段" }
            val name = File(row.getString("local_file")).name
            val video = File(clips, name)
            require(!video.exists() || video.delete()) { "无法删除本地文件" }
            File(clips, name + ".event.json").delete()
            database.getJSONObject("records").remove(eventId); persist(); publish(message="已删除本地片段")
        }
    }

    suspend fun tick() = withContext(Dispatchers.IO) {
        // Only complete, atomically renamed exports are *.mp4; interrupted muxing remains *.partial.
        clips.listFiles()?.filter { it.extension == "mp4" && System.currentTimeMillis() - it.lastModified() > 30_000 }?.forEach { file ->
            val known = mutex.withLock { records().any { it.optString("local_file") == file.name || it.optString("event_id") == file.nameWithoutExtension } }
            if (!known) try { enqueue(file, "import", "恢复上次已保存的片段") } catch (_: Exception) { }
        }
        if (!mutex.tryLock()) return@withContext
        try {
            if (!configured()) { publish(false, "服务器未配置 · 片段保存在手机"); return@withContext }
            val api = client()
            val all = database.getJSONObject("records")
            val pending = records().lastOrNull { it.optString("status") == "PENDING_UPLOAD" && it.optString("endpoint") in setOf("", api.endpoint) && it.optLong("next_retry_at") <= System.currentTimeMillis() }
            if (pending != null) {
                val file = File(clips, File(pending.getString("local_file")).name)
                if (!file.isFile) {
                    pending.put("status", "LOCAL_ERROR").put("error", "待上传视频文件不存在"); persist()
                } else {
                    pending.put("endpoint", api.endpoint); persist()
                    publish(message = "正在上传重点片段")
                    try {
                        val metadata = pending.optJSONObject("wire_metadata") ?: JSONObject(pending.getJSONObject("metadata").toString()).also {
                            if (it.has("capture") && !api.request("GET", "/health").optBoolean("capture_metadata")) it.remove("capture")
                            // Freeze the wire contract before sending; retries must keep identical metadata, including across server upgrades.
                            pending.put("wire_metadata", it); persist()
                        }
                        val task = api.upload(file, metadata, pending.getString("sha256")).put("endpoint", api.endpoint)
                        require(task.optString("event_id") == pending.getString("event_id") && task.optString("sha256") == pending.getString("sha256")) { "服务器接收确认不匹配" }
                        rememberRemote(task); persist()
                        File(clips, file.name + ".event.json").delete(); file.delete()
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        val message = error.message.orEmpty()
                        val permanent = listOf("HTTP 400", "HTTP 409", "HTTP 413", "HTTP 415", "HTTP 422").any { message.startsWith(it) }
                        val attempts = pending.optInt("retry_count") + 1
                        pending.put("status", if (permanent) "UPLOAD_ERROR" else "PENDING_UPLOAD")
                            .put("retry_count", attempts).put("error", message.take(240))
                            .put("next_retry_at", System.currentTimeMillis() + minOf(300_000L, 3000L * (1L shl attempts.coerceAtMost(7))))
                        persist()
                        if (message.startsWith("HTTP 401")) throw error
                    }
                }
            }
            val cursors = database.getJSONObject("cursors")
            var more: Boolean
            var pages = 0
            do {
                val response = api.request("GET", "/v1/changes?after=${cursors.optLong(api.endpoint, 0)}")
                val tasks = response.getJSONArray("tasks")
                for (index in 0 until tasks.length()) {
                    val task = tasks.getJSONObject(index).put("endpoint", api.endpoint)
                    rememberRemote(task)
                }
                // Store changes and cursor together, so interruption cannot skip a human correction.
                cursors.put(api.endpoint, response.getLong("cursor")); persist()
                more = response.optBoolean("has_more"); pages++
            } while (more && pages < 5)
            val listed = api.request("GET", "/v1/tasks?limit=100").optJSONArray("tasks") ?: JSONArray()
            for (index in 0 until listed.length()) {
                val task = listed.getJSONObject(index).put("endpoint", api.endpoint)
                rememberRemote(task)
            }
            persist()
            records().filter { it.optString("status") !in setOf("PENDING_UPLOAD", "UPLOAD_ERROR", "LOCAL_ERROR") }.forEach { row ->
                row.optString("local_file").takeIf { it.isNotBlank() }?.let { name ->
                    File(clips, File(name).name).delete(); File(clips, File(name).name + ".event.json").delete()
                }
            }
            publish(true, "服务器已连接 · 结果自动同步")
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if ((error.message ?: "").contains("HTTP 401")) {
                val (endpoint, _) = connection.load()
                if (endpoint.isNotBlank()) try {
                    connection.save(endpoint, "")
                    handshake()
                } catch (_: Exception) { }
            }
            publish(false, "同步失败，稍后自动重试：${error.message}")
        } finally {
            mutex.unlock()
            if (needsBackgroundWork()) UploadJobService.schedule(context)
        }
    }

    companion object {
        @Volatile private var instance: TaskSync? = null
        fun get(context: Context): TaskSync = instance ?: synchronized(this) {
            instance ?: TaskSync(context.applicationContext).also { instance = it }
        }
    }
}
