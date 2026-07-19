package com.sergey.loratracker.data

class Inmp441SoundDetector {
    private val maxPeakFreq: Float = 20000f

    fun detect(packet: TelemetryPacket): DetectionResult {
        if (packet.soundPeakFreq > maxPeakFreq) {
            return DetectionResult(false, 0f, null, SoundLevel.SILENT, DetectedObject.UNKNOWN, 0f, "Вне диапазона")
        }
        val detected = DetectedObject.classify(packet)
        val isNearby = detected != DetectedObject.UNKNOWN
        return DetectionResult(
            isObjectNearby = isNearby,
            confidence = if (isNearby) 1.0f else 0f,
            estimatedRadiusMeters = if (isNearby) detected.maxDetectionRangeMeters else null,
            soundLevel = if (isNearby) SoundLevel.HIGH else SoundLevel.SILENT,
            detectedObject = detected,
            rmsDb = 0f,
            reason = "${detected.displayName} | peak=${packet.soundPeakFreq.toInt()}Hz"
        )
    }
}
