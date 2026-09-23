package com.example.illegalcapture

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

class BackendClient(address: String, private val token: String) {
    val endpoint = address.trim().trimEnd('/')
    init {
        val uri = URI(endpoint)
        require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path.orEmpty().isEmpty()) { "请输入服务器根地址" }
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost"))) {
            "远程服务器需要 HTTPS；本机可使用 HTTP"
        }
        require('\n' !in token && '\r' !in token) { "访问令牌不能换行" }
    }

    fun request(method: String, path: String, json: JSONObject? = null): JSONObject =
        connect(method, path, bytes = json?.toString()?.toByteArray(Charsets.UTF_8), contentType = "application/json")

    fun upload(file: File, metadata: JSONObject, hash: String): JSONObject = connect("POST", "/v1/tasks", file,
        headers = mapOf("X-Event-Metadata" to metadata.toString().map {
            if (it.code > 127) "\\u%04x".format(it.code) else it.toString()
        }.joinToString(""), "X-Video-SHA256" to hash))

    fun recognizeFrame(jpeg: ByteArray): JSONObject = connect("POST", "/v1/recognize-frame", bytes = jpeg, contentType = "image/jpeg")

    fun downloadVideo(taskId: String, target: File) {
        java.util.UUID.fromString(taskId)
        val connection = URI("$endpoint/v1/tasks/$taskId/video?original=true").toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000; connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", AGENT)
            connection.setRequestProperty("Authorization", "Bearer $token")
            require(connection.responseCode == 200) { "视频不可用：HTTP ${connection.responseCode}" }
            connection.inputStream.use { input -> target.outputStream().use { output ->
                val bytes = ByteArray(64 * 1024); var size = 0L
                while (true) {
                    val count = input.read(bytes); if (count < 0) break
                    size += count; require(size <= 50L * 1024 * 1024) { "预览视频过大" }
                    output.write(bytes, 0, count)
                }
            } }
        } catch (error: Exception) { target.delete(); throw error }
        finally { connection.disconnect() }
    }

    private fun connect(method: String, path: String, file: File? = null, bytes: ByteArray? = null,
        contentType: String = "video/mp4", headers: Map<String, String> = emptyMap()): JSONObject {
        val connection = URI(endpoint + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 8000
            connection.readTimeout = if (file != null) 600_000 else if (path.contains("recognize-frame")) 20_000 else 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", AGENT)
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (file != null || bytes != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(file?.length() ?: bytes!!.size.toLong())
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { output ->
                    if (file != null) file.inputStream().use { it.copyTo(output, 64 * 1024) }
                    else output.write(bytes!!)
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 16 * 1024 * 1024) { "服务器响应过大" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code：${body.take(180)}")
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

    companion object {
        // Cloudflare 1010 bans missing/python signatures; Android already sends Dalvik, keep it explicit.
        private const val AGENT = "Dalvik/2.1.0 (Linux; U; Android 12; IllegalCapture)"
    }
}
