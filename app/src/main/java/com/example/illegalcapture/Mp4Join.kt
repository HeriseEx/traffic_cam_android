package com.example.illegalcapture

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

data class VideoSegment(val file: File, val startMs: Long, val endMs: Long)
data class ClipTiming(val startMs: Long, val endMs: Long, val gapsMs: Long)

object Mp4Join {
    fun concat(inputs: List<File>, output: File) {
        if (inputs.size == 1) { inputs.single().copyTo(output, overwrite = true); return }
        var at = 0L
        val segments = inputs.map { file ->
            val duration = durationMs(file)
            VideoSegment(file, at, at + duration).also { at += duration }
        }
        window(segments, output, 0, at)
    }

    fun durationMs(file: File): Long = MediaMetadataRetriever().let { reader ->
        try { reader.setDataSource(file.absolutePath)
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: error("视频无时长")
        } finally { reader.release() }
    }

    fun extract(input: File, output: File, startUs: Long, endUs: Long) {
        window(listOf(VideoSegment(input, 0, durationMs(input))), output, startUs / 1000, endUs / 1000)
    }

    /** Seek back to a decodable keyframe and report the actual retained interval, including recorder gaps. */
    fun window(segments: List<VideoSegment>, output: File, startMs: Long, endMs: Long): ClipTiming {
        require(endMs > startMs) { "裁剪区间无效" }
        val parts = segments.filter { it.endMs > startMs && it.startMs < endMs }.sortedBy { it.startMs }
        require(parts.isNotEmpty() && parts.all { it.file.isFile && it.file.length() > 0 }) { "缓存片段缺失" }
        val temp = File(output.parentFile, output.name + ".partial")
        val muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var success = false
        var track = -1
        var origin = -1L
        var last = -1L
        var finalFrameDuration = 33_333L
        var expected: MediaFormat? = null
        val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
        try {
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(parts.first().file.absolutePath)
                muxer.setOrientationHint(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0)
            } finally { reader.release() }
            for (part in parts) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(part.file.absolutePath)
                    val index = (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                    } ?: error("视频轨不存在")
                    val format = extractor.getTrackFormat(index)
                    if (!started) {
                        expected = format; track = muxer.addTrack(format); muxer.start(); started = true
                    } else {
                        require(listOf(MediaFormat.KEY_MIME, MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, "csd-0", "csd-1").all { key ->
                            when (key) {
                                MediaFormat.KEY_MIME -> format.getString(key) == expected!!.getString(key)
                                MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT -> format.getInteger(key) == expected!!.getInteger(key)
                                else -> format.getByteBuffer(key) == expected!!.getByteBuffer(key)
                            }
                        }) { "录像编码参数已变化，请重新开始" }
                    }
                    extractor.selectTrack(index)
                    val frameDuration = 1_000_000L / (if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceAtLeast(1) else 30)
                    val base = if (last < 0) part.startMs * 1000 else maxOf(part.startMs * 1000, last + finalFrameDuration)
                    val sourceOrigin = extractor.sampleTime.coerceAtLeast(0)
                    extractor.seekTo(sourceOrigin + (startMs - part.startMs).coerceAtLeast(0) * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val info = MediaCodec.BufferInfo()
                    while (extractor.sampleTime >= 0) {
                        val stamp = base + extractor.sampleTime - sourceOrigin
                        if (stamp > endMs * 1000) break
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        require(size <= buffer.capacity()) { "视频帧超限" }
                        if (origin < 0 || stamp >= origin) {
                            if (origin < 0) origin = stamp
                            info.set(0, size, stamp - origin, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            // Keep encoder/decode order. B-frame presentation times legitimately run backwards.
                            muxer.writeSampleData(track, buffer, info); last = maxOf(last, stamp)
                            finalFrameDuration = frameDuration
                        }
                        extractor.advance()
                    }
                } finally { extractor.release() }
            }
            require(origin >= 0 && last > origin) { "裁剪结果为空" }
            last = minOf(last + finalFrameDuration, endMs * 1000)
            val end = MediaCodec.BufferInfo().apply { set(0, 0, last - origin, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
            muxer.writeSampleData(track, buffer, end)
            muxer.stop(); started = false
            require(temp.length() <= 50L * 1024 * 1024 && last - origin < 89_000_000) { "片段超过服务器限制，原始缓存已保留" }
            success = true
        } finally {
            try { if (started) muxer.stop() } catch (_: Exception) { }
            muxer.release()
            if (!success) temp.delete()
        }
        if (output.exists()) require(output.delete())
        require(temp.renameTo(output)) { "无法保存片段" }
        return ClipTiming(origin / 1000, last / 1000, parts.zipWithNext().sumOf { (a, b) -> (b.startMs - a.endMs).coerceAtLeast(0) })
    }
}
