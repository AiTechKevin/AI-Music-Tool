# AI Music Studio (Android + Hybrid AI Backend)

An open-source, free Android AI Music Production Studio built with **Kotlin + Jetpack Compose + Media3 ExoPlayer** on Android and **Python + FastAPI + Demucs + Librosa + FFmpeg** on the hybrid AI backend.

## Features
- **Vocal Remover & Vocal Isolation** (2-stem, 4-stem, and 6-stem separation)
- **Drum, Bass, Piano, Guitar & Custom Stem Separation** (Demucs `htdemucs_6s` via Hybrid Backend + instant On-Device DSP fallback mode)
- **Multi-Track Mixing Studio** with synchronized playback, real-time waveform display, per-stem Volume, Mute, Solo, and Instrument Removal
- **Real-Time Speed & Pitch Control** (`0.5x–2.0x` tempo, `-12` to `+12` semitones) powered by Media3 ExoPlayer
- **Instrument Detection, Key & BPM Analysis** using Librosa chroma and onset analysis
- **Instrument Replacement & AI Arrangement Generator** (built-in synthesizer + backend layering)
- **Audio Enhancement** (noise reduction, vocal clarity, bass boost, loudness normalization via FFmpeg)
- **Video-to-Audio Extraction** (extracts audio tracks directly from MP4/MKV/MOV videos on Android)
- **Export Engine** (mix active stems to WAV locally on Android, export individual stems, or export MP3/WAV via backend, saved directly to `Downloads/AI-Music-Studio/`)
- **Automated APK Builds** via GitHub Actions (`.github/workflows/android-build.yml`)

---

## Project Structure

```text
AI-Music-Studio/
├── android/
│   ├── app/src/main/java/com/aimusicstudio/
│   │   ├── MainActivity.kt
│   │   ├── audio/        # On-device DSP separator, WAV codec, Video extractor, AI arrangement synth
│   │   ├── player/       # Synchronized Multi-track ExoPlayer engine (speed, pitch, mute/solo)
│   │   ├── editor/       # Studio ViewModel & reactive state management
│   │   ├── mixer/        # Multi-stem PCM audio mixer with soft-clipping limiter
│   │   ├── export/       # WAV exporter, MediaStore Downloads saver & Share intent
│   │   ├── data/         # Models, Local Project Repository, Hybrid FastAPI Client
│   │   └── ui/           # Jetpack Compose Material 3 screens (Home, AI Separation, Studio, AI Tools, My Projects)
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── backend/
│   ├── api/                    # FastAPI app, REST endpoints, async background job manager
│   ├── separation/             # Demucs (htdemucs / htdemucs_6s) AI stem separation engine
│   ├── instrument_detection/   # Librosa BPM, musical key & instrument activity analyzer
│   ├── audio_processing/       # FFmpeg enhancer, pitch/speed, instrument replacer, AI backing generator
│   └── models/                 # Pretrained model cache directory
├── assets/                     # Vector artwork
├── .github/workflows/
│   └── android-build.yml       # Builds installable Android APK automatically on GitHub
├── requirements.txt
└── LICENSE
```

---

## Step 1: Build the Installable APK Automatically on GitHub (No Android Studio Needed)

1. Create a new repository on GitHub (e.g., `AI-Music-Studio`).
2. Extract this ZIP and upload all files to the repository (maintaining the folder structure, including `.github/workflows/android-build.yml`).
3. Go to the **Actions** tab in your GitHub repository.
4. The **Build Android APK** workflow will run automatically on push (or click **Run workflow** manually).
5. When the job completes (~3–5 minutes), scroll down to **Artifacts** and download **`AI-Music-Studio-Debug-APK`**.
6. Transfer `app-debug.apk` to your Android phone and install it (enable *Install unknown apps* if prompted).

---

## Step 2: Run the Free Hybrid AI Backend (Optional for Studio-Grade Demucs AI)

The Android app works immediately in **On-Device Quick Mode** (no server needed). For studio-grade neural stem separation (Demucs `htdemucs_6s` / 6-stem AI), run the included Python backend on your PC or a free GPU notebook (e.g., Google Colab + Cloudflare Tunnel/ngrok):

### Local PC Setup (Windows / Linux / macOS)
1. Install **Python 3.10+** and **FFmpeg** (ensure `ffmpeg` is in your system PATH).
2. Install dependencies:
   ```bash
   python -m venv .venv
   # Windows:
   .venv\Scripts\activate
   # Linux/macOS:
   source .venv/bin/activate

   pip install -r requirements.txt
   ```
3. Start the server:
   ```bash
   uvicorn backend.api.main:app --host 0.0.0.0 --port 8000
   ```
   Or double-click `backend/run_server.bat` (Windows) / run `bash backend/run_server.sh` (Linux/macOS).
4. Open the Android app -> tap the **Server / Hybrid Mode** badge on the Home screen -> enter your PC's local IP address (e.g., `http://192.168.1.10:8000`) and tap **Test Connection**.

### Docker Setup
```bash
cd backend
docker compose up --build
```
