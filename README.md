# HotwordBridge — offline "Hey ChatGPT" for Android

A no-root experimental Android app for a Pixel 7 to detect **Hey ChatGPT** locally using **sherpa-onnx**, and then hand off to the official ChatGPT Android app.

**v0.6 — no Picovoice account, AccessKey, .ppn download or ChatGPT API key required.** The English model is packaged into the APK.

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

## v0.6 — offline Vosk ASR versus sherpa-onnx KWS

New **engine selector** lets you compare two approaches with the same Pixel
microphone (one engine at a time, never competing for the mic):

- **VOSK (default)** uses official Apache-2.0 40 MB
  `vosk-model-small-en-us-0.15` via `vosk-android 0.3.75`. The model ZIP is
  embedded in the APK; first use extracts it into app-private storage, without
  network access or account. Allow enough free space (~100 MB after installation).
- **SHERPA** retains the existing local keyword detector and the standard
  real-recording self-test from v0.5.

### Suggested on-device test sequence

1. Open v0.6 and confirm **选定引擎：VOSK**; tap **开始监听**.
2. On initial Vosk startup, wait for model extraction/loading and the stage
   **Vosk 实时转写中**; initial startup can be noticeably longer.
3. Say ordinary English, then **Hello World**, then **Hey Chat G P T**.
4. Observe **Vosk 实时识别** and **Vosk 最近完整句**. Its complete recognized
   phrases are saved locally in **Vosk 转写记录** for copying and comparison.
   No audio recording is saved or sent anywhere.
5. Only exact normalized `hey chat gpt` or `hey chat g p t` matches will
   launch ChatGPT or create a tap-to-open notification under Android policy;
   `hello world` triggers a harmless success event.
6. Stop listening and tap **切换识别引擎** to SHERPA to compare recognition.
7. The **清空本机转写记录** button clears transcript strings from app preferences.
   Uninstalling the app also erases its extracted model and all diagnostics.

**Important:** The ASR text is a *diagnostic*, not a guarantee of robust
hands-free detection. The Vosk recognizer performs utterance endpoint detection.
We retain an energy-based speech activity meter; we do not aggressively skip
silent PCM frames because that would interfere with Vosk endpointing.

The first build with this implementation must pass CI. A compiled APK is
not evidence that the Vosk native engine works perfectly on Pixel 7; send
diagnostic text rather than repeatedly shouting the wake phrase.

## v0.5: built-in real-voice regression on Android

The **Run official human recording self-test** button uses an upstream recorded
human utterance of **LIGHT UP** stored inside the APK as 16 kHz PCM. It streams
100 ms PCM windows through the *same Android sherpa-onnx AAR* and the same
model weights, using the upstream reference keywords file. It never uses the
microphone or starts ChatGPT.

1. Stop microphone listening first.
2. Tap **运行官方真人录音自测（不使用麦克风）**.
3. Wait for the result on screen and a toast: expected `LIGHT UP`.
4. If this passes, the native Android inference pipeline works with a known-good
   recording; failures on `HEY CHAT GPT` or `HELLO WORLD` then call for
   keyword/voice/acoustic evaluation rather than just lowering the threshold.
5. If it fails, inspect the reported error. Our Android model runtime or
   stream/asset wiring needs fixing before changing wake sensitivity.

The CI also runs the upstream human WAV recordings (`0.wav` / `1.wav`) through
the same pretrained model on the Linux runner; those known-good cases must
pass before a new APK is released. eSpeak-synthesized test utterances are reported
as *non-blocking diagnostics*, because synthetic speech can differ from
the training data. No audio is uploaded by the Android app.

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
