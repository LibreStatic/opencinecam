/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.StableFailure

const val ACTION_STOP_RECORDING = "com.librestatic.opencinecam.action.STOP_RECORDING"
const val EXTRA_AUDIO_ENABLED = "com.librestatic.opencinecam.extra.AUDIO_ENABLED"

data class RecordingPermissionState(
    val cameraGranted: Boolean,
    val microphoneGranted: Boolean,
    val notificationsGranted: Boolean,
)

fun validateRecordingPermissions(state: RecordingPermissionState, audioEnabled: Boolean, correlationId: String = "recording-fgs"): StableFailure? = when {
    !state.cameraGranted -> StableFailure("recording-fgs", FailureCode.CAPTURE_OPEN_FAILED, FailureSeverity.ERROR, Recoverability.USER_ACTION, correlationId, "Camera permission is required to record.")
    audioEnabled && !state.microphoneGranted -> StableFailure("recording-fgs", FailureCode.AUDIO_INITIALIZATION_FAILED, FailureSeverity.ERROR, Recoverability.USER_ACTION, correlationId, "Microphone permission is required for audio recording.")
    else -> null
}

sealed interface ForegroundRecordingStart {
    data class Started(val notificationVisible: Boolean) : ForegroundRecordingStart
    data class Rejected(val failure: StableFailure) : ForegroundRecordingStart
}

class RecordingForegroundController(
    private val service: Service,
    private val correlationId: String = "recording-fgs",
) {
    fun start(audioEnabled: Boolean): ForegroundRecordingStart {
        val permission = RecordingPermissionState(
            cameraGranted = ContextCompat.checkSelfPermission(service, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            microphoneGranted = ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            notificationsGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(service, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
        validateRecordingPermissions(permission, audioEnabled, correlationId)?.let { return ForegroundRecordingStart.Rejected(it) }
        return try {
            createChannel()
            val notification = notification()
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or if (audioEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(service, NOTIFICATION_ID, notification, types)
            ForegroundRecordingStart.Started(permission.notificationsGranted)
        } catch (_: SecurityException) {
            ForegroundRecordingStart.Rejected(StableFailure("recording-fgs", FailureCode.CAPTURE_OPEN_FAILED, FailureSeverity.ERROR, Recoverability.USER_ACTION, correlationId, "Foreground recording permission was denied."))
        } catch (_: RuntimeException) {
            ForegroundRecordingStart.Rejected(StableFailure("recording-fgs", FailureCode.CAPTURE_OPEN_FAILED, FailureSeverity.ERROR, Recoverability.RETRYABLE, correlationId, "Foreground recording could not be started."))
        }
    }

    fun stop() {
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        service.stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            service.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, service.getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = service.getString(R.string.recording_channel_summary)
                },
            )
        }
    }

    private fun notification(): Notification {
        val stopIntent = PendingIntent.getService(
            service,
            0,
            Intent(service, CaptureService::class.java).setAction(ACTION_STOP_RECORDING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(service.getString(R.string.recording_notification_title))
            .setContentText(service.getString(R.string.recording_notification_body))
            .setWhen(System.currentTimeMillis())
            .setUsesChronometer(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(NotificationCompat.Action(0, service.getString(R.string.stop_recording), stopIntent))
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 1001
    }
}
