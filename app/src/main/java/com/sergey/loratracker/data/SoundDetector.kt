package com.sergey.loratracker.data

class Inmp441SoundDetector {
    private val maxPeakFreq: Float = 20000f

    fun detect(packet: TelemetryPacket): DetectionResult {
        if (packet.soundPeakFreq > maxPeakFreq) {
            return DetectionResult(false, 0f, null, SoundLevel.SILENT, DetectedObject.UNKNOWN, 0f, "Вне диапазона")
        }
        return DetectedObject.classify(packet)
    }
}
