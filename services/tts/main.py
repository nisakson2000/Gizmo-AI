"""Gizmo-AI TTS Server — Qwen3-TTS voice synthesis with optional voice cloning."""

import asyncio
import base64
import io
import logging
import os
import tempfile
import time
from contextlib import asynccontextmanager

import numpy as np
import soundfile as sf
import torch
import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("gizmo-tts")

MODEL_DIR = os.getenv("TTS_MODEL_DIR", "/models/qwen3-tts/1.7B-Base")
IDLE_UNLOAD_SECONDS = int(os.getenv("TTS_IDLE_UNLOAD_SECONDS", "60"))
DEFAULT_REF_AUDIO = "/app/assets/default_voice.wav"
DEFAULT_REF_TEXT = "Hello, I am Gizmo, your local AI assistant."

# Global state
model = None
model_loaded = False
last_request_time = 0.0
unload_task = None


def load_model():
    """Load the Qwen3-TTS model onto GPU."""
    global model, model_loaded
    if model_loaded:
        return

    logger.info("Loading Qwen3-TTS model from %s ...", MODEL_DIR)
    start = time.time()

    from qwen_tts import Qwen3TTSModel

    device = "cuda:0" if torch.cuda.is_available() else "cpu"
    dtype = torch.bfloat16 if torch.cuda.is_available() else torch.float32

    model = Qwen3TTSModel.from_pretrained(
        MODEL_DIR,
        device_map=device,
        dtype=dtype,
    )

    elapsed = round(time.time() - start, 1)
    logger.info("Qwen3-TTS loaded on %s in %ss", device, elapsed)
    model_loaded = True


def unload_model():
    """Unload the model from VRAM."""
    global model, model_loaded
    if not model_loaded:
        return

    logger.info("Unloading Qwen3-TTS from VRAM (idle timeout)")
    del model
    model = None
    model_loaded = False
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
    logger.info("Qwen3-TTS unloaded")


async def idle_watcher():
    """Periodically check if the model should be unloaded."""
    global last_request_time
    while True:
        await asyncio.sleep(10)
        if model_loaded and IDLE_UNLOAD_SECONDS > 0:
            elapsed = time.time() - last_request_time
            if elapsed > IDLE_UNLOAD_SECONDS:
                unload_model()


@asynccontextmanager
async def lifespan(app: FastAPI):
    global unload_task
    load_model()
    unload_task = asyncio.create_task(idle_watcher())
    yield
    unload_task.cancel()
    unload_model()


app = FastAPI(title="Gizmo-AI TTS", version="2.0.0", lifespan=lifespan)


@app.get("/health")
async def health():
    device = "cuda:0" if torch.cuda.is_available() else "cpu"
    return {
        "status": "ok" if model_loaded else "idle",
        "model": "Qwen3-TTS-12Hz-1.7B-Base",
        "device": device,
        "loaded": model_loaded,
    }


@app.post("/v1/audio/speech")
async def synthesize(request: Request):
    """OpenAI-compatible TTS endpoint.

    Body JSON:
        model: str (ignored, always qwen3-tts)
        input: str — text to synthesize
        voice: str — "default" or voice name (currently unused)
        response_format: str — "wav" (default)
        speed: float — speech speed (currently unused)
        voice_reference: str — base64-encoded WAV for voice cloning (optional)
        voice_reference_text: str — transcript of the reference audio (optional, improves clone quality)
        language: str — language hint (default: "Auto")
    """
    global last_request_time
    last_request_time = time.time()

    try:
        body = await request.json()
    except Exception:
        return JSONResponse(status_code=400, content={"error": "Invalid JSON"})

    text = body.get("input", "")
    if not text:
        return JSONResponse(status_code=400, content={"error": "Missing 'input' field"})

    voice_ref = body.get("voice_reference")
    voice_ref_text = body.get("voice_reference_text", "")
    language = body.get("language", "Auto")

    # Ensure model is loaded
    if not model_loaded:
        load_model()

    try:
        if voice_ref:
            # Voice cloning mode
            ref_audio_bytes = base64.b64decode(voice_ref)
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
                tmp.write(ref_audio_bytes)
                tmp_path = tmp.name

            try:
                wavs, sr = model.generate_voice_clone(
                    text=text,
                    language=language,
                    ref_audio=tmp_path,
                    ref_text=voice_ref_text or None,
                    x_vector_only_mode=not bool(voice_ref_text),
                )
            finally:
                os.unlink(tmp_path)
        else:
            # Default voice mode — use bundled reference audio for voice cloning
            wavs, sr = model.generate_voice_clone(
                text=text,
                language=language,
                ref_audio=DEFAULT_REF_AUDIO,
                ref_text=DEFAULT_REF_TEXT,
                x_vector_only_mode=False,
            )

    except Exception as e:
        logger.error("TTS generation error: %s", e, exc_info=True)
        return JSONResponse(status_code=500, content={"error": str(e)})

    # Convert to WAV bytes
    buf = io.BytesIO()
    sf.write(buf, wavs[0], sr, format="WAV")
    buf.seek(0)

    return Response(content=buf.read(), media_type="audio/wav")


@app.post("/v1/audio/unload")
async def api_unload():
    """Unload model from VRAM."""
    unload_model()
    return {"status": "unloaded"}


@app.post("/v1/audio/load")
async def api_load():
    """Reload model into VRAM."""
    load_model()
    return {"status": "loaded"}


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8400, log_level="info")
