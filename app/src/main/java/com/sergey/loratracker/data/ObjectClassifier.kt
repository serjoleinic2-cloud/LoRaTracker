package com.sergey.loratracker.data

enum class DetectedObject(
    val displayName: String,
    val emoji: String,
    val description: String
) {
    DRONE("Дрон", "\uD83D\uDE81", "Пропеллеры"),
    UNKNOWN("Фон", "\uD83C\uDF3F", "Нет сигнала");

    companion object {
        @Volatile
        var demoMode = false

        fun classify(packet: TelemetryPacket, rmsDb: Float = 0f): DetectedObject {
            val peak = packet.soundPeakFreq

            if (peak < 80f) return UNKNOWN

            // DEMO MODE: любой сигнал > 400 Гц = ДРОН (для презентации)
            if (demoMode && peak > 400f) {
                return DRONE
            }

            // Обычный режим: дрон по высоким гармоникам
            if (peak > 1000f) return DRONE
            if (peak > 400f) return DRONE

            return UNKNOWN
        }
    }
}
