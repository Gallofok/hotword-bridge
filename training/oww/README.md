# Training the "Hey ChatGPT" model

The model is an [openWakeWord](https://github.com/dscripka/openWakeWord) classifier.
Training runs on Kaggle; evaluation runs anywhere with `onnxruntime` and `numpy`.

| File | Purpose |
|---|---|
| `run.py` | The training job: synthetic speech (Piper), optional real recordings, augmentation, training, ONNX export |
| `compat_launch.py` | Patches for newer library versions that openWakeWord's training code does not support yet |
| `job.json`, `params.json` | Job description for the `kgrun` Kaggle runner, and training sizes |
| `evaluate.py` | Scores a model on WAV recordings (whole-clip features) |
| `stream_ref.py` | Streaming reference; the Android `WakeWordDetector.kt` mirrors it |

## Real recordings

`run.py` looks for `user_clips/pos/*.wav` and `user_clips/neg/*.wav` (16 kHz mono,
trimmed to the phrase, at most 2 seconds) in the job's input data. That folder is
ignored by git: recordings are uploaded as a private Kaggle dataset and are never
committed. Without it, the job trains on synthetic speech only.

## After training

The exporter writes `hey_chatgpt.onnx` plus `hey_chatgpt.onnx.data`. Merge them
into one file before copying it to `app/src/main/assets/oww/`:

```python
import onnx
onnx.save(onnx.load("hey_chatgpt.onnx"), "merged/hey_chatgpt.onnx", save_as_external_data=False)
```

Then check it:

```bash
python stream_ref.py MODEL_DIR WAV_DIR --threshold 0.7
```

`WAV_DIR` holds files starting with `A`, `B`, `C` (one wake phrase each) and `D`
(speech without the phrase). If the test fixtures under `app/src/test/resources/oww`
or the model change, update the expected scores in `WakeWordDetectorTest.kt`.
