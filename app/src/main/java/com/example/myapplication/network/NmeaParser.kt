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
 *   130306 (0x1FD02) — Wind Data (ветер: скорость + угол)
 *   128259 (0x1F503) — Speed, Water Referenced (скорость через воду)
 *   128267 (0x1F50B) — Water Depth (глубина)
 *   130312 (0x1FD08) — Temperature (температура воды)
 *   130316 (0x1FD0C) — Temperature, Extended Range (температура воды, расширенный)
 *   129025 (0x1F801) — Position, Rapid Update (GPS координаты)
 *   129026 (0x1F802) — COG & SOG, Rapid Update (GPS курс и скорость)
 *
 * Один CAN-кадр может нести несколько измерений (например ветер = скорость + направление),
 * поэтому parse() возвращает список NmeaData (пустой, если PGN не поддерживается).
 */
object NmeaParser {

    private const val PGN_VESSEL_HEADING = 127250
    private const val PGN_WIND_DATA = 130306
    private const val PGN_SPEED_WATER = 128259
    private const val PGN_WATER_DEPTH = 128267
    private const val PGN_TEMPERATURE = 130312
    private const val PGN_TEMPERATURE_EXT = 130316
    private const val PGN_GPS_POSITION = 129025
    private const val PGN_GPS_COG_SOG = 129026

    // 1 м/с = 1.94384 узла
    private const val MS_TO_KNOTS = 1.94384
    // 0 °C = 273.15 K
    private const val KELVIN_OFFSET = 273.15
    // Источник температуры 0 = Sea Water (забортная вода)
    private const val TEMP_SOURCE_SEA_WATER = 0

    const val TYPE_HEADING = "HEADING"
    const val TYPE_WIND_SPEED = "WIND_SPEED_KNOTS"
    const val TYPE_WIND_DIRECTION = "WIND_DIRECTION"
    const val TYPE_SPEED_KNOTS = "SPEED_WATER_KNOTS"
    const val TYPE_DEPTH_METERS = "DEPTH_METERS"
    const val TYPE_WATER_TEMP = "WATER_TEMP_C"
    const val TYPE_GPS_LAT = "GPS_LATITUDE"
    const val TYPE_GPS_LON = "GPS_LONGITUDE"
    const val TYPE_GPS_SOG = "GPS_SOG_KNOTS"
    const val TYPE_GPS_COG = "GPS_COG_DEG"

    /**
     * Разбирает одну RAW-строку.
     *
     * @param headingDeg последний известный курс судна (град, 0..360). Нужен, чтобы перевести
     *   угол ветра (датчик меряет его относительно носа лодки) в пеленг относительно севера.
     *   Если null — направление ветра возвращается относительно носа.
     * @return список измерений (может быть пустым)
     */
    fun parse(line: String, headingDeg: Double? = null): List<NmeaData> {
        val parts = line.trim().split(" ")
        // Минимум: время, направление, заголовок, хотя бы 1 байт данных
        if (parts.size < 4) return emptyList()

        // Обрабатываем только данные с шины (R = Receive)
        if (parts[1].uppercase() != "R") return emptyList()

        val header = parts[2].toLongOrNull(16) ?: return emptyList()
        val pgn = extractPgn(header)

        val payload = parts.drop(3).mapNotNull { it.toIntOrNull(16) }

        return when (pgn) {
            PGN_VESSEL_HEADING -> parseVesselHeading(payload, line)
            PGN_WIND_DATA -> parseWindData(payload, line, headingDeg)
            PGN_SPEED_WATER -> parseSpeedWater(payload, line)
            PGN_WATER_DEPTH -> parseWaterDepth(payload, line)
            PGN_TEMPERATURE -> parseTemperature(payload, line)
            PGN_TEMPERATURE_EXT -> parseTemperatureExt(payload, line)
            PGN_GPS_POSITION -> parseGpsPosition(payload, line)
            PGN_GPS_COG_SOG -> parseGpsCogSog(payload, line)
            else -> emptyList()
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
    private fun parseVesselHeading(payload: List<Int>, raw: String): List<NmeaData> {
        if (payload.size < 3) return emptyList()

        val raw16 = payload[1] or (payload[2] shl 8)
        if (raw16 == 0x7FFF || raw16 == 0xFFFF) return emptyList() // нет данных

        val headingDeg = Math.toDegrees(raw16 * 0.0001)
        val normalized = normalizeDeg(headingDeg)

        return listOf(NmeaData(type = TYPE_HEADING, value = normalized, unit = "°", rawSentence = raw))
    }

    /**
     * PGN 130306 — Wind Data (Raymarine i60 Wind и др.)
     * Byte 0:    SID
     * Bytes 1-2: Wind Speed (uint16 LE, единица 0.01 м/с, 0xFFFF = нет данных)
     * Bytes 3-4: Wind Angle (uint16 LE, единица 0.0001 рад, 0xFFFF = нет данных)
     * Byte 5:    Reference (биты 0-2: 0=истинный/север, 1=магнитный, 2=видимый/apparent…)
     *
     * Мачтовый флюгер i60 шлёт ВИДИМЫЙ ветер — угол относительно носа лодки
     * (0° = в нос, 90° = правый борт, 180° = корма, 270° = левый борт).
     * Чтобы получить пеленг относительно севера, прибавляем курс судна.
     */
    private fun parseWindData(payload: List<Int>, raw: String, headingDeg: Double?): List<NmeaData> {
        if (payload.size < 5) return emptyList()

        val result = mutableListOf<NmeaData>()

        // Скорость ветра: прибор отдаёт в м/с → переводим в узлы
        val speedRaw = payload[1] or (payload[2] shl 8)
        if (speedRaw != 0xFFFF) {
            val knots = speedRaw * 0.01 * MS_TO_KNOTS
            result += NmeaData(type = TYPE_WIND_SPEED, value = knots, unit = "уз", rawSentence = raw)
        }

        // Угол ветра относительно носа лодки
        val angleRaw = payload[3] or (payload[4] shl 8)
        if (angleRaw != 0x7FFF && angleRaw != 0xFFFF) {
            val angleFromBow = normalizeDeg(Math.toDegrees(angleRaw * 0.0001))
            // Если знаем курс — переводим в пеленг относительно севера, иначе оставляем от носа
            val direction = if (headingDeg != null) normalizeDeg(angleFromBow + headingDeg) else angleFromBow
            result += NmeaData(type = TYPE_WIND_DIRECTION, value = direction, unit = "°", rawSentence = raw)
        }

        return result
    }

    /**
     * PGN 128259 — Speed, Water Referenced (Airmar DST800)
     * Byte 0:    SID
     * Bytes 1-2: Speed Water Referenced (uint16 LE, единица 0.01 м/с, 0xFFFF = нет данных)
     * Прибор отдаёт скорость в м/с → переводим в узлы.
     */
    private fun parseSpeedWater(payload: List<Int>, raw: String): List<NmeaData> {
        if (payload.size < 3) return emptyList()

        val rawSpeed = payload[1] or (payload[2] shl 8)
        if (rawSpeed == 0xFFFF) return emptyList()

        val knots = rawSpeed * 0.01 * MS_TO_KNOTS
        return listOf(NmeaData(type = TYPE_SPEED_KNOTS, value = knots, unit = "уз", rawSentence = raw))
    }

    /**
     * PGN 128267 — Water Depth (Airmar DST800)
     * Byte 0:    SID
     * Bytes 1-4: Depth (uint32 LE, единица 0.01 м, 0xFFFFFFFF = нет данных) — глубина под датчиком
     * Bytes 5-6: Offset (int16, 0.001 м) — смещение до ватерлинии/киля (здесь не учитываем)
     */
    private fun parseWaterDepth(payload: List<Int>, raw: String): List<NmeaData> {
        if (payload.size < 5) return emptyList()

        val rawDepth = payload[1].toLong() or
            (payload[2].toLong() shl 8) or
            (payload[3].toLong() shl 16) or
            (payload[4].toLong() shl 24)
        if (rawDepth == 0xFFFFFFFFL) return emptyList()

        val meters = rawDepth * 0.01
        return listOf(NmeaData(type = TYPE_DEPTH_METERS, value = meters, unit = "м", rawSentence = raw))
    }

    /**
     * PGN 130312 — Temperature (старый формат)
     * Byte 0:    SID
     * Byte 1:    Instance
     * Byte 2:    Source (0 = забортная вода)
     * Bytes 3-4: Actual Temperature (uint16 LE, единица 0.01 K, 0xFFFF = нет данных)
     */
    private fun parseTemperature(payload: List<Int>, raw: String): List<NmeaData> {
        if (payload.size < 5) return emptyList()
        if (payload[2] != TEMP_SOURCE_SEA_WATER) return emptyList() // только температура воды

        val rawT = payload[3] or (payload[4] shl 8)
        if (rawT == 0xFFFF) return emptyList()

        val celsius = rawT * 0.01 - KELVIN_OFFSET
        return listOf(NmeaData(type = TYPE_WATER_TEMP, value = celsius, unit = "°C", rawSentence = raw))
    }

    /**
     * PGN 130316 — Temperature, Extended Range (новый формат)
     * Byte 0:    SID
     * Byte 1:    Instance
     * Byte 2:    Source (0 = забортная вода)
     * Bytes 3-5: Actual Temperature (uint24 LE, единица 0.001 K, 0xFFFFFF = нет данных)
     */
    private fun parseTemperatureExt(payload: List<Int>, raw: String): List<NmeaData> {
        if (payload.size < 6) return emptyList()
        if (payload[2] != TEMP_SOURCE_SEA_WATER) return emptyList() // только температура воды

        val rawT = payload[3] or (payload[4] shl 8) or (payload[5] shl 16)
        if (rawT == 0xFFFFFF) return emptyList()

        val celsius = rawT * 0.001 - KELVIN_OFFSET
        return listOf(NmeaData(type = TYPE_WATER_TEMP, value = celsius, unit = "°C", rawSentence = raw))
    }

    /**
     * PGN 129025 — Position, Rapid Update (GPS координаты, Raymarine Axiom)
     * Bytes 0-3: Latitude  (int32 LE, единица 1e-7 °, 0x7FFFFFFF = нет данных, − = юг)
     * Bytes 4-7: Longitude (int32 LE, единица 1e-7 °, 0x7FFFFFFF = нет данных, − = запад)
     */
    private fun parseGpsPosition(payload: List<Int>, raw: String): List<NmeaData> {
        if (payload.size < 8) return emptyList()

        val result = mutableListOf<NmeaData>()

        val latRaw = payload[0] or (payload[1] shl 8) or (payload[2] shl 16) or (payload[3] shl 24)
        if (latRaw != 0x7FFFFFFF) {
            result += NmeaData(type = TYPE_GPS_LAT, value = latRaw * 1e-7, unit = "°", rawSentence = raw)
        }

        val lonRaw = payload[4] or (payload[5] shl 8) or (payload[6] shl 16) or (payload[7] shl 24)
        if (lonRaw != 0x7FFFFFFF) {
            result += NmeaData(type = TYPE_GPS_LON, value = lonRaw * 1e-7, unit = "°", rawSentence = raw)
        }

        return result
    }

    /**
     * PGN 129026 — COG & SOG, Rapid Update (GPS курс и скорость, Raymarine Axiom)
     * Byte 0:    SID
     * Byte 1:    COG Reference (биты 0-1: 0=истинный, 1=магнитный)
     * Bytes 2-3: COG (uint16 LE, единица 0.0001 рад, 0xFFFF = нет данных)
     * Bytes 4-5: SOG (uint16 LE, единица 0.01 м/с, 0xFFFF = нет данных) → узлы
     */
    private fun parseGpsCogSog(payload: List<Int>, raw: String): List<NmeaData> {
        if (payload.size < 6) return emptyList()

        val result = mutableListOf<NmeaData>()

        val cogRaw = payload[2] or (payload[3] shl 8)
        if (cogRaw != 0xFFFF) {
            val cogDeg = normalizeDeg(Math.toDegrees(cogRaw * 0.0001))
            result += NmeaData(type = TYPE_GPS_COG, value = cogDeg, unit = "°", rawSentence = raw)
        }

        val sogRaw = payload[4] or (payload[5] shl 8)
        if (sogRaw != 0xFFFF) {
            val knots = sogRaw * 0.01 * MS_TO_KNOTS
            result += NmeaData(type = TYPE_GPS_SOG, value = knots, unit = "уз", rawSentence = raw)
        }

        return result
    }

    /** Приводит угол в диапазон 0..360 */
    private fun normalizeDeg(deg: Double): Double = ((deg % 360) + 360) % 360
}
