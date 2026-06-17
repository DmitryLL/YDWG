package com.example.myapplication

import com.example.myapplication.network.NmeaParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка парсинга PGN 130306 (Wind Data, датчик Raymarine i60 Wind).
 *
 * Тестовая строка:
 *   CAN-заголовок 09FD0217 → PGN 130306
 *   payload: SID=00, скорость=03E8 LE (1000 → 10.00 м/с),
 *            угол=1474 LE (5236 → ~30°), reference=02 (apparent), 2 байта reserved
 */
class NmeaParserTest {

    private val windLine = "10:20:30.500 R 09FD0217 00 E8 03 74 14 02 FF FF"

    @Test
    fun windSpeed_convertedFromMetersPerSecondToKnots() {
        val speed = NmeaParser.parse(windLine)
            .first { it.type == NmeaParser.TYPE_WIND_SPEED }
        // 10 м/с × 1.94384 = 19.4384 уз
        assertEquals(19.4384, speed.value, 0.01)
        assertEquals("уз", speed.unit)
    }

    @Test
    fun windDirection_relativeToBow_whenHeadingUnknown() {
        val dir = NmeaParser.parse(windLine, headingDeg = null)
            .first { it.type == NmeaParser.TYPE_WIND_DIRECTION }
        // Без курса — угол относительно носа лодки (~30°)
        assertEquals(30.0, dir.value, 0.1)
    }

    @Test
    fun windDirection_relativeToNorth_whenHeadingKnown() {
        val dir = NmeaParser.parse(windLine, headingDeg = 100.0)
            .first { it.type == NmeaParser.TYPE_WIND_DIRECTION }
        // (30° от носа + курс 100°) = 130° от севера
        assertEquals(130.0, dir.value, 0.1)
    }

    @Test
    fun windDirection_wrapsAround360() {
        val dir = NmeaParser.parse(windLine, headingDeg = 350.0)
            .first { it.type == NmeaParser.TYPE_WIND_DIRECTION }
        // (30 + 350) mod 360 = 20°
        assertEquals(20.0, dir.value, 0.1)
    }

    @Test
    fun headingPacket_returnsHeadingOnly() {
        // PGN 127250, курс ~30° (угол 1474 LE = 5236 × 0.0001 рад)
        val line = "10:20:30.500 R 09F11217 00 74 14 FF FF FF FF 00"
        val result = NmeaParser.parse(line)
        assertEquals(1, result.size)
        assertEquals(NmeaParser.TYPE_HEADING, result[0].type)
        assertEquals(30.0, result[0].value, 0.1)
    }

    // ── Airmar DST800 ──────────────────────────────────────────────────────────

    @Test
    fun speedThroughWater_convertedToKnots() {
        // PGN 128259, скорость raw=257 (×0.01 м/с = 2.57 м/с)
        val line = "10:20:30.500 R 0DF50323 00 01 01 FF FF FF FF FF"
        val speed = NmeaParser.parse(line).first { it.type == NmeaParser.TYPE_SPEED_KNOTS }
        // 2.57 м/с × 1.94384 ≈ 5.0 уз
        assertEquals(5.0, speed.value, 0.05)
        assertEquals("уз", speed.unit)
    }

    @Test
    fun waterDepth_inMeters() {
        // PGN 128267, глубина raw=1234 (×0.01 м = 12.34 м), uint32 LE = D2 04 00 00
        val line = "10:20:30.500 R 0DF50B23 00 D2 04 00 00 00 00 FF"
        val depth = NmeaParser.parse(line).first { it.type == NmeaParser.TYPE_DEPTH_METERS }
        assertEquals(12.34, depth.value, 0.001)
        assertEquals("м", depth.unit)
    }

    @Test
    fun waterTemperature_pgn130312_inCelsius() {
        // PGN 130312, source=0 (вода), temp raw=29165 (×0.01 K = 291.65 K = 18.5 °C)
        val line = "10:20:30.500 R 15FD0823 00 00 00 ED 71 FF FF FF"
        val temp = NmeaParser.parse(line).first { it.type == NmeaParser.TYPE_WATER_TEMP }
        assertEquals(18.5, temp.value, 0.05)
        assertEquals("°C", temp.unit)
    }

    @Test
    fun waterTemperature_pgn130316_extendedRange_inCelsius() {
        // PGN 130316, source=0, temp uint24 raw=291650 (×0.001 K = 291.65 K = 18.5 °C), LE = 42 73 04
        val line = "10:20:30.500 R 15FD0C23 00 00 00 42 73 04 FF FF"
        val temp = NmeaParser.parse(line).first { it.type == NmeaParser.TYPE_WATER_TEMP }
        assertEquals(18.5, temp.value, 0.05)
    }

    @Test
    fun temperature_nonWaterSource_isIgnored() {
        // source=2 (внутренняя температура) — не температура воды, игнорируем
        val line = "10:20:30.500 R 15FD0823 00 00 02 ED 71 FF FF FF"
        assertTrue(NmeaParser.parse(line).isEmpty())
    }

    // ── Raymarine Axiom 7 (GPS) ──────────────────────────────────────────────────

    @Test
    fun gpsPosition_latLonInDegrees() {
        // PGN 129025: lat=10° (raw 100000000 = 0x05F5E100), lon=20° (raw 200000000 = 0x0BEBC200)
        val line = "10:20:30.500 R 0DF80116 00 E1 F5 05 00 C2 EB 0B"
        val result = NmeaParser.parse(line)
        val lat = result.first { it.type == NmeaParser.TYPE_GPS_LAT }
        val lon = result.first { it.type == NmeaParser.TYPE_GPS_LON }
        assertEquals(10.0, lat.value, 1e-6)
        assertEquals(20.0, lon.value, 1e-6)
    }

    @Test
    fun gpsPosition_southWest_isNegative() {
        // lat = -10° (int32 -100000000 = 0xFA0A1F00, LE = 00 1F 0A FA)
        val line = "10:20:30.500 R 0DF80116 00 1F 0A FA 00 C2 EB 0B"
        val lat = NmeaParser.parse(line).first { it.type == NmeaParser.TYPE_GPS_LAT }
        assertEquals(-10.0, lat.value, 1e-6)
    }

    @Test
    fun gpsCogSog_courseDegreesAndSpeedKnots() {
        // PGN 129026: COG=90° (raw 15708 = 0x3D5C), SOG=5 уз (raw 257 = 0x0101)
        val line = "10:20:30.500 R 0DF80216 00 00 5C 3D 01 01 FF FF"
        val result = NmeaParser.parse(line)
        val cog = result.first { it.type == NmeaParser.TYPE_GPS_COG }
        val sog = result.first { it.type == NmeaParser.TYPE_GPS_SOG }
        assertEquals(90.0, cog.value, 0.1)
        assertEquals(5.0, sog.value, 0.05)
        assertEquals("уз", sog.unit)
    }

    @Test
    fun transmittedAndUnknownLines_areIgnored() {
        // Направление T (отправлено) — игнорируем
        assertTrue(NmeaParser.parse("10:20:30.500 T 09FD0217 00 E8 03 74 14 02 FF FF").isEmpty())
        // Слишком короткая строка
        assertTrue(NmeaParser.parse("garbage").isEmpty())
    }
}
