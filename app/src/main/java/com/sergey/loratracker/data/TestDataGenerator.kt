package com.sergey.loratracker.data

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalTime
import kotlin.random.Random

object TestDataGenerator {

    private const val DETECTOR_LAT = 55.7539
    private const val DETECTOR_LON = 37.6208

    private val testObjects = listOf(DetectedObject.DRONE)

    private var angle = 0f
    private var currentObject: DetectedObject? = null
    private var objectDistance = 0f

    fun generateTestStream(): Flow<TelemetryPacket> = flow {
        Log.d("TEST", "Test stream started")
        var counter = 0

        while (true) {
            delay(1500)
            counter++
            Log.d("TEST", "Generating packet #$counter")

            if (currentObject == null || Random.nextFloat() < 0.3f) {
                currentObject = if (Random.nextFloat() > 0.2f) DetectedObject.DRONE else null
                objectDistance = if (currentObject != null) Random.nextFloat() * 40 + 5 else 0f
            } else {
                objectDistance = (objectDistance + Random.nextFloat() * 10 - 5).coerceIn(3f, 50f)
            }

            angle += Random.nextFloat() * 30 + 10

            val (peakFreq, centroidRatio) = if (currentObject == DetectedObject.DRONE) {
                Pair(3000f, 4.0f)
            } else {
                Pair(50f, 0.5f)
            }

            val distanceFactor = if (currentObject != null) (50f / objectDistance).coerceAtMost(3f) else 0f
            val centroid = peakFreq * (centroidRatio + distanceFactor * 0.5f)

            Log.d("TEST", "Emitting: peak=$peakFreq, obj=${currentObject?.displayName ?: "none"}")

            emit(TelemetryPacket(
                detectorId = 0,
                delayMs = Random.nextInt(10000, 99999),
                gpsSats = 8,
                latitude = DETECTOR_LAT,
                longitude = DETECTOR_LON,
                gpsTime = LocalTime.now(),
                accelX = 0.5f,
                accelY = -0.1f,
                accelZ = 10.2f,
                temperature = 22f,
                soundPeakFreq = peakFreq,
                soundCenterFreq = centroid,
                rssi = -50 - (objectDistance / 10).toInt()
            ))
        }
    }
}
