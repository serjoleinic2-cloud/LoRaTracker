package com.sergey.loratracker.data

import com.sergey.loratracker.service.FileLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object DetectorManager {
    private val detectors = mutableMapOf<Int, DetectorState>()

    data class DetectorState(
        val id: Int,
        val packetFlow: MutableSharedFlow<TelemetryPacket> = MutableSharedFlow(extraBufferCapacity = 10),
        var lastPacket: TelemetryPacket? = null,
        var lastDetection: DetectionResult? = null
    )

    fun getOrCreate(id: Int): DetectorState {
        return detectors.getOrPut(id) {
            FileLogger.d("DETECTOR", "Created detector $id")
            DetectorState(id)
        }
    }

    fun emit(id: Int, packet: TelemetryPacket) {
        val state = getOrCreate(id)
        state.lastPacket = packet
        state.packetFlow.tryEmit(packet)
        FileLogger.d("DETECTOR", "Emit to $id: peak=${packet.soundPeakFreq}")
    }

    fun getAllDetectors(): Map<Int, DetectorState> = detectors.toMap()

    fun getPacketFlow(id: Int): SharedFlow<TelemetryPacket> = getOrCreate(id).packetFlow.asSharedFlow()
}
