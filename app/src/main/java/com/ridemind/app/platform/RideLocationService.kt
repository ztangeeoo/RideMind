package com.ridemind.app.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.ridemind.app.R
import com.ridemind.app.RideEventLog
import com.ridemind.app.RideSessionState
import com.ridemind.app.RideUiState
import com.ridemind.app.UiCompanionMode
import com.ridemind.domain.AnnouncementDecision
import com.ridemind.domain.CompanionDirector
import com.ridemind.domain.CompanionMode
import com.ridemind.domain.DropReason
import com.ridemind.domain.RideEngine
import com.ridemind.domain.RideSample
import com.ridemind.domain.RideSnapshot

class RideLocationService : Service() {
    inner class LocalBinder : Binder() { fun service(): RideLocationService = this@RideLocationService }

    private val binder = LocalBinder()
    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var speech: RideSpeech
    private var engine = RideEngine()
    private var director = CompanionDirector(CompanionMode.STANDARD)
    private var state = RideUiState()
    private val listeners = mutableSetOf<(RideUiState) -> Unit>()
    private val events = mutableListOf<RideEventLog>()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (state.session != RideSessionState.RECORDING) return
            result.locations.forEach(::acceptLocation)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        speech = RideSpeech(this).also { helper ->
            helper.onError = { message -> update(state.copy(statusMessage = message)) }
        }
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun observe(listener: (RideUiState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeObserver(listener: (RideUiState) -> Unit) { listeners -= listener }

    fun startRide(): Boolean {
        if (!hasLocationPermission()) return false
        engine.reset()
        director.reset()
        events.clear()
        state = RideUiState(session = RideSessionState.RECORDING, mode = state.mode, statusMessage = "正在记录")
        startForeground(NOTIFICATION_ID, notification("正在记录骑行"))
        requestLocations()
        notifyState()
        return true
    }

    fun pauseRide() {
        if (state.session != RideSessionState.RECORDING) return
        fusedLocation.removeLocationUpdates(locationCallback)
        update(state.copy(session = RideSessionState.PAUSED, statusMessage = "已暂停"))
    }

    fun resumeRide() {
        if (state.session != RideSessionState.PAUSED || !hasLocationPermission()) return
        requestLocations()
        update(state.copy(session = RideSessionState.RECORDING, statusMessage = "继续记录"))
    }

    fun finishRide() {
        fusedLocation.removeLocationUpdates(locationCallback)
        val snapshot = engine.snapshot()
        val text = "骑行结束，共骑行 %.1f 公里，平均时速 %.1f 公里。".format(
            snapshot.distanceMeters / 1000.0,
            snapshot.averageSpeedMetersPerSecond * 3.6,
        )
        val spoken = speech.speak(text, "ride-end")
        events += RideEventLog(System.currentTimeMillis(), "结束回顾", text, if (spoken) "已播报" else "未播报")
        update(state.copy(session = RideSessionState.FINISHED, snapshot = snapshot, statusMessage = "骑行已结束", events = events.toList()))
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    fun setMode(mode: UiCompanionMode) {
        director.setMode(
            when (mode) {
                UiCompanionMode.CONCISE -> CompanionMode.QUIET
                UiCompanionMode.STANDARD -> CompanionMode.STANDARD
                UiCompanionMode.TALKATIVE -> CompanionMode.TALKATIVE
            }
        )
        update(state.copy(mode = mode))
    }

    fun muteTenMinutes() {
        val until = SystemClock.elapsedRealtime() + 10 * 60 * 1000L
        director.muteUntil(until)
        update(state.copy(mutedUntilMs = until, statusMessage = "已静音 10 分钟"))
    }

    fun unmute() {
        director.muteUntil(0L)
        update(state.copy(mutedUntilMs = 0L, statusMessage = "陪伴语音已恢复"))
    }

    fun addFeedback(index: Int, feedback: String) {
        events.getOrNull(index)?.feedback = feedback
        update(state.copy(events = events.toList()))
    }

    private fun acceptLocation(location: Location) {
        val snapshot = engine.accept(
            RideSample(
                elapsedRealtimeMs = location.elapsedRealtimeNanos / 1_000_000L,
                latitude = location.latitude,
                longitude = location.longitude,
                speedMetersPerSecond = if (location.hasSpeed()) location.speed.toDouble() else 0.0,
                horizontalAccuracyMeters = location.accuracy.toDouble(),
                altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
                verticalAccuracyMeters = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
                    location.verticalAccuracyMeters.toDouble()
                } else null,
            )
        )
        evaluateAnnouncement(snapshot)
        update(state.copy(snapshot = snapshot, events = events.toList()))
    }

    private fun evaluateAnnouncement(snapshot: RideSnapshot) {
        val now = SystemClock.elapsedRealtime()
        when (val decision = director.evaluate(snapshot, now)) {
            is AnnouncementDecision.Speak -> {
                val spoken = speech.speak(decision.event.text, decision.event.dedupeKey)
                events += RideEventLog(System.currentTimeMillis(), decision.event.type.name, decision.event.text, if (spoken) "已播报" else "音频焦点/TTS 失败")
            }
            is AnnouncementDecision.Drop -> {
                val event = decision.event
                if (event != null && decision.reason != DropReason.NO_EVENT) {
                    events += RideEventLog(System.currentTimeMillis(), event.type.name, event.text, "丢弃：${decision.reason.name}")
                }
            }
        }
    }

    private fun requestLocations() {
        if (!hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(800L)
            .build()
        fusedLocation.requestLocationUpdates(request, locationCallback, mainLooper)
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun update(newState: RideUiState) {
        state = newState
        notifyState()
    }

    private fun notifyState() = listeners.toList().forEach { it(state) }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "骑行记录", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        fusedLocation.removeLocationUpdates(locationCallback)
        speech.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "ride_tracking"
        private const val NOTIFICATION_ID = 1001
    }
}
