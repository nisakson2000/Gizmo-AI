"""Gizmo-AI TTS Server — Qwen3-TTS voice synthesis with optional voice cloning."""

import asyncio
import base64
import hashlib
import io
import logging
import os
import re
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

MAX_CHUNK_CHARS = 200

# Global state
model = None
model_loaded = False
last_request_time = 0.0
unload_task = None

# Cache for voice clone prompts (speaker embeddings)
_clone_prompt_cache: dict[str, object] = {}


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
    _clone_prompt_cache.clear()
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


app = FastAPI(title="Gizmo-AI TTS", version="3.0.0", lifespan=lifespan)


@app.get("/health")
async def health():
    device = "cuda:0" if torch.cuda.is_available() else "cpu"
    return {
        "status": "ok" if model_loaded else "idle",
        "model": "Qwen3-TTS-12Hz-1.7B-Base",
        "device": device,
        "loaded": model_loaded,
        "cached_voices": len(_clone_prompt_cache),
    }


def _chunk_text(text: str) -> list[str]:
    """Split text into chunks at sentence boundaries, max ~200 chars each."""
    sentences = re.split(r'(?<=[.!?])\s+', text.strip())
    chunks = []
    current = ""
    for sentence in sentences:
        if len(current) + len(sentence) + 1 > MAX_CHUNK_CHARS and current:
            chunks.append(current.strip())
            current = sentence
        else:
            current = f"{current} {sentence}" if current else sentence
    if current.strip():
        chunks.append(current.strip())
    return chunks if chunks else [text]


def _get_clone_prompt(ref_audio_path: str, ref_text: str | None, x_vector_only: bool):
    """Get or create a cached voice clone prompt."""
    with open(ref_audio_path, "rb") as f:
        file_hash = hashlib.md5(f.read()).hexdigest()
    cache_key = f"{file_hash}:{ref_text or ''}:{x_vector_only}"

    if cache_key in _clone_prompt_cache:
        logger.info("Using cached voice clone prompt for %s", file_hash[:8])
        return _clone_prompt_cache[cache_key]

    prompt = model.create_voice_clone_prompt(
        ref_audio=ref_audio_path,
        ref_text=ref_text,
        x_vector_only_mode=x_vector_only,
    )
    _clone_prompt_cache[cache_key] = prompt
    logger.info("Cached voice clone prompt for %s (ICL=%s)", file_hash[:8], not x_vector_only)
    return prompt


def _generate_chunks(chunks: list[str], language: str, clone_prompt=None,
                     ref_audio: str | None = None, ref_text: str | None = None,
                     x_vector_only: bool = False) -> tuple[np.ndarray, int]:
    """Generate audio for text chunks and concatenate."""
    all_wavs = []
    sr = 24000  # fallback

    for chunk in chunks:
        if clone_prompt is not None:
            chunk_wavs, sr = model.generate_voice_clone(
                text=chunk,
                language=language,
                voice_clone_prompt=clone_prompt,
            )
        elif ref_audio:
            chunk_wavs, sr = model.generate_voice_clone(
                text=chunk,
                language=language,
                ref_audio=ref_audio,
                ref_text=ref_text,
                x_vector_only_mode=x_vector_only,
            )
        else:
            chunk_wavs, sr = model.generate_voice_clone(
                text=chunk,
                language=language,
                ref_audio=DEFAULT_REF_AUDIO,
                ref_text=DEFAULT_REF_TEXT,
                x_vector_only_mode=False,
            )
        all_wavs.append(chunk_wavs[0])

    final_wav = np.concatenate(all_wavs) if len(all_wavs) > 1 else all_wavs[0]
    return final_wav, sr


@app.post("/v1/audio/speech")
async def synthesize(request: Request):
    """OpenAI-compatible TTS endpoint.

    Body JSON:
        model: str (ignored, always qwen3-tts)
        input: str — text to synthesize
        voice: str — "default" or voice name (currently unused)
        response_format: str — "wav" (default)
        speed: float — speech speed multiplier (0.5–2.0)
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
    speed = max(0.5, min(float(body.get("speed", 1.0)), 2.0))

    # Ensure model is loaded
    if not model_loaded:
        load_model()

    try:
        chunks = _chunk_text(text) if len(text) > MAX_CHUNK_CHARS else [text]
        logger.info("Generating TTS: %d chars, %d chunks, speed=%.1f, lang=%s",
                     len(text), len(chunks), speed, language)

        if voice_ref:
            # Voice cloning mode — single call (no chunking to avoid per-chunk warmup artifacts)
            ref_audio_bytes = base64.b64decode(voice_ref)
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
                tmp.write(ref_audio_bytes)
                tmp_path = tmp.name

            try:
                wavs, sr = model.generate_voice_clone(
                    text=text,
                    language=language,
                    ref_audio=tmp_path,
                    ref_text=None,
                    x_vector_only_mode=True,
                )
                final_wav = wavs[0]
            finally:
                os.unlink(tmp_path)
        else:
            # Default voice mode — chunking is safe (no cloning artifacts)
            final_wav, sr = _generate_chunks(chunks, language)

    except Exception as e:
        logger.error("TTS generation error: %s", e, exc_info=True)
        return JSONResponse(status_code=500, content={"error": str(e)})

    # Apply speed adjustment via resampling
    if abs(speed - 1.0) > 0.05:
        try:
            import scipy.signal
            original_len = len(final_wav)
            new_len = int(original_len / speed)
            final_wav = scipy.signal.resample(final_wav, new_len).astype(np.float32)
            logger.info("Applied speed %.1fx: %d → %d samples", speed, original_len, new_len)
        except ImportError:
            logger.warning("scipy not available, speed control disabled")

    # Convert to WAV bytes
    buf = io.BytesIO()
    sf.write(buf, final_wav, sr, format="WAV")
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
