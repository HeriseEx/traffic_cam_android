package com.example.illegalcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Vehicle(val label: String, val score: Float, val box: RectF, val plate: String? = null,
    val trackId: Long = 0, val predicted: Boolean = false, val plateConfirmed: Boolean = false,
    val path: List<TrackPoint> = emptyList(), val appearance: List<Float> = emptyList(),
    val signalOff: Boolean? = null)
data class Lamp(val color: String, val score: Float, val box: RectF)
data class DetectionResult(
    val vehicles: List<Vehicle>,
    val elapsedMs: Long,
    val lamps: List<Lamp> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
    val road: RoadFrame = RoadFrame(),
)

fun DetectionResult.observations() = vehicles.map { v -> TrackObservation(v.label, v.score,
    TrackBox(v.box.left / width, v.box.top / height, v.box.right / width, v.box.bottom / height), v.appearance, v.signalOff) }

fun DetectionResult.withTracks(tracks: List<TrackedVehicle>) = copy(vehicles = tracks.map { t ->
    val b = t.observation.box
    Vehicle(t.observation.label, t.observation.score, RectF(b.left * width, b.top * height, b.right * width, b.bottom * height),
        t.plate, t.id, t.predicted, t.plateConfirmed, t.path.map { it.copy(x = it.x * width, y = it.y * height) })
})

fun parseTrackPlates(response: org.json.JSONObject): List<TrackPlate> {
    val rows = response.optJSONArray("plates") ?: return emptyList()
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val b = row.optJSONArray("box_normalized")?.takeIf { it.length() == 4 } ?: return@mapNotNull null
        val coords = List(4) { b.optDouble(it, Double.NaN).toFloat() }
        if (coords.any { !it.isFinite() || it !in 0f..1f }) return@mapNotNull null
        val score = row.optDouble("confidence", 0.0).toFloat()
        if (!score.isFinite() || score !in 0f..1f) return@mapNotNull null
        TrackPlate(row.optString("text"), score, TrackBox(coords[0], coords[1], coords[2], coords[3]))
    }
}
/** 后端 recognize-frame 返回的车牌；[box] 为相对整帧的归一化坐标。 */
data class PlateHit(val text: String, val confidence: Float, val box: RectF)

private fun overlap(a: RectF, b: RectF): Float {
    val width = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
    val height = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
    val intersection = width * height
    val union = a.width() * a.height() + b.width() * b.height() - intersection
    return if (union > 0f) intersection / union else 0f
}

/**
 * 用最近几次识别按位置投票，纠正单帧误读（省份汉字最常见，实测 川 被读成 冀/陕/京）。
 * 同一位置（IoU>0.5）出现次数最多的文字胜出，次数相同比最高置信度；相机大幅移动时
 * 位置对不上，自动退回单帧结果，首次出现的车牌无延迟直接显示。
 * ponytail: 无跟踪器，纯几何关联，窗口 4 次≈10 秒；升级路径是端侧 track id。
 */
class PlateVoter(private val window: Int = 4) {
    private val history = ArrayDeque<List<PlateHit>>()

    fun correct(latest: List<PlateHit>): List<PlateHit> {
        history.addLast(latest)
        if (history.size > window) history.removeFirst()
        val past = history.flatten()
        return latest.map { hit ->
            val votes = HashMap<String, Pair<Int, Float>>()
            for (candidate in past) {
                if (overlap(candidate.box, hit.box) <= .5f) continue
                val (count, best) = votes[candidate.text] ?: (0 to 0f)
                votes[candidate.text] = count + 1 to maxOf(best, candidate.confidence)
            }
            val winner = votes.maxByOrNull { it.value.first * 10000f + it.value.second }
            if (winner == null || winner.key == hit.text) hit
            else hit.copy(text = winner.key, confidence = winner.value.second)
        }
    }

    fun clear() { history.clear() }

    fun confirmed(): String? {
        val counts = history.flatten().groupingBy { it.text }.eachCount()
        return counts.filter { it.value >= 2 }.maxByOrNull { it.value }?.key
    }
}

/** Live JPEG is ~2.5s; two identical colors ≈ 5s before the HUD may call it stable. */
class SignalHold(private val hold: Int = 2) {
    var color: String = "UNKNOWN"
        private set
    var stable: Boolean = false
        private set
    private var pending: String? = null
    private var streak = 0

    fun update(observed: String): String {
        if (observed == pending) streak++ else { pending = observed; streak = 1 }
        if (streak >= hold && pending != color) {
            color = pending!!
        }
        stable = streak >= hold && observed == color && color in setOf("RED", "GREEN", "YELLOW")
        return color
    }

    fun clear() { color = "UNKNOWN"; stable = false; pending = null; streak = 0 }
}

/**
 * Local hint that the lead box is still dropping while the lamp is red.
 * ponytail: 36px over ≥8 analysis frames; ego-motion can look the same — server must not treat this as a stop-line cross.
 */
class ApproachWatch {
    var approaching: Boolean = false
        private set
    private var first = -1f
    private var samples = 0

    fun reset() { first = -1f; samples = 0; approaching = false }

    fun update(vehicles: List<Vehicle>): Boolean {
        val lead = vehicles.maxByOrNull { it.box.width() * it.box.height() } ?: return approaching
        if (first < 0f) first = lead.box.bottom
        samples++
        if (samples >= 8 && kotlin.math.abs(lead.box.bottom - first) > 36f) approaching = true
        return approaching
    }
}

fun sampleLampColor(bitmap: Bitmap, box: RectF): String? {
    val left = box.left.toInt().coerceIn(0, bitmap.width - 1)
    val top = box.top.toInt().coerceIn(0, bitmap.height - 1)
    val right = box.right.toInt().coerceIn(left + 1, bitmap.width)
    val bottom = box.bottom.toInt().coerceIn(top + 1, bitmap.height)
    val step = maxOf(1, minOf(right - left, bottom - top) / 12)
    val hsv = FloatArray(3)
    var red = 0
    var green = 0
    var yellow = 0
    var y = top
    while (y < bottom) {
        var x = left
        while (x < right) {
            android.graphics.Color.colorToHSV(bitmap.getPixel(x, y), hsv)
            if (hsv[1] > 0.35f && hsv[2] > 0.35f) {
                val h = hsv[0]
                when {
                    h <= 18f || h >= 330f -> red++
                    h in 70f..170f -> green++
                    h in 25f..60f -> yellow++
                }
            }
            x += step
        }
        y += step
    }
    val best = listOf("RED" to red, "GREEN" to green, "YELLOW" to yellow).maxBy { it.second }
    if (red >= 3 && red + yellow >= green) return "RED"
    return if (best.second >= 3) best.first else null
}

fun observeLamps(lamps: List<Lamp>, width: Float, height: Float): String {
    if (lamps.isEmpty() || width <= 0f || height <= 0f) return "OFF"
    fun cx(lamp: Lamp) = (lamp.box.left + lamp.box.right) / 2f / width
    fun cy(lamp: Lamp) = (lamp.box.top + lamp.box.bottom) / 2f / height
    val usable = lamps.filter { it.color == "RED" || it.color == "GREEN" }
    val boxes = usable.filter { cx(it) in 0.22f..0.78f && cy(it) < .55f }
    val pool = boxes.ifEmpty {
        usable.filter { cx(it) in 0.32f..0.68f && cy(it) < .46f }
    }
    if (pool.isEmpty()) return "OFF"
    return pool.minBy { kotlin.math.abs(cx(it) - .5f) * 2f + kotlin.math.abs(cy(it) - .38f) }.color
}

/**
 * Night dashcam lamps are often too small for EfficientDet class 9.
 * Same upper-frame HSV idea as the server glow path; skip bright daytime frames.
 */
fun glowLamps(bitmap: Bitmap): List<Lamp> {
    val width = bitmap.width
    val height = bitmap.height
    if (width < 32 || height < 32) return emptyList()
    val y0 = (height * .08f).toInt()
    val y1 = (height * .48f).toInt()
    val x0 = (width * .12f).toInt()
    val x1 = (width * .95f).toInt()
    if (y1 - y0 < 16 || x1 - x0 < 16) return emptyList()
    val step = maxOf(6, minOf(width, height) / 40)
    var sum = 0L
    var count = 0
    var y = y0
    while (y < y1) {
        var x = x0
        while (x < x1) {
            val pixel = bitmap.getPixel(x, y)
            sum += (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
            count++
            x += step * 3
        }
        y += step * 3
    }
    if (count == 0 || sum / count > 70) return emptyList()
    val cols = 8
    val rows = 4
    val cellW = (x1 - x0) / cols
    val cellH = (y1 - y0) / rows
    if (cellW < 8 || cellH < 8) return emptyList()
    val lamps = ArrayList<Lamp>()
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            val box = RectF(
                (x0 + col * cellW).toFloat(),
                (y0 + row * cellH).toFloat(),
                (x0 + (col + 1) * cellW).toFloat(),
                (y0 + (row + 1) * cellH).toFloat())
            val color = sampleLampColor(bitmap, box) ?: continue
            if (color == "YELLOW" || col !in 2..5) continue
            lamps.add(Lamp(color, 0.2f, box))
        }
    }
    return lamps
}

/**
 * 车牌中心点落在哪个车辆框内就归属谁；多个候选取面积最小的框。
 * ponytail: 车牌约 2.5 秒刷新一次而车辆框逐帧更新，快速移动的车会短暂错位；
 * 升级路径是端侧跟踪器（按 track id 关联）而不是更复杂的几何匹配。
 */
fun attachPlates(vehicles: List<Vehicle>, plates: List<PlateHit>, width: Int, height: Int): List<Vehicle> {
    if (vehicles.isEmpty() || plates.isEmpty()) return vehicles
    val chosen = HashMap<Int, PlateHit>()
    plates.forEach { plate ->
        val cx = (plate.box.left + plate.box.right) / 2 * width
        val cy = (plate.box.top + plate.box.bottom) / 2 * height
        val index = vehicles.indices.filter { vehicles[it].box.contains(cx, cy) }
            .minByOrNull { vehicles[it].box.width() * vehicles[it].box.height() } ?: return@forEach
        if (plate.confidence > (chosen[index]?.confidence ?: -1f)) chosen[index] = plate
    }
    return vehicles.mapIndexed { index, vehicle ->
        chosen[index]?.let { vehicle.copy(plate = it.text) } ?: vehicle
    }
}

/** Single worker thread owns this interpreter and all reusable input/output buffers. */
class VehicleDetector(context: Context) : Closeable {
    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(SIZE * SIZE)
    private val scaled = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(scaled)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val boxes: Array<Array<FloatArray>>
    private val classes: Array<FloatArray>
    private val scores: Array<FloatArray>
    private val count = FloatArray(1)
    private val outputs: Map<Int, Any>

    init {
        val model = context.assets.open(MODEL).use { stream ->
            val bytes = stream.readBytes()
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
        try {
            val tensor = interpreter.getInputTensor(0)
            require(tensor.dataType() == DataType.UINT8 &&
                tensor.shape().contentEquals(intArrayOf(1, SIZE, SIZE, 3))) {
                "模型输入不匹配：需要 UINT8 [1,320,320,3]"
            }
            // Pinned EfficientDet-Lite0 v1 includes TFLite_Detection_PostProcess (NMS).
            require(interpreter.outputTensorCount == 4) {
                "模型需要含检测后处理的 4 个输出，实际为 ${interpreter.outputTensorCount} 个"
            }
            val capacity = interpreter.getOutputTensor(0).shape()[1]
            val expected = arrayOf(intArrayOf(1, capacity, 4), intArrayOf(1, capacity),
                intArrayOf(1, capacity), intArrayOf(1))
            expected.forEachIndexed { index, shape ->
                require(interpreter.getOutputTensor(index).shape().contentEquals(shape) &&
                    interpreter.getOutputTensor(index).dataType() == DataType.FLOAT32) {
                    "模型输出不匹配：$index"
                }
            }
            boxes = Array(1) { Array(capacity) { FloatArray(4) } }
            classes = Array(1) { FloatArray(capacity) }
            scores = Array(1) { FloatArray(capacity) }
            outputs = mapOf(0 to boxes, 1 to classes, 2 to scores, 3 to count)
        } catch (error: Exception) {
            interpreter.close()
            scaled.recycle()
            throw error
        }
    }

    fun detect(bitmap: Bitmap, threshold: Float): DetectionResult {
        require(threshold.isFinite() && threshold in 0f..1f)
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0)
        val start = SystemClock.elapsedRealtime()
        val scale = minOf(SIZE.toFloat() / bitmap.width, SIZE.toFloat() / bitmap.height)
        val dx = (SIZE - bitmap.width * scale) / 2f
        val dy = (SIZE - bitmap.height * scale) / 2f
        canvas.drawColor(Color.rgb(128, 128, 128))
        canvas.drawBitmap(bitmap, null,
            RectF(dx, dy, dx + bitmap.width * scale, dy + bitmap.height * scale), paint)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        for (pixel in pixels) {
            input.put((pixel shr 16 and 255).toByte())
            input.put((pixel shr 8 and 255).toByte())
            input.put((pixel and 255).toByte())
        }
        input.rewind()
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
        val vehicles = ArrayList<Vehicle>()
        val lamps = ArrayList<Lamp>()
        val lampFloor = minOf(threshold, 0.2f).coerceAtLeast(0.12f)
        for (i in 0 until count[0].toInt().coerceIn(0, scores[0].size)) {
            val score = scores[0][i]
            if (!score.isFinite()) continue
            val b = boxes[0][i]
            if (b.any { !it.isFinite() }) continue
            val box = RectF(
                ((b[1] * SIZE - dx) / scale).coerceIn(0f, bitmap.width.toFloat()),
                ((b[0] * SIZE - dy) / scale).coerceIn(0f, bitmap.height.toFloat()),
                ((b[3] * SIZE - dx) / scale).coerceIn(0f, bitmap.width.toFloat()),
                ((b[2] * SIZE - dy) / scale).coerceIn(0f, bitmap.height.toFloat()))
            if (box.width() <= 0 || box.height() <= 0) continue
            val classId = classes[0][i].toInt()
            if (classId == LAMP) {
                if (score >= lampFloor) sampleLampColor(bitmap, box)?.let { lamps.add(Lamp(it, score, box)) }
                continue
            }
            val label = LABELS[classId] ?: continue
            if (score < threshold) continue
            val histogram = FloatArray(64)
            // Sample the central body: less road/background in the appearance signature.
            for (y in 1..8) for (x in 1..8) {
                val pixel = bitmap.getPixel((box.left + box.width() * (.2f + x * .06f)).toInt().coerceIn(0, bitmap.width - 1),
                    (box.top + box.height() * (.2f + y * .06f)).toInt().coerceIn(0, bitmap.height - 1))
                val bin = (Color.red(pixel) / 64) * 16 + (Color.green(pixel) / 64) * 4 + Color.blue(pixel) / 64
                histogram[bin] += 1f / 64
            }
            vehicles.add(Vehicle(label, score, box, appearance = histogram.toList()))
        }
        if (lamps.isEmpty()) lamps.addAll(glowLamps(bitmap))
        return DetectionResult(vehicles, SystemClock.elapsedRealtime() - start, lamps, bitmap.width, bitmap.height)
    }

    override fun close() {
        interpreter.close()
        scaled.recycle()
    }

    companion object {
        const val MODEL = "efficientdet_lite0.tflite"
        const val SIZE = 320
        // Indices verified against labelmap.txt embedded in the pinned model.
        val LABELS = mapOf(2 to "汽车", 3 to "摩托车", 5 to "公交车", 7 to "卡车")
        const val LAMP = 9
    }
}
