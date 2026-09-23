package com.example.illegalcapture

object LiveVerdict {
    private val pending = setOf("PENDING_UPLOAD", "QUEUED", "PROCESSING")

    fun line(
        recording: Boolean,
        taskStatus: String?,
        redStable: Boolean,
        approaching: Boolean,
        plateConfirmed: Boolean,
    ): String? {
        if (recording) return "正在截取重点片段"
        if (taskStatus in pending) return "疑似闯红灯（待服务器复核）"
        if (!(redStable && approaching)) return null
        return if (plateConfirmed) "疑似闯红灯（本地已识别）" else "疑似闯红灯（车牌未确认）"
    }
}
