package com.example.illegalcapture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import android.content.res.Configuration
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import android.os.ParcelFileDescriptor
import java.security.MessageDigest
import java.io.File

class VehicleSmokeTest {
    private val ui = createAndroidComposeRule<MainActivity>()
    // MIUI can block an instrumentation process from launching an app in the background.
    // Start through the authorized ADB shell first; no device security settings are changed.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard",
                "am start -W -n ${instrumentation.targetContext.packageName}/.MainActivity")) {
                ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
            }
        }
    }).around(ui)

    @Test fun bundledModelDetectsRealVehiclesAndRejectsBlankImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hash = context.assets.open(VehicleDetector.MODEL).use {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
        assertEquals("2e04c53bfeac0ac2a30c057c7e2a777594ce39baaac35a92f74fb1e8c4fc4e0b", hash)
        VehicleDetector(context).use { detector ->
            val bitmap = context.assets.open("traffic.jpg").use { BitmapFactory.decodeStream(it) }
            val result = detector.detect(bitmap, 0.4f)
            assertTrue("Real traffic photo must contain cars", result.vehicles.any { it.label == "汽车" })
            assertTrue("Real traffic photo must contain trucks", result.vehicles.any { it.label == "卡车" })
            result.vehicles.forEach {
                assertTrue(it.label in VehicleDetector.LABELS.values)
                assertTrue(it.score in 0.4f..1f)
                assertTrue(it.box.left >= 0 && it.box.top >= 0)
                assertTrue(it.box.right <= bitmap.width && it.box.bottom <= bitmap.height)
                assertTrue(it.box.width() > 0 && it.box.height() > 0)
            }
            assertTrue(detector.detect(bitmap, 0.8f).vehicles.size <= result.vehicles.size)
            val times = List(10) { detector.detect(bitmap, 0.4f).elapsedMs }.sorted()
            Log.i("VehicleSmokeTest", "vehicles=${result.vehicles.map { "${it.label}:${it.score}" }} medianMs=${times[5]}")
            bitmap.recycle()
            val blank = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
            blank.eraseColor(Color.BLACK)
            assertTrue(detector.detect(blank, 0.4f).vehicles.isEmpty())
            try {
                detector.detect(blank, Float.NaN)
                fail("Invalid threshold must be rejected")
            } catch (_: IllegalArgumentException) { }
            blank.recycle()
        }
    }

    @Test fun platesAttachToSmallestContainingVehicleBox() {
        val vehicles = listOf(
            Vehicle("汽车", .9f, android.graphics.RectF(0f, 0f, 400f, 400f)),
            Vehicle("汽车", .8f, android.graphics.RectF(100f, 100f, 300f, 300f)),
            Vehicle("卡车", .7f, android.graphics.RectF(600f, 0f, 1000f, 500f)))
        val plates = listOf(
            PlateHit("川A10001", .95f, android.graphics.RectF(.15f, .2f, .25f, .25f)), // 两框嵌套，取小框
            PlateHit("苏ED51712", .90f, android.graphics.RectF(.70f, .30f, .80f, .35f)), // 卡车
            PlateHit("云A99999", .50f, android.graphics.RectF(.45f, .90f, .50f, .95f))) // 不在任何框内
        val labeled = attachPlates(vehicles, plates, 1000, 1000)
        assertNull(labeled[0].plate)
        assertEquals("川A10001", labeled[1].plate)
        assertEquals("苏ED51712", labeled[2].plate)
        assertEquals(vehicles, attachPlates(vehicles, emptyList(), 1000, 1000))
        assertEquals(emptyList<Vehicle>(), attachPlates(emptyList(), plates, 1000, 1000))
    }

    @Test fun plateVoterCorrectsProvinceMisreadWithPositionVotes() {
        val voter = PlateVoter(4)
        val box = android.graphics.RectF(.44f, .58f, .48f, .61f)
        val other = android.graphics.RectF(.10f, .20f, .20f, .30f)
        val first = voter.correct(listOf(PlateHit("川A10001", .99f, box)))
        assertEquals("川A10001", first[0].text) // 首次出现直接显示
        voter.correct(listOf(PlateHit("川A10001", .98f, box)))
        val misread = voter.correct(listOf(PlateHit("冀A10001", .99f, box), PlateHit("苏ED51712", .90f, other)))
        assertEquals("川A10001", misread[0].text) // 位置投票纠正省份误读
        assertEquals("苏ED51712", misread[1].text) // 另一辆车不受影响
        voter.correct(listOf(PlateHit("川A10001", .97f, box)))
        val still = voter.correct(listOf(PlateHit("陕A10001", .98f, box)))
        assertEquals("川A10001", still[0].text)
        assertEquals("川A10001", voter.confirmed())
        voter.clear()
        val fresh = voter.correct(listOf(PlateHit("冀A10001", .99f, box)))
        assertEquals("冀A10001", fresh[0].text) // 清空后不再沿用旧票
        assertNull(voter.confirmed())
    }

    @Test fun redLightTriggerNeedsStableLampApproachAndPlateVotes() {
        val hold = SignalHold(2)
        assertEquals("UNKNOWN", hold.update("RED"))
        assertFalse(hold.stable)
        assertEquals("RED", hold.update("RED"))
        assertTrue(hold.stable)
        hold.update("GREEN")
        assertEquals("RED", hold.color) // 单帧不得翻转
        assertEquals("GREEN", hold.update("GREEN"))
        val watch = ApproachWatch()
        val box = { bottom: Float -> listOf(Vehicle("汽车", .9f, android.graphics.RectF(0f, 0f, 120f, bottom))) }
        repeat(7) { watch.update(box(400f)) }
        assertFalse(watch.approaching)
        watch.update(box(400f))
        assertFalse(watch.approaching) // 底边没下移
        watch.reset()
        repeat(7) { watch.update(box(400f + it)) }
        assertFalse(watch.approaching)
        watch.update(box(450f))
        assertTrue(watch.approaching)
        watch.reset()
        repeat(7) { watch.update(box(450f - it)) }
        watch.update(box(400f))
        assertTrue(watch.approaching) // 红灯期间前车远去同样算仍在移动
        val lamp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        lamp.eraseColor(Color.RED)
        assertEquals("RED", sampleLampColor(lamp, android.graphics.RectF(0f, 0f, 48f, 48f)))
        lamp.eraseColor(Color.GREEN)
        assertEquals("GREEN", sampleLampColor(lamp, android.graphics.RectF(0f, 0f, 48f, 48f)))
        lamp.recycle()
        val center = Lamp("RED", .9f, android.graphics.RectF(400f, 80f, 440f, 140f))
        val side = Lamp("GREEN", .9f, android.graphics.RectF(900f, 80f, 940f, 140f))
        assertEquals("RED", observeLamps(listOf(center, side), 1080f, 1920f))
        val centerGreen = Lamp("GREEN", .9f, android.graphics.RectF(400f, 80f, 440f, 140f))
        val sideRed = Lamp("RED", .9f, android.graphics.RectF(900f, 80f, 940f, 140f))
        assertEquals("GREEN", observeLamps(listOf(centerGreen, sideRed), 1080f, 1920f))
        assertEquals("OFF", observeLamps(listOf(side), 1080f, 1920f))
        val yellow = Lamp("YELLOW", .9f, android.graphics.RectF(400f, 80f, 440f, 140f))
        assertEquals("OFF", observeLamps(listOf(yellow), 1080f, 1920f))
        val night = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        night.eraseColor(Color.BLACK)
        val dot = android.graphics.Canvas(night)
        dot.drawCircle(160f, 40f, 6f, android.graphics.Paint().apply { color = Color.RED })
        val glow = glowLamps(night)
        assertTrue(glow.any { it.color == "RED" })
        night.recycle()
    }

    @Test fun mp4JoinConcatenatesTwoSilentClips() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val a = File(context.cacheDir, "join-a.mp4")
        val b = File(context.cacheDir, "join-b.mp4")
        val out = File(context.cacheDir, "join-out.mp4")
        context.assets.open("traffic.mp4").use { input -> a.outputStream().use { input.copyTo(it) } }
        b.writeBytes(a.readBytes())
        Mp4Join.concat(listOf(a), out)
        assertEquals(a.length(), out.length())
        Mp4Join.concat(listOf(a, b), out)
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(out.absolutePath)
        val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        retriever.release()
        assertTrue("joined duration $duration", duration >= 5000)
        a.delete(); b.delete(); out.delete()
    }

    @Test fun dynamicWindowKeepsDecodeableStartAndReportsRecorderGap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "window-source.mp4")
        val output = File(context.cacheDir, "window-result.mp4")
        context.assets.open("traffic.mp4").use { stream -> input.outputStream().use { stream.copyTo(it) } }
        try {
            val length = Mp4Join.durationMs(input)
            val segments = listOf(VideoSegment(input, 10_000, 10_000 + length), VideoSegment(input, 10_000 + length + 500, 10_000 + 2 * length + 500))
            val until = 10_000 + length + 1500
            val timing = Mp4Join.window(segments, output, 11_400, until)
            assertTrue(timing.startMs <= 11_400)
            assertTrue(timing.endMs <= until)
            assertEquals(500L, timing.gapsMs)
            assertTrue("duration=${Mp4Join.durationMs(output)}, timing=$timing", kotlin.math.abs(Mp4Join.durationMs(output) - (timing.endMs - timing.startMs)) < 250)
            val reader = android.media.MediaMetadataRetriever()
            try { reader.setDataSource(output.absolutePath); assertNotNull(reader.getFrameAtTime(0)) } finally { reader.release() }
        } finally { input.delete(); output.delete() }
    }

    @Test fun realCacheSurvivesActivityRecreationAndQueuesVideo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sync = TaskSync.get(context)
        val previous = sync.connection.load()
        val prefs = context.getSharedPreferences("MainActivity", android.content.Context.MODE_PRIVATE)
        val auto = prefs.getBoolean("automatic", true)
        var testEvent: String? = null
        try {
            sync.connection.save("http://127.0.0.1:1", "offline-test")
            prefs.edit().putBoolean("automatic", false).apply()
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.CAMERA")).use { it.readBytes() }
            ui.activityRule.scenario.recreate()
            val ring = File(context.filesDir, "ring")
            val began = System.currentTimeMillis()
            ui.waitUntil(25_000) { ring.listFiles().orEmpty().any { it.lastModified() >= began && it.length() > 1000 } && System.currentTimeMillis() - began > 6500 }
            val prior = sync.state.value.records.map { it.optString("event_id") }.toSet()
            ui.onNodeWithText("标记重点").performClick()
            ui.onNodeWithText("结束片段").assertExists()
            val marked = System.currentTimeMillis()
            ui.waitUntil(5000) { System.currentTimeMillis() - marked > 2000 }
            ui.activityRule.scenario.recreate()
            ui.waitUntil(25_000) { sync.state.value.records.any { it.optString("event_id") !in prior && it.optString("status") == "PENDING_UPLOAD" } }
            val task = sync.state.value.records.first { it.optString("event_id") !in prior }
            testEvent = task.getString("event_id")
            val file = File(sync.clips, task.getString("local_file"))
            val duration = Mp4Join.durationMs(file)
            assertTrue("prebuffer and postbuffer duration=$duration", duration >= 6000)
            assertNotNull(task.getJSONObject("metadata").optJSONObject("capture"))
            assertTrue(file.isFile)
            kotlinx.coroutines.runBlocking {
                assertEquals(task.getString("event_id"), sync.enqueue(file, "manual").getString("event_id"))
            }
            screenshot("pipeline-landscape.png")
            menu(); settings(); ui.onNodeWithText("竖屏").performScrollTo().performClick()
            ui.onNodeWithText("完成").performClick()
            val rotated = System.currentTimeMillis()
            ui.waitUntil(20_000) { ring.listFiles().orEmpty().any { it.lastModified() > rotated && it.length() > 1000 } && System.currentTimeMillis() - rotated > 6000 }
            screenshot("pipeline-portrait.png")
            settings(); ui.onNodeWithText("横屏").performClick(); ui.onNodeWithText("完成").performClick()
            ui.onNodeWithText("服务器与记录").performClick()
            ui.onAllNodes(hasText("查看片段"))[0].performScrollTo().performClick()
            waitForText("关闭预览")
            screenshot("pipeline-preview.png")
            ui.onNodeWithText("关闭预览").performClick()
            ui.onNodeWithText("返回监看").performScrollTo().performClick()
        } finally {
            testEvent?.let { kotlinx.coroutines.runBlocking { sync.discardLocal(it) } }
            prefs.edit().putBoolean("automatic", auto).apply()
            if (previous.first.isBlank()) sync.connection.clear() else sync.connection.save(previous.first, previous.second)
        }
    }

    @Test fun dynamicEvidenceUploadsIdempotentlyAndReceivesIndependentVerdict() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sync = TaskSync.get(context)
        val previous = sync.connection.load()
        try {
            val token = File(context.filesDir, "pipeline-test-token").readText().trim()
            sync.connection.save("http://127.0.0.1:61617", token)
            val file = File(sync.clips, "${java.util.UUID.randomUUID()}.mp4")
            context.assets.open("traffic.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
            val capture = JSONObject().put("camera_mode", "moving").put("captured_at", System.currentTimeMillis() / 1000.0)
                .put("duration_ms", 3000).put("recording_gaps_ms", 0).put("incidents", org.json.JSONArray().put(
                    JSONObject().put("track_id", 7).put("kind", "RED_LIGHT").put("start_ms", 500).put("end_ms", 1800)
                        .put("plate", "川A12345").put("plate_confirmed", true)))
            val pending = kotlinx.coroutines.runBlocking { sync.enqueue(file, "automatic", "链路测试", "RED_LIGHT", capture) }
            val api = sync.client()
            val metadata = pending.getJSONObject("metadata")
            val uploaded = api.upload(file, metadata, pending.getString("sha256"))
            val duplicate = api.upload(file, metadata, pending.getString("sha256"))
            assertEquals(uploaded.getString("task_id"), duplicate.getString("task_id"))
            val preview = File(context.cacheDir, "download-check.mp4")
            try {
                api.downloadVideo(uploaded.getString("task_id"), preview)
                assertEquals(pending.getString("sha256"), MessageDigest.getInstance("SHA-256").digest(preview.readBytes()).joinToString("") { "%02x".format(it) })
            } finally { preview.delete() }
            // Stop foreground polling and force the real Android JobService to deliver/reconcile the queued clip.
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertNotNull(context.getSystemService(android.app.job.JobScheduler::class.java).getPendingJob(61616))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cmd jobscheduler run -f ${context.packageName} 61616")).use { it.readBytes() }
            val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
            while (android.os.SystemClock.elapsedRealtime() < deadline && sync.state.value.records.none {
                it.optString("event_id") == pending.getString("event_id") && it.optString("status") == "ANALYZED"
            }) android.os.SystemClock.sleep(200)
            val task = sync.state.value.records.single { it.optString("event_id") == pending.getString("event_id") }
            assertEquals("ANALYZED", task.getString("status"))
            assertEquals(7, task.getJSONObject("metadata").getJSONObject("capture").getJSONArray("incidents").getJSONObject(0).getInt("track_id"))
            // A static test video must not turn into a violation just because the client sends a hint.
            assertEquals("UNKNOWN", task.getJSONObject("result").getString("decision"))
            assertFalse(file.exists())
            File(context.filesDir, "pipeline-result.json").writeText(task.toString())
        } finally {
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            if (previous.first.isBlank()) sync.connection.clear() else sync.connection.save(previous.first, previous.second)
        }
    }

    @Test fun photoAndCameraSurviveRotationAndRealRecording() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${instrumentation.targetContext.packageName} android.permission.CAMERA").close()
        ui.activityRule.scenario.recreate()
        val sync = configureBackend()
        menu(); settings()
        ui.onNodeWithText("竖屏").performClick()
        ui.waitUntil(10_000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        ui.onNodeWithText("完成").performClick()
        screenshot("portrait-menu.png")
        settings()
        ui.onNodeWithText("横屏").performClick()
        ui.waitUntil(10_000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        ui.onNodeWithText("测试图").performScrollTo().performClick()
        waitForText("辆车：")
        screenshot("landscape-menu.png")
        settings()
        ui.onNodeWithText("相机", substring = false).performScrollTo().performClick()
        settings(); waitForText("相机最多 8 次/秒")
        ui.onNodeWithText("完成").performClick()
        val warmed = System.currentTimeMillis()
        ui.waitUntil(20_000) { System.currentTimeMillis() - warmed >= 12_000 }
        ui.onNodeWithText("标记重点").performClick()
        waitForText("正在录制重点片段")
        ui.onNodeWithText("结束片段").assertExists()
        ui.activityRule.scenario.recreate()
        menu(); settings(); waitForText("相机最多 8 次/秒")
        ui.onNodeWithText("完成").performClick()
    }

    @Test fun androidUploadsVideoAndReceivesBackendResult() {
        val sync = configureBackend()
        val prior = sync.state.value.records.map { it.optString("event_id") }.toSet()
        menu()
        ui.onNodeWithText("服务器与记录").performClick()
        ui.onNodeWithText("保存并连接").performScrollTo().performClick()
        waitForText("连接成功")
        ui.onNodeWithText("上传内置测试视频").performScrollTo().performClick()
        ui.waitUntil(60_000) { sync.state.value.records.any { it.optString("event_id") !in prior && it.optString("status") == "ANALYZED" } }
        val task = sync.state.value.records.first { it.optString("event_id") !in prior }
        val original = task.getJSONObject("result").toString()
        val note = "真机自动同步验证：内置静态图片不是违法证据（${task.getString("event_id").take(8)}）"
        sync.client().request("POST", "/v1/tasks/${task.getString("task_id")}/review", JSONObject()
            .put("expected_revision", task.getInt("revision")).put("decision", "INVALID")
            .put("reviewer", "自动化测试").put("note", note))
        ui.waitUntil(15_000) { sync.state.value.records.any { it.optString("event_id") == task.getString("event_id") && it.optJSONObject("review")?.optString("note") == note } }
        val updated = sync.state.value.records.first { it.optString("event_id") == task.getString("event_id") }
        assertEquals("REJECTED", updated.getJSONObject("effective_result").getString("decision"))
        assertEquals(original, updated.getJSONObject("result").toString())
        ui.onNodeWithText(note).performScrollTo().assertExists()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "device-test-result.json").writeText(updated.toString())
        ui.onNodeWithText("返回车辆识别").performScrollTo().performClick()
    }

    @Test fun offlineWakePhraseStartsMicrophoneAndRecognizesChineseAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${instrumentation.targetContext.packageName} android.permission.RECORD_AUDIO").close()
        assertTrue(VoiceMarker.matches("开始 标记。"))
        assertFalse(VoiceMarker.matches("停止标记"))
        menu()
        if (ui.onAllNodes(hasText("语音标记", substring=false)).fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithText("语音标记").performClick()
        waitForText("语音已开启", 60_000)
        val root = File(instrumentation.targetContext.noBackupFilesDir,"model-cn-0.22")
        Model(root.absolutePath).use { model -> Recognizer(model,16000f).use { recognizer ->
            val pcm = instrumentation.context.assets.open("start-mark.pcm").use { it.readBytes() }
            val text = StringBuilder()
            pcm.asList().chunked(8000).forEach { chunk ->
                val bytes = chunk.toByteArray()
                if (recognizer.acceptWaveForm(bytes, bytes.size)) text.append(JSONObject(recognizer.result).optString("text"))
            }
            text.append(JSONObject(recognizer.finalResult).optString("text"))
            assertTrue("Actual offline recognizer: $text", VoiceMarker.matches(text.toString()))
        } }
        ui.onNodeWithText("关闭语音").performClick()
    }

    private fun configureBackend(): TaskSync {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = File(context.filesDir, "backend-test-token").readText().trim()
        assertTrue(token.isNotBlank())
        return TaskSync.get(context).also {
            it.connection.save(ServerConnection.LOCAL,token)
            kotlinx.coroutines.runBlocking { it.tick() }
            ui.waitUntil(15_000) { it.state.value.connected }
        }
    }
    private fun menu() {
        if (ui.onAllNodes(hasText("更多设置")).fetchSemanticsNodes().isEmpty()) ui.onNodeWithTag("playerTap").performClick()
        waitForText("更多设置")
    }
    private fun settings() { ui.onNodeWithText("更多设置").performClick() }
    private fun screenshot(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
    }
    private fun waitForText(text: String, timeout: Long = 30_000) {
        ui.waitUntil(timeout) {
            ui.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
