from pathlib import Path
from typing import Dict, List

import librosa
import numpy as np

KEY_NAMES = ["C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"]

# Krumhansl-Schmuckler major/minor profiles
MAJOR_PROFILE = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
MINOR_PROFILE = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])


def estimate_musical_key(y: np.ndarray, sr: int) -> str:
    chroma = librosa.feature.chroma_cqt(y=y, sr=sr)
    chroma_mean = chroma.mean(axis=1)
    best_score = -1e9
    best_key = "C Major"

    for i, name in enumerate(KEY_NAMES):
        maj_corr = float(np.corrcoef(chroma_mean, np.roll(MAJOR_PROFILE, i))[0, 1])
        min_corr = float(np.corrcoef(chroma_mean, np.roll(MINOR_PROFILE, i))[0, 1])
        if maj_corr > best_score:
            best_score = maj_corr
            best_key = f"{name} Major"
        if min_corr > best_score:
            best_score = min_corr
            best_key = f"{name} Minor"
    return best_key


def analyze_audio_file(audio_path: Path) -> Dict:
    y, sr = librosa.load(str(audio_path), sr=22050, mono=True, duration=90.0)
    duration_sec = float(librosa.get_duration(y=y, sr=sr))

    tempo, _ = librosa.beat.beat_track(y=y, sr=sr)
    bpm = round(float(np.atleast_1d(tempo)[0]), 1)
    musical_key = estimate_musical_key(y, sr)

    # Harmonic/Percussive separation + spectral energy heuristic for instrument detection
    y_harm, y_perc = librosa.effects.hpss(y)
    S = np.abs(librosa.stft(y)) ** 2
    freqs = librosa.fft_frequencies(sr=sr)
    total_e = float(np.sum(S) + 1e-9)

    bass_e = float(np.sum(S[(freqs >= 30) & (freqs < 220), :])) / total_e
    vocal_e = float(np.sum(S[(freqs >= 220) & (freqs < 3200), :])) / total_e
    piano_e = float(np.sum(S[(freqs >= 260) & (freqs < 2200), :])) / total_e
    guitar_e = float(np.sum(S[(freqs >= 600) & (freqs < 4200), :])) / total_e
    perc_ratio = float(np.mean(y_perc ** 2) / (np.mean(y_harm ** 2) + np.mean(y_perc ** 2) + 1e-9))

    instruments: List[Dict] = [
        {"name": "Vocals", "confidence": round(min(0.98, max(0.15, vocal_e * 1.45)), 2)},
        {"name": "Drums / Percussion", "confidence": round(min(0.98, max(0.15, perc_ratio * 2.1)), 2)},
        {"name": "Bass", "confidence": round(min(0.98, max(0.12, bass_e * 2.4)), 2)},
        {"name": "Piano / Keys", "confidence": round(min(0.95, max(0.10, piano_e * 1.25)), 2)},
        {"name": "Guitar / Strings", "confidence": round(min(0.95, max(0.10, guitar_e * 1.15)), 2)},
    ]
    instruments.sort(key=lambda x: x["confidence"], reverse=True)

    return {
        "bpm": bpm,
        "key": musical_key,
        "duration_sec": round(duration_sec, 2),
        "instruments": instruments,
    }
