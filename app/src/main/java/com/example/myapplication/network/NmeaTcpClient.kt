package com.example.myapplication.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP клиент для подключения к Yacht Devices WiFi Gateway (YDWG).
 *
 * YDWG работает как TCP сервер:
 *  - В режиме точки доступа (AP): IP = 192.168.4.1, порт = 1457
 *  - После подключения шлёт строки NMEA 2000 в формате YD RAW непрерывно
 */
class NmeaTcpClient(
    private val host: String,
    private val port: Int = 1457
) {
    private val tag = "NmeaTcpClient"

    fun receive(): Flow<String> = flow {
        Log.i(tag, "Подключаюсь к $host:$port по TCP")
        // Явный таймаут на подключение — иначе при недоступном шлюзе сокет висит очень долго
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), 5000)
        socket.soTimeout = 5000
        Log.i(tag, "TCP соединение установлено")

        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        try {
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (_: java.net.SocketTimeoutException) {
                    continue // таймаут — продолжаем ждать
                } ?: break  // null = соединение закрыто

                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    emit(trimmed) // отдаём все строки, фильтрация в сервисе
                }
            }
        } finally {
            socket.close()
            Log.i(tag, "TCP соединение закрыто")
        }
    }.flowOn(Dispatchers.IO)
}
