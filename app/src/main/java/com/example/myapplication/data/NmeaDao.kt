package com.example.myapplication.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NmeaDao {

    @Insert
    suspend fun insert(record: NmeaRecord)

    @Query("SELECT COUNT(*) FROM nmea_records")
    suspend fun getCount(): Long

    // Последние 200 записей определённого типа (для истории)
    @Query("""
        SELECT * FROM nmea_records
        WHERE type = :type
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    fun getRecentByType(type: String, limit: Int = 200): Flow<List<NmeaRecord>>

    // Все записи за период (для экспорта)
    @Query("""
        SELECT * FROM nmea_records
        WHERE timestamp BETWEEN :from AND :to
        ORDER BY timestamp ASC
    """)
    suspend fun getByPeriod(from: Long, to: Long): List<NmeaRecord>

    // Удалить старые записи (например, старше 30 дней)
    @Query("DELETE FROM nmea_records WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}
