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

// Состояние UI — передаётся в Activity через StateFlow
data class NmeaUiState(
    val isConnected: Boolean = false,
    val headingTrue: Double? = null,
    val windSpeedKnots: Double? = null,
    val windDirectionDeg: Double? = null,
    val speedKnots: Double? = null,
    val depthMeters: Double? = null,
    val waterTempC: Double? = null,
    val gpsLat: Double? = null,
    val gpsLon: Double? = null,
    val gpsSog: Double? = null,
    val gpsCog: Double? = null,
    val errorMessage: String? = null
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
    // Время последней записи в БД по каждому типу — троттлим не чаще 1 раза в секунду на тип
    private val lastWriteByType = mutableMapOf<String, Long>()

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
        lastWriteByType.clear()

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

            // Последний курс держим отдельно (нужен парсеру ветра сразу), а экран обновляем раз в секунду
            var latestHeading: Double? = null
            var pending = NmeaUiState(isConnected = true)
            var lastUiEmitMs = 0L

            try {
                flow.collect { sentence ->
                    val dataList = NmeaParser.parse(sentence, latestHeading)
                    if (dataList.isEmpty()) return@collect

                    val now = System.currentTimeMillis()
                    for (data in dataList) {
                        if (data.type == NmeaParser.TYPE_HEADING) latestHeading = data.value

                        // Копим последнее значение каждого датчика (в память, без обновления экрана)
                        pending = when (data.type) {
                            NmeaParser.TYPE_HEADING -> pending.copy(headingTrue = data.value)
                            NmeaParser.TYPE_WIND_SPEED -> pending.copy(windSpeedKnots = data.value)
                            NmeaParser.TYPE_WIND_DIRECTION -> pending.copy(windDirectionDeg = data.value)
                            NmeaParser.TYPE_SPEED_KNOTS -> pending.copy(speedKnots = data.value)
                            NmeaParser.TYPE_DEPTH_METERS -> pending.copy(depthMeters = data.value)
                            NmeaParser.TYPE_WATER_TEMP -> pending.copy(waterTempC = data.value)
                            NmeaParser.TYPE_GPS_LAT -> pending.copy(gpsLat = data.value)
                            NmeaParser.TYPE_GPS_LON -> pending.copy(gpsLon = data.value)
                            NmeaParser.TYPE_GPS_SOG -> pending.copy(gpsSog = data.value)
                            NmeaParser.TYPE_GPS_COG -> pending.copy(gpsCog = data.value)
                            else -> pending
                        }

                        // Пишем в БД не чаще 1 раза в секунду на каждый тип
                        val lastWrite = lastWriteByType[data.type] ?: 0L
                        if (now - lastWrite >= 1000L) {
                            lastWriteByType[data.type] = now
                            dao.insert(
                                NmeaRecord(
                                    timestamp = now,
                                    type = data.type,
                                    value = data.value,
                                    unit = data.unit,
                                    rawSentence = sentence
                                )
                            )
                        }
                    }

                    // Обновляем экран не чаще 1 раза в секунду
                    if (now - lastUiEmitMs >= 1000L) {
                        lastUiEmitMs = now
                        _uiState.value = pending
                    }
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
            .setContentTitle("YDWG")
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
