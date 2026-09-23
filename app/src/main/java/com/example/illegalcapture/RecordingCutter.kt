package com.example.illegalcapture

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

data class IncidentWindow(val startMs: Long, val endMs: Long, val plate: String, val type: String,
    val event: LiveIncident)

/** Replay uses the same identity and event rules as the live camera. */
object RecordingCutter {
    fun scan(file: File, detector: VehicleDetector, recognize: (ByteArray) -> JSONObject,
             onProgress: ((Long, Long) -> Unit)? = null): List<IncidentWindow> {
        val retriever = MediaMetadataRetriever()
        val tracker = TrafficTracker()
        val hold = SignalHold()
        val watch = IncidentWatch()
        val events = linkedMapOf<String, LiveIncident>()
        var duration = 0L
        try {
            retriever.setDataSource(file.absolutePath)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            var t = 0L
            while (t < duration) {
                val bitmap = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: break
                try {
                    val detection = detector.detect(bitmap, .4f)
                    tracker.update(detection.observations(), t)
                    var observed = observeLamps(detection.lamps, detection.width.toFloat(), detection.height.toFloat())
                    if (t % 1500L == 0L) {
                        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                        val response = recognize(jpeg)
                        tracker.acceptPlates(parseTrackPlates(response), t, t)
                        if (observed == "OFF") observed = response.optString("signal_observed", "OFF")
                    }
                    hold.update(observed)
                    watch.update(tracker.current(t), t, hold.stable && hold.color == "RED").forEach {
                        events["${it.trackId}:${it.kind}:${it.startAt}"] = it.copy()
                    }
                } finally { bitmap.recycle() }
                onProgress?.invoke(t, duration)
                t += 500
            }
        } finally { retriever.release() }
        return events.values.flatMap { event ->
            val end = minOf(duration, event.endAt + watch.tailMs + 3000)
            var start = (event.startAt - 3000).coerceAtLeast(0)
            val parts = arrayListOf<IncidentWindow>()
            while (start < end) {
                val until = minOf(end, start + 70_000)
                parts.add(IncidentWindow(start, until, event.plate ?: "待确认", if (event.kind == "RED_LIGHT") "RED_LIGHT" else "UNKNOWN", event))
                if (until == end) break
                start = until - 3000
            }
            parts
        }.sortedBy { it.startMs }
    }
}
