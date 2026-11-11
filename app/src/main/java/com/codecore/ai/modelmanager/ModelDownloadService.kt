package com.codecore.ai.modelmanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ModelDownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "codecore_model_downloads"
        const val NOTIFICATION_ID = 4040
        const val ACTION_START = "ACTION_START_DOWNLOAD"
        const val ACTION_UPDATE_PROGRESS = "ACTION_UPDATE_PROGRESS"
        const val ACTION_STOP = "ACTION_STOP_DOWNLOAD"
        const val EXTRA_MODEL_NAME = "EXTRA_MODEL_NAME"
        const val EXTRA_PROGRESS = "EXTRA_PROGRESS"

        fun start(context: Context, modelName: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateProgress(context: Context, modelName: String, percent: Int) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_UPDATE_PROGRESS
                putExtra(EXTRA_MODEL_NAME, modelName)
                putExtra(EXTRA_PROGRESS, percent)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val modelName = intent?.getStringExtra(EXTRA_MODEL_NAME) ?: "GGUF Модель"
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
        val notification = buildNotification(modelName, progress)

        if (action == ACTION_UPDATE_PROGRESS) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
            return START_NOT_STICKY
        }

        // ACTION_START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(modelName: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Загрузка модели: $modelName")
            .setContentText(if (progress > 0) "Загружено $progress%" else "Подготовка…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Загрузка моделей CodeCore AI",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления о прогрессе скачивания GGUF файлов"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
