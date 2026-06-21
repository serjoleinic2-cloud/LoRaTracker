package com.sergey.loratracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sergey.loratracker.data.DetectionResult
import com.sergey.loratracker.data.Inmp441SoundDetector
import com.sergey.loratracker.data.TelemetryPacket
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
                _packet.value = p
                _detection.value = soundDetector.detect(p)
            }
        }
        viewModelScope.launch {
            UsbSerialService.connectionState.collect { _connected.value = it }
        }
    }
}
