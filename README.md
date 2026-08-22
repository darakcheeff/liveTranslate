# Gemini Live Voice Translator (Phone & Watch)

Синхронный голосовой переводчик в реальном времени для Android-смартфонов (Android 10+) и смарт-часов **Huawei Watch 4 Pro** (HarmonyOS / Android Runtime) с прямой интеграцией с **Gemini Multimodal Live API (Bidirectional WebSockets)**.

## Архитектура проекта

Мультимодульный проект на чистом **Kotlin / Android Native SDK**:
* **`:core`**: Общие модели данных (`Language`, `VoiceName`, `GeminiConfig`), состояния подключения, шифрованное хранилище `EncryptedPreferencesManager`.
* **`:gemini-client`**: Прямой клиент OkHttp WebSocket для протокола `BidiGenerateContent`, REST API обнаружение моделей `GET /v1beta/models`, отказоустойчивая ротация пула API-ключей и каскад моделей.
* **`:audio-engine`**: Низкоуровневый захват `AudioRecord` (16kHz Mono) с `AcousticEchoCanceler` (AEC) и `NoiseSuppressor`, воспроизведение `AudioTrack` (24kHz Mono) с Jitter Ring Buffer (150-250 мс), `AudioFocusManager` и `LiveTranslationService` (Foreground Service).
* **`:phone-app`**: Приложение для смартфона с визуализатором звуковой волны, субтитрами в реальном времени, историей переводов с экспортом в файл и сканером QR-кода часов (CameraX / ZXing).
* **`:watch-app`**: Автономное приложение для Huawei Watch 4 Pro (круглый дисплей 466×466 px), тактильный отклик (Haptic Feedback), авто-маршрутизация звука (Bluetooth-гарнитура vs динамик) и спаривание без ручного ввода через генерацию QR-кода и Bluetooth RFCOMM.

## Сборка

```bash
# Сборка приложения для телефона
./gradlew :phone-app:assembleRelease

# Сборка приложения для смарт-часов Huawei Watch 4 Pro
./gradlew :watch-app:assembleRelease
```
