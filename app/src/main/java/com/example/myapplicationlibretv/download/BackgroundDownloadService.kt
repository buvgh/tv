package com.example.myapplicationlibretv.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.myapplicationlibretv.R
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BackgroundDownloadService : Service() {
    companion object {
        private const val CHANNEL_ID = "video_downloads"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "action_start"
        private const val ACTION_RESUME = "action_resume"
        private const val ACTION_PAUSE = "action_pause"
        private const val ACTION_DELETE = "action_delete"
        private const val EXTRA_TASK_ID = "extra_task_id"
        private const val EXTRA_RAW_URL = "extra_raw_url"
        private const val EXTRA_TITLE = "extra_title"
        private val activeJobs = ConcurrentHashMap<String, Job>()

        fun start(context: Context, rawUrl: String, title: String?) {
            val taskId = System.currentTimeMillis().toString()
            DownloadCenter.enqueue(context, taskId, title ?: "视频下载", rawUrl)
            startServiceCompat(
                context,
                Intent(context, BackgroundDownloadService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_TASK_ID, taskId)
                    putExtra(EXTRA_RAW_URL, rawUrl)
                    putExtra(EXTRA_TITLE, title)
                }
            )
        }

        fun resume(context: Context, taskId: String) {
            startServiceCompat(
                context,
                Intent(context, BackgroundDownloadService::class.java).apply {
                    action = ACTION_RESUME
                    putExtra(EXTRA_TASK_ID, taskId)
                }
            )
        }

        fun pause(context: Context, taskId: String) {
            startServiceCompat(
                context,
                Intent(context, BackgroundDownloadService::class.java).apply {
                    action = ACTION_PAUSE
                    putExtra(EXTRA_TASK_ID, taskId)
                }
            )
        }

        fun delete(context: Context, taskId: String) {
            startServiceCompat(
                context,
                Intent(context, BackgroundDownloadService::class.java).apply {
                    action = ACTION_DELETE
                    putExtra(EXTRA_TASK_ID, taskId)
                }
            )
        }

        private fun startServiceCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()

        val action = intent?.action.orEmpty()
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID).orEmpty()

        when (action) {
            ACTION_PAUSE -> {
                activeJobs.remove(taskId)?.cancel()
                DownloadCenter.pause(applicationContext, taskId)
                updateNotification("已暂停下载")
                if (activeJobs.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            }
            ACTION_DELETE -> {
                activeJobs.remove(taskId)?.cancel()
                deleteDownloadArtifacts(applicationContext, taskId)
                DownloadCenter.delete(applicationContext, taskId, removeFile = true)
                updateNotification("已删除下载任务")
                if (activeJobs.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            }
            ACTION_START, ACTION_RESUME -> {
                startForeground(NOTIFICATION_ID, buildNotification("准备下载..."))
                if (taskId.isBlank() || activeJobs.containsKey(taskId)) {
                    return START_NOT_STICKY
                }
                val rawUrl = intent?.getStringExtra(EXTRA_RAW_URL)
                val title = intent?.getStringExtra(EXTRA_TITLE)
                val job = serviceScope.launch {
                    val record = DownloadCenter.getTask(applicationContext, taskId)
                    val targetUrl = rawUrl ?: record?.rawUrl.orEmpty()
                    val targetTitle = title ?: record?.title ?: "视频下载"

                    runCatching {
                        val parsed = parseVideoUrl(targetUrl)
                        require(parsed.url.isNotBlank()) { "下载地址为空" }
                        downloadVideoFile(
                            context = applicationContext,
                            taskId = taskId,
                            parsed = parsed,
                            displayTitle = targetTitle
                        ) { progress ->
                            DownloadCenter.updateProgress(applicationContext, taskId, progress)
                            updateNotification(progress)
                        }
                    }.onSuccess {
                        DownloadCenter.complete(applicationContext, taskId, it.fileName, it.fileUri)
                        updateNotification("下载完成：${it.fileName}")
                    }.onFailure {
                        val message = it.localizedMessage ?: "Unknown error"
                        if (!message.contains("cancel", ignoreCase = true)) {
                            DownloadCenter.fail(applicationContext, taskId, message)
                            updateNotification("下载失败：$message")
                        }
                    }

                    activeJobs.remove(taskId)
                    if (activeJobs.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf(startId)
                    }
                }
                activeJobs[taskId] = job
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("枫林晚TV 后台下载")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "视频下载",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }
}
