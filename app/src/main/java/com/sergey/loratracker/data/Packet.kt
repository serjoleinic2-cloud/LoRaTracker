package com.sergey.loratracker.data

import java.time.LocalTime

data class TelemetryPacket(
    val detectorId: Int,
    val delayMs: Int,
    val gpsSats: Int,
    val latitude: Double,
    val longitude: Double,
    val gpsTime: LocalTime?,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val temperature: Float,
    val soundPeakFreq: Float,
    val soundCenterFreq: Float,
    val rssi: Int,
    val soundLevelDb: Float = 0f
) {
    val hasGpsFix: Boolean
        get() = latitude != 0.0 && longitude != 0.0
    
    val isGpsValid: Boolean
        get() = !latitude.isNaN() && !longitude.isNaN() 
                && latitude in -90.0..90.0 
                && longitude in -180.0..180.0
    
    // INMP441: оценка энергии через соотношение centroid/peak
    val soundEnergyRatio: Float
        get() = if (soundPeakFreq > 0f) soundCenterFreq / soundPeakFreq else 1f

    fun calculateDbSPL(peakAmplitude: Float): Float {
        return 94f + 20f * kotlin.math.log10(peakAmplitude / 420426f)
    }
}

enum class SoundLevel(val displayName: String) {
    SILENT("Тишина"),
    LOW("Низкий"),
    MEDIUM("Средний"),
    HIGH("Высокий"),
    ALERT("Критический")
}

data class DetectionResult(
    val isObjectNearby: Boolean,
    val confidence: Float,
    val estimatedRadiusMeters: Float?,
    val soundLevel: SoundLevel,
    val detectedObject: DetectedObject,
    val rmsDb: Float,
    val reason: String
)