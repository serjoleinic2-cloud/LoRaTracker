package com.sergey.loratracker.data

import java.time.LocalTime

object PacketParser {
    fun testParse(): Boolean {
        val testLine = "Received packet - '20893;0;0.00;0.00;12;32;50;0.50;-0.13;10.23;28.58;132.81;317.22' with RSSI -65"
        val result = parse(testLine)
        return result != null
    }
    private val packetRegex = Regex(
        """Received packet - '([\d.;-]+)' with RSSI (-?\d+)"""
    )
    
    fun parse(line: String): Pair<TelemetryPacket, Int>? {
        val match = packetRegex.find(line) ?: return null
        val fields = match.groupValues[1].split(";")
        if (fields.size != 13) return null
        
        return try {
            val packet = TelemetryPacket(
                delayMs = fields[0].toInt(),
                gpsSats = fields[1].toInt(),
                latitude = fields[2].toDouble(),
                longitude = fields[3].toDouble(),
                gpsTime = parseTime(fields[4], fields[5], fields[6]),
                accelX = fields[7].toFloat(),
                accelY = fields[8].toFloat(),
                accelZ = fields[9].toFloat(),
                temperature = fields[10].toFloat(),
                soundPeakFreq = fields[11].toFloat(),
                soundCenterFreq = fields[12].toFloat(),
                rssi = match.groupValues[2].toInt()
            )
            packet to match.groupValues[2].toInt()
        } catch (e: Exception) {
            null
        }
    }
    
    private fun parseTime(h: String, m: String, s: String): LocalTime? {
        return if (h == "0" && m == "0" && s == "0") null
        else LocalTime.of(h.toInt(), m.toInt(), s.toInt())
    }
}