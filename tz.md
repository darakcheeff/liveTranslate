# **Техническое задание: Система синхронного голосового перевода «Gemini Live Voice Translator» (Phone & Watch)**

## **1. Общие сведения и назначение системы**

Разработка легковесного клиентского программного обеспечения для Android-смартфонов и смарт-часов **Huawei Watch 4 Pro** (HarmonyOS / Android Runtime), обеспечивающего синхронный голосовой перевод речи в реальном времени.

### **Ключевые архитектурные принципы**

* **Прямая интеграция:** Прямая работа клиентов с **Gemini Multimodal Live API (Bidirectional WebSockets)** без промежуточного бэкенда.
* **Автономность часов:** Часы работают автономно (eSIM LTE / Wi-Fi / сопряженный интернет), выполняя независимые WebSocket-сессии.
* **Высокая отказоустойчивость:** Поддержка пула из нескольких API-ключей с автоматической ротацией и каскадным переключением на резервные модели (Model Fallback).
* **Динамическое обнаружение моделей:** Получение актуального списка доступных моделей Gemini по REST API с локальным fallback-списком.
* **Бесшовное сопряжение без ввода данных на часах:** Первичная настройка на смартфоне с последующей быстрой передачей конфигурации на часы через сканирование QR-кода и Bluetooth RFCOMM.
* **Технологический стек и версии ОС:** 
  * Чистый **Kotlin / Android SDK** (без тяжелых сторонних фреймворков).
  * **Phone App:** `minSdkVersion: 29` (**Android 10**), `targetSdkVersion: 34` / `35`.
  * **Watch App (Huawei Watch 4 Pro):** `minSdkVersion: 29` (Android 10 Runtime / HarmonyOS 4.x).
  * Размер каждого APK — до **5–8 МБ**, оптимизированное энергопотребление и сверхнизкая задержка звука (Ultra-low latency).

---

## **2. Модель отказоустойчивости и управление моделями (Fault Tolerance Architecture)**

### **2.1. Динамическое получение моделей и пула ключей**
* **REST API Discovery:** Приложение поддерживает запрос `GET https://generativelanguage.googleapis.com/v1beta/models?key={ACTIVE_KEY}` для автоматического обновления списка поддерживаемых моделей реального времени (фильтрация по поддержке метода `bidiGenerateContent` / `live`).
* **Локальный список по умолчанию (Fallback List):**
  1. `models/gemini-2.0-flash-exp` (или актуальная Live-модель по умолчанию)
  2. `models/gemini-2.0-flash-realtime-exp`
  3. `models/gemini-1.5-flash` (резервный fallback)

### **2.2. Уровень 1: Ротация пула API-ключей (Key Pool Rotation)**
* В настройках задается список API-ключей Google AI Studio (Key_1, Key_2, ..., Key_N).
* Активный ключ отслеживается по индексу `currentKeyIndex`.
* При возникновении ошибок:
  * `429 Too Many Requests` (превышение RPM / TPM / RPD)
  * `403 Forbidden` / `401 Unauthorized` (невалидный или заблокированный ключ)
  * Ошибки закрытия WebSocket (`1008 Policy Violation`, HTTP != `101 Switching Protocols`)
* **Алгоритм:** Сессия мгновенно пересоздается со следующим ключом: `currentKeyIndex = (currentKeyIndex + 1) % keys.size`. При смене ключа отображается ненавязчивый индикатор/Toast, сессия перевода не прерывается для пользователя.

### **2.3. Уровень 2: Каскадный fallback моделей (Model Fallback Cascade)**
* Если все ключи из пула исчерпали квоты на приоритетной модели, система переключается на следующую доступную модель из списка и повторяет цикл по пулу ключей.

```
[Старт сессии] 
       │
       ▼
 [Модель #1 (Primary Live)] ──(Ошибка ключа / 429)──> [Следующий API-ключ в пуле]
       │                                                         │
       │ (Все ключи исчерпаны)                                    │
       ▼                                                         │
 [Модель #2 (Secondary Live)] <──────────────────────────────────┘
       │
       │ (Все ключи исчерпаны)
       ▼
 [Модель #3 (Fallback)]      ──> [Повторный проход по пулу ключей]
```

---

## **3. Настройки и конфигурация**

1. **Языковая пара:**
   * **«Мой язык» (Our / Target Language):** Язык пользователя (*Русский*, *Казахский*, *Испанский* и др.).
   * **«Язык оппонента» (Opponent Language):** Язык собеседника (*Английский*, *Китайский*, *Немецкий* и др.).
2. **Пул API-ключей:**
   * Добавление, редактирование, удаление нескольких ключей Gemini API.
   * Валидация ключей при добавлении через тестовый REST-запрос.
3. **Выбор аудио-голоса Gemini:**
   * Список встроенных голосов (`Puck`, `Charon`, `Aoede`, `Fenrir`, `Kore`).
4. **Опция субтитров (Live Transcription):**
   * Тумблер: *«Отображать субтитры / текст перевода в реальном времени»*.
5. **История переводов и экспорт:**
   * Тумблер: *«Сохранять историю диалогов»*.
   * Экран истории с возможностью поиска, очистки и кнопки **«Экспорт в файл»** (`.txt` / `.json`).
6. **Синхронизация с часами:**
   * Кнопка *«Связать с часами (Сканировать QR-код)»* — открывает встроенную камеру для считывания QR-кода с часов и передачи настроек.

---

## **4. Требования к пользовательскому интерфейсу (UI/UX)**

### **4.1. Смартфон (Mobile App)**

#### **Состояние ожидания (Inactive State)**
* Крупные кнопки быстрого запуска:
  1. **[ 🎧 Односторонний перевод ]** («Шепот/Синхрон»: микрофон ➔ перевод на «Мой язык» ➔ вывод строго в наушники).
  2. **[ 🗣 Двусторонний перевод ]** («Диалог»: микрофон ➔ перевод в обе стороны ➔ динамик/наушники).
* Шапка: текущая языковая пара (например, *«Русский ↔ English»*) с кнопкой быстрого реверса.
* Иконки: «История», «Синхронизация с часами», «Настройки».

#### **Состояние активной сессии (Active State)**
* Плашка текущего сетевого статуса (*«Подключено»*, *«Смена ключа...»*, *«Переподключение...»*).
* Анимированный индикатор аудиоактивности (Waveform / Visualizer).
* **Блок субтитров:** (если включен в настройках) отображение распознанного и переведенного текста в реальном времени.
* Центральная кнопка: **[ ⏹ СТОП ]** (красная, крупная). Немедленно останавливает запись `AudioRecord`, сбрасывает `AudioTrack` и закрывает WebSocket.

---

### **4.2. Смарт-часы (Wearable App — Huawei Watch 4 Pro, 466×466 px)**

#### **Экран первого запуска / Сопряжения (QR Code State)**
* На часах **ручной ввод исключен**.
* При первом запуске (или по кнопке в меню) часы генерируют и отображают на круглом экране **QR-код сопряжения** (содержит Bluetooth MAC-адрес часов, сгенерированный одноразовый UUID/токен сессии).
* После сканирования QR-кода телефоном часы принимают полный JSON-конфиг по Bluetooth RFCOMM, сохраняют его в `EncryptedSharedPreferences` и автоматически переходят на главный экран.

#### **Главный экран часов**
* Две адаптивные круглые кнопки: **[ 🎧 Односторонний ]** и **[ 🗣 Двусторонний ]**.
* Индикатор статуса сети (eSIM LTE / Wi-Fi / Online).
* Индикатор аудиовыхода (наушники Bluetooth / динамик часов).

#### **Активное состояние на часах**
* Крупная центральная кнопка **[ ⏹ СТОП ]** с выраженным виброоткликом (Haptic feedback).
* Компактный бегущий текст субтитров перевода (при включенной опции).

---

## **5. Сетевое взаимодействие и протокол Gemini Multimodal Live API**

### **5.1. WebSocket Соединение**
* **Endpoint:** `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key={ACTIVE_API_KEY}`
* **Транспорт:** `OkHttp WebSocket` с кастомным `WebSocketListener`.

### **5.2. Стартовый Handshake (Setup Payload)**
Сразу после успешного установления WebSocket-соединения клиент отправляет настроечное JSON-сообщение:
```json
{
  "setup": {
    "model": "models/gemini-2.0-flash-exp",
    "generationConfig": {
      "responseModalities": ["AUDIO"],
      "speechConfig": {
        "voiceConfig": {
          "prebuiltVoiceConfig": {
            "voiceName": "Puck"
          }
        }
      }
    },
    "systemInstruction": {
      "parts": [
        {
          "text": "<SYSTEM_PROMPT>"
        }
      ]
    }
  }
}
```

### **5.3. Генерация System Prompts**

* **Односторонний перевод (Solo / Earphone Mode):**
  > `You are a real-time one-way interpreter. Listen to the incoming audio and immediately translate everything spoken in [OPPONENT_LANGUAGE] into [OUR_LANGUAGE]. Output ONLY the translated audio in [OUR_LANGUAGE]. Do not add any greetings, comments, explanations, or conversational filler.`

* **Двусторонний перевод (Two-way Dialogue Mode):**
  > `You are a real-time bidirectional interpreter between [OUR_LANGUAGE] and [OPPONENT_LANGUAGE]. If you hear [OUR_LANGUAGE], translate it immediately into [OPPONENT_LANGUAGE]. If you hear [OPPONENT_LANGUAGE], translate it immediately into [OUR_LANGUAGE]. Output ONLY the translated speech audio. Do not engage in conversation or add filler words.`

### **5.4. Потоковая передача аудио и управление контекстом**
* **Отправка данных (Realtime Input):** Чанки PCM 16kHz передаются в формате Base64:
  ```json
  {
    "realtimeInput": {
      "mediaChunks": [
        {
          "mimeType": "audio/pcm;rate=16000",
          "data": "<BASE64_PCM_CHUNK>"
        }
      ]
    }
  }
  ```
* **Прием данных (Model Turn):** Прием Base64 PCM 24kHz из `serverContent.modelTurn.parts[].inlineData.data` и текста из `parts[].text`.
* **Управление переполнением контекста (Context Window Management):**
  * Для предотвращения роста задержек (TTFB) при длительных сессиях система выполняет «тихий» сброс контекста диалога при обнаружении паузы в речи более 5 секунд или принудительно каждые 15 минут непрерывной работы.

---

## **6. Межприборное взаимодействие (Phone ↔ Watch Sync)**

1. **Протокол сопряжения:**
   * Часы создают `BluetoothServerSocket` (RFCOMM SPP) с уникальным UUID приложения и генерируют QR-код со своим BT MAC-адресом и одноразовым токеном.
   * Смартфон сканирует QR-код, подключается по Bluetooth RFCOMM и передает зашифрованный JSON-пакет настроек.
2. **Формат передаваемого пакета конфигурации:**
   ```json
   {
     "version": 1,
     "apiKeys": ["AIzaSy...", "AIzaSy..."],
     "ourLanguage": "ru",
     "opponentLanguage": "en",
     "selectedVoice": "Puck",
     "showSubtitles": true,
     "saveHistory": false,
     "preferredModels": ["models/gemini-2.0-flash-exp", "models/gemini-2.0-flash-realtime-exp"]
   }
   ```
3. **Безопасность:** Данные на обоих устройствах сохраняются в `EncryptedSharedPreferences` (Jetpack Security).

---

## **7. Низкоуровневые требования к Audio Engine (Kotlin)**

### **7.1. Захват аудио (AudioRecord)**
* **Параметры:** Sample Rate: `16000 Hz`, Channel: `CHANNEL_IN_MONO`, Format: `ENCODING_PCM_16BIT`.
* **Источник звука:** `MediaRecorder.AudioSource.VOICE_COMMUNICATION` (для аппаратной привязки эхоподавления).
* **Размер чанка:** 3200 байт (100 мс) в корутине `Dispatchers.IO`.
* **VAD / RMS фильтр:** Программный RMS-детектор тишины для отсечения фонового шума и экономии трафика.

### **7.2. Подавление акустического эха (AEC) и подавление шума**
* Активация программного/аппаратного `AcousticEchoCanceler` и `NoiseSuppressor` через AudioSessionId `AudioRecord`.
* **Audio Ducking / Muting:** В режиме громкой связи при активном воспроизведении перевода динамиком микрофонный гейт автоматически повышает порог чувствительности (порог RMS) для исключения самозахвата переведенного звука.

### **7.3. Воспроизведение звука (AudioTrack) и Jitter Buffer**
* **Параметры:** Sample Rate: `24000 Hz`, Channel: `CHANNEL_OUT_MONO`, Format: `ENCODING_PCM_16BIT`, Stream: `USAGE_VOICE_COMMUNICATION` (в режиме диалога) или `USAGE_MEDIA` (в режиме наушников).
* **Jitter Ring Buffer:** Адаптивный кольцевой буфер на 150–250 мс перед началом воспроизведения для сглаживания сетевого джиттера мобильной связи.
* **Обработка прерываний:** Мгновенный сброс буфера `audioTrack.pause()` + `audioTrack.flush()` при получении флага `serverContent.interrupted = true`.

### **7.4. Управление AudioFocus**
* Регистрация `AudioManager.OnAudioFocusChangeListener`. При входящем телефонном звонке (`AUDIOFOCUS_LOSS_TRANSIENT`) сессия перевода автоматически приостанавливается.

---

## **8. Энергоэффективность, разрешения и системные сервисы**

### **8.1. Фоновая устойчивость (Foreground Service)**
* Работа через `ForegroundService` с типом `foregroundServiceType="microphone"`.
* Удержание `PowerManager.WakeLock` (`PARTIAL_WAKE_LOCK`) и `WifiManager.WifiLock` (`WIFI_MODE_FULL_HIGH_PERF`) во время активной сессии для предотвращения задержек сетевого чипа.
* Запрос на отключение оптимизации батареи (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

### **8.2. Разрешения (AndroidManifest.xml)**
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

<!-- Bluetooth для Android 10–11 (API 29–30) -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />

<!-- Bluetooth для Android 12+ (API 31+) -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />

<uses-permission android:name="android.permission.CAMERA" /> <!-- Для сканирования QR на телефоне -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```
