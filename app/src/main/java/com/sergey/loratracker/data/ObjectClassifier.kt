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
    TANK("Танк", "\uD83D\uDEA2", 40f..250f, 1.0f, 0f, 1000f, "Гусеницы"),
    UNKNOWN("Фон", "\uD83C\uDF3F", 0f..0f, 0f, 0f, 0f, "Нет сигнала");

    companion object {
        fun classify(packet: TelemetryPacket): DetectionResult {
            val peak = packet.soundPeakFreq

            FileLogger.d("CLASSIFY", "peak=${peak.toInt()} accel=(${packet.accelX},${packet.accelY},${packet.accelZ})")

            if (peak > 20000f) {
                return DetectionResult(false, 0f, null, SoundLevel.SILENT, UNKNOWN, 0f, "ТИШИНА")
            }

            if (peak > 500f) {
                return DetectionResult(
                    isObjectNearby = true,
                    confidence = 1.0f,
                    estimatedRadiusMeters = 50f,
                    soundLevel = SoundLevel.HIGH,
                    detectedObject = DRONE,
                    rmsDb = 0f,
                    reason = "ДРОН | ${peak.toInt()}Hz"
                )
            }

            val vibro = VibrationDetector.detect(packet)
            if (vibro.isTank) {
                return DetectionResult(
                    isObjectNearby = true,
                    confidence = 0.9f,
                    estimatedRadiusMeters = 100f,
                    soundLevel = SoundLevel.MEDIUM,
                    detectedObject = TANK,
                    rmsDb = 0f,
                    reason = "ТАНК | вибрация ${"%.1f".format(vibro.vibrationFreq)}Hz RMS=${"%.2f".format(vibro.vibrationRms)}"
                )
            }

            return DetectionResult(false, 0f, null, SoundLevel.SILENT, UNKNOWN, 0f, "НЕТ ЦЕЛИ | ${peak.toInt()}Hz")
        }
    }
}
