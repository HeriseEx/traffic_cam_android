package com.example.illegalcapture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class DetectionOverlay(context: Context) : View(context) {
    var vehicles: List<Vehicle> = emptyList()
        set(value) {
            field = value
            describe()
            invalidate()
        }
    var lamps: List<Lamp> = emptyList()
        set(value) {
            field = value
            describe()
            invalidate()
        }
    var plates: List<PlateHit> = emptyList()
        set(value) {
            field = value
            describe()
            invalidate()
        }
    private fun describe() {
        contentDescription = (vehicles.map { label(it) } + lamps.map { lampLabel(it) } + plates.map { plateLabel(it) }).joinToString()
    }
    private fun label(vehicle: Vehicle) =
        (if (vehicle.trackId > 0) "#${vehicle.trackId} " else "") + vehicle.label +
            (if (vehicle.predicted) " · 遮挡保留" else " ${(vehicle.score * 100).toInt()}%") +
            (vehicle.plate?.let { " · $it${if (vehicle.plateConfirmed) " ✓" else " ?"}" } ?: "")
    private fun lampLabel(lamp: Lamp) = when (lamp.color) {
        "RED" -> "红灯"; "GREEN" -> "绿灯"; "YELLOW" -> "黄灯"; else -> "信号灯"
    } + " ${(lamp.score * 100).toInt()}%"
    private fun plateLabel(plate: PlateHit) =
        "${plate.text} ${"%.0f".format(plate.confidence * 100)}%"
    private val density = resources.displayMetrics.density
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val background = Paint().apply { color = Color.argb(220, 8, 30, 25) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14 * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        vehicles.forEach { vehicle ->
            val color = if (vehicle.predicted) Color.rgb(255, 200, 100) else Color.rgb(74, 255, 175)
            border.color = color
            border.pathEffect = if (vehicle.predicted) android.graphics.DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f) else null
            vehicle.path.zipWithNext().forEach { (a, b) -> canvas.drawLine(a.x, a.y, b.x, b.y, border) }
            drawBox(canvas, vehicle.box, label(vehicle), color)
        }
        border.pathEffect = null
        plates.forEach { plate -> drawBox(canvas, plate.box, plateLabel(plate), Color.rgb(255, 210, 80)) }
        lamps.forEach { lamp ->
            val color = when (lamp.color) {
                "RED" -> Color.rgb(255, 80, 80)
                "GREEN" -> Color.rgb(80, 220, 120)
                "YELLOW" -> Color.rgb(255, 210, 80)
                else -> Color.rgb(220, 220, 220)
            }
            drawBox(canvas, lamp.box, lampLabel(lamp), color)
        }
    }

    private fun drawBox(canvas: Canvas, box: android.graphics.RectF, label: String, color: Int) {
        border.color = color
        canvas.drawRect(box, border)
        val labelWidth = text.measureText(label) + 12 * density
        val left = box.left.coerceIn(0f, (width - labelWidth).coerceAtLeast(0f))
        val baseline = (box.top - 6 * density).coerceAtLeast(20 * density)
        canvas.drawRect(left, baseline - 17 * density, left + labelWidth,
            baseline + 5 * density, background)
        canvas.drawText(label, left + 6 * density, baseline, text)
    }
}
