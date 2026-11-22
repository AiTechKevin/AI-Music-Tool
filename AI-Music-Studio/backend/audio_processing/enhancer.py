import subprocess
from pathlib import Path

import numpy as np
import soundfile as sf

from backend.audio_processing.ffmpeg_processor import has_ffmpeg


def enhance_audio(input_path: Path, output_path: Path, preset: str = "studio_master") -> Path:
    """Applies FFmpeg EQ, high-pass noise cleanup, and EBU R128 loudness normalization."""
    if has_ffmpeg():
        filter_map = {
            "studio_master": "highpass=f=40,equalizer=f=3000:t=q:w=1.2:g=2.5,loudnorm=I=-14:TP=-1.0:LRA=11",
            "vocal_clarity": "highpass=f=85,afftdn=nf=-25,equalizer=f=2800:t=q:w=1.0:g=4.0,loudnorm=I=-14:TP=-1.0",
            "bass_boost": "equalizer=f=90:t=q:w=1.0:g=5.5,loudnorm=I=-14:TP=-1.0",
        }
        af = filter_map.get(preset, filter_map["studio_master"])
        cmd = [
            "ffmpeg",
            "-y",
            "-i",
            str(input_path),
            "-af",
            af,
            str(output_path),
        ]
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return output_path

    # Pure numpy fallback normalization
    audio, sr = sf.read(str(input_path), always_2d=True)
    peak = float(np.max(np.abs(audio)) + 1e-6)
    normalized = np.clip((audio / peak) * 0.95, -1.0, 1.0)
    sf.write(str(output_path), normalized, sr)
    return output_path
