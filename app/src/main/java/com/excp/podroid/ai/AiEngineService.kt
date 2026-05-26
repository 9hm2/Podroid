/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Foreground service owning the llama-server subprocess. Patterned after
 * PodroidService: persistent notification with start/stop, lifecycle
 * gated by user toggle in Settings. Independent of the VM service —
 * either can run without the other, and the VM consumes the AI engine
 * over SLIRP loopback (10.0.2.2:port) only when the engine is up.
 *
 * Thermal awareness: when the system PowerManager reports SEVERE thermal
 * load we automatically stop the process and update the notification.
 * The user can re-enable in Settings; we don't auto-restart on cooldown.
 */
package com.excp.podroid.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.excp.podroid.MainActivity
import com.excp.podroid.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AiEngineService : Service() {

    @Inject lateinit var repository: AiEngineRepository
    @Inject lateinit var process: LlamaServerProcess

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("AI engine — starting…"))
        stateJob = scope.launch {
            process.state.collectLatest { st ->
                val text = when (st) {
                    AiEngineState.Idle           -> "AI engine — stopped"
                    AiEngineState.Starting       -> "AI engine — starting…"
                    is AiEngineState.Running     -> "AI engine — ${st.modelId} on ${st.backend.label}"
                    is AiEngineState.Stopping    -> "AI engine — stopping (${st.reason})"
                    is AiEngineState.Failed      -> "AI engine — failed: ${st.message}"
                }
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification(text))
                // Stop the service once the process has been Idle for a tick
                // and the user toggled it off (covers process crash and
                // user-initiated stop).
                if (st is AiEngineState.Idle && !repository.isEnabled()) {
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START   -> scope.launch { startEngine() }
            ACTION_STOP    -> scope.launch { stopEngine(clearEnabled = true) }
            ACTION_PAUSE   -> scope.launch { stopEngine(clearEnabled = false) }
            ACTION_RESTART -> scope.launch { restartEngine() }
            else           -> scope.launch { startEngine() }
        }
        attachThermalListener()
        return START_STICKY
    }

    private suspend fun startEngine() {
        if (process.isRunning) return
        val profile = repository.snapshotProfile()
        val port = repository.snapshotPort()
        val ok = process.start(profile, port)
        if (!ok) {
            Log.w(TAG, "Engine failed to start; staying foreground so the user sees the notification.")
        }
    }

    /** [clearEnabled]=true means the user explicitly turned the engine off
     *  (Settings toggle, notification Stop button) — the persisted flag
     *  flips so the engine stays off across future VM launches. false is
     *  for VM-coupled pauses (PodroidService observer): only the process
     *  goes down; `enabled` stays so the next VM start brings the engine
     *  back automatically. */
    private suspend fun stopEngine(clearEnabled: Boolean) {
        process.stop(if (clearEnabled) "user" else "vm-stopped")
        if (clearEnabled) repository.setEnabled(false)
        stopSelf()
    }

    /** Hard-restart: stop the subprocess and immediately re-launch with the
     *  user's current profile. Used by the notification "Restart" action
     *  when something has gone sideways and the user wants a clean retry
     *  without re-entering Settings. Does NOT flip the `enabled` flag —
     *  it stays on. */
    private suspend fun restartEngine() {
        process.stop("restart")
        // Give the OS a beat to release the listening port before re-bind.
        kotlinx.coroutines.delay(300)
        startEngine()
    }

    private fun attachThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (thermalListener != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            // SEVERE (4) and above: kill the engine. MODERATE (3) we tolerate.
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                scope.launch {
                    if (repository.profile.let { /* read */ true } && process.isRunning) {
                        Log.w(TAG, "Thermal status $status — pausing engine")
                        process.stop("thermal")
                    }
                }
            }
        }
        thermalListener = listener
        runCatching { pm.addThermalStatusListener(listener) }
    }

    private fun detachThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val l = thermalListener ?: return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        runCatching { pm.removeThermalStatusListener(l) }
        thermalListener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        detachThermalListener()
        process.stop("service-destroyed")
        stateJob?.cancel()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AI Engine", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Status of the on-device AI engine (llama.cpp)."
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(text: String): Notification {
        val contentPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, AiEngineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val restartPi = PendingIntent.getService(
            this, 2,
            Intent(this, AiEngineService::class.java).setAction(ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Podroid AI")
            .setContentText(text)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(NotificationCompat.Action(0, "Restart", restartPi))
            .addAction(NotificationCompat.Action(0, "Stop", stopPi))
            .build()
    }

    companion object {
        const val ACTION_START   = "com.excp.podroid.ai.START"
        /** User-initiated full stop: process down + `enabled=false`. */
        const val ACTION_STOP    = "com.excp.podroid.ai.STOP"
        /** VM-coupled pause: process down but `enabled` stays so the next
         *  VM-start auto-resumes the engine. Use from PodroidService only. */
        const val ACTION_PAUSE   = "com.excp.podroid.ai.PAUSE"
        const val ACTION_RESTART = "com.excp.podroid.ai.RESTART"
        private const val CHANNEL_ID = "podroid_ai"
        private const val NOTIF_ID = 1101
        private const val TAG = "AiEngineService"

        fun start(context: Context) {
            val i = Intent(context, AiEngineService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
        fun stop(context: Context) {
            val i = Intent(context, AiEngineService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
        /** VM-driven pause — keeps the user's toggle on. */
        fun pause(context: Context) {
            val i = Intent(context, AiEngineService::class.java).setAction(ACTION_PAUSE)
            context.startService(i)
        }
        fun restart(context: Context) {
            val i = Intent(context, AiEngineService::class.java).setAction(ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
