package com.ridemind.domain

enum class CompanionMode(val socialBudgetPerHour: Int) { QUIET(2), STANDARD(6), TALKATIVE(10) }
enum class AnnouncementType { START, CRUISE, CLIMB_START, CLIMB_END, DISTANCE, HALF_HOUR, RESUME, FINISH }
enum class DropReason { NO_EVENT, MUTED, UNSAFE_CONTEXT, COOLDOWN, BUDGET_EXHAUSTED, EXPIRED, DUPLICATE }

data class AnnouncementEvent(
    val type: AnnouncementType,
    val priority: Int,
    val createdAtMs: Long,
    val validUntilMs: Long,
    val dedupeKey: String,
    val text: String,
    val social: Boolean,
    val interruptible: Boolean = true,
)

sealed interface AnnouncementDecision {
    data class Speak(val event: AnnouncementEvent) : AnnouncementDecision
    data class Drop(val reason: DropReason, val event: AnnouncementEvent? = null) : AnnouncementDecision
}

class CompanionDirector(mode: CompanionMode = CompanionMode.STANDARD) {
    private var mode = mode
    private var mutedUntilMs = 0L
    private val spokenKeys = mutableSetOf<String>()
    private val lastSpokenByType = mutableMapOf<AnnouncementType, Long>()
    private val socialSpokenAt = ArrayDeque<Long>()
    private var lastSpokenAt = Long.MIN_VALUE
    private var previousPhase = RidePhase.STATIONARY
    private var nextDistanceMeters = 5_000.0
    private var halfHourEmitted = false

    fun setMode(value: CompanionMode) { mode = value }
    fun muteUntil(elapsedRealtimeMs: Long) { mutedUntilMs = elapsedRealtimeMs }
    fun reset() {
        mutedUntilMs = 0
        spokenKeys.clear()
        lastSpokenByType.clear()
        socialSpokenAt.clear()
        lastSpokenAt = Long.MIN_VALUE
        previousPhase = RidePhase.STATIONARY
        nextDistanceMeters = 5_000.0
        halfHourEmitted = false
    }

    fun evaluate(snapshot: RideSnapshot, nowMs: Long): AnnouncementDecision {
        val event = candidate(snapshot, nowMs)
            ?: return if (isUnsafe(snapshot)) AnnouncementDecision.Drop(DropReason.UNSAFE_CONTEXT)
            else AnnouncementDecision.Drop(DropReason.NO_EVENT)
        previousPhase = snapshot.phase
        return assess(event, snapshot, nowMs)
    }

    fun finish(snapshot: RideSnapshot, nowMs: Long): AnnouncementDecision = assess(
        AnnouncementEvent(
            AnnouncementType.FINISH, 2, nowMs, nowMs + 30_000, "finish",
            "骑行结束。${formatDistance(snapshot.distanceMeters)}，用时${formatDuration(snapshot.elapsedMs)}。今天辛苦了。", false,
        ), snapshot, nowMs,
    )

    fun assess(event: AnnouncementEvent, snapshot: RideSnapshot, nowMs: Long): AnnouncementDecision {
        if (nowMs > event.validUntilMs) return AnnouncementDecision.Drop(DropReason.EXPIRED, event)
        if (nowMs < mutedUntilMs) return AnnouncementDecision.Drop(DropReason.MUTED, event)
        if (event.social && isUnsafe(snapshot)) return AnnouncementDecision.Drop(DropReason.UNSAFE_CONTEXT, event)
        if (event.dedupeKey in spokenKeys) return AnnouncementDecision.Drop(DropReason.DUPLICATE, event)
        val lastOfType = lastSpokenByType[event.type]
        if (lastOfType != null && nowMs - lastOfType < 600_000) return AnnouncementDecision.Drop(DropReason.COOLDOWN, event)
        if (lastSpokenAt != Long.MIN_VALUE && nowMs - lastSpokenAt < 90_000 && event.priority > 1) {
            return AnnouncementDecision.Drop(DropReason.COOLDOWN, event)
        }
        while (socialSpokenAt.isNotEmpty() && nowMs - socialSpokenAt.first() >= 3_600_000) socialSpokenAt.removeFirst()
        if (event.social && socialSpokenAt.size >= mode.socialBudgetPerHour) {
            return AnnouncementDecision.Drop(DropReason.BUDGET_EXHAUSTED, event)
        }
        spokenKeys += event.dedupeKey
        lastSpokenByType[event.type] = nowMs
        lastSpokenAt = nowMs
        if (event.social) socialSpokenAt.addLast(nowMs)
        return AnnouncementDecision.Speak(event)
    }

    private fun candidate(snapshot: RideSnapshot, nowMs: Long): AnnouncementEvent? {
        if (snapshot.phase == RidePhase.STARTING && previousPhase == RidePhase.STATIONARY) {
            return event(AnnouncementType.START, nowMs, "start", "出发吧。今天按自己的节奏骑，我会在需要的时候陪你。", false)
        }
        if (snapshot.distanceMeters >= nextDistanceMeters) {
            val km = (nextDistanceMeters / 1_000).toInt()
            nextDistanceMeters += 5_000
            return event(AnnouncementType.DISTANCE, nowMs, "distance-$km", "已经骑了${km}公里，节奏不错。", false)
        }
        if (snapshot.phase == RidePhase.CLIMBING && previousPhase != RidePhase.CLIMBING) {
            return event(AnnouncementType.CLIMB_START, nowMs, "climb-${nowMs / 600_000}", "开始爬坡了，别急，先把呼吸和节奏稳住。", true)
        }
        if (snapshot.phase == RidePhase.RECOVERING && previousPhase == RidePhase.CLIMBING) {
            return event(AnnouncementType.CLIMB_END, nowMs, "climb-end-${nowMs / 600_000}", "坡顶到了，做得不错，缓一缓再继续。", true)
        }
        if (!halfHourEmitted && snapshot.elapsedMs >= 1_800_000) {
            halfHourEmitted = true
            return event(AnnouncementType.HALF_HOUR, nowMs, "half-hour", "已经一起骑了半小时。感觉如何？需要安静一点，随时可以告诉我。", true)
        }
        if (snapshot.phase == RidePhase.CRUISING && previousPhase == RidePhase.STARTING) {
            return event(AnnouncementType.CRUISE, nowMs, "first-cruise", "现在进入巡航了。路况简单时，我偶尔陪你说两句。", true)
        }
        return null
    }

    private fun event(type: AnnouncementType, nowMs: Long, key: String, text: String, social: Boolean) =
        AnnouncementEvent(type, if (social) 3 else 2, nowMs, nowMs + 20_000, key, text, social)

    private fun isUnsafe(snapshot: RideSnapshot) = snapshot.currentSpeedMetersPerSecond >= 13.9 || snapshot.gradePercent <= -3.0
    private fun formatDistance(meters: Double) = "%.1f公里".format(meters / 1_000.0)
    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes < 60) "${minutes}分钟" else "${minutes / 60}小时${minutes % 60}分钟"
    }
}
