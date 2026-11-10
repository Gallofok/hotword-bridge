"""Run a script with small compatibility patches for libraries that openWakeWord's training code expects in older versions.

usage: python compat_launch.py SCRIPT [args...]
torchaudio >= 2.9 dropped `info` and moved `load` to torchcodec; torch-audiomentations still calls both. Use soundfile instead.
"""
import runpy, sys
import soundfile as sf
import torch, torchaudio


class _Info:
    def __init__(self, i):
        self.sample_rate, self.num_frames, self.num_channels = i.samplerate, i.frames, i.channels


def _info(path, *a, **k):
    return _Info(sf.info(str(path)))


def _load(path, frame_offset=0, num_frames=-1, *a, **k):
    data, sr = sf.read(str(path), start=int(frame_offset), frames=int(num_frames), dtype="float32", always_2d=True)
    return torch.from_numpy(data.T.copy()), sr


torchaudio.info = _info
torchaudio.load = _load
sys.argv = sys.argv[1:]
runpy.run_path(sys.argv[0], run_name="__main__")
