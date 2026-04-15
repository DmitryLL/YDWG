package com.example.myapplication.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Слушает UDP-порт и получает NMEA 0183 предложения от Yacht Devices WiFi Gateway (YDWG).
 *
 * YDWG в режиме точки доступа:
 *  - WiFi сеть: создаётся самим устройством
 *  - NMEA данные: UDP broadcast на порт 10110
 *
 * Телефон должен быть подключён к WiFi-сети YDWG.
 */
class NmeaUdpClient(
    private val context: Context,
    private val port: Int = 10110
) {
    private val tag = "NmeaUdpClient"

    /**
     * Возвращает Flow<String> — поток NMEA предложений.
     * Flow активен пока корутина не отменена.
     * Запускается в Dispatchers.IO (фоновый поток).
     */
    fun receive(): Flow<String> = flow {
        // MulticastLock нужен на некоторых Android устройствах
        // для приёма UDP broadcast пакетов
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifiManager.createMulticastLock("ydwg_nmea_lock").apply {
            setReferenceCounted(true)
            acquire()
        }

        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(java.net.InetSocketAddress(port))
            soTimeout = 3000
        }

        Log.i(tag, "Слушаю UDP порт $port (broadcast)")

        try {
            val buffer = ByteArray(4096)
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    // Один UDP-пакет может содержать несколько предложений NMEA
                    raw.split("\r\n", "\n", "\r").forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("$")) {
                            emit(trimmed)
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Нормальная ситуация — просто продолжаем ждать
                    // При отмене корутины flow сам бросит CancellationException
                }
            }
        } finally {
            socket.close()
            if (multicastLock.isHeld) multicastLock.release()
            Log.i(tag, "UDP сокет закрыт")
        }
    }.flowOn(Dispatchers.IO)
}
