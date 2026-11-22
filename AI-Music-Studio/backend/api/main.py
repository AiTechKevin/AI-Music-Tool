import shutil
import uuid
from pathlib import Path
from typing import Dict

from fastapi import BackgroundTasks, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

from backend.api.job_manager import job_manager
from backend.api.schemas import (
    AnalysisResponse,
    HealthResponse,
    InstrumentItem,
    JobCreatedResponse,
    JobStatusResponse,
)
from backend.audio_processing.ai_generator import generate_instrumental_wav
from backend.audio_processing.enhancer import enhance_audio
from backend.audio_processing.ffmpeg_processor import change_speed_and_pitch, convert_export_format
from backend.audio_processing.instrument_replacer import replace_stem_instrument
from backend.instrument_detection.analyzer import analyze_audio_file
from backend.separation.demucs_engine import (
    ensure_wav_input,
    is_demucs_installed,
    is_ffmpeg_installed,
    separate_with_demucs,
)

WORKSPACE_DIR = Path(__file__).resolve().parent.parent / "workspace"
WORKSPACE_DIR.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="AI Music Studio Hybrid Backend", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


def _save_upload(upload: UploadFile, dest_dir: Path) -> Path:
    dest_dir.mkdir(parents=True, exist_ok=True)
    safe_name = (upload.filename or "audio.wav").replace(" ", "_")
    dest_path = dest_dir / safe_name
    with dest_path.open("wb") as buffer:
        shutil.copyfileobj(upload.file, buffer)
    return dest_path


def _run_separation_job(job_id: str, input_path: Path, job_dir: Path, mode: str) -> None:
    try:
        job_manager.update_job(job_id, status="processing", progress=0.1, message="Preparing audio...")
        stems = separate_with_demucs(
            input_audio=input_path,
            output_dir=job_dir / "stems",
            mode=mode,
            progress_cb=lambda p, msg: job_manager.update_job(job_id, progress=p, message=msg),
        )
        stem_urls: Dict[str, str] = {}
        for stem_name, stem_path in stems.items():
            # Copy to flat job_dir/out for clean download URLs
            out_dir = job_dir / "out"
            out_dir.mkdir(exist_ok=True)
            final_file = out_dir / f"{stem_name}.wav"
            if stem_path.resolve() != final_file.resolve():
                shutil.copy2(stem_path, final_file)
            stem_urls[stem_name] = f"/api/files/{job_id}/{stem_name}.wav"

        job_manager.update_job(
            job_id,
            status="completed",
            progress=1.0,
            message="Separation completed",
            stems=stem_urls,
        )
    except Exception as exc:
        job_manager.update_job(job_id, status="failed", error=str(exc), message="Separation failed")


@app.get("/api/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(
        status="ok",
        demucs_available=is_demucs_installed(),
        ffmpeg_available=is_ffmpeg_installed(),
    )


@app.post("/api/separate", response_model=JobCreatedResponse)
async def separate_endpoint(
    background_tasks: BackgroundTasks,
    file: UploadFile = File(...),
    mode: str = Form("4stems"),
) -> JobCreatedResponse:
    job = job_manager.create_job()
    job_dir = WORKSPACE_DIR / job.job_id
    input_path = _save_upload(file, job_dir)
    background_tasks.add_task(_run_separation_job, job.job_id, input_path, job_dir, mode)
    return JobCreatedResponse(job_id=job.job_id, status=job.status)


@app.get("/api/jobs/{job_id}", response_model=JobStatusResponse)
def get_job_status(job_id: str) -> JobStatusResponse:
    job = job_manager.get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    return JobStatusResponse(
        job_id=job.job_id,
        status=job.status,
        progress=job.progress,
        message=job.message,
        stems=job.stems,
        output_url=job.output_url,
        error=job.error,
    )


@app.get("/api/files/{job_id}/{filename}")
def download_job_file(job_id: str, filename: str) -> FileResponse:
    file_path = WORKSPACE_DIR / job_id / "out" / filename
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="File not found")
    return FileResponse(str(file_path), media_type="audio/wav", filename=filename)


@app.post("/api/analyze", response_model=AnalysisResponse)
async def analyze_endpoint(file: UploadFile = File(...)) -> AnalysisResponse:
    req_dir = WORKSPACE_DIR / f"analyze_{uuid.uuid4().hex[:8]}"
    input_path = _save_upload(file, req_dir)
    wav_path = ensure_wav_input(input_path, req_dir)
    result = analyze_audio_file(wav_path)
    return AnalysisResponse(
        bpm=result["bpm"],
        key=result["key"],
        duration_sec=result["duration_sec"],
        instruments=[InstrumentItem(**item) for item in result["instruments"]],
    )


@app.post("/api/enhance")
async def enhance_endpoint(
    file: UploadFile = File(...),
    preset: str = Form("studio_master"),
) -> FileResponse:
    req_dir = WORKSPACE_DIR / f"enhance_{uuid.uuid4().hex[:8]}"
    input_path = _save_upload(file, req_dir)
    out_path = req_dir / f"enhanced_{preset}.wav"
    enhance_audio(input_path, out_path, preset=preset)
    return FileResponse(str(out_path), media_type="audio/wav", filename=out_path.name)


@app.post("/api/replace-instrument")
async def replace_instrument_endpoint(
    file: UploadFile = File(...),
    target_instrument: str = Form("Piano"),
) -> FileResponse:
    req_dir = WORKSPACE_DIR / f"replace_{uuid.uuid4().hex[:8]}"
    input_path = _save_upload(file, req_dir)
    wav_path = ensure_wav_input(input_path, req_dir)
    out_path = req_dir / f"replaced_{target_instrument.lower().replace(' ', '_')}.wav"
    replace_stem_instrument(wav_path, out_path, target_instrument=target_instrument)
    return FileResponse(str(out_path), media_type="audio/wav", filename=out_path.name)


@app.post("/api/speed-pitch")
async def speed_pitch_endpoint(
    file: UploadFile = File(...),
    speed: float = Form(1.0),
    pitch_semitones: int = Form(0),
) -> FileResponse:
    req_dir = WORKSPACE_DIR / f"sp_{uuid.uuid4().hex[:8]}"
    input_path = _save_upload(file, req_dir)
    out_path = req_dir / "transformed.wav"
    change_speed_and_pitch(input_path, out_path, speed=speed, pitch_semitones=pitch_semitones)
    return FileResponse(str(out_path), media_type="audio/wav", filename=out_path.name)


@app.post("/api/export")
async def export_format_endpoint(
    file: UploadFile = File(...),
    fmt: str = Form("mp3"),
) -> FileResponse:
    req_dir = WORKSPACE_DIR / f"exp_{uuid.uuid4().hex[:8]}"
    input_path = _save_upload(file, req_dir)
    ext = "mp3" if fmt.lower() == "mp3" else "wav"
    out_path = req_dir / f"export.{ext}"
    convert_export_format(input_path, out_path, fmt=ext)
    media = "audio/mpeg" if ext == "mp3" else "audio/wav"
    return FileResponse(str(out_path), media_type=media, filename=out_path.name)


@app.post("/api/generate-music")
async def generate_music_endpoint(
    genre: str = "Pop",
    key: str = "C",
    bpm: int = 110,
    duration: int = 16,
) -> FileResponse:
    req_dir = WORKSPACE_DIR / f"gen_{uuid.uuid4().hex[:8]}"
    req_dir.mkdir(parents=True, exist_ok=True)
    out_path = req_dir / f"ai_{genre.lower()}_{key}_{bpm}bpm.wav"
    generate_instrumental_wav(out_path, genre=genre, key=key, bpm=bpm, duration_sec=duration)
    return FileResponse(str(out_path), media_type="audio/wav", filename=out_path.name)
