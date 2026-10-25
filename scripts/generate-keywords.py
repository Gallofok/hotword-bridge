#!/usr/bin/env python3
"""Generate sherpa-onnx KWS token sequences with the model's own BPE tokenizer.

'HEY CHAT GPT' uses three spoken words; label is the UI-facing 'Hey ChatGPT'.
You can tune keyword aliases and thresholds after on-device testing.
"""
from pathlib import Path
import sys
import sentencepiece as spm

model_path, token_path, keyword_path = map(Path, sys.argv[1:4])
sp = spm.SentencePieceProcessor(model_file=str(model_path))
known = {line.rsplit(maxsplit=1)[0] for line in token_path.read_text().splitlines() if line.strip()}
phrases = [
    ("HEY CHAT GPT", "HEY_CHATGPT"),
    ("HEY CHAT G P T", "HEY_CHATGPT_LETTERS"),
]
results = []
for phrase, alias in phrases:
    pieces = sp.encode(phrase, out_type=str)
    if not pieces or any(p not in known or p == "<unk>" for p in pieces):
        raise RuntimeError(f"Unsupported keyword tokens for {phrase!r}: {pieces}")
    results.append(" ".join([*pieces, "@"+alias]))
keyword_path.write_text("\n".join(results)+"\n", encoding="utf-8")
print("Wrote keywords:")
print(keyword_path.read_text())
