
# ЧЕКПОИНТ LoRaTracker — 23.06.2026

## ПРОЕКТ
Android-приложение + ESP32 детектор для обнаружения дронов/техники/людей по звуку через INMP441 + LoRa.

## ТЕКУЩАЯ ПРОБЛЕМА
ESP32 шлет застрявший peak=109.37 Гц (self-noise INMP441). Причина: SAMPLE_RATE=16000, CHUNK=512 → FFT разрешение 31.25 Гц/бин, интерполяция между бинами 3 и 4 дает 109.37 всегда.

## РАЗДЕЛЕНИЕ РАБОТЫ
- **Второй разработчик** (ESP32 прошивка): получил аудит от Claude, вносит 5 правок (SAMPLE_RATE→48000, CHUNK→2048, dma_buf_len→512, I2S_COMM_FORMAT_STAND_I2S, delay(100)).
- **Opencode** (Android): получает задания от пользователя через этот диалог, вносит правки в код.

## ЧТО OPENCODE УЖЕ СДЕЛАЛ
1. Packet.kt — добавлены поля `soundType` и `emoji` (по умолчанию пустые строки)
2. UsbSerialService.kt — проверка `soundPeakFreq > 0f`, при false: `soundType="Микрофон неактивен"`, `emoji="⚠️"` через `copy()`

## ЧТО OPENCODE ЕЩЕ НЕ СДЕЛАЛ (5 заданий в очереди)
1. UsbSerialService.kt — `detectorId = fields[0].toInt()` (delayMs как ID) → исправить на `detectorId = 1`
2. Inmp441SoundDetector.kt — `maxPeakFreq = 12000f` → поднять до `20000f` (дрон 8-20 кГц)
3. ObjectClassifier.kt — `DRONE = 400f..1200f` → исправить на `8000f..20000f`
4. MainActivity.kt — добавить показ `packet.soundType` / `packet.emoji` (сейчас UI берет из DetectionResult, маркер никогда не виден)
5. MainActivity.kt — удалить дублирующий stuck peak detection (мешает #4)

## ФАЙЛЫ ДЛЯ СКАЧИВАНИЯ
- ESP32 аудит: [ESP32_AUDIT.md](sandbox:///mnt/agents/output/ESP32_AUDIT.md)
- Свод правок ESP32 для коллеги: [ESP32_FIXES_SUMMARY.md](sandbox:///mnt/agents/output/ESP32_FIXES_SUMMARY.md)
- Пачка заданий opencode: [OPENCODE_FINAL_BATCH.md](sandbox:///mnt/agents/output/OPENCODE_FINAL_BATCH.md)

## РЕПОЗИТОРИЙ
https://github.com/serjoleinic2-cloud/LoRaTracker

## ПРАВИЛО РАБОТЫ С OPENCODE
- Только чистые задания, без объяснений
- Каждое задание оценивать 0-100, должно быть 95+
- Android Studio, PowerShell, тест на Samsung S21+
