import importlib.util
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Callable, Dict, Optional

import numpy as np
import soundfile as sf
from scipy.signal import butter, filtfilt


def is_demucs_installed() -> bool:
    return importlib.util.find_spec("demucs") is not None


def is_ffmpeg_installed() -> bool:
    return shutil.which("ffmpeg") is not None


def ensure_wav_input(input_path: Path, work_dir: Path) -> Path:
    """Extracts audio from videos or converts compressed audio to 44.1kHz stereo WAV."""
    if input_path.suffix.lower() == ".wav":
        return input_path
    out_wav = work_dir / f"{input_path.stem}_input.wav"
    if is_ffmpeg_installed():
        cmd = [
            "ffmpeg",
            "-y",
            "-i",
            str(input_path),
            "-vn",
            "-ac",
            "2",
            "-ar",
            "44100",
            str(out_wav),
        ]
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return out_wav
    return input_path


def separate_with_demucs(
    input_audio: Path,
    output_dir: Path,
    mode: str = "4stems",
    progress_cb: Optional[Callable[[float, str], None]] = None,
) -> Dict[str, Path]:
    """
    Separates an audio track into stems using open-source Demucs (htdemucs / htdemucs_6s).
    Falls back to SciPy DSP separation if Demucs is not installed in the environment.
    """
    output_dir.mkdir(parents=True, exist_ok=True)
    wav_input = ensure_wav_input(input_audio, output_dir)

    if is_demucs_installed():
        model_name = "htdemucs_6s" if mode == "6stems" else "htdemucs"
        cmd = [sys.executable, "-m", "demucs", "-n", model_name, "-o", str(output_dir)]
        if mode == "2stems":
            cmd.extend(["--two-stems", "vocals"])
        cmd.append(str(wav_input))

        if progress_cb:
            progress_cb(0.25, f"Running Demucs ({model_name}) neural separation...")

        subprocess.run(cmd, check=True)

        stem_folder = output_dir / model_name / wav_input.stem
        stems: Dict[str, Path] = {}
        if stem_folder.exists():
            for wav_file in sorted(stem_folder.glob("*.wav")):
                stems[wav_file.stem.lower()] = wav_file
        if stems:
            if progress_cb:
                progress_cb(0.95, "Finalizing AI stems...")
            return stems

    # Fallback DSP separator when Demucs/PyTorch is unavailable
    if progress_cb:
        progress_cb(0.45, "Running DSP fallback stem separation...")
    return _fallback_dsp_separate(wav_input, output_dir, mode)


def _butter_filter(data: np.ndarray, sr: int, cutoff, btype: str, order: int = 4) -> np.ndarray:
    nyq = 0.5 * sr
    if isinstance(cutoff, tuple):
        norm = [c / nyq for c in cutoff]
    else:
        norm = cutoff / nyq
    b, a = butter(order, norm, btype=btype)
    return filtfilt(b, a, data, axis=0)


def _fallback_dsp_separate(wav_input: Path, output_dir: Path, mode: str) -> Dict[str, Path]:
    audio, sr = sf.read(str(wav_input), always_2d=True)
    if audio.shape[1] == 1:
        audio = np.repeat(audio, 2, axis=1)

    mid = 0.5 * (audio[:, 0] + audio[:, 1])
    side = 0.5 * (audio[:, 0] - audio[:, 1])

    vocals_mono = _butter_filter(mid, sr, (200.0, 4000.0), "bandpass")
    vocals = np.stack([vocals_mono, vocals_mono], axis=1)
    instrumental = np.clip(audio - 0.85 * vocals, -1.0, 1.0)
    bass_mono = _butter_filter(mid, sr, 220.0, "lowpass")
    bass = np.stack([bass_mono, bass_mono], axis=1)
    drums_hi = _butter_filter(mid, sr, 4500.0, "highpass")
    drums = np.clip(np.stack([0.4 * bass_mono + drums_hi, 0.4 * bass_mono + drums_hi], axis=1), -1.0, 1.0)
    piano = _butter_filter(instrumental, sr, (250.0, 2800.0), "bandpass")
    guitar = _butter_filter(np.stack([side, -side], axis=1) + 0.5 * instrumental, sr, (800.0, 4200.0), "bandpass")
    other = np.clip(instrumental - 0.5 * bass - 0.3 * drums, -1.0, 1.0)

    stem_map = {
        "2stems": {"vocals": vocals, "no_vocals": instrumental},
        "4stems": {"vocals": vocals, "drums": drums, "bass": bass, "other": other},
        "6stems": {
            "vocals": vocals,
            "drums": drums,
            "bass": bass,
            "piano": piano,
            "guitar": guitar,
            "other": other,
        },
    }.get(mode, {"vocals": vocals, "drums": drums, "bass": bass, "other": other})

    results: Dict[str, Path] = {}
    for name, data in stem_map.items():
        path = output_dir / f"{name}.wav"
        sf.write(str(path), np.clip(data, -1.0, 1.0), sr)
        results[name] = path
    return results
