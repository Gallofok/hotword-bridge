"""Train a custom openWakeWord model for "Hey ChatGPT" on Kaggle (via kgrun).

Stages: deps -> downloads -> synthetic clips (Piper) -> augmentation -> training -> ONNX export.
Everything large stays in /tmp; only the model, config and logs go to KGRUN_OUT.
params.json: smoke=true uses the small validation feature file as negative data to test the pipeline quickly.
"""
import glob, io, json, os, shutil, subprocess, sys, tarfile, time, traceback
from pathlib import Path

OUT = Path(os.environ.get("KGRUN_OUT", "out")); OUT.mkdir(parents=True, exist_ok=True)
JOB = Path(__file__).resolve().parent
P = json.load(open(JOB / "params.json"))
W = Path("/tmp/oww"); W.mkdir(parents=True, exist_ok=True)
os.chdir(W)
os.environ["TORCH_FORCE_NO_WEIGHTS_ONLY_LOAD"] = "1"   # Piper .pt generator is a full pickle
LOG = open(OUT / "stages.log", "a")
MODEL_NAME = "hey_chatgpt"


def say(*a):
    msg = " ".join(str(x) for x in a)
    print(msg, flush=True); LOG.write(msg + "\n"); LOG.flush()


def sh(cmd, check=True, **kw):
    say("$", cmd)
    r = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, **kw)
    tail = r.stdout[-6000:]
    say(tail)
    if check and r.returncode:
        raise RuntimeError(f"command failed ({r.returncode}): {cmd}")
    return r


def stage(name, fn):
    t = time.time(); say(f"\n===== {name} =====")
    try:
        fn(); say(f"===== {name} ok in {time.time() - t:.0f}s")
    except Exception:
        say(f"===== {name} FAILED\n" + traceback.format_exc()); raise


def deps():
    say(sys.version); sh("nvidia-smi -L; df -h /tmp /kaggle/working | cat; free -g | head -2", check=False)
    sh("pip list 2>/dev/null | grep -i -E '^(torch|torchaudio|numpy|scipy|onnx|onnxruntime|datasets|speechbrain|librosa|soundfile|huggingface) '", check=False)
    if not Path("openwakeword").exists():
        sh("git clone -q --depth 1 https://github.com/dscripka/openWakeWord openwakeword")
    if not Path("piper-sample-generator").exists():
        sh("git clone -q https://github.com/dscripka/piper-sample-generator")
    sh("cd openwakeword && git log -1 --format='%h %ad' && cd ../piper-sample-generator && git log -1 --format='%h %ad'")
    sh("pip install -q --no-deps -e ./openwakeword")
    pkgs = ["piper-phonemize", "webrtcvad", "mutagen", "torchinfo", "torchmetrics", "speechbrain", "audiomentations",
            "torch-audiomentations", "acoustics", "pronouncing", "onnx", "onnxruntime", "onnxscript", "espeak-phonemizer", "pyyaml", "tqdm"]
    pkgs.remove("piper-phonemize")
    sh("apt-get install -y -qq espeak-ng libespeak-ng1 >/dev/null 2>&1; which espeak-ng; ls /usr/lib/x86_64-linux-gnu | grep -i espeak", check=False)
    for p in pkgs:      # one by one so a single missing wheel does not hide the others
        sh(f"pip install -q {p}", check=False)
    sh("pip list 2>/dev/null | grep -i -E '^(piper|speechbrain|audiomentations|torch-audiomentations|acoustics|pronouncing|onnx|webrtcvad)'", check=False)


def fetch(url, dest):
    dest = Path(dest)
    if not dest.exists() or dest.stat().st_size < 1000:
        dest.parent.mkdir(parents=True, exist_ok=True)
        sh(f"wget -q -O '{dest}.part' '{url}' && mv '{dest}.part' '{dest}'")
    say(dest, f"{dest.stat().st_size / 1e6:.0f} MB")


def to_wav16(data, dest, min_len=8000):
    import numpy as np, soundfile as sf
    from scipy.signal import resample_poly
    x, sr = sf.read(io.BytesIO(data), dtype="float32", always_2d=True)
    x = x.mean(axis=1)
    if sr != 16000:
        x = resample_poly(x, 16000, sr)
    if len(x) < min_len:
        return False
    sf.write(dest, np.clip(x, -1, 1), 16000, subtype="PCM_16"); return True


def hf_audio(repo, dest, limit, prefer=(), min_len=8000):
    """Write up to `limit` 16 kHz wavs from a Hugging Face dataset repo (tar of audio files or parquet with audio bytes)."""
    from huggingface_hub import HfApi, hf_hub_download
    dest = Path(dest); dest.mkdir(parents=True, exist_ok=True)
    have = len(list(dest.glob("*.wav")))
    if have >= limit:
        return
    files = HfApi().list_repo_files(repo, repo_type="dataset")
    cands = [f for f in files if f.endswith((".tar", ".parquet", ".wav", ".flac"))]
    cands.sort(key=lambda f: (not any(k in f for k in prefer), f))
    say(repo, len(files), "files; candidates:", cands[:5])
    n = have
    for f in cands:
        if n >= limit:
            break
        local = hf_hub_download(repo, f, repo_type="dataset", cache_dir="/tmp/hf")
        try:
            if f.endswith(".tar"):
                with tarfile.open(local) as tf:
                    for m in tf:
                        if n >= limit: break
                        if m.isfile() and m.name.endswith((".flac", ".wav", ".mp3")):
                            try: n += to_wav16(tf.extractfile(m).read(), dest / f"a{n:06d}.wav")
                            except Exception as e: say("skip", m.name, e)
            elif f.endswith(".parquet"):
                import pyarrow.parquet as pq
                pf = pq.ParquetFile(local)
                col = next(c for c in pf.schema_arrow.names if "audio" in c.lower())
                for batch in pf.iter_batches(batch_size=32, columns=[col]):
                    for item in batch.column(0).to_pylist():
                        if n >= limit: break
                        try: n += to_wav16(item["bytes"], dest / f"a{n:06d}.wav")
                        except Exception as e: say("skip row", e)
                    if n >= limit: break
            else:
                n += to_wav16(open(local, "rb").read(), dest / f"a{n:06d}.wav", min_len)
        finally:
            if f.endswith((".tar", ".parquet")):
                try: os.remove(os.path.realpath(local))
                except OSError: pass
    say(dest, n, "wav files")
    if n < min(limit, 20):
        raise RuntimeError(f"too few audio files from {repo}: {n}")


def downloads():
    rel = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
    for f in ["embedding_model.onnx", "melspectrogram.onnx"]:
        fetch(f"{rel}/{f}", f"openwakeword/openwakeword/resources/models/{f}")
    # The dscripka fork (2023) loads this v1.0.0 generator and phonemizes with espeak-phonemizer (no piper-phonemize wheel on py3.13).
    fetch("https://github.com/rhasspy/piper-sample-generator/releases/download/v1.0.0/en-us-libritts-high.pt",
          "piper-sample-generator/models/en-us-libritts-high.pt")
    sh("ls piper-sample-generator/models; grep -n 'import\\|phonemi' piper-sample-generator/generate_samples.py | head -40", check=False)
    hfd = "https://huggingface.co/datasets/davidscripka/openwakeword_features/resolve/main"
    fetch(f"{hfd}/validation_set_features.npy", "validation_set_features.npy")
    if not P["smoke"]:
        fetch(f"{hfd}/openwakeword_features_ACAV100M_2000_hrs_16bit.npy", "negative_features.npy")
    hf_audio("davidscripka/MIT_environmental_impulse_responses", "mit_rirs", 270, min_len=800)
    hf_audio("agkphysics/AudioSet", "audioset_16k", P["audioset_clips"], prefer=("bal_train",))
    sh("df -h /tmp | cat; du -sh /tmp/oww/* | sort -h | tail", check=False)


def config():
    import yaml
    c = yaml.safe_load(open("openwakeword/examples/custom_model.yml"))
    say("default config keys:", sorted(c))
    c.update(
        model_name=MODEL_NAME,
        # "gee pee tee" makes the TTS spell the letters; all words are in the CMU dictionary.
        target_phrase=["hey chat gee pee tee"],
        custom_negative_phrases=["hey google", "hey chat", "chat", "hey check", "hey check gps", "hey siri", "hey jeep",
                                 "check the gps", "hey chad", "hey cat", "g p s", "hey, check it", "hello world"],
        n_samples=P["n_samples"], n_samples_val=P["n_samples_val"], steps=P["steps"],
        piper_sample_generator_path="./piper-sample-generator", output_dir="./model_out",
        rir_paths=["./mit_rirs"], background_paths=["./audioset_16k"], background_paths_duplication_rate=[1],
        false_positive_validation_data_path="./validation_set_features.npy",
        feature_data_files={"ACAV100M_sample": "./validation_set_features.npy" if P["smoke"] else "./negative_features.npy"},
    )
    for k, v in P.get("config", {}).items():
        c[k] = v
    yaml.safe_dump(c, open("hwb.yml", "w"))
    shutil.copy("hwb.yml", OUT / "training_config.yml")
    say(open("hwb.yml").read())


def train(flag):
    def run():
        r = sh(f"{sys.executable} {JOB}/compat_launch.py openwakeword/openwakeword/train.py --training_config hwb.yml --{flag}", check=False)
        onnx = Path("model_out") / f"{MODEL_NAME}.onnx"
        if r.returncode and not (flag == "train_model" and onnx.exists()):   # TFLite conversion may fail; ONNX is what we ship
            raise RuntimeError(f"train.py --{flag} failed ({r.returncode})")
    return run


def collect():
    sh("ls -la model_out | head -30; for d in model_out/*/*; do echo $d $(ls $d | wc -l); done", check=False)
    for f in glob.glob("model_out/*.onnx") + glob.glob("model_out/*.onnx.data"):
        shutil.copy(f, OUT); say("saved", f, os.path.getsize(f))
    # a few synthetic clips to listen to, so the phrase pronunciation can be checked by ear
    for sub in ["positive_train", "negative_train"]:
        for f in sorted(glob.glob(f"model_out/{MODEL_NAME}/{sub}/*.wav"))[:6]:
            d = OUT / "sample_clips" / sub; d.mkdir(parents=True, exist_ok=True); shutil.copy(f, d)
    for f in ["embedding_model.onnx", "melspectrogram.onnx"]:
        shutil.copy(f"openwakeword/openwakeword/resources/models/{f}", OUT)


ok = False
try:
    stage("deps", deps); stage("downloads", downloads); stage("config", config)
    stage("generate_clips", train("generate_clips")); stage("augment_clips", train("augment_clips"))
    stage("train_model", train("train_model")); stage("collect", collect)
    ok = True
finally:
    json.dump({"ok": ok, "params": P}, open(OUT / "result.json", "w"))
    LOG.close()
sys.exit(0 if ok else 1)
