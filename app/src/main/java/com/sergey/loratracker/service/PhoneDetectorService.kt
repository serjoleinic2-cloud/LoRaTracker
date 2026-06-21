package com.sergey.loratracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sergey.loratracker.MainActivity
import com.sergey.loratracker.R
import com.sergey.loratracker.audio.AudioAnalyzer
import com.sergey.loratracker.data.TelemetryPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import java.time.LocalTime

class PhoneDetectorService : Service() {

    companion object {
        val DETECTOR_ID = java.util.UUID.randomUUID().toString().takeLast(4).uppercase()
        private const val TAG = "PhoneDetector"
        private const val CHANNEL_ID = "phone_detector"
        private const val NOTIFICATION_ID = 2

        private val _packetFlow = MutableSharedFlow<TelemetryPacket>(extraBufferCapacity = 10)
        val packetFlow: SharedFlow<TelemetryPacket> = _packetFlow.asSharedFlow()

        private val _rmsDbFlow = MutableSharedFlow<Float>(extraBufferCapacity = 10)
        val rmsDbFlow: SharedFlow<Float> = _rmsDbFlow.asSharedFlow()

        private val _isRunning = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
        val isRunning: SharedFlow<Boolean> = _isRunning.asSharedFlow()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var audioAnalyzer: AudioAnalyzer? = null
    private var locationManager: LocationManager? = null
    private var currentLocation: Location? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentLocation = location
            Log.d(TAG, "GPS: ${location.latitude}, ${location.longitude}")
        }
        @Deprecated("Deprecated in API 29", level = DeprecationLevel.HIDDEN)
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        startDetection()
        return START_STICKY
    }

    private fun startDetection() {
        val testRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            44100,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            1024
        )
        if (testRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Microphone is busy! Another app is using it.")
            return
        }
        testRecord.release()

        _isRunning.tryEmit(true)

        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000,
                1f,
                locationListener
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "GPS permission denied", e)
        }

        audioAnalyzer = AudioAnalyzer()
        serviceScope.launch {
            audioAnalyzer?.startAnalysis()?.collect { features ->
                val location = currentLocation
                val packet = TelemetryPacket(
                    delayMs = System.currentTimeMillis().toInt(),
                    gpsSats = if (location != null) 8 else 0,
                    latitude = location?.latitude ?: 0.0,
                    longitude = location?.longitude ?: 0.0,
                    gpsTime = LocalTime.now(),
                    accelX = 0f,
                    accelY = 0f,
                    accelZ = 9.8f,
                    temperature = 0f,
                    soundPeakFreq = features.peakFreq,
                    soundCenterFreq = features.centroidFreq,
                    rssi = features.rmsDb.toInt()
                )
                _packetFlow.tryEmit(packet)
                _rmsDbFlow.tryEmit(features.rmsDb)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioAnalyzer?.stop()
        locationManager?.removeUpdates(locationListener)
        serviceScope.cancel()
        _isRunning.tryEmit(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Детектор телефона",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Анализ звука и GPS"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎤 Детектор активен")
            .setContentText("Слушаю окружение...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
