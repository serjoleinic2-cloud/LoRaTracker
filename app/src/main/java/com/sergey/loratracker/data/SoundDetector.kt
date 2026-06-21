package com.sergey.loratracker.data

class Inmp441SoundDetector(
    private val detectionRadiusMeters: Float = 5f,
    private val centroidRatioThreshold: Float = 2.0f,
    private val minPeakFreq: Float = 80f,
    private val maxPeakFreq: Float = 12000f
) {
    fun detect(packet: TelemetryPacket, rmsDb: Float = 0f): DetectionResult {
        if (packet.soundPeakFreq < minPeakFreq || packet.soundPeakFreq > maxPeakFreq) {
            return DetectionResult(
                isObjectNearby = false,
                confidence = 0f,
                estimatedRadiusMeters = null,
                soundLevel = SoundLevel.SILENT,
                detectedObject = DetectedObject.UNKNOWN,
                rmsDb = rmsDb,
                reason = "Тишина или вне диапазона INMP441"
            )
        }

        val detectedObject = DetectedObject.classify(packet, rmsDb)
        val ratio = packet.soundEnergyRatio

        return when {
            ratio > 4.0f && detectedObject != DetectedObject.UNKNOWN -> DetectionResult(
                isObjectNearby = true,
                confidence = 1.0f,
                estimatedRadiusMeters = detectionRadiusMeters,
                soundLevel = SoundLevel.ALERT,
                detectedObject = detectedObject,
                rmsDb = rmsDb,
                reason = "${detectedObject.emoji} ${detectedObject.displayName} в радиусе ${detectionRadiusMeters.toInt()}м!"
            )
            ratio > centroidRatioThreshold && detectedObject != DetectedObject.UNKNOWN -> DetectionResult(
                isObjectNearby = true,
                confidence = (ratio / 4.0f).coerceIn(0.5f, 0.9f),
                estimatedRadiusMeters = detectionRadiusMeters * 1.5f,
                soundLevel = SoundLevel.HIGH,
                detectedObject = detectedObject,
                rmsDb = rmsDb,
                reason = "${detectedObject.emoji} Обнаружен ${detectedObject.displayName}"
            )
            detectedObject != DetectedObject.UNKNOWN -> DetectionResult(
                isObjectNearby = false,
                confidence = 0.3f,
                estimatedRadiusMeters = null,
                soundLevel = SoundLevel.MEDIUM,
                detectedObject = detectedObject,
                rmsDb = rmsDb,
                reason = "${detectedObject.emoji} Слабый сигнал: ${detectedObject.displayName}"
            )
            else -> DetectionResult(
                isObjectNearby = false,
                confidence = 0f,
                estimatedRadiusMeters = null,
                soundLevel = SoundLevel.LOW,
                detectedObject = DetectedObject.UNKNOWN,
                rmsDb = rmsDb,
                reason = "Фоновый шум"
            )
        }
    }
}
