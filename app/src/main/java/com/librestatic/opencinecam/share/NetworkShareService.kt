/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.librestatic.opencinecam.MainActivity
import com.librestatic.opencinecam.R
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Runs the network drive (OCC-PLAN-071 §8, route 3) as a visible `dataSync` foreground service
 * with a notification carrying the address and Stop. Started only by the operator through
 * [NetworkShare.start]; it stops on Stop, after 15 idle minutes, when no Wi-Fi, hotspot or USB
 * tethering address is left, when a take starts recording, and when Android's data-sync time
 * limit ends it ([onTimeout]). The password never reaches the notification or the log.
 */
class NetworkShareService : Service() {
    @Volatile private var session: NetworkShareSession? = null
    @Volatile private var ticker: ScheduledExecutorService? = null
    @Volatile private var destroyed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val control = Executors.newSingleThreadExecutor { Thread(it, "share-control").apply { isDaemon = true } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Through the control thread, so a stop that races a start lands after it.
                val queued = runCatching {
                    control.execute {
                        val current = session
                        if (current != null) current.stop(NetworkShareStopReason.OPERATOR) else scope.launch { finish(NetworkShareStopReason.OPERATOR) }
                    }
                }
                if (queued.isFailure) finish(NetworkShareStopReason.OPERATOR)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // Foreground first: the promise made by startForegroundService must be kept even when the start is refused.
                if (!enterForeground(null)) {
                    finish(NetworkShareStopReason.FAILED)
                    return START_NOT_STICKY
                }
                if (session == null) control.execute(::startSession)
            }
            else -> if (session == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        if (session != null) return
        val networks = AndroidShareNetworks(this)
        val credentials = ShareCredentials.generate()
        val created = NetworkShareSession(
            tree = ShareTree(MediaStoreShareTakes(this)),
            credentials = credentials,
            addresses = networks::bindings,
            takeActive = { NetworkShare.takeActive.value },
            log = { Log.i(TAG, it) },
            onStopped = { reason -> scope.launch { finish(reason) } },
        )
        when (val start = created.start()) {
            is NetworkShareSession.Start.Refused -> scope.launch { finish(start.reason) }
            is NetworkShareSession.Start.Running -> {
                if (destroyed) {
                    created.stop(NetworkShareStopReason.OPERATOR)
                    return
                }
                session = created
                Log.i(TAG, "network drive listening on ${start.urls.size} address(es), port ${start.port}")
                scope.launch {
                    NetworkShare.publish(NetworkShareState.Running(start.urls, credentials.user, credentials.password))
                    enterForeground(start.urls.firstOrNull())
                    // Stop the moment a take starts, not at the next tick: no disk contention with RAW recording.
                    NetworkShare.takeActive.filter { it }.collect { control.execute { session?.stop(NetworkShareStopReason.RECORDING) } }
                }
                ticker = Executors.newSingleThreadScheduledExecutor { Thread(it, "share-tick").apply { isDaemon = true } }.also { timer ->
                    timer.scheduleWithFixedDelay({
                        val current = session ?: return@scheduleWithFixedDelay
                        current.tick()
                        if (current.running) {
                            val urls = current.urls()
                            val shown = NetworkShare.state.value
                            if (shown is NetworkShareState.Running && shown.urls != urls) scope.launch {
                                NetworkShare.publish(shown.copy(urls = urls))
                                enterForeground(urls.firstOrNull())
                            }
                        }
                    }, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS)
                }
            }
        }
    }

    /** Main thread: publish the stop and leave the foreground. */
    private fun finish(reason: NetworkShareStopReason) {
        Log.i(TAG, "network drive stopped: $reason")
        ticker?.shutdownNow()
        ticker = null
        session = null
        NetworkShare.publish(NetworkShareState.Stopped(reason))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+: the data-sync budget is spent; stop before the system has to.
        val current = session
        if (current != null) current.stop(NetworkShareStopReason.TIMEOUT) else finish(NetworkShareStopReason.TIMEOUT)
    }

    override fun onDestroy() {
        destroyed = true
        session?.stop(NetworkShareStopReason.OPERATOR)
        session = null
        ticker?.shutdownNow()
        if (NetworkShare.state.value !is NetworkShareState.Stopped) NetworkShare.publish(NetworkShareState.Stopped(NetworkShareStopReason.OPERATOR))
        scope.cancel()
        control.shutdown()
        super.onDestroy()
    }

    private fun enterForeground(url: String?): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(url), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        true
    } catch (_: RuntimeException) {
        false
    }

    private fun notification(url: String?): Notification {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.network_share_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(this, 0, Intent(this, NetworkShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(if (url == null) R.string.network_share_state_starting else R.string.network_share_notification_title))
            .apply { if (url != null) setContentText(url) }
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(NotificationCompat.Action(0, getString(R.string.network_share_notification_stop), stop))
            .build()
    }

    companion object {
        private const val TAG = "NetworkShare"
        const val ACTION_START = "com.librestatic.opencinecam.share.START"
        const val ACTION_STOP = "com.librestatic.opencinecam.share.STOP"
        private const val CHANNEL_ID = "network_share"
        private const val NOTIFICATION_ID = 0x5ea7
        private const val TICK_SECONDS = 5L
    }
}
