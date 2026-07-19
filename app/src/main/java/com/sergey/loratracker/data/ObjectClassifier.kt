package com.sergey.loratracker.data

import com.sergey.loratracker.service.FileLogger

enum class DetectedObject(
    val displayName: String,
    val emoji: String,
    val peakFreqRange: ClosedFloatingPointRange<Float>,
    val centroidMinRatio: Float,
    val minDb: Float,
    val maxDetectionRangeMeters: Float,
    val description: String
) {
    DRONE("Дрон", "\uD83D\uDE81", 400f..20000f, 1.0f, 0f, 500f, "Пропеллеры"),
    UNKNOWN("Фон", "\uD83C\uDF3F", 0f..0f, 0f, 0f, 0f, "Нет сигнала");

    companion object {
        private val peakHistory = mutableListOf<Float>()
        private const val HISTORY_SIZE = 5

        var onDroneDetected: (() -> Unit)? = null

        fun classify(packet: TelemetryPacket): DetectedObject {
            val peak = packet.soundPeakFreq

            if (peak < 80f) return UNKNOWN

            peakHistory.add(peak)
            if (peakHistory.size > HISTORY_SIZE) peakHistory.removeAt(0)

            val variance = if (peakHistory.size >= 3) {
                val avg = peakHistory.average().toFloat()
                peakHistory.map { (it - avg) * (it - avg) }.average().toFloat()
            } else 0f

            FileLogger.d("CLASSIFY", "peak=${peak.toInt()} var=${variance.toInt()} history=${peakHistory.size}")

            if (variance > 5000f) return UNKNOWN

            if (peakHistory.size >= 3 && peakHistory.takeLast(3).all { it > 400f }) {
                onDroneDetected?.invoke()
                return DRONE
            }

            return UNKNOWN
        }

        fun reset() {
            peakHistory.clear()
        }
    }
}
