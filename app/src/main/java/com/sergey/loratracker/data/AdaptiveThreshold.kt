package com.sergey.loratracker.data

import com.sergey.loratracker.service.FileLogger

object AdaptiveThreshold {
    private val backgroundRatios = mutableListOf<Float>()
    private const val CALIBRATION_PACKETS = 10
    private var calibrationStartTime = 0L
    private const val CALIBRATION_TIMEOUT_MS = 30000L
    private var isCalibrated = false

    fun calibrate(ratio: Float, peak: Float) {
        val beforeSize = backgroundRatios.size
        if (backgroundRatios.size < CALIBRATION_PACKETS) {
            backgroundRatios.add(ratio)
            FileLogger.d("CALIBRATE", "ADD: before=$beforeSize after=${backgroundRatios.size} ratio=$ratio")
        }
        if (backgroundRatios.size >= CALIBRATION_PACKETS && !isCalibrated) {
            isCalibrated = true
            FileLogger.d("CALIBRATE", "DONE: avg=${getThreshold()}, samples=${backgroundRatios.size}")
        }
    }

    fun isCalibrated(): Boolean = isCalibrated

    fun getThreshold(): Float {
        if (backgroundRatios.isEmpty()) return 1.0f
        return backgroundRatios.average().toFloat() * 1.2f
    }

    fun getNoiseFloor(): Float {
        if (backgroundRatios.isEmpty()) return 0f
        return backgroundRatios.average().toFloat()
    }

    fun getProgress(): Int = backgroundRatios.size

    fun startCalibration() {
        calibrationStartTime = System.currentTimeMillis()
    }

    fun isTimeout(): Boolean {
        if (calibrationStartTime == 0L) return false
        return System.currentTimeMillis() - calibrationStartTime > CALIBRATION_TIMEOUT_MS
    }

    fun forceCalibrate() {
        if (backgroundRatios.size >= 5) {
            isCalibrated = true
            FileLogger.d("CALIBRATE", "FORCED after timeout, samples=${backgroundRatios.size}")
        }
    }

    fun reset() {
        backgroundRatios.clear()
        calibrationStartTime = 0L
        isCalibrated = false
    }
}
