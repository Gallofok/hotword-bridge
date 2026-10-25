# HotwordBridge — offline "Hey ChatGPT" for Android

A no-root experimental Android app for a Pixel 7 to detect **Hey ChatGPT** locally using **sherpa-onnx**, and then hand off to the official ChatGPT Android app.

**v0.3 — no Picovoice account, AccessKey, .ppn download or ChatGPT API key required.** The English model is packaged into the APK.

## Direct APK download (no ZIP extraction)

**[Download the latest HotwordBridge.apk](https://github.com/Gallofok/hotword-bridge/releases/latest/download/HotwordBridge.apk)**

The `main` branch GitHub Actions workflow builds a signed-for-debug APK and
publishes it as a **GitHub Release asset** after every successful build.
The same permanent `releases/latest/download/HotwordBridge.apk` URL points at
the newest release. Do not choose GitHub's automatically generated "Source code
(zip)" or the Actions `Artifacts` ZIP.

On Pixel:
1. Tap the direct link above (sign into GitHub if requested).
2. Tap `HotwordBridge.apk` from Downloads to install. Allow "Install unknown apps" for the browser/files app when prompted.
3. If Android says "App not installed" due to signing conflict, uninstall the previous HotwordBridge debug build, then install the new APK. Uninstall clears its settings.
4. Open HotwordBridge, tap Start listening, and allow microphone + notification permissions.
5. Test by speaking "Hello World" near the phone before testing "Hey Chat G P T".

This is an **experimental debug build**, not Play Store-certified. GitHub Actions
success verifies compilation, not successful wake detection on your actual Pixel.

You can build locally with Java 17, Android SDK and Gradle 8.11.1:

```bash
python3 -m pip install sentencepiece==0.2.1
bash scripts/prepare-sherpa.sh
gradle :app:assembleDebug
```

## Technical details

- Kotlin, `minSdk 26`, `targetSdk 35`, Android microphone foreground service.
- `sherpa-onnx v2.0.0` pre-built Android AAR (SHA256 pinned against upstream GitHub release).
- English **GigaSpeech Zipformer KWS 3.3M** ONNX model: int8 encoder + joiner, fp32 decoder, 16 kHz mono PCM.
- Build pipeline downloads the open-source runtime and pretrained model. Model files and third-party binaries are not committed to this repository.
- The model's `bpe.model` is used to generate valid keyword token sequences for "HEY CHAT GPT" (and the letters variant).
- The APK is arm64-v8a only to suit the target Pixel.
- Uses sherpa-onnx `KeywordSpotter` + Android `AudioRecord` with a visible microphone foreground service. It does **not** upload your audio.
- User-initiated start; no always-on boot restart. Battery saver and OS microphone policy may interrupt background detection.

## Important Android restrictions

- When HotwordBridge is **visible**, a wake event attempts to open the official ChatGPT app (not a private/unsupported Voice deep link).
- When **backgrounded or locked**, Android restricts launching activities. After recognition, HotwordBridge issues a tap-to-open notification instead of promising an unsupported fully hands-free launch.
- Enable ChatGPT's **Start with Voice** option when supported by your ChatGPT version; direct voice entry depends on the official app.
- A Debug APK compiling successfully is not proof that the wake word is recognized reliably on a real Pixel. Test the detection and report the observed behavior.
- Microphone listening stops after each detected wake word, so press **Start listening** again for the next session.

## Third-party licenses

sherpa-onnx engine is Apache-2.0, see https://github.com/k2-fsa/sherpa-onnx.
The GigaSpeech KWS model originates from the sherpa-onnx `kws-models` releases; **check model licensing and suitability separately** before redistribution or commercial use.

## Roadmap

- [x] Replace Picovoice with sherpa-onnx / remove AccessKey
- [x] Bundle offline KWS assets in GitHub Actions builds
- [x] Android AudioRecord worker and notification/ChatGPT handoff
- [ ] Verify GitHub Actions v0.2 APK build
- [ ] Test on Pixel 7; adjust wake thresholds and phrase pronunciation
- [ ] Investigate official Assistant role integration / robust background behavior

References:
- https://k2-fsa.github.io/sherpa/onnx/kws/index.html
- https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html
- https://developer.android.com/develop/background-work/services/fgs/service-types#microphone

## v0.3 diagnostics: audio recording vs wake-word sensitivity

While keeping the app on screen:
1. Tap **Start listening** and allow microphone/notification permissions.
2. Watch the **audio level bar** and read the counters.
3. Speak normally close to the phone and confirm the bar moves, the input level changes from silence, **audio sample seconds increase**, and **model decode steps increase**.
4. If sample seconds stay at zero, inspect the displayed model/microphone startup stage and error. Microphone sampling has not started.
5. If seconds increase but input stays near **-99 dBFS** and the meter is empty, check Android microphone privacy toggle, permission, Bluetooth microphone routing, or competing apps.
6. If samples and decoding both increase, the microphone and processing are active. Test the more common phrase **Hello World** as a positive control.
7. If Hello World works but Hey Chat GPT does not, try English spelling **Hey Chat Gee Pee Tee**; otherwise wake-word model tuning is needed.
8. Tap **Copy diagnostics** and share the *text only* if debugging is needed. Audio content is not saved or uploaded.

The v0.3 profile uses per-keyword boost 2.5, threshold 0.12 for Hey Chat GPT, and 1.5 / 0.20 for Hello World. These are intentionally more permissive than the original defaults and may cause false positives.

This remains a proof-of-concept. GitHub Actions build success proves source compiles, not that any particular device recognizes a spoken phrase.
