package com.example.myapplication.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Одна запись в локальной базе данных.
 * Хранит показание компаса или глубины с временной меткой.
 */
@Entity(tableName = "nmea_records")
data class NmeaRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,       // System.currentTimeMillis()
    val type: String,          // "HEADING_MAGNETIC", "HEADING_TRUE", "DEPTH_METERS"
    val value: Double,         // курс в градусах или глубина в метрах
    val unit: String,          // "°" или "м"
    val rawSentence: String    // оригинальная NMEA строка, например $HCHDG,245.1,...
)
