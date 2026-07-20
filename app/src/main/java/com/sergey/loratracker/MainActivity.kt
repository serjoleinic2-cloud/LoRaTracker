package com.sergey.loratracker

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.sergey.loratracker.data.DetectedObject
import com.sergey.loratracker.data.DetectionResult
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
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapView: MapView
    private lateinit var usbManager: UsbManager
    private val viewModel: TrackerViewModel by viewModels()
    private var lastGpsPoint: GeoPoint? = null
    private var gpsJumpCount = 0
    private val detectorMarkers = mutableMapOf<Int, Marker>()
    private var pendingUsbIntent: PendingIntent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        FileLogger.init(this)
        FileLogger.d("MAIN", "MainActivity onCreate")

        val osmPath = File(filesDir, "osmdroid")
        osmPath.mkdirs()
        Configuration.getInstance().load(
            this,
            getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().osmdroidBasePath = osmPath
        Configuration.getInstance().osmdroidTileCache = File(osmPath, "tiles")
        Configuration.getInstance().userAgentValue = "LoRaTracker/1.0"

        mapView = binding.mapView
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(17.0)
        mapView.controller.setCenter(GeoPoint(55.7539, 37.6208))

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        pendingUsbIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent("com.sergey.loratracker.USB_PERMISSION"),
            PendingIntent.FLAG_IMMUTABLE
        )

        binding.usbConnectButton.setOnClickListener { checkUsbDevices() }

        binding.testModeButton.visibility = View.VISIBLE
        binding.testModeButton.text = "ДЕМО: ВЫКЛ"
        binding.testModeButton.setOnClickListener {
            DetectedObject.Companion.demoMode = !DetectedObject.Companion.demoMode
            val msg = if (DetectedObject.Companion.demoMode) "ДЕМО: ВКЛ" else "ДЕМО: ВЫКЛ"
            binding.testModeButton.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        startUsbService()
        observeData()
    }

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.packet.collect { packet ->
                        packet?.let { updateUI(it) }
                    }
                }
                launch {
                    viewModel.detection.collect { detection ->
                        detection?.let { updateDetectionUI(it) }
                    }
                }
                launch {
                    viewModel.connected.collect { connected ->
                        updateConnectionStatus(connected)
                    }
                }
            }
        }
    }

    private fun updateUI(packet: TelemetryPacket) {
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
            binding.peakFreqText.text = "Пик: ${packet.soundPeakFreq.toInt()}Hz"
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

            if (DetectedObject.Companion.demoMode && detection.detectedObject == DetectedObject.DRONE) {
                binding.objectName.setTextColor(android.graphics.Color.parseColor("#FF9800"))
            } else {
                binding.objectName.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
            }

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
        if (!packet.isGpsValid) return
        val detectorPoint = GeoPoint(packet.latitude, packet.longitude)
        val detectorId = packet.detectorId

        runOnUiThread {
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
                    snippet = "Детектор $detectorId | ${dist.toInt()}м"
                }
                mapView.overlays.add(objMarker)
            }
            mapView.invalidate()
        }
    }

    private fun checkUsbDevices() {
        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            binding.usbStatusText.text = "USB: НЕТ УСТРОЙСТВ"
            return
        }
        for ((_, device) in deviceList) {
            if (UsbSerialProber.getDefaultProber().probeDevice(device) != null) {
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
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            binding.usbStatusText.text = "USB: РАЗРЕШЕНО"
                            startUsbService()
                        }
                    } else {
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