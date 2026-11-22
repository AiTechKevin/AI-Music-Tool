@echo off
cd /d "%~dp0\.."
uvicorn backend.api.main:app --host 0.0.0.0 --port 8000
pause
