package com.ridemind.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ridemind.app.databinding.ActivityMainBinding
import com.ridemind.app.platform.RideLocationService
import com.ridemind.domain.RidePhase
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var rideService: RideLocationService? = null
    private var bound = false
    private var startWhenConnected = false
    private var latestState = RideUiState()
    private val stateObserver: (RideUiState) -> Unit = { runOnUiThread { render(it) } }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            rideService = (binder as RideLocationService.LocalBinder).service()
            bound = true
            rideService?.observe(stateObserver)
            if (startWhenConnected) {
                startWhenConnected = false
                rideService?.startRide()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            rideService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) startRideServiceAndRide()
        else render(latestState.copy(statusMessage = "需要精确位置权限才能开始骑行"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindActions()
        bindService(Intent(this, RideLocationService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun bindActions() = with(binding) {
        startPauseButton.setOnClickListener {
            when (latestState.session) {
                RideSessionState.IDLE, RideSessionState.FINISHED -> ensurePermissionsAndStart()
                RideSessionState.RECORDING -> rideService?.pauseRide()
                RideSessionState.PAUSED -> rideService?.resumeRide()
            }
        }
        finishButton.setOnClickListener { rideService?.finishRide() }
        modeGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            rideService?.setMode(
                when (checkedId) {
                    R.id.conciseButton -> UiCompanionMode.CONCISE
                    R.id.talkativeButton -> UiCompanionMode.TALKATIVE
                    else -> UiCompanionMode.STANDARD
                }
            )
        }
        muteButton.setOnClickListener { rideService?.muteTenMinutes() }
        unmuteButton.setOnClickListener { rideService?.unmute() }
    }

    private fun ensurePermissionsAndStart() {
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) startRideServiceAndRide() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startRideServiceAndRide() {
        ContextCompat.startForegroundService(this, Intent(this, RideLocationService::class.java))
        if (bound) rideService?.startRide()
        else {
            startWhenConnected = true
            binding.statusText.text = "正在启动骑行服务…"
        }
    }

    private fun render(state: RideUiState) {
        latestState = state
        val snapshot = state.snapshot
        binding.statusText.text = state.statusMessage
        binding.durationText.text = formatDuration(snapshot.elapsedMs)
        binding.distanceText.text = String.format(Locale.getDefault(), "%.2f km", snapshot.distanceMeters / 1000.0)
        binding.speedText.text = String.format(Locale.getDefault(), "%.1f / %.1f km/h", snapshot.currentSpeedMetersPerSecond * 3.6, snapshot.averageSpeedMetersPerSecond * 3.6)
        binding.phaseText.text = phaseLabel(snapshot.phase)
        binding.startPauseButton.text = when (state.session) {
            RideSessionState.IDLE, RideSessionState.FINISHED -> getString(R.string.start_ride)
            RideSessionState.RECORDING -> getString(R.string.pause_ride)
            RideSessionState.PAUSED -> getString(R.string.resume_ride)
        }
        binding.finishButton.isEnabled = state.session == RideSessionState.RECORDING || state.session == RideSessionState.PAUSED
        binding.summaryText.text = if (state.session == RideSessionState.FINISHED) {
            "本次：${formatDuration(snapshot.elapsedMs)} · %.2f km · 均速 %.1f km/h · 播报 %d 条".format(
                snapshot.distanceMeters / 1000.0,
                snapshot.averageSpeedMetersPerSecond * 3.6,
                state.events.count { it.result == "已播报" },
            )
        } else "已记录 ${state.events.size} 个陪伴决策。结束骑行后显示完整摘要。"
        renderEvents(state.events)
    }

    private fun renderEvents(events: List<RideEventLog>) {
        binding.eventContainer.removeAllViews()
        events.takeLast(20).forEachIndexed { visibleIndex, event ->
            val actualIndex = (events.size - minOf(20, events.size)) + visibleIndex
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 10, 12, 10)
                addView(TextView(this@MainActivity).apply { text = "${event.trigger} · ${event.result}\n${event.text}" })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(feedbackButton("有用", actualIndex), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(feedbackButton("打扰", actualIndex), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                })
            }
            binding.eventContainer.addView(row)
        }
    }

    private fun feedbackButton(label: String, index: Int) = Button(this).apply {
        text = label
        setOnClickListener { rideService?.addFeedback(index, label) }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60)
    }

    private fun phaseLabel(phase: RidePhase): String = when (phase) {
        RidePhase.STATIONARY -> "静止"
        RidePhase.STARTING -> "出发"
        RidePhase.CRUISING -> "巡航"
        RidePhase.CLIMBING -> "爬坡"
        RidePhase.RECOVERING -> "恢复"
    }

    override fun onDestroy() {
        if (bound) {
            rideService?.removeObserver(stateObserver)
            unbindService(serviceConnection)
        }
        super.onDestroy()
    }
}
