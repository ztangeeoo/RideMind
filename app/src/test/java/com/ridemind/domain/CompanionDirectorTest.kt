package com.ridemind.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionDirectorTest {
    @Test
    fun `welcomes once after ride starts moving`() {
        val director = CompanionDirector(CompanionMode.STANDARD)
        val snapshot = snapshot(elapsed = 15_000, phase = RidePhase.STARTING)

        val first = director.evaluate(snapshot, nowMs = 15_000)
        val second = director.evaluate(snapshot, nowMs = 16_000)

        assertEquals(AnnouncementType.START, (first as AnnouncementDecision.Speak).event.type)
        assertTrue(second is AnnouncementDecision.Drop)
    }

    @Test
    fun `never emits social speech during fast descent`() {
        val director = CompanionDirector(CompanionMode.TALKATIVE)
        val result = director.evaluate(
            snapshot(elapsed = 1_900_000, phase = RidePhase.CRUISING, speed = 14.5, grade = -4.0),
            nowMs = 1_900_000,
        )

        assertEquals(DropReason.UNSAFE_CONTEXT, (result as AnnouncementDecision.Drop).reason)
    }

    @Test
    fun `mute suppresses non critical announcements`() {
        val director = CompanionDirector(CompanionMode.STANDARD)
        director.muteUntil(700_000)

        val result = director.evaluate(
            snapshot(elapsed = 600_000, distance = 5_050.0, phase = RidePhase.CRUISING),
            nowMs = 600_000,
        )

        assertEquals(DropReason.MUTED, (result as AnnouncementDecision.Drop).reason)
    }

    @Test
    fun `distance milestone is emitted once`() {
        val director = CompanionDirector(CompanionMode.STANDARD)
        val first = director.evaluate(
            snapshot(elapsed = 500_000, distance = 5_010.0, phase = RidePhase.CRUISING),
            nowMs = 500_000,
        )
        val second = director.evaluate(
            snapshot(elapsed = 600_000, distance = 5_200.0, phase = RidePhase.CRUISING),
            nowMs = 600_000,
        )

        assertEquals(AnnouncementType.DISTANCE, (first as AnnouncementDecision.Speak).event.type)
        assertTrue(second is AnnouncementDecision.Drop)
    }

    @Test
    fun `expired queued event is dropped`() {
        val director = CompanionDirector(CompanionMode.STANDARD)
        val event = AnnouncementEvent(
            type = AnnouncementType.CLIMB_START,
            priority = 2,
            createdAtMs = 10_000,
            validUntilMs = 20_000,
            dedupeKey = "climb-1",
            text = "开始爬坡，按自己的节奏来。",
            social = true,
        )

        val result = director.assess(event, snapshot(elapsed = 30_000), nowMs = 30_000)

        assertEquals(DropReason.EXPIRED, (result as AnnouncementDecision.Drop).reason)
    }

    private fun snapshot(
        elapsed: Long,
        distance: Double = 100.0,
        speed: Double = 6.0,
        grade: Double = 0.0,
        phase: RidePhase = RidePhase.CRUISING,
    ) = RideSnapshot(
        elapsedMs = elapsed,
        distanceMeters = distance,
        currentSpeedMetersPerSecond = speed,
        averageSpeedMetersPerSecond = 5.5,
        gradePercent = grade,
        phase = phase,
        moving = speed > 1.5,
    )
}
