package com.example.xdownloader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.xdownloader.MainActivity
import com.example.xdownloader.R
import com.example.xdownloader.utils.DownloadUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Конвертация в GIF в foreground-сервисе.
 *
 * Раньше работа шла в lifecycleScope активити, и при сворачивании приложения
 * Android (11+) замораживает фоновый процесс: поток просто встаёт на месте,
 * пока пользователь не вернётся. Работа при этом не отменяется — отсюда
 * «стоит на месте, а заходишь — продолжает идти».
 *
 * Foreground-сервис с постоянным уведомлением выводит процесс из заморозки,
 * поэтому конвертация идёт до конца при свёрнутом приложении.
 *
 * Состояние отдаётся через [state]: активити подписывается на него в onStart
 * и отписывается в onStop, так что поворот экрана и возврат в приложение
 * показывают актуальный прогресс.
 */
class GifConversionService : Service() {

    /** Что сейчас происходит — для экрана. */
    data class State(
        val running: Boolean = false,
        val progress: Int = 0,
        val status: String = "",
        val resultUri: Uri? = null,
        val error: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Растёт с каждым запуском; отменённый job видит, что уже не актуален. */
    private var runGeneration = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() обязывает вызвать startForeground() в течение
        // 5 секунд, иначе система бросает RemoteServiceException. Поэтому уведомление
        // показываем ПЕРВЫМ делом, до любых проверок intent.
        startAsForeground("Подготовка к конвертации GIF...", 0)

        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "X_Post"
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        val maxDim = intent.getIntExtra(EXTRA_MAX_DIM, 0)

        // Новый запуск отменяет предыдущий: два одновременных GIF только мешают друг другу.
        job?.cancel()

        // Поколение вместо сравнения ссылок на job: отменённый job не должен
        // ни перезаписать состояние нового запуска, ни остановить сервис.
        val generation = ++runGeneration

        job = scope.launch {
            var lastProgress = -1
            fun stillCurrent() = generation == runGeneration

            try {
                val (uri, errorMsg) = DownloadUtil.downloadAndConvertToGif(
                    context = this@GifConversionService,
                    videoUrl = url,
                    title = title,
                    expectedDurationMs = durationMs,
                    maxDimension = maxDim,
                    onStatusUpdate = { status ->
                        if (stillCurrent()) {
                            _state.value = _state.value.copy(running = true, status = status)
                            updateNotification(status, _state.value.progress)
                        }
                    },
                    onProgress = { progress ->
                        if (stillCurrent()) {
                            _state.value = _state.value.copy(running = true, progress = progress)
                            // Уведомление дёргаем не чаще, чем раз в 2%, иначе шторка мигает
                            if (progress - lastProgress >= 2 || progress >= 100) {
                                lastProgress = progress
                                updateNotification("Создание GIF: $progress%", progress)
                            }
                        }
                    }
                )

                if (!stillCurrent()) return@launch

                _state.value = if (uri != null) {
                    State(running = false, progress = 100, resultUri = uri)
                } else {
                    State(running = false, error = errorMsg ?: "Не удалось создать GIF")
                }
                finishNotification(uri != null, errorMsg)
            } catch (e: CancellationException) {
                // Отменён новым запуском — молча выходим, состояние уже переписано им.
                throw e
            } catch (e: Throwable) {
                if (!stillCurrent()) return@launch
                _state.value = State(running = false, error = e.message ?: "Сбой при конвертации")
                finishNotification(false, e.message)
            } finally {
                // Уведомление оставляем в шторке: пользователь увидит результат,
                // даже если приложение было свёрнуто.
                if (stillCurrent()) {
                    detachForeground()
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ уведомление

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.gif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.gif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gif_notify)
            .setContentTitle(getString(R.string.gif_notification_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress in 1..99) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun startAsForeground(text: String, progress: Int) {
        val notification = buildNotification(text, progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String, progress: Int) {
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(text, progress))
        } catch (ignored: Throwable) {
        }
    }

    /** Итоговое уведомление вместо прогресса: без крестика «идёт» и без полосы. */
    private fun finishNotification(success: Boolean, errorMsg: String?) {
        try {
            val text = if (success) {
                getString(R.string.gif_notification_done)
            } else {
                getString(R.string.gif_notification_failed, errorMsg ?: "")
            }
            val openApp = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_gif_notify)
                .setContentTitle(getString(R.string.gif_notification_title))
                .setContentText(text)
                .setContentIntent(openApp)
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        } catch (ignored: Throwable) {
        }
    }

    private fun detachForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // DETACH оставляет уведомление в шторке после выхода из foreground
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
        } catch (ignored: Throwable) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "gif_conversion"
        private const val NOTIFICATION_ID = 4101

        const val ACTION_START = "com.example.xdownloader.action.CONVERT_GIF"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_DURATION_MS = "extra_duration_ms"
        private const val EXTRA_MAX_DIM = "extra_max_dim"

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        /** Забыть прошлый результат, чтобы экран не показал его повторно. */
        fun clearResult() {
            if (!_state.value.running) _state.value = State()
        }

        fun isRunning(): Boolean = _state.value.running

        fun start(context: Context, url: String, title: String, durationMs: Long, maxDim: Int) {
            _state.value = State(running = true, progress = 0, status = "Подготовка к конвертации GIF...")
            val intent = Intent(context, GifConversionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_DURATION_MS, durationMs)
                putExtra(EXTRA_MAX_DIM, maxDim)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
