#!/usr/bin/env python3
"""Reproducible WAV audio regression tests against the exact APK's KWS ONNX files.

Uses two *real speech* upstream recordings whose expected detections are documented:
  test_wavs/0.wav -> LIGHT UP
  test_wavs/1.wav -> LOVELY CHILD

Also tests our application's own Hello World / Hey Chat GPT keywords against
espeak-ng synthesized WAVs (informational until we have a human-recorded fixture).

Run from repository root, after scripts/prepare-sherpa.sh:
  python3 scripts/test-kws-recording.py

No microphone is used; this verifies token definitions and inference from PCM.
"""
from __future__ import annotations

import json
import sys
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

MODEL = Path("app/src/main/assets/kws")
FIXTURES = Path("test-fixtures/kws")
SAMPLE_RATE = 16000
CHUNK = 1600  # 100 ms, identical to HotwordBridge's Android AudioRecord loop


def read_pcm_wave(filename: Path):
    with wave.open(str(filename), "rb") as w:
        channels, width, sample_rate = w.getnchannels(), w.getsampwidth(), w.getframerate()
        if channels != 1 or width != 2:
            raise ValueError(f"{filename}: expected mono 16-bit PCM, got channels={channels} width={width}")
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
    return (pcm.astype(np.float32) / 32768.0, sample_rate)


def spotter(keywords: Path):
    return sherpa_onnx.KeywordSpotter(
        encoder=str(MODEL / "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
        decoder=str(MODEL / "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"),
        joiner=str(MODEL / "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
        tokens=str(MODEL / "tokens.txt"),
        keywords_file=str(keywords),
        num_threads=2,
        sample_rate=SAMPLE_RATE,
        feature_dim=80,
        max_active_paths=4,
        keywords_score=1.5,
        keywords_threshold=0.25,
        provider="cpu",
    )


def detect(kws, samples: np.ndarray, sample_rate: int) -> list[str]:
    s = kws.create_stream()
    found = []
    try:
        # Android sends 1600 samples on each AudioRecord read. Mimic this.
        for offset in range(0, samples.size, CHUNK):
            s.accept_waveform(sample_rate, samples[offset:offset + CHUNK])
            while kws.is_ready(s):
                kws.decode_stream(s)
                result = kws.get_result(s)
                if result:
                    found.append(str(result))
                    kws.reset_stream(s)

        # Flush final tokens as recommended by the upstream Python WAV example.
        s.accept_waveform(sample_rate, np.zeros(int(0.8 * sample_rate), dtype=np.float32))
        s.input_finished()
        while kws.is_ready(s):
            kws.decode_stream(s)
            result = kws.get_result(s)
            if result:
                found.append(str(result))
                kws.reset_stream(s)
    finally:
        # Native-backed stream is released automatically by the Python GC.
        del s
    return found


def main() -> int:
    cases = [
        ("official real speech #0", FIXTURES / "0.wav", FIXTURES / "official_keywords.txt", "LIGHT UP", True),
        ("official real speech #1", FIXTURES / "1.wav", FIXTURES / "official_keywords.txt", "LOVELY CHILD", True),
        ("synthetic HELLO WORLD", FIXTURES / "hello-world-synthetic.wav", MODEL / "keywords.txt", "HELLO_WORLD_TEST", False),
        ("synthetic HEY CHAT GPT", FIXTURES / "hey-chat-gpt-synthetic.wav", MODEL / "keywords.txt", "HEY_CHATGPT", False),
    ]
    spotters = {}
    results = []
    failures = []
    for label, wavpath, keywords, expected, required in cases:
        if not wavpath.is_file():
            if required:
                failures.append(f"Missing required real recording: {wavpath}")
            else:
                print(f"SKIPPED optional recording: {wavpath}", flush=True)
            continue
        key = str(keywords)
        if key not in spotters:
            spotters[key] = spotter(keywords)
        samples, sr = read_pcm_wave(wavpath)
        found = detect(spotters[key], samples, sr)
        ok = any(expected in x for x in found)
        row = dict(test=label, recording=str(wavpath), rate=sr, samples=int(samples.size),
                   expected=expected, recognized=found, matched=ok, required=required)
        results.append(row)
        print(f"{'PASS' if ok else 'FAIL' if required else 'NO_MATCH'}: {label}; expected={expected}; found={found}", flush=True)
        if required and not ok:
            failures.append(f"{label} was not recognized; expected {expected}, got {found}")

    Path("test-fixtures/kws/test-results.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    if failures:
        for failure in failures:
            print("ERROR: " + failure, file=sys.stderr)
        return 1
    print("PASS: upstream real-speech inference smoke tests passed.", flush=True)
    print("Note: synthesized speech tests are informative and do not establish device sensitivity.", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
