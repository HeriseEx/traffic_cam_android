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
            events = watch.update(tracker.update(listOf(car(.2f + i * .006f)), now), now, true)
        }
        val red = events.single { it.kind == "RED_LIGHT" }
        assertEquals(1L, red.trackId)
        assertTrue(red.startAt < red.endAt)
        assertFalse(watch.readyToFinish(4000, events))
        assertTrue(watch.readyToFinish(9000, events))
        val end = red.endAt
        watch.update(tracker.update(emptyList(), 3500), 3500, true)
        assertEquals(end, red.endAt) // predictions cannot fabricate fresh evidence
        assertEquals(1, events.count { it.kind == "RED_LIGHT" })
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
}
