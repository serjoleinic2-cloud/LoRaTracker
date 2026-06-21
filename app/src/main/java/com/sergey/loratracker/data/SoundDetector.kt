package com.sergey.loratracker.data

class Inmp441SoundDetector {
    fun detect(packet: TelemetryPacket): DetectionResult {
        return DetectedObject.classify(packet)
    }
}
