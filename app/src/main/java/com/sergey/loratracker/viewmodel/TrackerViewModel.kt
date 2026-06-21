package com.sergey.loratracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sergey.loratracker.data.DetectionResult
import com.sergey.loratracker.data.Inmp441SoundDetector
import com.sergey.loratracker.data.TelemetryPacket
import com.sergey.loratracker.service.FileLogger
import com.sergey.loratracker.service.UsbSerialService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TrackerViewModel : ViewModel() {
    private val soundDetector = Inmp441SoundDetector()

    private val _packet = MutableStateFlow<TelemetryPacket?>(null)
    val packet: StateFlow<TelemetryPacket?> = _packet.asStateFlow()

    private val _detection = MutableStateFlow<DetectionResult?>(null)
    val detection: StateFlow<DetectionResult?> = _detection.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    init {
        viewModelScope.launch {
            UsbSerialService.packetFlow.collect { p ->
                FileLogger.d("VM", "packetFlow: delay=${p.delayMs}, peak=${p.soundPeakFreq}")
                _packet.value = p
                val det = soundDetector.detect(p, p.rssi.toFloat() + 100f)
                FileLogger.d("VM", "detected: ${det.detectedObject.displayName}, dist=${det.estimatedRadiusMeters}, conf=${det.confidence}")
                _detection.value = det
            }
        }
        viewModelScope.launch {
            UsbSerialService.connectionState.collect {
                FileLogger.d("VM", "connection: $it")
                _connected.value = it
            }
        }
    }
}
