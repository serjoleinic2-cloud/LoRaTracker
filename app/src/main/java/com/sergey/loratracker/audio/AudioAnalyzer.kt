package com.sergey.loratracker.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.*

class AudioAnalyzer {

    companion object {
        const val SAMPLE_RATE = 44100
        const val FFT_SIZE = 4096
        const val TAG = "AudioAnalyzer"
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    fun startAnalysis(): Flow<AudioFeatures> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            return@flow
        }

        audioRecord?.startRecording()

        try {
            val noiseSuppressor = android.media.audiofx.NoiseSuppressor.create(audioRecord!!.audioSessionId)
            noiseSuppressor?.enabled = false
            Log.d(TAG, "NoiseSuppressor disabled")
        } catch (_: Exception) {}

        isRecording = true

        val testBuf = ShortArray(FFT_SIZE)
        val testRead = audioRecord?.read(testBuf, 0, FFT_SIZE) ?: 0
        if (testRead > 0) {
            val testRms = sqrt(testBuf.map { it * it }.average())
            Log.d(TAG, "Microphone OK, RMS=$testRms")
        } else {
            Log.e(TAG, "Microphone read failed: $testRead")
        }

        val buffer = ShortArray(FFT_SIZE)

        while (isRecording) {
            val read = audioRecord?.read(buffer, 0, FFT_SIZE) ?: 0
            if (read < FFT_SIZE) continue

            val features = extractSimpleFeatures(buffer)
            emit(features)
        }
    }.flowOn(Dispatchers.Default)

    fun stop() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun extractSimpleFeatures(buffer: ShortArray): AudioFeatures {
        val n = FFT_SIZE
        val real = DoubleArray(n) { buffer[it].toDouble() }
        val imag = DoubleArray(n) { 0.0 }

        val bits = (log2(n.toDouble())).toInt()
        for (i in 0 until n) {
            var j = 0
            for (k in 0 until bits) {
                j = (j shl 1) or ((i shr k) and 1)
            }
            if (j > i) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wlenR = cos(angle)
            val wlenI = sin(angle)
            for (i in 0 until n step len) {
                var wR = 1.0
                var wI = 0.0
                for (j in 0 until len / 2) {
                    val uR = real[i + j]
                    val uI = imag[i + j]
                    val vR = real[i + j + len / 2] * wR - imag[i + j + len / 2] * wI
                    val vI = real[i + j + len / 2] * wI + imag[i + j + len / 2] * wR
                    real[i + j] = uR + vR
                    imag[i + j] = uI + vI
                    real[i + j + len / 2] = uR - vR
                    imag[i + j + len / 2] = uI - vI
                    val nextWR = wR * wlenR - wI * wlenI
                    wI = wR * wlenI + wI * wlenR
                    wR = nextWR
                }
            }
            len *= 2
        }

        val magnitudes = DoubleArray(n / 2) {
            sqrt(real[it] * real[it] + imag[it] * imag[it])
        }

        var peakIdx = 0
        var peakMag = 0.0
        for (i in 50 until n / 4) {
            if (magnitudes[i] > peakMag) {
                peakMag = magnitudes[i]
                peakIdx = i
            }
        }
        val peakFreq = peakIdx * SAMPLE_RATE.toFloat() / n

        var sumMag = 0.0
        var sumWeighted = 0.0
        for (i in 0 until n / 2) {
            val freq = i * SAMPLE_RATE.toDouble() / n
            sumMag += magnitudes[i]
            sumWeighted += freq * magnitudes[i]
        }
        val centroid = if (sumMag > 0) (sumWeighted / sumMag).toFloat() else 0f

        var sumSq = 0.0
        for (sample in buffer) sumSq += sample * sample
        val rms = sqrt(sumSq / buffer.size)
        val db = (20 * log10(rms / 32768.0 + 1e-10)).toFloat()

        val isWind = peakFreq < 200f && db > 50f && centroid > peakFreq * 3
        val isRain = peakFreq > 8000f && db > 40f && centroid > 10000f

        val filteredPeak = if (isWind || isRain) 0f else peakFreq
        val filteredCentroid = if (isWind || isRain) 0f else centroid

        if (isWind) Log.d(TAG, "Wind detected, filtering")
        if (isRain) Log.d(TAG, "Rain detected, filtering")

        return AudioFeatures(
            peakFreq = filteredPeak.coerceIn(50f, 20000f),
            centroidFreq = filteredCentroid.coerceIn(100f, 20000f),
            rmsDb = db,
            energyRatio = if (filteredPeak > 0) (filteredCentroid / filteredPeak).coerceAtLeast(0.1f) else 0.1f
        )
    }

    data class AudioFeatures(
        val peakFreq: Float,
        val centroidFreq: Float,
        val rmsDb: Float,
        val energyRatio: Float
    )
}
