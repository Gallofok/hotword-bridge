#!/usr/bin/env python3
"""Streaming reference for the Android detector (WakeWordDetector.kt mirrors this file step by step).

Audio arrives in 1280-sample chunks (80 ms at 16 kHz). For every chunk:
  1. mel: run melspectrogram.onnx on the last 1760 samples (chunk + 480 of history) -> exactly 8 new frames, value/10 + 2
  2. embedding: run embedding_model.onnx on the last 76 mel frames -> one 96-value vector
  3. score: run the classifier on the last N embeddings (N comes from the model input shape)
Nothing is scored until N embeddings exist.

usage: stream_ref.py MODEL_DIR WAV_DIR [--threshold 0.7]
"""
import argparse, glob, os, sys, wave
import numpy as np
import onnxruntime as ort

CHUNK, HISTORY, MEL_WINDOW = 1280, 480, 76


class StreamingDetector:
    def __init__(self, model_dir, name="hey_chatgpt"):
        so = ort.SessionOptions(); so.intra_op_num_threads = 1
        load = lambda f: ort.InferenceSession(os.path.join(model_dir, f), so, providers=["CPUExecutionProvider"])
        self.mel, self.emb, self.clf = load("melspectrogram.onnx"), load("embedding_model.onnx"), load(f"{name}.onnx")
        self.n = self.clf.get_inputs()[0].shape[1]
        self.reset()

    def reset(self):
        self.raw = np.zeros(HISTORY, dtype=np.float32)          # last 480 samples of the previous chunk
        self.mels = np.zeros((0, 32), dtype=np.float32)
        self.embs = np.zeros((0, 96), dtype=np.float32)

    def accept(self, chunk):
        """chunk: 1280 int16 samples. Returns the score, or None while the buffers are still filling."""
        assert len(chunk) == CHUNK
        x = np.concatenate([self.raw, chunk.astype(np.float32)])
        self.raw = x[-HISTORY:]
        mel = self.mel.run(None, {self.mel.get_inputs()[0].name: x[None, :]})[0].reshape(-1, 32) / 10 + 2
        assert mel.shape[0] == 8, mel.shape
        self.mels = np.concatenate([self.mels, mel])[-MEL_WINDOW:]
        if len(self.mels) < MEL_WINDOW:
            return None
        e = self.emb.run(None, {self.emb.get_inputs()[0].name: self.mels[None, :, :, None]})[0].reshape(1, 96)
        self.embs = np.concatenate([self.embs, e])[-self.n:]
        if len(self.embs) < self.n:
            return None
        return float(self.clf.run(None, {self.clf.get_inputs()[0].name: self.embs[None]})[0].reshape(-1)[0])


def run_file(det, pcm, offset=0, lead_s=3.0, tail_s=1.0):
    """Feed silence, then the clip, then silence, like a microphone that was already listening."""
    det.reset()
    x = np.concatenate([np.zeros(int(lead_s * 16000) + offset, np.int16), pcm, np.zeros(int(tail_s * 16000), np.int16)])
    scores = [det.accept(x[i:i + CHUNK]) for i in range(0, len(x) - CHUNK + 1, CHUNK)]
    return np.array([s if s is not None else 0.0 for s in scores])


def read_wav(path):
    with wave.open(path) as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("model_dir"); ap.add_argument("wav_dir")
    ap.add_argument("--threshold", type=float, default=0.7)
    a = ap.parse_args()
    det = StreamingDetector(a.model_dir)
    offsets = [0, 160, 320, 480, 640, 800, 960, 1120]
    print(f"classifier window: {det.n} embeddings; threshold {a.threshold}")
    groups = {}
    for f in sorted(glob.glob(os.path.join(a.wav_dir, "*.wav"))):
        groups.setdefault(os.path.basename(f)[0], []).append(f)
    for g, files in groups.items():
        if g == "D":
            n = 0
            for f in files:
                s = run_file(det, read_wav(f)); above = s >= a.threshold
                n += int(np.sum(above[1:] & ~above[:-1]) + above[0])
            print(f"{g}: {n} false triggers in {sum(len(read_wav(f)) for f in files) / 16000 / 60:.1f} min")
        else:
            hit = np.array([[run_file(det, read_wav(f), o).max() >= a.threshold for o in offsets] for f in files])
            print(f"{g}: {hit.mean() * 100:.0f}% over {len(offsets)} offsets, {int(hit.all(1).sum())}/{len(files)} clips at every offset")


if __name__ == "__main__":
    sys.exit(main())
