package io.github.nideta231.hermesremote.data

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the process, and with it [LiveLink]'s socket, alive while a turn the phone cares about is
 * running, so "finished" and "needs your approval" can be posted with the app in the background.
 * The notifications themselves come from [LiveLink]; this service only holds the process up and
 * stops as soon as nothing is running.
 *
 * Foreground service rather than WorkManager: turns finish in seconds to minutes, and the socket
 * must stay open the whole time (WorkManager would close it between runs).
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        startForegroundCompat()
        // A sticky restart arrives with no intent and a fresh process: open the socket again.
        if (KeepAlive.needsReopen(intent == null, LiveLink.gateway != null)) LiveLink.restore(this)
        LiveLink.reattach()
        if (!started) {
            started = true
            scope.launch {
                val prefs = KeepAlivePrefs(this@WatchService)
                // Give a just-sent prompt a moment to register as running, then hold until idle.
                withTimeoutOrNull(START_GRACE_MS) { LiveLink.busy.first { it.isNotEmpty() } }
                var lastBusy = SystemClock.elapsedRealtime()
                while (true) {
                    val busy = LiveLink.busy.value
                    val work = LiveLink.backgroundWork.value
                    if (busy.isNotEmpty() || work.isNotEmpty()) lastBusy = SystemClock.elapsedRealtime()
                    // The linger also lets the "finished" notification post before the process may go.
                    if (!KeepAlive.stayUp(busy, work, SystemClock.elapsedRealtime() - lastBusy, prefs.keepConnected, prefs.lingerMs)) break
                    delay(POLL_MS)
                }
                stopNow()
            }
        }
        return START_STICKY
    }

    /** Swiping the app from recents must not end work the session still owns. */
    override fun onTaskRemoved(rootIntent: Intent?) {}

    // Android 14 calls the first, Android 15 the second.
    override fun onTimeout(startId: Int) = timedOut()
    override fun onTimeout(startId: Int, fgsType: Int) = timedOut()

    private fun timedOut() {
        when (KeepAlive.onTimeout(restartedAfterTimeout)) {
            KeepAlive.OnTimeout.RESTART -> {
                restartedAfterTimeout = true
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                start(this)
            }
            KeepAlive.OnTimeout.STOP -> stopNow()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stopNow() {
        restartedAfterTimeout = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat() {
        val n = Notifier.watchNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, Notifier.WATCH_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifier.WATCH_ID, n)
        }
    }

    companion object {
        private const val START_GRACE_MS = 15_000L
        private const val POLL_MS = 1_000L
        @Volatile private var restartedAfterTimeout = false

        /** Call when the app goes to the background with a turn running (Android 12+: while still allowed). */
        fun start(context: Context) {
            val i = Intent(context, WatchService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            }
        }
    }
}
