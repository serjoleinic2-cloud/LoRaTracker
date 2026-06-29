package com.sergey.loratracker.data

import com.sergey.loratracker.service.FileLogger
import kotlin.math.sqrt

object VibrationDetector {
    private const val VIBRATION_THRESHOLD = 1.5f
    private const val MIN_FREQ = 20f
    private const val MAX_FREQ = 100f
    private const val HISTORY_SIZE = 10
    private const val GRAVITY = 9.8f

    private val accelHistory = mutableListOf<Triple<Float, Float, Float>>()

    data class VibrationResult(val isTank: Boolean, val vibrationRms: Float, val vibrationFreq: Float)

    fun detect(packet: TelemetryPacket): VibrationResult {
        val ax = packet.accelX
        val ay = packet.accelY
        val az = packet.accelZ

        val magnitude = sqrt(ax * ax + ay * ay + az * az)
        val vibrationRms = kotlin.math.abs(magnitude - GRAVITY)

        accelHistory.add(Triple(ax, ay, az))
        if (accelHistory.size > HISTORY_SIZE) accelHistory.removeAt(0)

        val vibrationFreq = estimateFrequency()

        val isTank = vibrationRms > VIBRATION_THRESHOLD && vibrationFreq in MIN_FREQ..MAX_FREQ

        FileLogger.d("VIBRO", "rms=${"%.2f".format(vibrationRms)} freq=${"%.1f".format(vibrationFreq)}Hz tank=$isTank")

        return VibrationResult(isTank, vibrationRms, vibrationFreq)
    }

    private fun estimateFrequency(): Float {
        if (accelHistory.size < 3) return 0f

        var zeroCrossings = 0
        for (i in 1 until accelHistory.size) {
            val prev = accelHistory[i - 1].first + accelHistory[i - 1].second + accelHistory[i - 1].third
            val curr = accelHistory[i].first + accelHistory[i].second + accelHistory[i].third
            if (prev * curr < 0) zeroCrossings++
        }

        return (zeroCrossings / 2f) * 10f
    }

    fun reset() {
        accelHistory.clear()
    }
}
