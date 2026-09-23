package com.example.illegalcapture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveVerdictTest {
    @Test fun waitingOnServerRequiresAnInFlightTask() {
        assertEquals(
            "疑似闯红灯（车牌未确认）",
            LiveVerdict.line(false, null, true, true, false),
        )
        assertEquals(
            "疑似闯红灯（本地已识别）",
            LiveVerdict.line(false, null, true, true, true),
        )
        assertEquals(
            "疑似闯红灯（待服务器复核）",
            LiveVerdict.line(false, "PENDING_UPLOAD", true, true, true),
        )
        assertEquals("正在截取重点片段", LiveVerdict.line(true, null, true, true, true))
        assertNull(LiveVerdict.line(false, "ANALYZED", false, false, true))
    }
}
