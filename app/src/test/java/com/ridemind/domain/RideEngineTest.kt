package com.ridemind.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideEngineTest {
    @Test
    fun `ignores implausible gps jump when accumulating distance`() {
        val engine = RideEngine()
        engine.accept(sample(t = 0, lat = 31.2304, lon = 121.4737, speed = 5.0))
        engine.accept(sample(t = 1_000, lat = 32.2304, lon = 122.4737, speed = 5.0))

        assertEquals(0.0, engine.snapshot().distanceMeters, 0.1)
    }

    @Test
    fun `moves through starting to cruising after sustained motion`() {
        val engine = RideEngine()
        repeat(16) { second ->
            engine.accept(sample(t = second * 1_000L, lon = 121.4737 + second * 0.00005, speed = 6.0))
        }

        assertEquals(RidePhase.CRUISING, engine.snapshot().phase)
    }

    @Test
    fun `detects sustained climb from smoothed altitude gain`() {
        val engine = RideEngine()
        repeat(24) { second ->
            engine.accept(
                sample(
                    t = second * 1_000L,
                    lon = 121.4737 + second * 0.00005,
                    speed = 5.0,
                    altitude = 10.0 + second * 0.45,
                    verticalAccuracy = 2.0,
                )
            )
        }

        assertEquals(RidePhase.CLIMBING, engine.snapshot().phase)
        assertTrue(engine.snapshot().gradePercent > 2.0)
    }

    private fun sample(
        t: Long,
        lat: Double = 31.2304,
        lon: Double = 121.4737,
        speed: Double,
        altitude: Double? = 10.0,
        verticalAccuracy: Double? = 2.0,
    ) = RideSample(
        elapsedRealtimeMs = t,
        latitude = lat,
        longitude = lon,
        speedMetersPerSecond = speed,
        horizontalAccuracyMeters = 3.0,
        altitudeMeters = altitude,
        verticalAccuracyMeters = verticalAccuracy,
    )
}
