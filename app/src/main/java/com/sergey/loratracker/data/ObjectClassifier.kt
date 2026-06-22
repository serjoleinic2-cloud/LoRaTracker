package com.sergey.loratracker.data

import com.sergey.loratracker.service.FileLogger

enum class DetectedObject(
    val displayName: String,
    val emoji: String,
    val peakFreqRange: ClosedFloatingPointRange<Float>,
    val centroidMinRatio: Float,
    val minDb: Float,
    val maxDetectionRangeMeters: Float,
    val description: String
) {
    HUMAN("Человек", "\uD83D\uDEB6", 85f..350f, 1.0f, 0f, 50f, "Голос, шаги"),
    GROUP("Группа", "\uD83D\uDC65", 150f..500f, 1.5f, 0f, 80f, "Несколько человек"),
    CAR("Легковая", "\uD83D\uDE97", 200f..1500f, 0.8f, 0f, 150f, "Двигатель"),
    TRUCK("Грузовик", "\uD83D\uDE9A", 80f..800f, 0.8f, 0f, 300f, "Дизель"),
    MOTORCYCLE("Мотоцикл", "\uD83C\uDFCD", 1500f..5000f, 2.0f, 0f, 200f, "Высокие обороты"),
    SCOOTER("Мопед", "\uD83D\uDEF5", 500f..1500f, 1.5f, 0f, 100f, "Скутер"),
    DRONE("Дрон", "\uD83D\uDE81", 400f..1500f, 0.5f, 0f, 500f, "Пропеллеры (басовый тон)"),
    DRONE_SMALL("Мелкий дрон", "\uD83D\uDE81", 10000f..20000f, 1.0f, 0f, 300f, "Мелкий пропеллер"),
    HELICOPTER("Вертолёт", "\uD83D\uDE81", 500f..3000f, 1.5f, 0f, 2000f, "Несущий винт"),
    AIRPLANE("Самолёт", "\u2708", 300f..2000f, 1.2f, 0f, 5000f, "Реактивный"),
    TANK("Танк", "\uD83D\uDEA2", 100f..2000f, 2.0f, 0f, 1000f, "Гусеницы"),
    CONSTRUCTION("Стройка", "\uD83C\uDFD7", 300f..2000f, 1.8f, 0f, 200f, "Техника"),
    BICYCLE("Велосипед", "\uD83D\uDEB2", 50f..200f, 1.1f, 0f, 30f, "Цепь"),
    LOW_FREQ("Низкочастотный", "\uD83D\uDD0A", 150f..1000f, 0.5f, 0f, 100f, "Мотор/гул неопределён"),
    UNKNOWN("Фон", "\uD83C\uDF3F", 0f..0f, 0f, 0f, 0f, "Нет сигнала");

    companion object {
        fun classify(packet: TelemetryPacket): DetectionResult {
            val peak = packet.soundPeakFreq
            val ratio = packet.soundEnergyRatio
            val centroid = packet.soundCenterFreq

            if (peak < 50f || peak > 20000f) {
                return DetectionResult(false, 0f, null, SoundLevel.SILENT, UNKNOWN, 0f, "ТИШИНА")
            }

            if (peak < 150f && ratio < 1.2f) {
                return DetectionResult(false, 0f, null, SoundLevel.LOW, UNKNOWN, 0f, "ГОРОДСКОЙ ФОН")
            }

            if (peak < 200f && ratio > 5f) {
                return DetectionResult(false, 0f, null, SoundLevel.LOW, UNKNOWN, 0f, "ВЕТЕР")
            }

            val hasHarmonics = centroid > peak * 1.5f

            if (peak in 400f..1500f && hasHarmonics) {
                val confidence = ((centroid / peak) / 2f).coerceAtMost(1.0f)
                return DetectionResult(
                    isObjectNearby = true,
                    confidence = confidence,
                    estimatedRadiusMeters = 300f * (1f - confidence),
                    soundLevel = SoundLevel.HIGH,
                    detectedObject = DRONE,
                    rmsDb = 0f,
                    reason = "ДРОН | ${peak.toInt()}Hz + гармоники"
                )
            }

            val scores = values().filter { it != UNKNOWN }.map { obj ->
                val inRange = peak in obj.peakFreqRange
                val ratioOk = ratio >= obj.centroidMinRatio
                val ratioScore = if (ratioOk) ratio / obj.centroidMinRatio else 0f
                val center = (obj.peakFreqRange.start + obj.peakFreqRange.endInclusive) / 2
                val rangeWidth = obj.peakFreqRange.endInclusive - obj.peakFreqRange.start
                val peakScore = 1f - kotlin.math.abs(peak - center) / (rangeWidth + 1f)
                val totalScore = ratioScore * peakScore * (if (inRange) 1f else 0.1f)
                obj to totalScore
            }

            val scoreStr = scores.sortedByDescending { it.second }.joinToString(", ") {
                "${it.first.name}=${"%.2f".format(it.second)}"
            }
            FileLogger.d("CLASSIFY", "peak=${peak.toInt()} ratio=${"%.2f".format(ratio)} | ALL: $scoreStr")

            val (best, bestScore) = scores.maxByOrNull { it.second } ?: (UNKNOWN to 0f)

            return if (best != UNKNOWN && bestScore > 0.1f) {
                val confidence = bestScore.coerceAtMost(1.0f)
                val distance = when {
                    confidence > 0.95f -> best.maxDetectionRangeMeters * 0.05f
                    confidence > 0.85f -> best.maxDetectionRangeMeters * 0.15f
                    confidence > 0.75f -> best.maxDetectionRangeMeters * 0.35f
                    confidence > 0.6f -> best.maxDetectionRangeMeters * 0.6f
                    else -> best.maxDetectionRangeMeters * 0.9f
                }
                DetectionResult(
                    isObjectNearby = confidence >= 0.6f,
                    confidence = confidence,
                    estimatedRadiusMeters = distance.coerceAtLeast(5f),
                    soundLevel = when {
                        confidence > 0.9f -> SoundLevel.ALERT
                        confidence > 0.75f -> SoundLevel.HIGH
                        else -> SoundLevel.MEDIUM
                    },
                    detectedObject = best,
                    rmsDb = 0f,
                    reason = "ЦЕЛЬ: ${best.displayName} | ${peak.toInt()}Hz | score=${"%.2f".format(confidence)}"
                )
            } else {
                DetectionResult(false, 0f, null, SoundLevel.MEDIUM, UNKNOWN, 0f, "НЕТ СООТВЕТСТВИЯ | ${peak.toInt()}Hz")
            }
        }
    }
}
