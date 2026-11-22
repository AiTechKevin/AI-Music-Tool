from pathlib import Path
from typing import Dict

import numpy as np
import soundfile as sf

KEY_OFFSETS: Dict[str, int] = {
    "C": 0, "C#": 1, "D": 2, "Eb": 3, "E": 4, "F": 5,
    "F#": 6, "G": 7, "Ab": 8, "A": 9, "Bb": 10, "B": 11,
}


def _midi_to_freq(midi: int) -> float:
    return 440.0 * (2.0 ** ((midi - 69) / 12.0))


def generate_instrumental_wav(
    output_path: Path,
    genre: str = "Pop",
    key: str = "C",
    bpm: int = 110,
    duration_sec: int = 16,
    sr: int = 44100,
) -> Path:
    """Generates a multi-instrument chord, bass, and drum backing arrangement."""
    n = sr * max(4, min(duration_sec, 60))
    t = np.arange(n, dtype=np.float64) / sr
    beat_dur = 60.0 / max(60, min(bpm, 180))
    bar_dur = beat_dur * 4.0

    root = 60 + KEY_OFFSETS.get(key.upper(), 0)
    if genre.lower() == "lo-fi":
        chords = [
            [root, root + 4, root + 7, root + 11],
            [root + 9, root + 12, root + 16, root + 19],
            [root + 2, root + 5, root + 9, root + 12],
            [root + 7, root + 11, root + 14, root + 17],
        ]
    elif genre.lower() == "edm":
        chords = [
            [root, root + 3, root + 7],
            [root + 8, root + 12, root + 15],
            [root + 3, root + 7, root + 10],
            [root + 10, root + 14, root + 17],
        ]
    else:
        chords = [
            [root, root + 4, root + 7],
            [root + 7, root + 11, root + 14],
            [root + 9, root + 12, root + 16],
            [root + 5, root + 9, root + 12],
        ]

    pad = np.zeros(n, dtype=np.float64)
    bass = np.zeros(n, dtype=np.float64)
    drums = np.zeros(n, dtype=np.float64)

    bar_indices = (t / bar_dur).astype(int) % len(chords)
    bar_pos = np.mod(t, bar_dur)
    beat_pos = np.mod(t, beat_dur)
    beat_indices = (t / beat_dur).astype(int)

    for c_idx, notes in enumerate(chords):
        mask = bar_indices == c_idx
        env = np.exp(-bar_pos[mask] * 1.1) * (1.0 - np.exp(-bar_pos[mask] * 35.0))
        chord_sig = np.zeros_like(env)
        for midi_note in notes:
            f = _midi_to_freq(midi_note)
            chord_sig += 0.6 * np.sin(2 * np.pi * f * t[mask]) + 0.2 * np.sin(4 * np.pi * f * t[mask])
        pad[mask] = (chord_sig / len(notes)) * env * 0.38

        bf = _midi_to_freq(notes[0] - 12)
        b_env = np.exp(-beat_pos[mask] * 2.5)
        bass[mask] = np.sin(2 * np.pi * bf * t[mask]) * b_env * 0.36

    # Kick on even beats
    kick_mask = (beat_indices % 2 == 0) & (beat_pos < 0.16)
    kf = 50.0 + 95.0 * np.exp(-beat_pos[kick_mask] * 35.0)
    drums[kick_mask] += np.sin(2 * np.pi * kf * beat_pos[kick_mask]) * np.exp(-beat_pos[kick_mask] * 18.0) * 0.48

    # Snare on odd beats
    rng = np.random.default_rng(42)
    snare_mask = (beat_indices % 2 == 1) & (beat_pos < 0.14)
    drums[snare_mask] += rng.uniform(-1.0, 1.0, size=np.sum(snare_mask)) * np.exp(-beat_pos[snare_mask] * 25.0) * 0.22

    pan = np.sin(2 * np.pi * 0.35 * t) * 0.1
    left = np.clip(pad * (1.0 + pan) + bass + drums, -0.98, 0.98)
    right = np.clip(pad * (1.0 - pan) + bass + drums, -0.98, 0.98)

    stereo = np.stack([left, right], axis=1)
    sf.write(str(output_path), stereo, sr)
    return output_path
