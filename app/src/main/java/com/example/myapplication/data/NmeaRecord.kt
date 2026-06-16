package com.example.myapplication.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Одна запись в локальной базе данных.
 * Хранит показание датчика (курс, ветер и т.п.) с временной меткой.
 */
@Entity(tableName = "nmea_records")
data class NmeaRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,       // System.currentTimeMillis()
    val type: String,          // "HEADING", "WIND_SPEED_KNOTS", "WIND_DIRECTION"
    val value: Double,         // курс/направление в градусах или скорость ветра в узлах
    val unit: String,          // "°" или "уз"
    val rawSentence: String    // оригинальная RAW NMEA 2000 строка
)
