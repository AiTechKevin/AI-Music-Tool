import shutil
import subprocess
from pathlib import Path


def has_ffmpeg() -> bool:
    return shutil.which("ffmpeg") is not None


def change_speed_and_pitch(
    input_path: Path,
    output_path: Path,
    speed: float = 1.0,
    pitch_semitones: int = 0,
    sample_rate: int = 44100,
) -> Path:
    """Uses FFmpeg asetrate + atempo to shift pitch and playback speed independently."""
    if not has_ffmpeg():
        raise RuntimeError("FFmpeg is not installed on the server.")

    pitch_factor = 2.0 ** (pitch_semitones / 12.0)
    effective_tempo = max(0.5, min(2.0, speed / pitch_factor))
    filter_chain = (
        f"asetrate={sample_rate}*{pitch_factor:.5f},"
        f"aresample={sample_rate},"
        f"atempo={effective_tempo:.5f}"
    )

    cmd = [
        "ffmpeg",
        "-y",
        "-i",
        str(input_path),
        "-af",
        filter_chain,
        str(output_path),
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return output_path


def convert_export_format(input_path: Path, output_path: Path, fmt: str = "mp3") -> Path:
    if not has_ffmpeg():
        raise RuntimeError("FFmpeg is not installed on the server.")

    cmd = ["ffmpeg", "-y", "-i", str(input_path)]
    if fmt.lower() == "mp3":
        cmd.extend(["-b:a", "320k"])
    cmd.append(str(output_path))
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return output_path
