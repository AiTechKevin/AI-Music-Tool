from typing import Dict, List, Optional
from pydantic import BaseModel


class HealthResponse(BaseModel):
    status: str
    demucs_available: bool
    ffmpeg_available: bool


class JobCreatedResponse(BaseModel):
    job_id: str
    status: str


class JobStatusResponse(BaseModel):
    job_id: str
    status: str
    progress: float = 0.0
    message: str = ""
    stems: Dict[str, str] = {}
    output_url: Optional[str] = None
    error: Optional[str] = None


class InstrumentItem(BaseModel):
    name: str
    confidence: float


class AnalysisResponse(BaseModel):
    bpm: float
    key: str
    duration_sec: float
    instruments: List[InstrumentItem]
