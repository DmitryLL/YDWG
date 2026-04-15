package com.example.myapplication.network

data class NmeaData(
    val type: String = "HEADING",
    val value: Double,
    val unit: String = "°",
    val rawSentence: String
)

/**
 * Парсер формата Yacht Devices RAW (NMEA 2000 через YD WiFi Gateway).
 *
 * Формат строки:
 *   HH:MM:SS.mmm D HHHHHHHH PP PP PP PP PP PP PP PP
 *   - HH:MM:SS.mmm  — временная метка
 *   - D             — направление: R (получено с шины), T (отправлено)
 *   - HHHHHHHH      — 32-bit CAN заголовок (Priority + PGN + Source Address)
 *   - PP PP ...     — байты полезной нагрузки (hex)
 *
 * Поддерживаемые PGN:
 *   127250 (0x1F112) — Vessel Heading (курс судна)
 */
object NmeaParser {

    private const val PGN_VESSEL_HEADING = 127250

    fun parse(line: String): NmeaData? {
        val parts = line.trim().split(" ")
        // Минимум: время, направление, заголовок, хотя бы 1 байт данных
        if (parts.size < 4) return null

        // Обрабатываем только данные с шины (R = Receive)
        if (parts[1].uppercase() != "R") return null

        val header = parts[2].toLongOrNull(16) ?: return null
        val pgn = extractPgn(header)

        val payload = parts.drop(3).mapNotNull { it.toIntOrNull(16) }

        return when (pgn) {
            PGN_VESSEL_HEADING -> parseVesselHeading(payload, line)
            else -> null
        }
    }

    /**
     * Извлекает PGN из 32-bit CAN заголовка.
     *
     * Структура заголовка (биты 28-0 = 29-bit CAN ID):
     *  Bits 28-26: Priority
     *  Bit  25:    Reserved
     *  Bit  24:    Data Page (DP)
     *  Bits 23-16: PDU Format (PF)
     *  Bits 15-8:  PDU Specific (PS)
     *  Bits 7-0:   Source Address (SA)
     *
     * Если PF >= 240 → broadcast PGN = (DP<<16) | (PF<<8) | PS
     * Если PF <  240 → peer-to-peer, PGN = (DP<<16) | (PF<<8)
     */
    private fun extractPgn(header: Long): Int {
        val sa = (header and 0xFF).toInt()
        val ps = ((header shr 8) and 0xFF).toInt()
        val pf = ((header shr 16) and 0xFF).toInt()
        val dp = ((header shr 24) and 0x01).toInt()
        return if (pf >= 240) {
            (dp shl 16) or (pf shl 8) or ps
        } else {
            (dp shl 16) or (pf shl 8)
        }
    }

    /**
     * PGN 127250 — Vessel Heading
     * Byte 0:   SID
     * Bytes 1-2: Heading (uint16 little-endian, единица 0.0001 рад, 0x7FFF = нет данных)
     * Bytes 3-4: Deviation
     * Bytes 5-6: Variation
     * Byte 7:   Reference (bits 0-1: 0=истинный, 1=магнитный, 2=компасный)
     */
    private fun parseVesselHeading(payload: List<Int>, raw: String): NmeaData? {
        if (payload.size < 3) return null

        val raw16 = payload[1] or (payload[2] shl 8)
        if (raw16 == 0x7FFF || raw16 == 0xFFFF) return null // нет данных

        val headingRad = raw16 * 0.0001
        val headingDeg = Math.toDegrees(headingRad)

        // Нормализуем в диапазон 0..360
        val normalized = ((headingDeg % 360) + 360) % 360

        return NmeaData(value = normalized, rawSentence = raw)
    }
}
