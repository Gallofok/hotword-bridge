#!/usr/bin/env python3
"""Score an openWakeWord model on real recordings (16 kHz mono WAV), offline, with onnxruntime only.

usage: evaluate.py MODEL_DIR WAV_DIR [--threshold 0.5]
MODEL_DIR holds melspectrogram.onnx, embedding_model.onnx and hey_chatgpt.onnx.
WAV_DIR holds A*/B*/C* (one wake phrase each) and D* (speech without the wake phrase).

The feature pipeline mirrors openWakeWord: mel frames every 10 ms, an embedding per 76 frames every 8 frames (80 ms),
and the classifier looks at the last 16 embeddings. This is also the reference for the Android port.
"""
import argparse, glob, json, os, sys, wave
import numpy as np
import onnxruntime as ort


def read_wav(path):
    with wave.open(path) as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)


class Scorer:
    def __init__(self, model_dir, name="hey_chatgpt"):
        so = ort.SessionOptions(); so.intra_op_num_threads = 2
        load = lambda f: ort.InferenceSession(os.path.join(model_dir, f), so, providers=["CPUExecutionProvider"])
        self.mel, self.emb, self.clf = load("melspectrogram.onnx"), load("embedding_model.onnx"), load(f"{name}.onnx")
        self.n_emb = self.clf.get_inputs()[0].shape[1]

    def scores(self, pcm, pad_s=1.0):
        """Return (times_s, scores): one score per 80 ms step."""
        pad = np.zeros(int(pad_s * 16000), dtype=np.int16)          # so the phrase is never at the very edge of the window
        x = np.concatenate([pad, pcm, pad]).astype(np.float32)[None, :]
        mel = self.mel.run(None, {self.mel.get_inputs()[0].name: x})[0].squeeze() / 10 + 2     # (frames, 32)
        starts = range(0, mel.shape[0] - 76 + 1, 8)
        win = np.stack([mel[s:s + 76] for s in starts])[..., None].astype(np.float32)
        emb = np.concatenate([self.emb.run(None, {self.emb.get_inputs()[0].name: win[i:i + 64]})[0].reshape(-1, 96)
                              for i in range(0, len(win), 64)])
        n = self.n_emb
        if len(emb) < n:
            emb = np.concatenate([np.repeat(emb[:1], n - len(emb), axis=0), emb])
        feats = np.stack([emb[i:i + n] for i in range(len(emb) - n + 1)]).astype(np.float32)
        out = np.concatenate([self.clf.run(None, {self.clf.get_inputs()[0].name: feats[i:i + 1]})[0].reshape(-1)
                              for i in range(len(feats))])
        t = (np.arange(len(out)) + n - 1) * 0.08 + 0.76 - pad_s
        return t, out


def triggers(t, s, thr, refractory=2.0):
    """Count separate activations: a score above thr, then nothing counted for `refractory` seconds."""
    hits, last = [], -1e9
    for ti, si in zip(t, s):
        if si >= thr and ti - last >= refractory:
            hits.append(round(float(ti), 1)); last = ti
    return hits


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("model_dir"); ap.add_argument("wav_dir")
    ap.add_argument("--threshold", type=float, default=0.5); ap.add_argument("--json")
    a = ap.parse_args()
    sc = Scorer(a.model_dir)
    pos, neg, neg_seconds = {}, [], 0.0
    for f in sorted(glob.glob(os.path.join(a.wav_dir, "*.wav"))):
        name = os.path.basename(f)[:-4]; pcm = read_wav(f); t, s = sc.scores(pcm)
        if name[0] in "ABC":
            pos[name] = float(s.max())
        else:
            neg.append((name, t, s)); neg_seconds += len(pcm) / 16000
    print(f"model: {a.model_dir}\npositives: {len(pos)} clips, negatives: {neg_seconds / 60:.1f} min of speech\n")
    print("thr   hit A   hit B   hit C   total      false triggers in negative speech")
    table = []
    for thr in (0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9):
        by = {c: [v >= thr for k, v in pos.items() if k[0] == c] for c in "ABC"}
        fp = sum(len(triggers(t, s, thr)) for _, t, s in neg)
        tot = sum(sum(v) for v in by.values())
        table.append({"thr": thr, "hits": tot, "n": len(pos), "false": fp})
        print(f"{thr:.1f}   " + "   ".join(f"{sum(by[c]):2d}/{len(by[c]):<2d}" for c in "ABC") + f"   {tot:2d}/{len(pos)}      {fp}")
    print("\nmax score per positive clip:")
    for c in "ABC":
        print(" ", c, " ".join(f"{v:.2f}" for k, v in pos.items() if k[0] == c))
    print("highest scores in negative speech:", " ".join(f"{n}:{s.max():.2f}" for n, _, s in neg))
    if a.json:
        json.dump({"table": table, "positives": pos, "neg_max": {n: float(s.max()) for n, _, s in neg}, "neg_minutes": neg_seconds / 60},
                  open(a.json, "w"), indent=1)


if __name__ == "__main__":
    sys.exit(main())
