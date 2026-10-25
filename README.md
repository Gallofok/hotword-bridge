# HotwordBridge — offline "Hey ChatGPT" for Android

A no-root experimental Android app for a Pixel 7 to detect **Hey ChatGPT** locally using **sherpa-onnx**, and then hand off to the official ChatGPT Android app.

**v0.2 — no Picovoice account, AccessKey, .ppn download or ChatGPT API key required.** The English model is packaged into the APK.

## Download and install

1. Open the **Actions** tab, find a successful **Build debug APK** run on `main`.
2. Download the `hotword-bridge-sherpa-debug-apk` artifact.
3. Unzip and install `app-debug.apk` on your Pixel. **Uninstall the old v0.1 APK first**, since CI debug signing keys may differ between runs. Uninstalling clears old app data.
4. Tap **Start listening** and allow microphone and notification access.
5. Say **"Hey Chat GPT"** in English. You can also try spelling out G-P-T. This is a keyword-spotting experiment and sensitivity may require tuning after phone testing.
6. The service stops after detection to release the microphone for ChatGPT.

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
