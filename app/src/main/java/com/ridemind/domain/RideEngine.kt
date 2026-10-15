package com.ridemind.domain

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class RidePhase { STATIONARY, STARTING, CRUISING, CLIMBING, RECOVERING }

data class RideSample(
    val elapsedRealtimeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val speedMetersPerSecond: Double,
    val horizontalAccuracyMeters: Double,
    val altitudeMeters: Double? = null,
    val verticalAccuracyMeters: Double? = null,
)

data class RideSnapshot(
    val elapsedMs: Long = 0,
    val distanceMeters: Double = 0.0,
    val currentSpeedMetersPerSecond: Double = 0.0,
    val averageSpeedMetersPerSecond: Double = 0.0,
    val gradePercent: Double = 0.0,
    val phase: RidePhase = RidePhase.STATIONARY,
    val moving: Boolean = false,
)

class RideEngine {
    private val samples = ArrayDeque<RideSample>()
    private var firstTimeMs: Long? = null
    private var lastSample: RideSample? = null
    private var distanceMeters = 0.0
    private var movingSinceMs: Long? = null
    private var climbingSinceMs: Long? = null
    private var wasClimbing = false
    private var current = RideSnapshot()

    fun reset() {
        samples.clear()
        firstTimeMs = null
        lastSample = null
        distanceMeters = 0.0
        movingSinceMs = null
        climbingSinceMs = null
        wasClimbing = false
        current = RideSnapshot()
    }

    fun accept(sample: RideSample): RideSnapshot {
        if (sample.horizontalAccuracyMeters > 40.0) return current
        val first = firstTimeMs ?: sample.elapsedRealtimeMs.also { firstTimeMs = it }
        val previous = lastSample
        if (previous != null) {
            val dtSeconds = (sample.elapsedRealtimeMs - previous.elapsedRealtimeMs) / 1_000.0
            val segment = haversineMeters(previous, sample)
            val plausibleLimit = maxOf(50.0, dtSeconds.coerceAtLeast(0.0) * 25.0)
            if (dtSeconds in 0.2..10.0 && segment <= plausibleLimit) distanceMeters += segment
        }
        lastSample = sample
        samples.addLast(sample)
        while (samples.isNotEmpty() && sample.elapsedRealtimeMs - samples.first().elapsedRealtimeMs > 20_000) {
            samples.removeFirst()
        }

        val moving = sample.speedMetersPerSecond >= 1.5
        if (moving) {
            if (movingSinceMs == null) movingSinceMs = sample.elapsedRealtimeMs
        } else movingSinceMs = null

        val grade = calculateGrade()
        if (moving && grade >= 2.0) {
            if (climbingSinceMs == null) climbingSinceMs = sample.elapsedRealtimeMs
        } else climbingSinceMs = null
        val climbing = climbingSinceMs?.let { sample.elapsedRealtimeMs - it >= 8_000 } == true
        val sustainedMotion = movingSinceMs?.let { sample.elapsedRealtimeMs - it >= 12_000 } == true
        val nextPhase = when {
            !moving -> RidePhase.STATIONARY
            climbing -> RidePhase.CLIMBING
            wasClimbing -> RidePhase.RECOVERING
            sustainedMotion -> RidePhase.CRUISING
            else -> RidePhase.STARTING
        }
        wasClimbing = nextPhase == RidePhase.CLIMBING
        val elapsed = (sample.elapsedRealtimeMs - first).coerceAtLeast(0)
        val average = if (elapsed > 0) distanceMeters / (elapsed / 1_000.0) else 0.0
        current = RideSnapshot(elapsed, distanceMeters, sample.speedMetersPerSecond.coerceAtLeast(0.0), average, grade, nextPhase, moving)
        return current
    }

    fun snapshot(): RideSnapshot = current

    private fun calculateGrade(): Double {
        val valid = samples.filter { it.altitudeMeters != null && (it.verticalAccuracyMeters ?: Double.MAX_VALUE) <= 8.0 }
        if (valid.size < 6) return 0.0
        val first = valid.first()
        val last = valid.last()
        val horizontal = haversineMeters(first, last)
        if (horizontal < 20.0) return 0.0
        return (((last.altitudeMeters!! - first.altitudeMeters!!) / horizontal) * 100.0).coerceIn(-15.0, 15.0)
    }

    private fun haversineMeters(a: RideSample, b: RideSample): Double {
        val radius = 6_371_000.0
        val lat1 = a.latitude * PI / 180.0
        val lat2 = b.latitude * PI / 180.0
        val dLat = (b.latitude - a.latitude) * PI / 180.0
        val dLon = (b.longitude - a.longitude) * PI / 180.0
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return radius * 2 * atan2(sqrt(h), sqrt(1 - h))
    }
}
