package com.example.illegalcapture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.illegalcapture.ui.theme.IllegalCaptureTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class BackendActivity : ComponentActivity() {
    private val sync by lazy { TaskSync.get(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { IllegalCaptureTheme(darkTheme = true, dynamicColor = false) { Screen() } }
        if (intent.getBooleanExtra("simulate_recording", false)) {
            lifecycleScope.launch {
                val resultFile = java.io.File(filesDir, "simulate-result.json")
                try {
                    sync.connection.save(ServerConnection.LOCAL, "")
                    val file = java.io.File(getExternalFilesDir(null), "test.mp4")
                    require(file.isFile && file.length() > 0L) { "test.mp4 不存在" }
                    val detector = VehicleDetector(applicationContext)
                    try {
                        val n = sync.simulateIncidents(file, detector) { at, total ->
                            resultFile.writeText(org.json.JSONObject().put("ok", false).put("scanning_ms", at).put("duration_ms", total).toString())
                        }
                        sync.tick()
                        resultFile.writeText(org.json.JSONObject().put("ok", true).put("clips", n).toString())
                    } finally { detector.close() }
                } catch (error: Exception) {
                    android.util.Log.e("IllegalCapture", "simulateRecording failed", error)
                    resultFile.writeText(org.json.JSONObject().put("ok", false).put("error", error.message ?: "unknown").toString())
                }
            }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { sync.tick(); delay(3000) }
        } }
    }
    @Composable private fun Screen() {
        val saved = remember { sync.connection.load() }
        var endpoint by remember { mutableStateOf(saved.first.ifBlank { ServerConnection.REMOTE }) }
        var message by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var preview by remember { mutableStateOf<java.io.File?>(null) }
        var deleteTarget by remember { mutableStateOf<String?>(null) }
        var noteTarget by remember { mutableStateOf<String?>(null) }
        var noteText by remember { mutableStateOf("") }
        val state by sync.state.collectAsState()
        fun action(block: suspend () -> String) {
            lifecycleScope.launch {
                busy = true
                try { message = block() } catch (e: Exception) { message = "操作失败：${e.message}" }
                finally { busy = false }
            }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) action { sync.importVideo { requireNotNull(contentResolver.openInputStream(uri)) }; sync.tick(); "视频已加入自动处理队列" }
        }
        val recordPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) action {
                val detector = VehicleDetector(this@BackendActivity)
                try {
                    val copied = java.io.File(cacheDir, "${java.util.UUID.randomUUID()}.mp4")
                    val n = try {
                        withContext(Dispatchers.IO) {
                            requireNotNull(contentResolver.openInputStream(uri)).use { input -> copied.outputStream().use { output ->
                                val bytes = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = input.read(bytes); if (count < 0) break
                                    total += count
                                    require(total < 500L * 1024 * 1024 && copied.usableSpace > 150L * 1024 * 1024) { "录像过大或可用空间不足" }
                                    output.write(bytes, 0, count)
                                }
                            } }
                        }
                        sync.simulateIncidents(copied, detector)
                    } finally { copied.delete() }
                    sync.tick()
                    "已按车牌+违法时段裁剪 $n 段并上传"
                } finally { detector.close() }
            }
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                    Text("取证记录", style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick={finish()}) { Text("返回监看") }
                }
                val pendingCount = state.records.count { it.optString("status") == "PENDING_UPLOAD" }
                val failedCount = state.records.count { it.optString("status") in setOf("UPLOAD_ERROR", "LOCAL_ERROR", "ERROR") }
                Text("待上传 $pendingCount · 需处理 $failedCount · 共 ${state.records.size} 条")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(endpoint, { endpoint = it }, label = { Text("服务地址") }, singleLine = true,
                        enabled = !busy, modifier = Modifier.weight(1f))
                    OutlinedButton(enabled = !busy, onClick = { endpoint = ServerConnection.LOCAL }) { Text("本机") }
                    OutlinedButton(enabled = !busy, onClick = { endpoint = ServerConnection.REMOTE }) { Text("重置") }
                }
                Text("先保存地址，再用取证账户登录。管理员账户不能上传。邀请链接里带有服务地址，30 分钟、只能用一次。", style = MaterialTheme.typography.bodySmall)
                var accountName by remember { mutableStateOf("") }
                var accountPassword by remember { mutableStateOf("") }
                var inviteToken by remember { mutableStateOf("") }
                OutlinedTextField(accountName, { accountName = it }, label = { Text("用户名") }, singleLine = true, enabled = !busy)
                OutlinedTextField(accountPassword, { accountPassword = it }, label = { Text("密码") }, singleLine = true, enabled = !busy, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && accountName.isNotBlank() && accountPassword.length >= 8, onClick = { action {
                        val name = withContext(Dispatchers.IO) { sync.loginAccount(accountName.trim(), accountPassword) }
                        "已登录 $name"
                    } }) { Text("登录") }
                    OutlinedTextField(inviteToken, { inviteToken = it }, label = { Text("邀请链接") }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                    OutlinedButton(enabled = !busy && inviteToken.isNotBlank() && accountName.isNotBlank() && accountPassword.length >= 8, onClick = { action {
                        val (origin, token) = splitInvite(inviteToken)
                        if (origin.isNotEmpty()) endpoint = origin
                        val name = withContext(Dispatchers.IO) {
                            if (origin.isNotEmpty()) sync.connection.save(origin, "")
                            sync.registerAccount(token, accountName.trim(), accountPassword)
                        }
                        "已注册 $name"
                    } }) { Text("注册") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { action {
                        withContext(Dispatchers.IO) {
                            val now = endpoint.trim().trimEnd('/')
                            val (old, session) = sync.connection.load()
                            sync.connection.save(now, if (old == now) session else "")
                            sync.client().request("GET", "/v1/settings")
                        }; sync.tick(); "连接成功"
                    } }) { Text("保存并连接") }
                    OutlinedButton(enabled = !busy, onClick = { action {
                        withContext(Dispatchers.IO) { sync.connection.clear() }; sync.tick(); "已断开，片段仍保存在手机"
                    } }) { Text("断开") }
                }
                Text(message.ifBlank { state.message })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(enabled=!busy, onClick={action { sync.retryPending(); "已重试待上传片段并刷新记录" }}) { Text("立即同步 / 重试上传") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { picker.launch("video/*") }) { Text("导入视频") }
                    OutlinedButton(enabled = !busy, onClick = { recordPicker.launch("video/*") }) { Text("按事件提取") }
                    OutlinedButton(enabled = !busy, onClick = { action {
                        sync.importVideo { assets.open("traffic.mp4") }; sync.tick(); "测试视频已上传，服务器自动分析"
                    } }) { Text("上传内置测试视频") }
                }
                Text("业务记录", style = MaterialTheme.typography.titleLarge)
                var filter by remember { mutableStateOf("全部") }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("全部", "待上传", "需处理").forEach { label -> FilterChip(filter==label, {filter=label}, label={Text(label)}) }
                }
                val filtered = state.records.filter { when(filter) {
                    "待上传" -> it.optString("status")=="PENDING_UPLOAD"
                    "需处理" -> it.optString("status") in setOf("UPLOAD_ERROR", "LOCAL_ERROR", "ERROR")
                    else -> true
                } }
                if (filtered.isEmpty()) Text("暂无${if(filter=="全部") "取证" else filter}记录。监看时发现疑似事件会自动保存。",style=MaterialTheme.typography.bodyMedium)
                filtered.take(100).forEach { task ->
                    val result = task.optJSONObject("effective_result") ?: task.optJSONObject("result")
                    val review = task.optJSONObject("review")
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("状态：${taskStatus(task.optString("status"))}", style = MaterialTheme.typography.titleMedium)
                        Text("车牌：${result?.optString("plate")?.takeUnless { it.isBlank() || it == "null" } ?: "未确认"}")
                        Text("信号灯：${signalName(task.optJSONObject("result")?.optJSONObject("signal_state"))}")
                        Text("违法行为：${violationName(result?.optString("violation_type").orEmpty())}")
                        val spoken = task.optJSONObject("result")
                        val transcript = spoken?.optString("transcript")?.takeUnless { it.isBlank() || it == "null" }
                        if (transcript != null) Text("口述原文：$transcript")
                        val matched = spoken?.optString("spoken_type")?.takeUnless { it.isBlank() || it == "null" }
                        if (matched != null) Text("口述匹配：${violationName(matched)}")
                        val capture = task.optJSONObject("metadata")?.optJSONObject("capture") ?: task.optJSONObject("mobile_capture")
                        if (capture != null) {
                            Text("动态片段 ${"%.1f".format(capture.optLong("duration_ms") / 1000f)} 秒 · ${capture.optJSONArray("incidents")?.length() ?: 0} 个动作", style=MaterialTheme.typography.bodySmall)
                            if(capture.optBoolean("prebuffer_truncated")) Text("启动时缓存不足，已保留可用画面", style=MaterialTheme.typography.bodySmall)
                        }
                        val error = task.optString("error").takeUnless { it.isBlank() || it == "null" }
                        if (error != null) Text(error, color=MaterialTheme.colorScheme.error, style=MaterialTheme.typography.bodySmall)
                        if (task.optLong("next_retry_at") > System.currentTimeMillis() && task.optString("status")=="PENDING_UPLOAD")
                            Text("稍后自动重试 · 已尝试 ${task.optInt("retry_count")} 次", style=MaterialTheme.typography.bodySmall)
                        Text(if (review == null) "自动判定" else "人工复核：${reviewName(review.optString("decision"))}")
                        if (review != null) Text(review.optString("note"), style = MaterialTheme.typography.bodySmall)
                        Text("事件：${task.optString("event_id")}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled=!busy, onClick={action {
                                val file = withContext(Dispatchers.IO) {
                                    val local = task.optString("local_file").takeIf { it.isNotBlank() }?.let { java.io.File(sync.clips, java.io.File(it).name) }
                                    if (local?.isFile == true) local else {
                                        val api = sync.client()
                                        require(task.optString("endpoint")==api.endpoint) { "请先连接此记录所属服务器" }
                                        java.io.File(cacheDir, "evidence-preview.mp4").also { api.downloadVideo(task.getString("task_id"), it) }
                                    }
                                }
                                preview=file; "已打开片段"
                            }}) { Text("查看片段") }
                            if (task.optString("status") == "NEED_NOTE")
                                TextButton(enabled=!busy, onClick={noteTarget=task.getString("event_id"); noteText=""}) { Text("填写备注") }
                            if(task.optString("status") in setOf("PENDING_UPLOAD", "UPLOAD_ERROR", "LOCAL_ERROR", "NEED_NOTE"))
                                TextButton(enabled=!busy, onClick={deleteTarget=task.getString("event_id")}) { Text("删除本地片段") }
                        }
                    } }
                }
                OutlinedButton(onClick = { finish() }) { Text("返回车辆识别") }
            }
        }
        preview?.let { file ->
            androidx.compose.ui.window.Dialog(onDismissRequest={preview=null}) {
                Surface(shape=MaterialTheme.shapes.large) {
                    Column(Modifier.padding(12.dp)) {
                        androidx.compose.ui.viewinterop.AndroidView(modifier=Modifier.fillMaxWidth().height(240.dp), factory={context ->
                            android.widget.VideoView(context).apply {
                                setVideoPath(file.absolutePath)
                                setMediaController(android.widget.MediaController(context).also { it.setAnchorView(this) })
                                setOnPreparedListener { start() }
                                setOnErrorListener { _, _, _ -> message="此视频暂时无法播放";preview=null;true }
                            }
                        }, onRelease={it.stopPlayback()})
                        TextButton(onClick={preview=null}) { Text("关闭预览") }
                    }
                }
            }
        }
        deleteTarget?.let { id -> AlertDialog(onDismissRequest={deleteTarget=null}, title={Text("删除本地片段？")},
            text={Text("此片段尚未上传，删除后无法恢复。")}, dismissButton={TextButton(onClick={deleteTarget=null}){Text("保留")}},
            confirmButton={TextButton(onClick={deleteTarget=null;action {sync.discardLocal(id);"已删除"}}){Text("删除")}}) }
        noteTarget?.let { id -> AlertDialog(onDismissRequest={noteTarget=null}, title={Text("没有定位，填写备注后才能上传")},
            text={OutlinedTextField(noteText, {noteText=it}, label={Text("备注")})},
            dismissButton={TextButton(onClick={noteTarget=null}){Text("取消")}},
            confirmButton={TextButton(enabled=noteText.isNotBlank(), onClick={
                val text=noteText; noteTarget=null; action { sync.attachNote(id, text); "已提交备注" }
            }){Text("上传")}}) }
    }
}

private fun splitInvite(raw: String): Pair<String, String> {
    val text = raw.trim()
    if (!text.startsWith("http://") && !text.startsWith("https://")) return "" to text
    val uri = android.net.Uri.parse(text)
    val token = uri.getQueryParameter("token")?.trim().orEmpty()
    require(token.length >= 16) { "邀请链接里没有有效邀请码" }
    val origin = "${uri.scheme}://${uri.authority}".trimEnd('/')
    require(origin.length > 8) { "邀请链接里没有服务地址" }
    return origin to token
}

fun taskStatus(value: String) = when (value) {
    "PENDING_UPLOAD" -> "本地待上传"; "QUEUED" -> "服务器排队中"; "PROCESSING" -> "服务器分析中"
    "ANALYZED" -> "服务器分析完成"; "REJECTED" -> "未检出或视频无效"; "EXPIRED" -> "视频已过期"
    "UPLOAD_ERROR" -> "上传受阻 · 原片已保留"; "NEED_NOTE" -> "缺少定位，需填写备注"
    "ERROR", "LOCAL_ERROR" -> "处理失败"; else -> value
}
fun violationName(value: String) = when (value) {
    "LATERAL_MOVEMENT" -> "横向移动（待复核）"; "SOLID_LINE" -> "压实线"; "WRONG_WAY" -> "逆行"; "RED_LIGHT" -> "闯红灯"
    "EMERGENCY_LANE" -> "侵走高速应急车道"; "NO_SIGNAL" -> "变道不打灯"; "OVERTAKE" -> "越线超车"
    "DANGEROUS_CHANGE" -> "危险变道"; "CUT_IN" -> "加塞"; "ILLEGAL_PARKING" -> "主城区机动车乱停乱放"
    "RESTRICTED_LANE" -> "疑似占用专用车道"; "NONE" -> "未发现违法"; else -> "无法可靠判定"
}
fun signalName(state: JSONObject?): String = signalLabel(state?.optString("color").orEmpty(), state?.optBoolean("stable") == true)
fun signalLabel(color: String, stable: Boolean): String {
    val name = when (color) {
        "RED" -> "红灯"; "GREEN" -> "绿灯"; "YELLOW" -> "黄灯"; "OFF" -> "未见到灯"; else -> "无法确认"
    }
    return if (stable && color in setOf("RED", "GREEN", "YELLOW")) "$name（稳定）" else name
}
fun reviewName(value: String) = when (value) {
    "VALID" -> "人工确认 / 纠正"; "INVALID" -> "AI 判定无效"; "UNCERTAIN" -> "证据不足"; else -> "自动判定"
}
