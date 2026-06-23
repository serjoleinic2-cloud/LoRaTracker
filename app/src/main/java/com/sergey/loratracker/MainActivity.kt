package com.sergey.loratracker

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.sergey.loratracker.data.DetectedObject
import com.sergey.loratracker.data.DetectionResult
import com.sergey.loratracker.data.AdaptiveThreshold
import com.sergey.loratracker.data.TelemetryPacket
import com.sergey.loratracker.databinding.ActivityMainBinding
import com.sergey.loratracker.service.FileLogger
import com.sergey.loratracker.service.UsbSerialService
import com.sergey.loratracker.viewmodel.TrackerViewModel
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapView: MapView
    private lateinit var usbManager: UsbManager
    private val viewModel: TrackerViewModel by viewModels()
    private var lastPeakFreq = 0f
    private var lastCenterFreq = 0f
    private var stableCount = 0
    private var lastGpsPoint: GeoPoint? = null
    private var gpsJumpCount = 0
    private var fixedDetectorPoint: GeoPoint? = null
    private val detectorMarkers = mutableMapOf<Int, Marker>()
    private val targetMarkers = mutableListOf<Marker>()
    private val targetCircles = mutableListOf<Polygon>()
    private val targetLines = mutableListOf<Polyline>()
    private lateinit var wakeLock: android.os.PowerManager.WakeLock
    private var pendingUsbIntent: PendingIntent? = null
    private var isTestMode = false
    private var isFirstFix = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(
            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "LoRaTracker::KeepScreenOn"
        )
        wakeLock.acquire()

        FileLogger.init(this)
        FileLogger.d("MAIN", "MainActivity onCreate")

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = connectivityManager.activeNetworkInfo
        val isOnline = networkInfo?.isConnected == true
        Log.d("MAIN", "Internet: $isOnline")

        if (!isOnline) {
            binding.usbStatusText.text = "НЕТ ИНТЕРНЕТА"
        }

        val osmPath = File(filesDir, "osmdroid")
        osmPath.mkdirs()
        Configuration.getInstance().load(
            this,
            getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().osmdroidBasePath = osmPath
        Configuration.getInstance().osmdroidTileCache = File(osmPath, "tiles")
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 50L * 1024 * 1024
        Configuration.getInstance().tileFileSystemCacheTrimBytes = 30L * 1024 * 1024
        Configuration.getInstance().userAgentValue = "LoRaTracker/1.0"
        Configuration.getInstance().isDebugMapView = true
        Configuration.getInstance().isDebugTileProviders = true

        mapView = binding.mapView
        Log.d("MAP", "MapView created: $mapView")

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        Log.d("MAP", "Tile source set")

        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(17.0)
        Log.d("MAP", "Zoom set to 17")

        val center = GeoPoint(55.7539, 37.6208)
        mapView.controller.setCenter(center)
        Log.d("MAP", "Center set to $center")

        mapView.invalidate()
        Log.d("MAP", "Invalidated")

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        pendingUsbIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent("com.sergey.loratracker.USB_PERMISSION"),
            PendingIntent.FLAG_IMMUTABLE
        )

        binding.testModeButton.visibility = View.GONE
        binding.usbConnectButton.setOnClickListener { checkUsbDevices() }

        binding.showLogsButton.setOnClickListener {
            val dir = File(getExternalFilesDir(null), "lora_logs")
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() }
            val latest = files?.firstOrNull()
            binding.usbStatusText.text = if (latest != null) {
                val content = latest.readText().lines().takeLast(30).joinToString("\n")
                "LOG: ${latest.name}\n\n$content"
            } else {
                "Нет логов"
            }
        }

        binding.shareLogsButton.setOnClickListener {
            val dir = File(getExternalFilesDir(null), "lora_logs")
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() }
            val latest = files?.firstOrNull()
            if (latest != null) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    latest
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Отправить логи"))
            } else {
                binding.usbStatusText.text = "Нет логов для отправки"
            }
        }

        binding.calibrateButton.setOnClickListener {
            AdaptiveThreshold.reset()
            binding.objectDescription.text = "Калибровка: собираю фоновый шум (20 пакетов)..."
            FileLogger.d("MAIN", "Calibration started")
        }

        binding.resetCalibrateButton.setOnClickListener {
            AdaptiveThreshold.reset()
            binding.objectDescription.text = "Калибровка сброшена"
            FileLogger.d("MAIN", "Calibration reset")
        }

        binding.centerMapButton.setOnClickListener {
            val packet = viewModel.packet.value
            if (packet != null && packet.latitude != 0.0 && packet.longitude != 0.0) {
                mapView.controller.animateTo(GeoPoint(packet.latitude, packet.longitude))
                mapView.controller.setZoom(18.0)
            }
        }

        startUsbService()
        observeData()

        binding.objectDescription.text = "Автокалибровка фона..."
        AdaptiveThreshold.reset()
    }

    private fun observeData() {
        FileLogger.d("MAIN", "observeData started")

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.packet.collect { packet ->
                        packet?.let {
                            FileLogger.d("MAIN", "packet: ${it.detectorId}, peak=${it.soundPeakFreq}")
                            updateUI(it)
                            val detection = viewModel.detection.value
                            if (detection != null) {
                                updateMap(it, detection)
                            }
                        }
                    }
                }
                launch {
                    viewModel.detection.collect { detection ->
                        detection?.let {
                            FileLogger.d("MAIN", "detection: ${it.detectedObject.displayName}")
                            updateDetectionUI(it)
                            val packet = viewModel.packet.value ?: return@let
                            updateMap(packet, it)
                        }
                    }
                }
                launch {
                    viewModel.connected.collect { connected ->
                        FileLogger.d("MAIN", "viewModel connected: $connected")
                        updateConnectionStatus(connected)
                    }
                }
            }
        }
    }

    private fun updateUI(packet: TelemetryPacket) {
        FileLogger.d("AUDIO", "peak=${packet.soundPeakFreq}Hz centroid=${packet.soundCenterFreq}Hz ratio=${packet.soundEnergyRatio} sat=${packet.gpsSats}")
        runOnUiThread {
            if (packet.soundType.isNotEmpty()) {
                binding.objectName.text = packet.soundType
                binding.objectEmoji.text = packet.emoji
                binding.objectDescription.text = "Датчик не передает звук"
                binding.distanceText.visibility = View.GONE
                return@runOnUiThread
            }

            binding.gpsStatus.text = if (packet.gpsSats > 0) {
                "GPS: ${packet.gpsSats} спутн."
            } else {
                "GPS: поиск..."
            }
            val currentPoint = GeoPoint(packet.latitude, packet.longitude)
            if (lastGpsPoint != null) {
                val dist = currentPoint.distanceToAsDouble(lastGpsPoint).toFloat()
                if (dist > 100f) {
                    gpsJumpCount++
                    if (gpsJumpCount > 3) {
                        binding.gpsStatus.text = "GPS: ГЛУШИТСЯ!"
                        binding.gpsStatus.setTextColor(android.graphics.Color.parseColor("#FF0000"))
                    }
                } else {
                    gpsJumpCount = 0
                    binding.gpsStatus.setTextColor(android.graphics.Color.parseColor("#BBBBBB"))
                }
            }
            lastGpsPoint = currentPoint
            binding.tempText.text = "Темп: ${packet.temperature}°C"
            binding.rssiText.text = "RSSI: ${packet.rssi} dBm"
            binding.peakFreqText.text = "Пик: ${packet.soundPeakFreq}Hz"
            binding.centroidText.text = "Центр: ${packet.soundCenterFreq}Hz"

            val maxRange = DetectedObject.values()
                .filter { it != DetectedObject.UNKNOWN && packet.soundPeakFreq in it.peakFreqRange }
                .maxByOrNull { it.maxDetectionRangeMeters }
            binding.maxRangeText.text = "Дальность: ${maxRange?.maxDetectionRangeMeters ?: 0}м"

            if (!AdaptiveThreshold.isCalibrated()) {
                binding.objectDescription.text = "Калибровка: ${AdaptiveThreshold.getProgress()}/20"
                binding.objectEmoji.text = "⏳"
                binding.objectName.text = "КАЛИБРОВКА"
            }

        }
    }

    private fun updateDetectionUI(detection: DetectionResult) {
        runOnUiThread {
            if (!AdaptiveThreshold.isCalibrated()) {
                binding.objectDescription.text = "Калибровка: ${AdaptiveThreshold.getProgress()}/20"
                binding.objectEmoji.text = "⏳"
                binding.objectName.text = "КАЛИБРОВКА"
                binding.distanceText.visibility = View.GONE
            }

            if (detection.isObjectNearby && detection.detectedObject != DetectedObject.UNKNOWN) {
                try {
                    val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
                    toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 150)
                } catch (_: Exception) {}
                try {
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        vibrator.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        vibrator.vibrate(200)
                    }
                } catch (_: Exception) {}
            }

            binding.objectEmoji.text = detection.detectedObject.emoji
            binding.objectName.text = if (detection.isObjectNearby) {
                detection.detectedObject.displayName.uppercase()
            } else {
                "ПАТРУЛИРОВАНИЕ"
            }
            binding.objectDescription.text = detection.reason

            if (detection.isObjectNearby && detection.estimatedRadiusMeters != null) {
                binding.distanceText.text = "${detection.estimatedRadiusMeters.toInt()}м"
                binding.distanceText.visibility = View.VISIBLE
            } else {
                binding.distanceText.visibility = View.GONE
            }

        }
    }

    private fun updateConnectionStatus(connected: Boolean) {
        runOnUiThread {
            binding.usbStatusText.text = if (connected) "USB: ПОДКЛЮЧЕНО" else "USB: ОТКЛЮЧЕНО"
            binding.usbStatusText.setTextColor(
                if (connected) android.graphics.Color.parseColor("#4CAF50")
                else android.graphics.Color.parseColor("#F44336")
            )
        }
    }

    private fun updateMap(packet: TelemetryPacket, detection: DetectionResult) {
        FileLogger.d("MAP", "updateMap called: lat=${packet.latitude}, lon=${packet.longitude}, id=${packet.detectorId}")

        val detectorPoint = when {
            packet.latitude != 0.0 && packet.longitude != 0.0 -> {
                fixedDetectorPoint = GeoPoint(packet.latitude, packet.longitude)
                GeoPoint(packet.latitude, packet.longitude)
            }
            fixedDetectorPoint != null -> fixedDetectorPoint!!
            else -> GeoPoint(55.7558, 37.6173)
        }

        val detectorId = packet.detectorId

        if (isFirstFix && packet.latitude != 0.0 && packet.longitude != 0.0) {
            mapView.controller.animateTo(detectorPoint)
            mapView.controller.setZoom(18.0)
            isFirstFix = false
        }

        runOnUiThread {
            targetMarkers.forEach { mapView.overlays.remove(it) }
            targetCircles.forEach { mapView.overlays.remove(it) }
            targetLines.forEach { mapView.overlays.remove(it) }
            targetMarkers.clear()
            targetCircles.clear()
            targetLines.clear()

            val existingMarker = detectorMarkers[detectorId]
            if (existingMarker != null) {
                existingMarker.position = detectorPoint
                existingMarker.snippet = "ID:$detectorId RSSI:${packet.rssi}"
            } else {
                val newMarker = Marker(mapView).apply {
                    position = detectorPoint
                    title = "📡 ДЕТЕКТОР $detectorId"
                    snippet = "RSSI:${packet.rssi}"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                detectorMarkers[detectorId] = newMarker
                mapView.overlays.add(newMarker)
            }

            if (detection.isObjectNearby && detection.estimatedRadiusMeters != null) {
                val dist = detection.estimatedRadiusMeters
                val angle = (packet.delayMs % 360).toDouble()
                val objLat = packet.latitude + kotlin.math.cos(Math.toRadians(angle)) * dist * 0.00001
                val objLon = packet.longitude + kotlin.math.sin(Math.toRadians(angle)) * dist * 0.00001

                val objMarker = Marker(mapView).apply {
                    position = GeoPoint(objLat, objLon)
                    title = "${detection.detectedObject.emoji} ${detection.detectedObject.displayName}"
                    snippet = "Расстояние: ${dist.toInt()}м"
                    setOnMarkerClickListener { marker, mapView ->
                        marker.showInfoWindow()
                        true
                    }
                }
                targetMarkers.add(objMarker)
                mapView.overlays.add(objMarker)

                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                handler.postDelayed({
                    objMarker.icon = resources.getDrawable(android.R.drawable.ic_menu_compass, null)
                }, 100)
                handler.postDelayed({
                    objMarker.icon = null
                }, 500)

                val line = Polyline().apply {
                    addPoint(detectorPoint)
                    addPoint(GeoPoint(objLat, objLon))
                    color = android.graphics.Color.RED
                    width = 3f
                }
                targetLines.add(line)
                mapView.overlays.add(line)

                val objCircle = Polygon().apply {
                    points = Polygon.pointsAsCircle(detectorPoint, dist.toDouble())
                    fillColor = 0x11FF0000
                    strokeColor = 0xFFFF0000.toInt()
                    strokeWidth = 2f
                }
                targetCircles.add(objCircle)
                mapView.overlays.add(objCircle)
            }

            mapView.invalidate()
            mapView.controller.animateTo(detectorPoint)
        }
    }

    private fun checkUsbDevices() {
        val deviceList = usbManager.deviceList
        FileLogger.d("MAIN", "checkUsbDevices: ${deviceList.size} devices")
        Log.d("USB", "Found ${deviceList.size} USB devices")

        if (deviceList.isEmpty()) {
            binding.usbStatusText.text = "USB: НЕТ УСТРОЙСТВ"
            return
        }

        for ((name, device) in deviceList) {
            Log.d("USB", "Device: $name, VID=${device.vendorId}, PID=${device.productId}")

            if (UsbSerialProber.getDefaultProber().probeDevice(device) != null) {
                Log.d("USB", "Supported serial device found!")

                if (usbManager.hasPermission(device)) {
                    binding.usbStatusText.text = "USB: ЕСТЬ РАЗРЕШЕНИЕ"
                    startUsbService()
                } else {
                    binding.usbStatusText.text = "USB: ЗАПРОШЕНО"
                    usbManager.requestPermission(device, pendingUsbIntent)
                }
                return
            }
        }

        binding.usbStatusText.text = "USB: НЕТ ПОДХОДЯЩИХ"
    }

    private fun startUsbService() {
        val intent = Intent(this, UsbSerialService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.sergey.loratracker.USB_PERMISSION") {
                synchronized(this) {
                    val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            Log.d("USB", "Permission granted for ${it.deviceName}")
                            binding.usbStatusText.text = "USB: РАЗРЕШЕНО"
                            startUsbService()
                        }
                    } else {
                        Log.d("USB", "Permission denied")
                        binding.usbStatusText.text = "USB: ОТКАЗАНО"
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        val filter = IntentFilter("com.sergey.loratracker.USB_PERMISSION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        unregisterReceiver(usbReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock.isHeld) wakeLock.release()
    }
}
