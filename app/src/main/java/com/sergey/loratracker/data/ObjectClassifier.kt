package com.sergey.loratracker.data

enum class DetectedObject(
    val displayName: String,
    val emoji: String,
    val peakFreqRange: ClosedFloatingPointRange<Float>,
    val centroidMinRatio: Float,
    val minDb: Float,
    val description: String
) {
    HUMAN(
        "Человек",
        "\uD83D\uDEB6",
        85f..350f,
        1.2f,
        0f,
        "Голос, шаги"
    ),
    MOTORCYCLE(
        "Мотоцикл / скутер",
        "\uD83C\uDFCD",
        800f..5000f,
        1.5f,
        0f,
        "Высокие обороты"
    ),
    TRUCK(
        "Грузовик / автобус",
        "\uD83D\uDE9A",
        200f..1500f,
        1.3f,
        0f,
        "Дизель"
    ),
    TANK(
        "Танк / БТР",
        "\uD83D\uDEA2",
        100f..2000f,
        2.5f,
        0f,
        "Гусеницы"
    ),
    DRONE(
        "Дрон",
        "\uD83D\uDE81",
        2000f..15000f,
        1.5f,
        0f,
        "Пропеллеры"
    ),
    MIXED_GROUP(
        "Смешанная группа",
        "\uD83D\uDC65",
        500f..2000f,
        2.0f,
        0f,
        "Группа объектов"
    ),
    UNKNOWN(
        "Фон / неопределено",
        "\uD83C\uDF3F",
        0f..0f,
        0f,
        0f,
        "Нет сигнала"
    );

    companion object {
        fun classify(packet: TelemetryPacket, rmsDb: Float = 0f): DetectedObject {
            if (packet.soundPeakFreq < 50) return UNKNOWN

            if (packet.soundPeakFreq < 150 && packet.soundCenterFreq < 400) {
                return UNKNOWN
            }

            val candidates = values().filter {
                it != UNKNOWN && packet.soundPeakFreq in it.peakFreqRange
            }

            return candidates.maxByOrNull { it.centroidMinRatio } ?: UNKNOWN
        }
    }
}
