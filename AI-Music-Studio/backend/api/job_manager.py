import threading
import uuid
from dataclasses import dataclass, field
from typing import Dict, Optional


@dataclass
class JobState:
    job_id: str
    status: str = "queued"
    progress: float = 0.0
    message: str = "Queued"
    stems: Dict[str, str] = field(default_factory=dict)
    output_url: Optional[str] = None
    error: Optional[str] = None


class JobManager:
    def __init__(self) -> None:
        self._jobs: Dict[str, JobState] = {}
        self._lock = threading.Lock()

    def create_job(self) -> JobState:
        job_id = uuid.uuid4().hex[:12]
        job = JobState(job_id=job_id)
        with self._lock:
            self._jobs[job_id] = job
        return job

    def get_job(self, job_id: str) -> Optional[JobState]:
        with self._lock:
            return self._jobs.get(job_id)

    def update_job(
        self,
        job_id: str,
        *,
        status: Optional[str] = None,
        progress: Optional[float] = None,
        message: Optional[str] = None,
        stems: Optional[Dict[str, str]] = None,
        output_url: Optional[str] = None,
        error: Optional[str] = None,
    ) -> None:
        with self._lock:
            job = self._jobs.get(job_id)
            if not job:
                return
            if status is not None:
                job.status = status
            if progress is not None:
                job.progress = progress
            if message is not None:
                job.message = message
            if stems is not None:
                job.stems = stems
            if output_url is not None:
                job.output_url = output_url
            if error is not None:
                job.error = error


job_manager = JobManager()
