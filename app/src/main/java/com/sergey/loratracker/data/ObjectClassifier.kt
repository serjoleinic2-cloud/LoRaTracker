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
    DRONE("Дрон", "\uD83D\uDE81", 500f..20000f, 1.0f, 0f, 500f, "Пропеллеры"),
    UNKNOWN("Фон", "\uD83C\uDF3F", 0f..0f, 0f, 0f, 0f, "Нет сигнала");

    companion object {
        fun classify(packet: TelemetryPacket): DetectionResult {
            val peak = packet.soundPeakFreq
            val centroid = packet.soundCenterFreq

            FileLogger.d("CLASSIFY", "peak=${peak.toInt()} centroid=${centroid.toInt()}")

            if (peak < 40f) {
                return DetectionResult(false, 0f, null, SoundLevel.SILENT, UNKNOWN, 0f, "ТИШИНА")
            }

            if (centroid > 1000f || peak > 500f) {
                return DetectionResult(
                    isObjectNearby = true,
                    confidence = 1.0f,
                    estimatedRadiusMeters = 50f,
                    soundLevel = SoundLevel.HIGH,
                    detectedObject = DRONE,
                    rmsDb = 0f,
                    reason = "ДРОН | peak=${peak.toInt()}Hz centroid=${centroid.toInt()}Hz"
                )
            }

            return DetectionResult(false, 0f, null, SoundLevel.SILENT, UNKNOWN, 0f, "ФОН")
        }
    }
}
