package com.sergey.loratracker.data

class Inmp441SoundDetector {
    fun detect(packet: TelemetryPacket, rmsDb: Float = 60f): DetectionResult {
        val peak = packet.soundPeakFreq
        val ratio = packet.soundEnergyRatio

        if (peak < 50f || rmsDb < 25f) {
            return DetectionResult(
                isObjectNearby = false,
                confidence = 0f,
                estimatedRadiusMeters = null,
                soundLevel = SoundLevel.SILENT,
                detectedObject = DetectedObject.UNKNOWN,
                rmsDb = rmsDb,
                reason = "ТИШИНА"
            )
        }

        if (peak < 200f && ratio > 5f && rmsDb > 55f) {
            return DetectionResult(
                isObjectNearby = false,
                confidence = 0f,
                estimatedRadiusMeters = null,
                soundLevel = SoundLevel.LOW,
                detectedObject = DetectedObject.UNKNOWN,
                rmsDb = rmsDb,
                reason = "ВЕТЕР/ДОЖДЬ"
            )
        }

        if (peak < 150f && ratio < 1.5f && rmsDb < 45f) {
            return DetectionResult(
                isObjectNearby = false,
                confidence = 0f,
                estimatedRadiusMeters = null,
                soundLevel = SoundLevel.LOW,
                detectedObject = DetectedObject.UNKNOWN,
                rmsDb = rmsDb,
                reason = "ГОРОДСКОЙ ФОН"
            )
        }

        val obj = DetectedObject.classify(packet, rmsDb)
        val best = obj.detectedObject

        if (best == DetectedObject.UNKNOWN) {
            return DetectionResult(
                isObjectNearby = false,
                confidence = 0f,
                estimatedRadiusMeters = null,
                soundLevel = SoundLevel.MEDIUM,
                detectedObject = DetectedObject.UNKNOWN,
                rmsDb = rmsDb,
                reason = "НЕТ СООТВЕТСТВИЯ"
            )
        }

        val excessDb = rmsDb - best.minDb
        val distance = when {
            excessDb > 25f -> best.maxDetectionRangeMeters * 0.05f
            excessDb > 15f -> best.maxDetectionRangeMeters * 0.15f
            excessDb > 8f -> best.maxDetectionRangeMeters * 0.35f
            excessDb > 3f -> best.maxDetectionRangeMeters * 0.65f
            excessDb > 0f -> best.maxDetectionRangeMeters * 0.9f
            else -> best.maxDetectionRangeMeters
        }

        val confidence = (ratio / best.centroidMinRatio).coerceAtMost(1.0f)
        val isDetected = confidence >= 0.7f && excessDb >= -5f

        return if (isDetected) {
            DetectionResult(
                isObjectNearby = true,
                confidence = confidence,
                estimatedRadiusMeters = distance,
                soundLevel = when {
                    excessDb > 15f -> SoundLevel.ALERT
                    excessDb > 5f -> SoundLevel.HIGH
                    else -> SoundLevel.MEDIUM
                },
                detectedObject = best,
                rmsDb = rmsDb,
                reason = "ЦЕЛЬ: ${best.displayName} | ${distance.toInt()}м"
            )
        } else {
            DetectionResult(
                isObjectNearby = false,
                confidence = confidence,
                estimatedRadiusMeters = distance,
                soundLevel = SoundLevel.MEDIUM,
                detectedObject = best,
                rmsDb = rmsDb,
                reason = "СЛАБЫЙ: ${best.displayName} | ${distance.toInt()}м"
            )
        }
    }
}
