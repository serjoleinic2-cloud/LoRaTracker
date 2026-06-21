package com.sergey.loratracker

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
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
import com.sergey.loratracker.data.Inmp441SoundDetector
import com.sergey.loratracker.data.PacketParser
import com.sergey.loratracker.data.SoundLevel
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
            binding.statusText.text = "НЕТ ИНТЕРНЕТА"
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
            binding.statusText.text = if (latest != null) {
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
                binding.statusText.text = "Нет логов для отправки"
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
                FileLogger.d("MAIN", "Lifecycle STARTED")

                launch {
                    UsbSerialService.packetFlow.collect { packet ->
                        FileLogger.d("MAIN", "packetFlow: delay=${packet.delayMs}")
                        if (!isTestMode) updateUI(packet)
                    }
                }

                launch {
                    UsbSerialService.connectionState.collect { connected ->
                        FileLogger.d("MAIN", "connectionState: $connected")
                        if (!isTestMode) updateConnectionStatus(connected)
                    }
                }
            }
        }
    }

    private fun updateUI(packet: TelemetryPacket) {
        val detection = Inmp441SoundDetector().detect(packet, packet.rssi.toFloat() + 100f)
        FileLogger.d("DETECT", "peak=${packet.soundPeakFreq}Hz ratio=${packet.soundEnergyRatio} obj=${detection.detectedObject.displayName} dist=${detection.estimatedRadiusMeters}m conf=${detection.confidence}")

        runOnUiThread {
            binding.gpsStatus.text = "GPS: ${packet.gpsSats} спутн."
            binding.tempText.text = "Темп: ${packet.temperature}°C"
            binding.rssiText.text = "RSSI: ${packet.rssi} dBm"
            binding.peakFreqText.text = "Пик: ${packet.soundPeakFreq}Hz"
            binding.centroidText.text = "Центр: ${packet.soundCenterFreq}Hz"
            binding.maxRangeText.text = "Дальность: ${detection.detectedObject.maxDetectionRangeMeters}м"

            if (detection.isObjectNearby && detection.detectedObject != DetectedObject.UNKNOWN) {
                binding.objectEmoji.text = detection.detectedObject.emoji
                binding.objectName.text = detection.detectedObject.displayName.uppercase()
                binding.objectDescription.text = detection.reason
                binding.distanceText.text = "Расстояние: ${detection.estimatedRadiusMeters?.toInt() ?: "?"}м"
                binding.distanceText.visibility = View.VISIBLE

                binding.alertCard.visibility = View.VISIBLE
                binding.alertText.text = "⚠ ОБНАРУЖЕНО: ${detection.detectedObject.displayName.uppercase()}"

                updateMap(packet, detection)
            } else {
                binding.objectEmoji.text = "\uD83D\uDD0B"
                binding.objectName.text = "ПАТРУЛИРОВАНИЕ"
                binding.objectDescription.text = "Сканирование..."
                binding.distanceText.visibility = View.GONE
                binding.alertCard.visibility = View.GONE
            }
        }
    }

    private fun updateConnectionStatus(connected: Boolean) {
        binding.statusText.text = if (connected) "USB: ПОДКЛЮЧЕНО" else "USB: ОТКЛЮЧЕНО"
    }

    private fun updateMap(packet: TelemetryPacket, detection: DetectionResult) {
        val detectorPoint = GeoPoint(packet.latitude, packet.longitude)
        mapView.overlays.clear()

        val detectorMarker = Marker(mapView).apply {
            position = detectorPoint
            title = "📡 ВЫ"
            snippet = "ID: ${packet.delayMs}"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(detectorMarker)

        val maxRange = detection.detectedObject.maxDetectionRangeMeters
        val rangeCircle = Polygon().apply {
            points = Polygon.pointsAsCircle(detectorPoint, maxRange.toDouble())
            fillColor = 0x154CAF50
            strokeColor = 0xFF4CAF50.toInt()
            strokeWidth = 2f
        }
        mapView.overlays.add(rangeCircle)

        if (detection.isObjectNearby && detection.estimatedRadiusMeters != null) {
            val objRange = detection.estimatedRadiusMeters!!
            val objCircle = Polygon().apply {
                points = Polygon.pointsAsCircle(detectorPoint, objRange.toDouble())
                fillColor = 0x22FF0000
                strokeColor = 0xFFFF0000.toInt()
                strokeWidth = 3f
            }
            mapView.overlays.add(objCircle)
        }

        mapView.invalidate()
        mapView.controller.animateTo(detectorPoint)
    }

    private fun checkUsbDevices() {
        val deviceList = usbManager.deviceList
        FileLogger.d("MAIN", "checkUsbDevices: ${deviceList.size} devices")
        Log.d("USB", "Found ${deviceList.size} USB devices")

        if (deviceList.isEmpty()) {
            binding.statusText.text = "USB: НЕТ УСТРОЙСТВ"
            return
        }

        for ((name, device) in deviceList) {
            Log.d("USB", "Device: $name, VID=${device.vendorId}, PID=${device.productId}")

            if (UsbSerialProber.getDefaultProber().probeDevice(device) != null) {
                Log.d("USB", "Supported serial device found!")

                if (usbManager.hasPermission(device)) {
                    binding.statusText.text = "USB: ЕСТЬ РАЗРЕШЕНИЕ"
                    startUsbService()
                } else {
                    binding.statusText.text = "USB: ЗАПРОШЕНО"
                    usbManager.requestPermission(device, pendingUsbIntent)
                }
                return
            }
        }

        binding.statusText.text = "USB: НЕТ ПОДХОДЯЩИХ"
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
                            binding.statusText.text = "USB: РАЗРЕШЕНО"
                            startUsbService()
                        }
                    } else {
                        Log.d("USB", "Permission denied")
                        binding.statusText.text = "USB: ОТКАЗАНО"
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
