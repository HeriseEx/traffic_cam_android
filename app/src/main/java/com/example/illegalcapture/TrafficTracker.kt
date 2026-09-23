package com.example.illegalcapture

import kotlin.math.abs
import kotlin.math.hypot

/** Normalized coordinates; independent of Android so identity and timing can be replayed in JVM tests. */
data class TrackBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val x get() = (left + right) / 2
    val y get() = (top + bottom) / 2
    val width get() = right - left
    val height get() = bottom - top
    val area get() = width * height
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun move(dx: Float, dy: Float) = TrackBox(left + dx, top + dy, right + dx, bottom + dy)
    fun iou(b: TrackBox): Float {
        val intersection = (minOf(right, b.right) - maxOf(left, b.left)).coerceAtLeast(0f) *
            (minOf(bottom, b.bottom) - maxOf(top, b.top)).coerceAtLeast(0f)
        return intersection / (area + b.area - intersection).coerceAtLeast(.00001f)
    }
}

data class TrackPoint(val at: Long, val x: Float, val y: Float)
data class TrackObservation(val label: String, val score: Float, val box: TrackBox, val appearance: List<Float> = emptyList(),
    val signalOff: Boolean? = null)
data class TrackedVehicle(val id: Long, val observation: TrackObservation, val seenAt: Long,
    val path: List<TrackPoint>, val predicted: Boolean, val plate: String?, val plateConfirmed: Boolean)
data class TrackPlate(val text: String, val confidence: Float, val box: TrackBox)

class TrafficTracker(var occlusionMs: Long = 3000) {
    private data class Track(var observation: TrackObservation, var seen: Long, var vx: Float = 0f, var vy: Float = 0f,
        val path: ArrayDeque<TrackPoint> = ArrayDeque(), val votes: ArrayDeque<Pair<Long, TrackPlate>> = ArrayDeque())
    private val tracks = linkedMapOf<Long, Track>()
    private val snapshots = ArrayDeque<Pair<Long, Map<Long, TrackBox>>>()
    private var nextId = 1L
    private var lastAt = -1L

    fun clear() { tracks.clear(); snapshots.clear(); lastAt = -1 }

    fun update(observations: List<TrackObservation>, at: Long): List<TrackedVehicle> {
        require(at >= lastAt) { "Frames must be ordered" }
        lastAt = at
        tracks.entries.removeAll { at - it.value.seen > occlusionMs }
        val input = observations.filter { v -> v.box.run { listOf(left, top, right, bottom).all { it.isFinite() } && area > 0 && width > 0 && height > 0 } }
        val available = tracks.keys.toMutableSet()
        val assigned = mutableSetOf<Int>()
        val pairs = ArrayList<Triple<Float, Int, Long>>()
        for ((index, detection) in input.withIndex()) for ((id, track) in tracks) {
            if (detection.label != track.observation.label) continue
            val predicted = predict(track, at)
            val scale = maxOf(predicted.width, predicted.height, .04f)
            val distance = hypot(detection.box.x - predicted.x, detection.box.y - predicted.y) / scale
            val ratio = detection.box.area / track.observation.box.area
            val look = appearanceDistance(detection.appearance, track.observation.appearance)
            if (ratio !in .4f..2.5f || distance > .9f || look > .65f) continue
            pairs.add(Triple(distance * .5f + (1 - predicted.iou(detection.box)) * .3f + look * .7f, index, id))
        }
        // ponytail: O(n²) geometric/colour association for detector's small output; embedding ReID for crowded, same-colour vehicles.
        for ((cost, index, id) in pairs.sortedBy { it.first }) {
            if (index in assigned || id !in available) continue
            // An ambiguous reappearance must not inherit a plate from either possible owner.
            val ambiguous = pairs.any { it.second == index && it.third != id && it.third in available && abs(it.first - cost) < .08f }
            if (ambiguous) continue
            val track = tracks.getValue(id)
            val detection = input[index]
            val dt = (at - track.seen).coerceAtLeast(1).toFloat()
            track.vx = .4f * track.vx + .6f * (detection.box.x - track.observation.box.x) / dt
            track.vy = .4f * track.vy + .6f * (detection.box.y - track.observation.box.y) / dt
            track.observation = detection; track.seen = at
            track.path.addLast(TrackPoint(at, detection.box.x, detection.box.bottom))
            available.remove(id); assigned.add(index)
        }
        for ((index, observation) in input.withIndex()) if (index !in assigned) {
            tracks[nextId++] = Track(observation, at).apply { path.addLast(TrackPoint(at, observation.box.x, observation.box.bottom)) }
        }
        tracks.values.forEach { track ->
            while (track.path.size > 64 || (track.path.size > 1 && at - track.path.first().at > 8000)) track.path.removeFirst()
            while (track.votes.isNotEmpty() && at - track.votes.first().first > 15000) track.votes.removeFirst()
        }
        snapshots.addLast(at to tracks.filterValues { it.seen == at }.mapValues { it.value.observation.box })
        while (snapshots.isNotEmpty() && at - snapshots.first().first > 6000) snapshots.removeFirst()
        return current(at)
    }

    /** OCR belongs to the exact frame submitted, never to boxes at response time. */
    fun acceptPlates(plates: List<TrackPlate>, frameAt: Long, now: Long): Boolean {
        if (now - frameAt !in 0..6000) return false
        val frame = snapshots.firstOrNull { it.first == frameAt }?.second ?: return false
        val owners = mutableMapOf<Long, TrackPlate>()
        plates.filter { it.confidence >= .75f && it.text.length in 7..9 && it.box.area > 0 }.forEach { plate ->
            val candidates = frame.filterValues { it.contains(plate.box.x, plate.box.y) }.entries.sortedBy { it.value.area }
            val owner = candidates.firstOrNull() ?: return@forEach
            if (candidates.size > 1 && candidates[1].value.area < owner.value.area * 1.3f) return@forEach
            if (plate.confidence > (owners[owner.key]?.confidence ?: 0f)) owners[owner.key] = plate
        }
        owners.forEach { (id, plate) ->
            tracks[id]?.let { track ->
                if (track.votes.none { it.first == frameAt }) track.votes.addLast(frameAt to plate)
                while (track.votes.size > 6) track.votes.removeFirst()
            }
        }
        return true
    }

    fun clearPlates() { tracks.values.forEach { it.votes.clear() } }
    fun current(at: Long): List<TrackedVehicle> = tracks.mapNotNull { (id, track) ->
        if (at - track.seen > occlusionMs) return@mapNotNull null
        val votes = track.votes.filter { at - it.first <= 15000 }.groupBy { it.second.text }
        val winner = votes.maxByOrNull { it.value.size * 2 + it.value.maxOf { v -> v.second.confidence }.toDouble() }
        val confirmed = winner != null && winner.value.size >= 2 && votes.values.none { it !== winner.value && it.size >= winner.value.size }
        TrackedVehicle(id, track.observation.copy(box = predict(track, at)), track.seen, track.path.toList(), at != track.seen, winner?.key, confirmed)
    }

    private fun predict(t: Track, at: Long) = t.observation.box.move(t.vx * (at - t.seen).coerceAtMost(1000), t.vy * (at - t.seen).coerceAtMost(1000))
    private fun appearanceDistance(a: List<Float>, b: List<Float>): Float =
        if (a.isEmpty() || a.size != b.size) 0f else a.indices.sumOf { abs(a[it] - b[it]).toDouble() }.toFloat() / 2
}

data class LiveIncident(val trackId: Long, val kind: String, val startAt: Long, var endAt: Long,
    var plate: String?, var plateConfirmed: Boolean, val reason: String)

/** Image motion alone is not an offence. Red-light needs a stop line that stays put. */
class IncidentWatch(var motionThreshold: Float = .045f, var tailMs: Long = 2500) {
    private val events = linkedMapOf<Pair<Long, String>, LiveIncident>()
    private val samples = ArrayDeque<RoadRules.Sample>()
    fun clear() { events.clear(); samples.clear() }
    fun update(tracks: List<TrackedVehicle>, at: Long, redStable: Boolean, road: RoadFrame = RoadFrame()): List<LiveIncident> {
        samples.addLast(RoadRules.Sample(at, tracks, road, redStable))
        while (samples.isNotEmpty() && at - samples.first().at > 2500) samples.removeFirst()
        events.entries.removeAll { at - it.value.endAt > tailMs + 3000 }
        val reasons = mapOf(
            "RED_LIGHT" to "红灯期间越过稳定停止线",
            "SOLID_LINE" to "越过画面中稳定的实线",
            "EMERGENCY_LANE" to "在最右侧实线以外行驶",
            "NO_SIGNAL" to "变道时未见转向灯",
            "OVERTAKE" to "越过虚线并超过相邻车辆",
            "LATERAL_MOVEMENT" to "同一车辆明显横向移动，需复核车道线及转向灯",
        )
        val hits = ArrayList<Pair<Long, String>>()
        for (track in tracks.filter { !it.predicted }) {
            val box = track.observation.box
            if (box.bottom !in .25f.. .96f || box.area !in .003f.. .45f) continue
            val path = track.path.filter { at - it.at <= 2500 }
            if (path.size < 4 || path.last().at - path.first().at < 800) continue
            if (abs(path.last().x - path.first().x) >= maxOf(.08f, motionThreshold)) hits.add(track.id to "LATERAL_MOVEMENT")
        }
        hits.addAll(RoadRules.kinds(samples.toList()))
        for ((id, kind) in hits) {
            val track = tracks.firstOrNull { it.id == id } ?: continue
            val event = events.getOrPut(id to kind) {
                LiveIncident(id, kind, at, at, track.plate, track.plateConfirmed, reasons[kind] ?: kind)
            }
            event.endAt = at
            event.plate = track.plate
            event.plateConfirmed = track.plateConfirmed
        }
        return events.values.map { it.copy() }
    }
    fun readyToFinish(at: Long, incidents: List<LiveIncident>) = incidents.isNotEmpty() &&
        incidents.all { at - it.endAt > tailMs + 3000 }
}
