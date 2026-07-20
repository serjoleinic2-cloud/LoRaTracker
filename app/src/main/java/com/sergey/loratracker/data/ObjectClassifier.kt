package com.sergey.loratracker.data

enum class DetectedObject(
    val displayName: String,
    val emoji: String,
    val peakFreqRange: ClosedFloatingPointRange<Float>,
    val centroidMinRatio: Float,
    val minDb: Float,
    val maxDetectionRangeMeters: Float,
    val description: String
) {
    DRONE(
        "Дрон",
        "\uD83D\uDE81",
        400f..15000f,
        1.0f,
        0f,
        500f,
        "Пропеллеры"
    ),
    UNKNOWN(
        "Фон / неопределено",
        "\uD83C\uDF3F",
        0f..0f,
        0f,
        0f,
        0f,
        "Нет сигнала"
    );

    companion object {
        @Volatile
        var demoMode = false

        fun classify(packet: TelemetryPacket, rmsDb: Float = 0f): DetectedObject {
            val peak = packet.soundPeakFreq

            if (peak < 80f) return UNKNOWN

            if (demoMode && peak > 400f) {
                return DRONE
            }

            if (peak > 1000f) return DRONE
            if (peak > 400f) return DRONE

            return UNKNOWN
        }
    }
}