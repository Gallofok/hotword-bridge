# HotwordBridge — “Hey ChatGPT” for Android

A no-root Android prototype for launching ChatGPT Voice through local wake-word detection. Initially designed for a Pixel 7.

## What it can and cannot do

- Porcupine detects a custom **Hey ChatGPT** wake word **on-device** while a user-started microphone foreground service is running.
- A visible ongoing notification includes a Stop action.
- When the app is **visible**, the wake event attempts to open the official ChatGPT Android app. Enable **Settings → Voice → Start with Voice** inside ChatGPT.
- When the app is in the **background or the phone is locked**, Android restricts launching an Activity. The wake event posts a tap-to-open notification instead. Fully hands-free background/lock-screen launching is **not implemented**.
- No Root, no Accessibility Service, no unsupported fake ChatGPT voice deep-link. This is not equivalent to Google Assistant's system-level hotword support.
- Microphone service must be started from a visible app and may be stopped by Android/battery policy. Automatic microphone startup on boot is intentionally not implemented.

## Get your APK (no computer needed)

1. Open the **Actions** tab in this GitHub repository.
2. Open **Build debug APK** and select **Run workflow**, or use the automatically triggered build after commits.
3. After a successful run, download the **hotword-bridge-debug-apk** artifact.
4. Extract and sideload `app-debug.apk` on your Android phone (enable installation from this source when prompted).
5. Install the official ChatGPT Android app.

## Setup on the phone

1. Create a free account at https://console.picovoice.ai/ and get your personal **AccessKey**.
2. Create an Android custom wake-word model for the phrase **Hey ChatGPT**, download the resulting `.ppn` file onto your phone. Models are platform-specific: choose **Android**.
3. In HotwordBridge, paste your AccessKey and tap **Save AccessKey**. This is stored in app-private SharedPreferences on this device; never put it in a public GitHub repository or screenshots.
4. Tap **Import .ppn model**, choose the downloaded file.
5. Grant the microphone permission (and notifications if prompted), then tap **Start listening** while the app is open.
6. For ChatGPT, enable **Start with Voice** from **Settings → Voice** on compatible versions, and optionally **Background Conversations**.
7. Say **Hey ChatGPT**. If HotwordBridge is on screen, it attempts to launch ChatGPT. If backgrounded, tap the wake notification.

For a quick microphone test without a custom model, select **Test with “Porcupine”**; that is *not* “Hey ChatGPT” and still requires an AccessKey.

## Security and privacy

Audio processing occurs locally in the Porcupine SDK. Picovoice may need network access for license/AccessKey verification. No speech transcript is recorded or uploaded by HotwordBridge. AccessKey remains on the phone; delete the app's storage to remove it. AccessKeys may have account licensing restrictions. Do not distribute your personal key.

## Technical architecture

- Kotlin / Android, `minSdk 26`, `compileSdk 35`, `targetSdk 35`
- `ai.picovoice:porcupine-android:4.0.2`
- `ListeningService` is a foreground service with Android's `microphone` type.
- In-app UI imports an Android-specific .ppn file using Storage Access Framework.
- The wake callback posts a notification with a package-specific ChatGPT launcher `PendingIntent`.
- CI creates a debug APK via GitHub Actions; no Gradle wrapper binary needs to be stored in the repository.

## Roadmap

- [x] Android app scaffold and CI build workflow
- [x] AccessKey entry and Android custom model import
- [x] User-initiated foreground microphone detection
- [x] ChatGPT launch while foreground / background notification fallback
- [ ] Confirm GitHub Actions build success
- [ ] Test model sensitivity, battery life, notification flow on Pixel 7
- [ ] Investigate supported default-assistant integration for better hands-free behavior

Android foreground service guidance: https://developer.android.com/develop/background-work/services/fgs/service-types#microphone

Porcupine Android docs: https://picovoice.ai/docs/api/porcupine-android/

OpenAI Voice: https://help.openai.com/en/articles/20001274-chatgpt-voice
