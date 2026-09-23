package com.example.illegalcapture

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** ADB/local service credentials, encrypted with a device-bound Android Keystore key. */
class ServerConnection(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "server-connection.json"))
    private val off = File(context.noBackupFilesDir, "server-disconnected")
    private val deviceFile = File(context.noBackupFilesDir, "device-id")
    private val alias = "traffic-server-token-v1"
    @Synchronized fun deviceId(): String {
        if (deviceFile.isFile) {
            val id = deviceFile.readText().trim()
            if (id.isNotEmpty()) return id
        }
        val id = UUID.randomUUID().toString()
        deviceFile.writeText(id)
        return id
    }
    @Synchronized fun account(): String {
        if (off.exists() || !file.baseFile.exists()) return ""
        return try {
            JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }).optString("account")
        } catch (_: Exception) { "" }
    }
    @Synchronized fun load(): Pair<String, String> {
        if (off.exists()) return "" to ""
        if (!file.baseFile.exists()) return REMOTE to ""
        return try {
            val json = JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            val endpoint = json.optString("endpoint")
            if (endpoint.isBlank()) return "" to ""
            if (!json.has("iv") || json.optString("token").isEmpty()) return endpoint to ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, android.util.Base64.decode(json.getString("iv"), 0)))
            endpoint to cipher.doFinal(android.util.Base64.decode(json.getString("token"), 0)).toString(Charsets.UTF_8)
        } catch (_: Exception) { REMOTE to "" }
    }
    @Synchronized fun save(endpoint: String, token: String, account: String? = null) {
        BackendClient(endpoint, token)
        off.delete()
        val kept = when {
            account != null -> account.trim()
            token.isBlank() -> ""
            else -> this.account()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val value = JSONObject().put("endpoint", endpoint.trim().trimEnd('/'))
            .put("account", kept)
            .put("iv", android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
            .put("token", android.util.Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP))
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    @Synchronized fun clear() {
        file.delete()
        off.writeText("1")
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    companion object {
        const val REMOTE = "https://traffic.muqin.ccwu.cc"
        const val LOCAL = "http://127.0.0.1:61616"
        const val HELLO_SALT = "traffic-hello-v1"
        fun helloCode(deviceId: String, platform: String, ts: Long, nonce: String): String {
            val raw = "$deviceId\n$platform\n$ts\n$nonce\n$HELLO_SALT"
            return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(120).ifBlank { "android" }
    }
}
