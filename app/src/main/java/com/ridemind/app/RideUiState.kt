package com.ridemind.app

import com.ridemind.domain.RideSnapshot

enum class RideSessionState { IDLE, RECORDING, PAUSED, FINISHED }
enum class UiCompanionMode { CONCISE, STANDARD, TALKATIVE }

data class RideEventLog(
    val timestampMs: Long,
    val trigger: String,
    val text: String,
    val result: String,
    var feedback: String? = null,
)

data class RideUiState(
    val session: RideSessionState = RideSessionState.IDLE,
    val snapshot: RideSnapshot = RideSnapshot(),
    val mode: UiCompanionMode = UiCompanionMode.STANDARD,
    val mutedUntilMs: Long = 0L,
    val statusMessage: String = "准备出发",
    val events: List<RideEventLog> = emptyList(),
)
