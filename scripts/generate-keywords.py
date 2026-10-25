#!/usr/bin/env python3
"""Create BPE keyword lines for sherpa-onnx using the packaged model tokenizer.

The HELLO WORLD calibration phrase is intentionally common English and does NOT
open ChatGPT; if it triggers while the desired phrase does not, the microphone
and decoder are working and the problem is keyword matching/sensitivity.
"""
from pathlib import Path
import sys
import sentencepiece as spm

model_path, token_path, keyword_path = map(Path, sys.argv[1:4])
sp = spm.SentencePieceProcessor(model_file=str(model_path))
known = {
    line.rsplit(maxsplit=1)[0]
    for line in token_path.read_text(encoding="utf-8").splitlines()
    if line.strip()
}
# score boosts this phrase during search; lowering the threshold increases
# sensitivity at the expense of false alarms. Calibrator is less boosted.
phrases = [
    ("HEY CHAT GPT", "HEY_CHATGPT", 2.5, 0.12),
    ("HEY CHAT G P T", "HEY_CHATGPT_LETTERS", 2.5, 0.12),
    ("HELLO WORLD", "HELLO_WORLD_TEST", 1.5, 0.20),
]
lines = []
for phrase, alias, boost, threshold in phrases:
    pieces = sp.encode(phrase, out_type=str)
    if not pieces or any(p not in known or p == "<unk>" for p in pieces):
        raise RuntimeError(f"Unsupported keyword tokens for {phrase!r}: {pieces}")
    # Keyword syntax: token token :boost #threshold @alias
    lines.append(" ".join([*pieces, f":{boost:.1f}", f"#{threshold:.2f}", "@"+alias]))
keyword_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
print("Generated keyword definitions:")
print(keyword_path.read_text(encoding="utf-8"))
