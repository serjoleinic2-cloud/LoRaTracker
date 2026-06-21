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
    HUMAN("Человек", "\uD83D\uDEB6", 85f..350f, 1.2f, 35f, 50f, "Голос, шаги"),
    GROUP("Группа", "\uD83D\uDC65", 150f..500f, 2.0f, 50f, 80f, "Несколько человек"),
    CAR("Легковая", "\uD83D\uDE97", 200f..1500f, 2.0f, 55f, 150f, "Двигатель"),
    TRUCK("Грузовик", "\uD83D\uDE9A", 80f..800f, 1.8f, 65f, 300f, "Дизель"),
    MOTORCYCLE("Мотоцикл", "\uD83C\uDFCD", 1000f..5000f, 2.5f, 70f, 200f, "Высокие обороты"),
    DRONE("Дрон", "\uD83D\uDE81", 8000f..20000f, 3.0f, 50f, 500f, "Пропеллеры"),
    HELICOPTER("Вертолёт", "\uD83D\uDE81", 500f..3000f, 2.0f, 75f, 2000f, "Несущий винт"),
    AIRPLANE("Самолёт", "\u2708", 300f..2000f, 1.5f, 80f, 5000f, "Реактивный"),
    TANK("Танк", "\uD83D\uDEA2", 100f..2000f, 2.5f, 75f, 1000f, "Гусеницы"),
    CONSTRUCTION("Стройка", "\uD83C\uDFD7", 300f..2000f, 2.2f, 75f, 200f, "Техника"),
    BICYCLE("Велосипед", "\uD83D\uDEB2", 50f..200f, 1.3f, 35f, 30f, "Цепь"),
    UNKNOWN("Фон", "\uD83C\uDF3F", 0f..0f, 0f, 0f, 0f, "Нет сигнала");

    companion object {
        fun classify(packet: TelemetryPacket, rmsDb: Float = 60f): DetectionResult {
            val peak = packet.soundPeakFreq
            val centroid = packet.soundCenterFreq
            val ratio = packet.soundEnergyRatio

            if (peak < 50f || rmsDb < 30f) {
                return DetectionResult(false, 0f, null, SoundLevel.SILENT, UNKNOWN, rmsDb, "Тихо")
            }

            if (peak < 150f && ratio < 1.5f && rmsDb < 45f) {
                return DetectionResult(false, 0f, null, SoundLevel.LOW, UNKNOWN, rmsDb, "Городской фон")
            }

            val candidates = values().filter { it != UNKNOWN && peak in it.peakFreqRange }
            val valid = candidates.filter { ratio >= it.centroidMinRatio && rmsDb >= it.minDb }

            val best = valid.maxByOrNull {
                val center = (it.peakFreqRange.start + it.peakFreqRange.endInclusive) / 2
                val distScore = 1f - kotlin.math.abs(peak - center) / (it.peakFreqRange.endInclusive - it.peakFreqRange.start + 1f)
                ratio * distScore
            }

            return if (best != null) {
                val confidence = (ratio / best.centroidMinRatio).coerceAtMost(1.0f)
                val excessDb = rmsDb - best.minDb
                val distance = when {
                    excessDb > 20f -> best.maxDetectionRangeMeters * 0.1f
                    excessDb > 10f -> best.maxDetectionRangeMeters * 0.3f
                    excessDb > 5f -> best.maxDetectionRangeMeters * 0.6f
                    excessDb > 0f -> best.maxDetectionRangeMeters * 0.9f
                    else -> best.maxDetectionRangeMeters
                }
                DetectionResult(true, confidence, distance, SoundLevel.HIGH, best, rmsDb, "ЦЕЛЬ: ${best.displayName}")
            } else {
                DetectionResult(false, 0f, null, SoundLevel.MEDIUM, UNKNOWN, rmsDb, "Нет соответствия")
            }
        }
    }
}
