package com.sergey.loratracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbEndpoint
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sergey.loratracker.MainActivity
import com.sergey.loratracker.R
import com.sergey.loratracker.data.PacketParser
import com.sergey.loratracker.data.TelemetryPacket
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class UsbSerialService : Service(), SerialInputOutputManager.Listener {
    
    private val binder = LocalBinder()
    private var serialIoManager: SerialInputOutputManager? = null
    private var usbPort: UsbSerialPort? = null
    private val lineBuffer = StringBuilder()
    
    companion object {
        private const val TAG = "UsbSerialService"
        private const val CHANNEL_ID = "lora_tracker_channel"
        private const val NOTIFICATION_ID = 1
        
        private val _packetFlow = MutableSharedFlow<TelemetryPacket>(extraBufferCapacity = 10)
        val packetFlow: SharedFlow<TelemetryPacket> = _packetFlow.asSharedFlow()
        
        private val _connectionState = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
        val connectionState: SharedFlow<Boolean> = _connectionState.asSharedFlow()

        var lastPacket: TelemetryPacket? = null
            private set

        private val listeners = mutableListOf<(TelemetryPacket) -> Unit>()

        fun addListener(listener: (TelemetryPacket) -> Unit) {
            listeners.add(listener)
        }

        fun removeListener(listener: (TelemetryPacket) -> Unit) {
            listeners.remove(listener)
        }
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): UsbSerialService = this@UsbSerialService
    }
    
    override fun onBind(intent: Intent): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        connectToDevice()
        return START_STICKY
    }
    
    private fun connectToDevice() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList

        Log.d(TAG, "All USB devices: ${deviceList.size}")
        for ((name, device) in deviceList) {
            Log.d(TAG, "Device: $name, VID=${device.vendorId}, PID=${device.productId}")
        }

        // Ищем ESP32-C3 (VID 0x303A = 12346, PID 0x1001 = 4097)
        val espDevice = deviceList.values.find {
            it.vendorId == 12346 && it.productId == 4097
        }

        if (espDevice != null) {
            Log.d(TAG, "Found ESP32-C3! Connecting...")
            connectToEsp32C3(espDevice)
            return
        }

        // Fallback: стандартные драйверы
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        Log.d(TAG, "Found ${availableDrivers.size} serial drivers")

        if (availableDrivers.isEmpty()) {
            Log.e(TAG, "No USB serial devices found")
            _connectionState.tryEmit(false)
            return
        }

        val driver = availableDrivers.first()
        Log.d(TAG, "Using driver: ${driver.device.deviceName}")
        val connection = usbManager.openDevice(driver.device)
        
        if (connection == null) {
            Log.e(TAG, "Cannot open USB device - permission denied?")
            return
        }
        
        val port = driver.ports.firstOrNull()
        if (port == null) {
            Log.e(TAG, "No serial ports available")
            return
        }
        
        usbPort = port
        
        try {
            port.open(connection)
            port.setParameters(
                115200,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            port.setDTR(true)
            port.setRTS(true)

            serialIoManager = SerialInputOutputManager(port, this).apply {
                start()
            }
            
            _connectionState.tryEmit(true)
            Log.i(TAG, "Connected to ${driver.device.deviceName}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error opening port", e)
            _connectionState.tryEmit(false)
        }
    }

    private fun connectToEsp32C3(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // Пробуем CDC-ACM драйвер из mik3y library
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)

        if (driver != null) {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                Log.e(TAG, "Cannot open ESP32-C3")
                _connectionState.tryEmit(false)
                return
            }

            val port = driver.ports.firstOrNull()
            if (port == null) {
                Log.e(TAG, "No serial ports on ESP32-C3")
                connection.close()
                _connectionState.tryEmit(false)
                return
            }

            usbPort = port

            try {
                port.open(connection)
                port.setParameters(
                    115200,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
                port.setDTR(true)
                port.setRTS(true)

                serialIoManager = SerialInputOutputManager(port, this).apply {
                    start()
                }

                _connectionState.tryEmit(true)
                Log.i(TAG, "ESP32-C3 connected via CDC-ACM")

            } catch (e: Exception) {
                Log.e(TAG, "Error opening ESP32-C3 port", e)
                _connectionState.tryEmit(false)
            }
        } else {
            connectToEsp32C3Raw(device)
        }
    }

    private fun connectToEsp32C3Raw(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(device)

        if (connection == null) {
            Log.e(TAG, "Cannot open ESP32-C3 for raw USB")
            _connectionState.tryEmit(false)
            return
        }

        val usbInterface = device.getInterface(0)
        connection.claimInterface(usbInterface, true)

        val inEndpoint = usbInterface.getEndpoint(1)

        _connectionState.tryEmit(true)
        Log.i(TAG, "ESP32-C3 connected, starting read loop")

        Thread {
            val buffer = ByteArray(4096)
            var totalBytes = 0
            var loopCount = 0

            while (true) {
                loopCount++
                try {
                    val bytesRead = connection.bulkTransfer(inEndpoint, buffer, buffer.size, 2000)

                    if (loopCount % 10 == 0) {
                        Log.d(TAG, "Read loop #$loopCount, lastRead=$bytesRead, total=$totalBytes")
                    }

                    if (bytesRead > 0) {
                        totalBytes += bytesRead
                        Log.d(TAG, "READ $bytesRead bytes, total=$totalBytes")
                        val data = buffer.copyOf(bytesRead)
                        onNewData(data)
                    } else if (bytesRead == 0) {
                        // Таймаут, нормально
                    } else {
                        Log.e(TAG, "READ ERROR: $bytesRead")
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "EXCEPTION: ${e.message}")
                    break
                }
            }

            Log.i(TAG, "Read loop ended, total=$totalBytes bytes")
            connection.releaseInterface(usbInterface)
            connection.close()
            _connectionState.tryEmit(false)
        }.start()
    }
    
    override fun onNewData(data: ByteArray?) {
        Log.d(TAG, "onNewData called, data=${data?.size ?: "null"} bytes")
        if (data == null || data.isEmpty()) {
            Log.w(TAG, "onNewData: empty or null data")
            return
        }

        val raw = String(data, Charsets.UTF_8)
        Log.d(TAG, "RAW: [$raw]")

        lineBuffer.append(raw)
        Log.d(TAG, "Buffer size: ${lineBuffer.length}")

        var newlineIndex: Int
        while (lineBuffer.indexOf("\n").also { newlineIndex = it } != -1) {
            val line = lineBuffer.substring(0, newlineIndex).trim().removeSuffix("\r")
            lineBuffer.delete(0, newlineIndex + 1)

            Log.d(TAG, "LINE: [$line]")

            if (line.isNotEmpty()) {
                val parseResult = PacketParser.parse(line)
                Log.d(TAG, "PARSE RESULT for [$line]: ${if (parseResult != null) "OK" else "FAILED"}")
                parseResult?.let { (packet, _) ->
                    Log.d(TAG, "PARSED OK: delay=${packet.delayMs}")
                    val emitted = _packetFlow.tryEmit(packet)
                    Log.d(TAG, "EMITTED: $emitted")
                    lastPacket = packet
                    listeners.forEach { it(packet) }
                }
            }
        }
    }
    
    override fun onRunError(e: Exception?) {
        Log.e(TAG, "Serial error", e)
        _connectionState.tryEmit(false)
    }
    
    override fun onDestroy() {
        serialIoManager?.stop()
        try {
            usbPort?.close()
        } catch (_: Exception) {}
        super.onDestroy()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LoRa Tracker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновое чтение данных с LoRa32"
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
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LoRa Tracker активен")
            .setContentText("Ожидание данных с устройства...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}