package com.example.myapplication.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.data.AppDatabase
import com.example.myapplication.data.NmeaRecord
import com.example.myapplication.network.NmeaParser
import com.example.myapplication.network.NmeaTcpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Состояние UI — передаётся в Activity через StateFlow
data class NmeaUiState(
    val isConnected: Boolean = false,
    val headingTrue: Double? = null,
    val recordCount: Long = 0L,
    val lastUpdateMs: Long = 0L,
    val errorMessage: String? = null,
    val rawPacketCount: Long = 0L,
    val lastRawSentences: List<String> = emptyList()
)

/**
 * Foreground Service — работает в фоне когда приложение свёрнуто.
 * Получает NMEA данные от YDWG по UDP и сохраняет их в Room.
 */
class NmeaService : Service() {

    private val tag = "NmeaService"
    private val channelId = "nmea_channel"
    private val notificationId = 1

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null
    private var lastHeadingUpdateMs = 0L
    private val phoneTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val _uiState = MutableStateFlow(NmeaUiState())
    val uiState: StateFlow<NmeaUiState> = _uiState.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): NmeaService = this@NmeaService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(notificationId, buildNotification("Ожидание подключения…"))
    }

    /**
     * Начать получение NMEA данных с указанного UDP порта.
     * Если уже запущено — останавливает предыдущую сессию.
     */
    fun startListening(host: String = "192.168.4.1", port: Int = 1457) {
        collectJob?.cancel()
        lastHeadingUpdateMs = 0L

        val flow = NmeaTcpClient(host, port).receive()
        val dao = AppDatabase.getInstance(applicationContext).nmeaDao()

        collectJob = serviceScope.launch {
            _uiState.value = NmeaUiState(isConnected = true)
            updateNotification("$host:$port")
            Log.i(tag, "Подключение $host:$port")

            // Удаляем записи старше 7 дней при каждом подключении
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            dao.deleteOlderThan(cutoff)
            Log.i(tag, "Очистка БД: удалены записи старше 7 дней")

            try {
                flow.collect { sentence ->
                    Log.d(tag, "RAW: $sentence")

                    // Заменяем время устройства на время телефона
                    val phoneTime = phoneTimeFmt.format(Date())
                    val displayLine = sentence.replaceFirst(Regex("^\\S+"), phoneTime)

                    val updatedRaw = (_uiState.value.lastRawSentences + displayLine).takeLast(6)
                    _uiState.value = _uiState.value.copy(
                        rawPacketCount = _uiState.value.rawPacketCount + 1,
                        lastRawSentences = updatedRaw
                    )

                    val data = NmeaParser.parse(sentence) ?: return@collect

                    // Обновляем курс не чаще 1 раза в секунду
                    val now = System.currentTimeMillis()
                    if (now - lastHeadingUpdateMs < 1000L) return@collect
                    lastHeadingUpdateMs = now

                    _uiState.value = _uiState.value.copy(
                        headingTrue = data.value,
                        lastUpdateMs = now
                    )

                    dao.insert(
                        NmeaRecord(
                            timestamp = now,
                            type = data.type,
                            value = data.value,
                            unit = data.unit,
                            rawSentence = sentence
                        )
                    )

                    val count = dao.getCount()
                    _uiState.value = _uiState.value.copy(recordCount = count)
                }
            } catch (e: Exception) {
                Log.e(tag, "Ошибка получения данных: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isConnected = false,
                    errorMessage = e.message
                )
                updateNotification("Ошибка: ${e.message}")
            }
        }
    }

    /** Остановить приём данных, сбросить состояние */
    fun stopListening() {
        collectJob?.cancel()
        collectJob = null
        _uiState.value = NmeaUiState()
        updateNotification("Остановлено")
        Log.i(tag, "Приём данных остановлен")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "NMEA Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Сбор данных с YDWG WiFi Gateway"
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("YDWG Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, buildNotification(text))
    }
}
