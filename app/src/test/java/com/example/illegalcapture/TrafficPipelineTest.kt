package com.example.illegalcapture

import org.junit.Assert.*
import org.junit.Test

class TrafficPipelineTest {
    private fun car(x: Float, look: List<Float> = listOf(1f, 0f)) = TrackObservation("汽车", .9f,
        TrackBox(x, .35f, x + .15f, .6f), look)

    @Test fun occlusionRetainsIdentityButExpiredTrackCannotInheritPlate() {
        val tracker = TrafficTracker()
        val id = tracker.update(listOf(car(.2f)), 0).single().id
        tracker.update(listOf(car(.21f)), 200)
        assertTrue(tracker.update(emptyList(), 800).single().predicted)
        assertEquals(id, tracker.update(listOf(car(.25f)), 1000).single().id)
        tracker.update(emptyList(), 4100)
        assertNotEquals(id, tracker.update(listOf(car(.25f)), 4200).single().id)
    }

    @Test fun delayedOcrUsesCapturedFrameAndVotesStayOnTheirOwnVehicle() {
        val tracker = TrafficTracker()
        val first = tracker.update(listOf(car(.2f), car(.65f, listOf(0f, 1f))), 0)
        for (i in 1..10) tracker.update(listOf(car(.2f + i * .025f), car(.65f, listOf(0f, 1f))), i * 200L)
        val plate = TrackPlate("川A12345", .98f, TrackBox(.23f, .53f, .31f, .57f))
        assertTrue(tracker.acceptPlates(listOf(plate), 0, 2000))
        assertFalse(tracker.current(2000).first { it.id == first[0].id }.plateConfirmed)
        val second = plate.copy(box = TrackBox(.255f, .53f, .335f, .57f))
        tracker.acceptPlates(listOf(second), 200, 2200)
        val tracked = tracker.current(2200)
        assertEquals("川A12345", tracked.first { it.id == first[0].id }.plate)
        assertTrue(tracked.first { it.id == first[0].id }.plateConfirmed)
        assertNull(tracked.first { it.id == first[1].id }.plate)
        assertFalse(tracker.acceptPlates(listOf(plate), 0, 7000))
    }

    @Test fun crossingCarsDoNotExchangeIdentitiesOrCountOneOcrResponseTwice() {
        val tracker = TrafficTracker()
        val initial = tracker.update(listOf(car(.2f), car(.6f, listOf(0f, 1f))), 0)
        val plate = TrackPlate("川A12345", .98f, TrackBox(.23f, .53f, .31f, .57f))
        tracker.acceptPlates(listOf(plate), 0, 100)
        tracker.acceptPlates(listOf(plate), 0, 200)
        assertFalse(tracker.current(200).first().plateConfirmed)
        for (i in 1..10) {
            val rows = tracker.update(listOf(car(.2f + i * .04f), car(.6f - i * .04f, listOf(0f, 1f))), i * 200L)
            assertEquals(initial[0].id, rows.first { it.observation.appearance[0] == 1f && !it.predicted }.id)
            assertEquals(initial[1].id, rows.first { it.observation.appearance[1] == 1f && !it.predicted }.id)
        }
    }

    @Test fun ambiguousSameColourReappearanceDoesNotStealEitherId() {
        val tracker = TrafficTracker()
        val old = tracker.update(listOf(car(.2f), car(.5f)), 0).map { it.id }
        tracker.update(emptyList(), 200)
        val visible = tracker.update(listOf(car(.35f)), 500).single { !it.predicted }
        assertFalse(visible.id in old)
    }

    @Test fun eventDurationFollowsMovementAndSurvivesBriefOcclusion() {
        val tracker = TrafficTracker()
        val watch = IncidentWatch()
        var events = emptyList<LiveIncident>()
        for (i in 0..16) {
            val now = i * 200L
            events = watch.update(tracker.update(listOf(car(.2f + i * .012f)), now), now, true)
        }
        assertTrue(events.none { it.kind == "RED_LIGHT" })
        val red = events.single { it.kind == "LATERAL_MOVEMENT" }
        assertEquals(1L, red.trackId)
        assertTrue(red.startAt < red.endAt)
        assertFalse(watch.readyToFinish(4000, events))
        assertTrue(watch.readyToFinish(9000, events))
        val end = red.endAt
        watch.update(tracker.update(emptyList(), 3500), 3500, true)
        assertEquals(end, red.endAt) // predictions cannot fabricate fresh evidence
        assertEquals(1, events.count { it.kind == "LATERAL_MOVEMENT" })
    }

    @Test fun stationaryVehiclesAndMotionBeforeRedDoNotTriggerRedEvent() {
        val tracker = TrafficTracker(); val watch = IncidentWatch()
        repeat(20) { i ->
            val now = i * 200L
            val events = watch.update(tracker.update(listOf(car(.2f)), now), now, true)
            assertTrue(events.isEmpty())
        }
        watch.clear(); tracker.clear()
        repeat(8) { i ->
            val now = i * 200L
            val events = watch.update(tracker.update(listOf(car(.2f + i * .012f)), now), now, i >= 6)
            assertFalse(events.any { it.kind == "RED_LIGHT" })
        }
    }

    @Test fun offenceMenuKeepsParkingOutOfAutoAndRequiresMatchingPlates() {
        assertTrue(ViolationPolicy.autoReady("AUTO", "RED_LIGHT", "川A12345", true))
        assertTrue(ViolationPolicy.allows("AUTO", "CUT_IN"))
        assertFalse(ViolationPolicy.allows("RED_LIGHT", "SOLID_LINE"))
        assertFalse(ViolationPolicy.autoReady("AUTO", "RED_LIGHT", null, false))
        assertFalse(ViolationPolicy.mode("ILLEGAL_PARKING").kinds.contains("RED_LIGHT"))
        assertEquals("两张都要识别到车牌", ViolationPolicy.parkingBlock(mapOf("川A12345" to 1f), emptyMap()))
        assertEquals("两张车牌不一致，请重拍", ViolationPolicy.parkingBlock(mapOf("川A12345" to 1f), mapOf("川B12345" to 1f)))
        assertNull(ViolationPolicy.parkingBlock(mapOf("川A12345" to 1f), mapOf("川A12345" to 1f)))
        assertEquals("川A12345", ViolationPolicy.sharedPlate(mapOf("川A12345" to 2f, "川B99999" to 9f), mapOf("川A12345" to 4f, "川C00000" to 1f)))
        assertNull(ViolationPolicy.parkingBlock(mapOf("川A12345" to 2f, "川B99999" to 9f), mapOf("川A12345" to 4f, "川C00000" to 8f)))
        assertEquals("川A12345", ViolationPolicy.sharedPlate(mapOf("川A12345" to 1f, "川B99999" to 9f), mapOf("川A12345" to 8f, "川B99999" to 1f)))
        assertFalse(ViolationPolicy.holdKept(2999))
        assertTrue(ViolationPolicy.holdKept(3000))
        assertEquals(8000L, ViolationPolicy.LEAD_MS)
    }

    @Test fun redLightRequiresStableStopLineAndDoesNotInventCutIn() {
        val gray = IntArray(160 * 90)
        for (x in 10 until 150) gray[50 * 160 + x] = 220
        assertEquals(50f / 90f, RoadScan.stopY(gray, 160, 90)!!, .02f)
        assertNull(RoadScan.stopY(IntArray(160 * 90), 160, 90))
        val tracker = TrafficTracker()
        val watch = IncidentWatch()
        var events = emptyList<LiveIncident>()
        for (i in 0..8) {
            val now = i * 200L
            val bottom = .40f + i * .04f
            val box = TrackObservation("汽车", .9f, TrackBox(.4f, bottom - .2f, .55f, bottom))
            events = watch.update(tracker.update(listOf(box), now), now, true, RoadFrame(.55f))
        }
        assertTrue(events.any { it.kind == "RED_LIGHT" })
        watch.clear(); tracker.clear()
        for (i in 0..8) {
            val now = i * 200L
            val bottom = .40f + i * .04f
            val box = TrackObservation("汽车", .9f, TrackBox(.4f, bottom - .2f, .55f, bottom))
            events = watch.update(tracker.update(listOf(box), now), now, true, RoadFrame(.30f + i * .05f))
        }
        assertTrue(events.none { it.kind == "RED_LIGHT" })
        watch.clear(); tracker.clear()
        val line = RoadLine(.5f, .2f, .5f, .9f, true)
        for (i in listOf(.30f, .38f, .46f, .62f).indices) {
            val now = i * 200L
            val x = listOf(.30f, .38f, .46f, .62f)[i]
            val box = TrackObservation("汽车", .9f, TrackBox(x - .05f, .45f, x + .05f, .7f), signalOff = true)
            events = watch.update(tracker.update(listOf(box), now), now, false, RoadFrame(lines = listOf(line)))
        }
        assertTrue(events.any { it.kind == "SOLID_LINE" })
        assertTrue(events.any { it.kind == "NO_SIGNAL" })
        assertTrue(events.none { it.kind == "CUT_IN" || it.kind == "DANGEROUS_CHANGE" })
    }

    @Test fun resolutionKeepsModeAndFallsBackBelow1080() {
        val hd = RecordingTier.byId("720")
        val fhd = RecordingTier.byId("1080")
        val uhd = RecordingTier.byId("2160")
        assertEquals("1080", RecordingTier.choose(listOf(hd, fhd, uhd), "1080").id)
        assertEquals("2160", RecordingTier.choose(listOf(hd, fhd, uhd), "2160").id)
        assertEquals("720", RecordingTier.choose(listOf(hd), "2160").id)
        assertEquals("1080", RecordingTier.choose(listOf(hd, fhd, uhd), "1440").id)
        assertNull(RecordingTier.qualityName("1440"))
        assertTrue(RecordingTier.maxSeconds(hd) > RecordingTier.maxSeconds(fhd))
        assertTrue(RecordingTier.maxSeconds(fhd) > RecordingTier.maxSeconds(uhd))
    }
}
