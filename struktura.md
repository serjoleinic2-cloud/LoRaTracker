# LoRaTracker — структура проекта

```
D:\LoRaTracker\
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties
├── struktura.md
│
├── gradle\
│   ├── wrapper\
│   │   ├── gradle-wrapper.jar
│   │   └── gradle-wrapper.properties
│   └── gradle-daemon-jvm.properties
│
└── app\
    ├── build.gradle.kts
    │
    └── src\main\
        ├── AndroidManifest.xml
        │
        ├── res\
        │   ├── drawable\
        │   │   ├── ic_launcher_background.xml
        │   │   └── ic_launcher_foreground.xml
        │   ├── layout\
        │   │   └── activity_main.xml
        │   ├── mipmap-anydpi\
        │   │   ├── ic_launcher.xml
        │   │   └── ic_launcher_round.xml
        │   ├── values\
        │   │   ├── colors.xml
        │   │   ├── strings.xml
        │   │   └── themes.xml
        │   ├── values-night\
        │   │   └── themes.xml
        │   └── xml\
        │       ├── backup_rules.xml
        │       ├── data_extraction_rules.xml
        │       └── usb_device_filter.xml
        │
        └── java\com\sergey\loratracker\
            ├── MainActivity.kt
            │
            ├── audio\
            │   └── AudioAnalyzer.kt
            │
            ├── data\
            │   ├── ObjectClassifier.kt
            │   ├── Packet.kt
            │   ├── PacketParser.kt
            │   ├── SoundDetector.kt
            │   └── TestDataGenerator.kt
            │
            ├── service\
            │   ├── PhoneDetectorService.kt
            │   └── UsbSerialService.kt
            │
            └── viewmodel\
                └── TrackerViewModel.kt
```
