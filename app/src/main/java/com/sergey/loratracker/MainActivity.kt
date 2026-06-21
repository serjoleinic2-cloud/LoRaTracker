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
import com.sergey.loratracker.data.PacketParser
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
    private var lastMapUpdate = 0L
    private val MAP_UPDATE_INTERVAL_MS = 4000L
    private var lastGpsPoint: GeoPoint? = null
    private var gpsJumpCount = 0
    private var fixedDetectorPoint: GeoPoint? = null
    private var pendingUsbIntent: PendingIntent? = null
    private var isTestMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        val parserWorks = PacketParser.testParse()
        Log.d("MAIN", "Parser test: $parserWorks")

        startUsbService()
        observeData()
    }

    private fun observeData() {
        FileLogger.d("MAIN", "observeData started")

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.packet.collect { packet ->
                        packet?.let {
                            FileLogger.d("MAIN", "viewModel packet: ${it.delayMs}")
                            updateUI(it)
                        }
                    }
                }
                launch {
                    viewModel.detection.collect { detection ->
                        detection?.let {
                            FileLogger.d("MAIN", "viewModel detection: ${it.detectedObject.displayName}")
                            if (!isTestMode) updateDetectionUI(it)
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
            binding.gpsStatus.text = "GPS: ${packet.gpsSats} спутн."
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
        }
    }

    private fun updateDetectionUI(detection: DetectionResult) {
        runOnUiThread {
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

            val packet = viewModel.packet.value
            if (packet != null && packet.isGpsValid) {
                updateMap(packet, detection)
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
        val now = System.currentTimeMillis()
        if (now - lastMapUpdate < MAP_UPDATE_INTERVAL_MS) {
            return
        }
        lastMapUpdate = now

        if (!packet.isGpsValid) {
            FileLogger.d("MAP", "GPS invalid, skip map update")
            return
        }

        val detectorPoint = GeoPoint(packet.latitude, packet.longitude)

        runOnUiThread {
            val displayPoint = if (gpsJumpCount > 3 && fixedDetectorPoint != null) {
                fixedDetectorPoint
            } else {
                fixedDetectorPoint = detectorPoint
                detectorPoint
            }

            mapView.overlays.clear()

            val detectorMarker = Marker(mapView).apply {
                position = displayPoint
                title = "📡 ДЕТЕКТОР"
                snippet = "ID: ${packet.delayMs}\nRSSI: ${packet.rssi}"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = resources.getDrawable(android.R.drawable.ic_menu_mylocation, null)
            }
            mapView.overlays.add(detectorMarker)

            val maxRange = detection.detectedObject.maxDetectionRangeMeters
            if (maxRange > 0) {
                val rangeCircle = Polygon().apply {
                    points = Polygon.pointsAsCircle(displayPoint!!, maxRange.toDouble())
                    fillColor = 0x154CAF50
                    strokeColor = 0xFF4CAF50.toInt()
                    strokeWidth = 2f
                }
                mapView.overlays.add(rangeCircle)
            }

            if (detection.isObjectNearby && detection.estimatedRadiusMeters != null) {
                val dist = detection.estimatedRadiusMeters!!
                val angle = (packet.delayMs % 360).toDouble()
                val objLat = packet.latitude + kotlin.math.cos(Math.toRadians(angle)) * dist * 0.00001
                val objLon = packet.longitude + kotlin.math.sin(Math.toRadians(angle)) * dist * 0.00001
                val objPoint = GeoPoint(objLat, objLon)

                val objMarker = Marker(mapView).apply {
                    position = objPoint
                    title = "${detection.detectedObject.emoji} ${detection.detectedObject.displayName}"
                    snippet = "Расстояние: ${dist.toInt()}м\nУверенность: ${(detection.confidence * 100).toInt()}%"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                mapView.overlays.add(objMarker)

                val line = Polyline().apply {
                    addPoint(displayPoint!!)
                    addPoint(objPoint)
                    color = android.graphics.Color.parseColor("#FF0000")
                    width = 3f
                }
                mapView.overlays.add(line)

                val objCircle = Polygon().apply {
                    points = Polygon.pointsAsCircle(displayPoint!!, dist.toDouble())
                    fillColor = 0x11FF0000
                    strokeColor = 0xFFFF0000.toInt()
                    strokeWidth = 2f
                }
                mapView.overlays.add(objCircle)
            }

            mapView.invalidate()
            mapView.controller.animateTo(displayPoint!!)
            FileLogger.d("MAP", "Updated: detector at ${packet.latitude},${packet.longitude}, obj=${detection.detectedObject.displayName}")
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
}
