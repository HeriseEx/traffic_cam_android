package com.example.illegalcapture

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class RoadLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val solid: Boolean)
data class RoadFrame(val stopY: Float? = null, val lines: List<RoadLine> = emptyList())

/** Pixel marks for a forward camera. A line that slides between frames is ego-motion, not a crossing. */
object RoadScan {
    fun stopY(gray: IntArray, width: Int, height: Int): Float? {
        var best = 0
        var rowAt = -1
        val y0 = (height * .40f).toInt()
        val y1 = (height * .82f).toInt()
        for (row in y0 until y1) {
            val above = max(0, row - 2)
            val below = min(height - 1, row + 2)
            var run = 0
            var longest = 0
            for (x in 0 until width) {
                val value = gray[row * width + x]
                val bright = value > gray[above * width + x] + 18 && value > gray[below * width + x] + 18 && value > 90
                if (bright) {
                    run++
                    if (run > longest) longest = run
                } else run = 0
            }
            if (longest > best) {
                best = longest
                rowAt = row
            }
        }
        if (rowAt < 0 || best < width * .35f) return null
        return rowAt.toFloat() / height
    }

    fun laneLines(gray: IntArray, width: Int, height: Int): List<RoadLine> {
        val lines = ArrayList<RoadLine>()
        val y0 = (height * .35f).toInt()
        val y1 = (height * .90f).toInt()
        val span = (y1 - y0).coerceAtLeast(1)
        for (x in 1 until width - 1) {
            var run = 0
            var runs = 0
            var bright = 0
            var best = 0
            var bestY = y0
            for (y in y0 until y1) {
                val value = gray[y * width + x]
                val edge = value > gray[y * width + x - 1] + 18 && value > gray[y * width + x + 1] + 18 && value > 90
                if (edge) {
                    if (run == 0) runs++
                    run++
                    bright++
                    if (run > best) {
                        best = run
                        bestY = y
                    }
                } else run = 0
            }
            val start = (bestY - best + 1).toFloat() / height
            val end = bestY.toFloat() / height
            val nx = x.toFloat() / width
            when {
                bright >= span * .45f && runs <= 2 -> lines.add(RoadLine(nx, start, nx, end, true))
                bright >= span * .25f && runs >= 3 -> lines.add(RoadLine(nx, start, nx, end, false))
            }
        }
        return lines.take(8)
    }

    fun marks(bitmap: Bitmap): RoadFrame {
        val width = 160
        val height = 90
        val gray = IntArray(width * height)
        val stepX = bitmap.width.toFloat() / width
        val stepY = bitmap.height.toFloat() / height
        for (y in 0 until height) for (x in 0 until width) {
            val px = bitmap.getPixel(
                (x * stepX).toInt().coerceAtMost(bitmap.width - 1),
                (y * stepY).toInt().coerceAtMost(bitmap.height - 1))
            val r = (px shr 16) and 255
            val g = (px shr 8) and 255
            val b = px and 255
            gray[y * width + x] = (r * 3 + g * 6 + b) / 10
        }
        return RoadFrame(stopY(gray, width, height), laneLines(gray, width, height))
    }

    fun lampOff(bitmap: Bitmap, box: RectF): Boolean? {
        val x1 = box.left.toInt().coerceIn(0, bitmap.width - 1)
        val x2 = box.right.toInt().coerceIn(x1 + 1, bitmap.width)
        val y1 = box.top.toInt().coerceIn(0, bitmap.height - 1)
        val y2 = box.bottom.toInt().coerceIn(y1 + 1, bitmap.height)
        if (x2 - x1 < 24 || y2 - y1 < 24) return null
        val span = max(1, (x2 - x1) / 5)
        fun amber(from: Int, until: Int): Boolean {
            for (y in y1 until y2) for (x in from until until) {
                val px = bitmap.getPixel(x, y)
                val r = (px shr 16) and 255
                val g = (px shr 8) and 255
                val b = px and 255
                if (r > 160 && g in 70..210 && b < 90) return true
            }
            return false
        }
        if (amber(x1, x1 + span) || amber(x2 - span, x2)) return false
        return true
    }
}

object RoadRules {
    data class Sample(val at: Long, val tracks: List<TrackedVehicle>, val road: RoadFrame, val red: Boolean)

    fun kinds(samples: List<Sample>): List<Pair<Long, String>> {
        val found = ArrayList<Pair<Long, String>>()
        found.addAll(stopCrossings(samples).map { it to "RED_LIGHT" })
        found.addAll(laneKinds(samples))
        return found
    }

    private fun stopCrossings(samples: List<Sample>): Set<Long> {
        val ids = HashSet<Long>()
        fun flush(run: List<Sample>) {
            if (run.size < 4 || run.last().at - run.first().at < 600) return
            val ys = run.mapNotNull { it.road.stopY }
            if (ys.size != run.size || ys.max() - ys.min() > .03f) return
            val line = ys.sorted()[ys.size / 2]
            val tracks = HashMap<Long, ArrayList<Float>>()
            for (sample in run) if (sample.red) for (track in sample.tracks) if (!track.predicted) {
                tracks.getOrPut(track.id) { ArrayList() }.add(track.observation.box.bottom)
            }
            for ((id, bottoms) in tracks) {
                for (i in 1 until bottoms.size) {
                    val earlier = bottoms[i - 1]
                    val later = bottoms[i]
                    if ((earlier - line) * (later - line) < 0 && abs(earlier - line) + abs(later - line) > .02f)
                        ids.add(id)
                }
            }
        }
        val run = ArrayList<Sample>()
        for (sample in samples) {
            val y = sample.road.stopY
            if (y == null || (run.isNotEmpty() && sample.at - run.last().at > 1000)) {
                flush(run)
                run.clear()
            }
            if (y != null) run.add(sample)
        }
        flush(run)
        return ids
    }

    private fun laneKinds(samples: List<Sample>): List<Pair<Long, String>> {
        val result = ArrayList<Pair<Long, String>>()
        val solid = HashSet<Long>()
        val over = HashSet<Long>()
        val signal = HashSet<Long>()
        val emergency = HashSet<Long>()
        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            if (current.at - previous.at > 1200) continue
            val pairs = match(previous.road.lines, current.road.lines)
            val before = previous.tracks.filter { !it.predicted }.associateBy { it.id }
            val after = current.tracks.filter { !it.predicted }.associateBy { it.id }
            for ((id, first) in before) {
                val second = after[id] ?: continue
                for ((line0, line1) in pairs) {
                    val s0 = signed(first.observation.box, line0)
                    val s1 = signed(second.observation.box, line1)
                    if (s0 * s1 >= 0 || abs(s0) + abs(s1) <= .04f) continue
                    if (line0.solid && line1.solid && solid.add(id)) result.add(id to "SOLID_LINE")
                    if (!line0.solid && !line1.solid && over.add(id) && passed(previous, current, id))
                        result.add(id to "OVERTAKE")
                    if (signal.add(id) && first.observation.signalOff == true && second.observation.signalOff == true)
                        result.add(id to "NO_SIGNAL")
                }
            }
            val edge = rightmost(current.road.lines) ?: continue
            for (track in current.tracks) {
                if (track.predicted || track.id in emergency) continue
                val window = samples.drop(index).take(4)
                if (window.size < 4 || window.last().at - current.at < 1000) continue
                val points = ArrayList<Triple<Float, Float, Float>>()
                var complete = true
                for (sample in window) {
                    val vehicle = sample.tracks.firstOrNull { it.id == track.id && !it.predicted }
                    val line = rightmost(sample.road.lines)
                    if (vehicle == null || line == null || abs(midX(line) - midX(edge)) > .05f) {
                        complete = false
                        break
                    }
                    val box = vehicle.observation.box
                    points.add(Triple(box.x, box.bottom, xOn(line, box.bottom)))
                }
                if (!complete || points.size < 4) continue
                if (points.any { it.first - it.third <= .03f }) continue
                if (abs(points.last().second - points.first().second) < .04f) continue
                emergency.add(track.id)
                result.add(track.id to "EMERGENCY_LANE")
            }
        }
        return result
    }

    private fun match(previous: List<RoadLine>, current: List<RoadLine>): List<Pair<RoadLine, RoadLine>> {
        val used = HashSet<Int>()
        val pairs = ArrayList<Pair<RoadLine, RoadLine>>()
        for (left in previous) {
            var best = -1
            var distance = .05f
            val ln = hypot(left.x2 - left.x1, left.y2 - left.y1).coerceAtLeast(.0001f)
            for ((index, right) in current.withIndex()) {
                if (index in used) continue
                val rn = hypot(right.x2 - right.x1, right.y2 - right.y1).coerceAtLeast(.0001f)
                val cosine = abs((left.x2 - left.x1) * (right.x2 - right.x1) + (left.y2 - left.y1) * (right.y2 - right.y1)) / (ln * rn)
                if (cosine < .97f) continue
                val gap = hypot((right.x1 + right.x2) / 2 - (left.x1 + left.x2) / 2, (right.y1 + right.y2) / 2 - (left.y1 + left.y2) / 2)
                if (gap < distance) {
                    best = index
                    distance = gap
                }
            }
            if (best >= 0) {
                used.add(best)
                pairs.add(left to current[best])
            }
        }
        return pairs
    }

    private fun signed(box: TrackBox, line: RoadLine): Float {
        val dx = line.x2 - line.x1
        val dy = line.y2 - line.y1
        val length = hypot(dx, dy).coerceAtLeast(.0001f)
        return (dx * (box.bottom - line.y1) - dy * (box.x - line.x1)) / length
    }

    private fun passed(previous: Sample, current: Sample, id: Long): Boolean {
        val mine0 = previous.tracks.firstOrNull { it.id == id }?.observation?.box?.bottom ?: return false
        val mine1 = current.tracks.firstOrNull { it.id == id }?.observation?.box?.bottom ?: return false
        for (other in previous.tracks) {
            if (other.id == id) continue
            val next = current.tracks.firstOrNull { it.id == other.id } ?: continue
            if (mine0 > other.observation.box.bottom + .02f && mine1 < next.observation.box.bottom - .02f) return true
        }
        return false
    }

    private fun midX(line: RoadLine) = (line.x1 + line.x2) / 2
    private fun rightmost(lines: List<RoadLine>): RoadLine? =
        lines.filter { it.solid && abs(it.x2 - it.x1) <= abs(it.y2 - it.y1) }.maxByOrNull { midX(it) }

    private fun xOn(line: RoadLine, y: Float): Float {
        if (abs(line.y2 - line.y1) < .0001f) return midX(line)
        val t = ((y - line.y1) / (line.y2 - line.y1)).coerceIn(0f, 1f)
        return line.x1 + t * (line.x2 - line.x1)
    }
}
