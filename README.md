# HotwordBridge

An experimental Android app for Pixel phones: say **"Hey ChatGPT"** and it opens
the official ChatGPT app in voice mode. Recognition runs on the phone; audio is
never saved or uploaded. No account or API key is needed.

**[Download the latest HotwordBridge.apk](https://github.com/Gallofok/hotword-bridge/releases/latest/download/HotwordBridge.apk)**

This is a personal project. The wake-word model in this repository was trained
for one speaker's voice, so it will probably not respond to other people.

## How it works

- A foreground service records 16 kHz audio and feeds it to a small
  [openWakeWord](https://github.com/dscripka/openWakeWord) model through ONNX
  Runtime, in 80 ms steps.
- When the score passes the threshold, the app opens ChatGPT. It tries the
  ChatGPT voice screen first and falls back to the normal app.
- The model was trained on synthetic speech plus the owner's own recordings.
  See [training/oww](training/oww).

## Install and use

1. Download the APK above on the phone and install it (allow "Install unknown
   apps" when asked).
2. Open HotwordBridge, tap **开始监听**, and allow microphone and notification
   access.
3. Tap **允许在后台直接打开 ChatGPT** and turn on "display over other apps".
   Without it, a wake word heard while the app is in the background only shows
   a notification that you have to tap.
4. Say "Hey ChatGPT". The status text shows the live score, the threshold and
   the last wake event.

Settings on the main screen:

| Setting | Meaning |
|---|---|
| 灵敏度 高 / 中 / 低 | Score threshold 0.5 / 0.7 / 0.85. Higher sensitivity wakes more easily and may trigger by mistake more often. |
| 唤醒后继续监听 | Experimental. By default listening stops after one wake so ChatGPT has the microphone to itself. |
| ChatGPT 启动方式 | Which screen to open: automatic, voice activity, voice link, or the app's home screen. |
| 麦克风输入源 | `VOICE_RECOGNITION` (default) or `MIC`. |

Each build is signed with a temporary debug key, so a newer APK may refuse to
install over an older one. Uninstall the old one first; only the settings are
lost.

## Limits

- It does not open ChatGPT from the lock screen.
- Android may stop the background service to save battery.
- The ChatGPT voice screen is opened through an undocumented activity name and
  an undocumented link. A ChatGPT update can break either; the app then falls
  back to opening ChatGPT normally.
- A wake while another app is using the microphone is not possible: Android
  gives the microphone to the app in front.

## Measured accuracy

On 30 recordings of the owner that were not used for training (hand-held, on a
desk at about one metre, and deliberately varied), each tested at eight time
offsets with threshold 0.7:

| Model | Detected | False triggers in 5 minutes of ordinary speech |
|---|---|---|
| Synthetic speech only | 19% | 0 |
| Synthetic speech + owner's recordings (shipped) | 93% | 0 |

Five minutes is too short to state a false-trigger rate for daily use, and the
recordings came through Telegram voice messages rather than the app's own
microphone path. Real-world behaviour still has to be checked on the phone.

## Build

Requires Java 17, the Android SDK and Gradle 8.11.1.

```bash
bash scripts/prepare-oww.sh          # downloads two pinned feature models
gradle :app:testDebugUnitTest        # runs the detector on the JVM against reference scores
gradle :app:assembleDebug
```

GitHub Actions runs the same steps for every pull request. A push to `main`
also publishes the APK as a GitHub Release.

## Repository layout

| Path | Content |
|---|---|
| `app/src/main/java/.../WakeWordDetector.kt` | Streaming detector (pure Kotlin + ONNX Runtime) |
| `app/src/main/java/.../ListeningService.kt` | Microphone foreground service |
| `app/src/main/java/.../ChatGptLauncher.kt` | Ways to open ChatGPT voice |
| `app/src/main/assets/oww/hey_chatgpt.onnx` | The trained wake-word classifier |
| `training/oww/` | Training job, offline evaluation and the Python streaming reference |

## Licences of third-party parts

- ONNX Runtime: MIT.
- openWakeWord code: Apache-2.0. Its pretrained feature models
  (`melspectrogram.onnx`, `embedding_model.onnx`), which the build downloads,
  are CC BY-NC-SA 4.0: **non-commercial use only**.
