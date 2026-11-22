from pathlib import Path

import librosa
import numpy as np
import soundfile as sf


def replace_stem_instrument(
    input_stem: Path,
    output_path: Path,
    target_instrument: str = "Piano",
) -> Path:
    """
    Tracks fundamental pitch contour (f0) and amplitude envelope of an isolated stem
    using Librosa pYIN, then resynthesizes the melody using the target instrument timbre.
    """
    y, sr = librosa.load(str(input_stem), sr=22050, mono=True, duration=120.0)
    hop_length = 512

    f0, voiced_flag, _ = librosa.pyin(
        y,
        fmin=librosa.note_to_hz("C2"),
        fmax=librosa.note_to_hz("C6"),
        sr=sr,
        hop_length=hop_length,
    )
    rms = librosa.feature.rms(y=y, hop_length=hop_length)[0]

    # Interpolate pitch and amplitude envelope to sample resolution
    n_samples = len(y)
    frame_times = librosa.frames_to_samples(np.arange(len(rms)), hop_length=hop_length)
    sample_idx = np.arange(n_samples)

    valid_f0 = np.nan_to_num(f0, nan=0.0)
    f0_samples = np.interp(sample_idx, frame_times[: len(valid_f0)], valid_f0)
    amp_samples = np.interp(sample_idx, frame_times, rms)

    phase = 2.0 * np.pi * np.cumsum(f0_samples) / sr

    inst = target_instrument.lower()
    if "piano" in inst:
        sig = 0.60 * np.sin(phase) + 0.25 * np.sin(2 * phase) + 0.12 * np.sin(3 * phase)
    elif "bass" in inst:
        sig = 0.78 * np.sin(0.5 * phase) + 0.22 * np.sin(phase)
    elif "guitar" in inst:
        sig = 0.50 * np.sin(phase) + 0.30 * np.sin(2 * phase) + 0.15 * np.sin(4 * phase)
    elif "strings" in inst:
        sig = 0.45 * np.sin(phase) + 0.30 * np.sin(1.5 * phase) + 0.25 * np.sin(2 * phase)
    else:
        sig = 0.55 * np.sin(phase) + 0.35 * np.sin(2 * phase)

    voiced_mask = (f0_samples > 30.0).astype(np.float32)
    out = sig * amp_samples * voiced_mask * 3.2
    peak = float(np.max(np.abs(out)) + 1e-6)
    out = np.clip((out / peak) * 0.9, -1.0, 1.0)
    stereo = np.stack([out, out], axis=1)
    sf.write(str(output_path), stereo, sr)
    return output_path
