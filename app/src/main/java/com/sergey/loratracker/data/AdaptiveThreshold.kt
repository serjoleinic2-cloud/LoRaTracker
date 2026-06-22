package com.sergey.loratracker.data

import com.sergey.loratracker.service.FileLogger

object AdaptiveThreshold {
    private val backgroundRatios = mutableListOf<Float>()
    private const val CALIBRATION_PACKETS = 20
    private var isCalibrated = false

    fun calibrate(ratio: Float, peak: Float) {
        if (backgroundRatios.size < CALIBRATION_PACKETS) {
            backgroundRatios.add(ratio)
            FileLogger.d("CALIBRATE", "Sample ${backgroundRatios.size}/$CALIBRATION_PACKETS: ratio=$ratio")
            if (backgroundRatios.size == CALIBRATION_PACKETS) {
                isCalibrated = true
                FileLogger.d("CALIBRATE", "DONE: avg=${getThreshold()}")
            }
        }
    }

    fun isCalibrated(): Boolean = isCalibrated

    fun getThreshold(): Float {
        if (backgroundRatios.isEmpty()) return 1.0f
        return backgroundRatios.average().toFloat() * 1.5f
    }

    fun getNoiseFloor(): Float {
        if (backgroundRatios.isEmpty()) return 0f
        return backgroundRatios.average().toFloat()
    }

    fun getProgress(): Int = backgroundRatios.size

    fun reset() {
        backgroundRatios.clear()
        isCalibrated = false
    }
}
