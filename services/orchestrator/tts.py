"""faster-qwen3-tts proxy — batch and streaming text-to-speech."""

import asyncio
import json
import os
import httpx
import base64
import logging
from typing import AsyncGenerator, Optional

import websockets

TTS_HOST = os.getenv("TTS_HOST", "gizmo-tts")
TTS_PORT = os.getenv("TTS_PORT", "8400")
TTS_URL = f"http://{TTS_HOST}:{TTS_PORT}/v1/audio/speech"
TTS_WS_URL = f"ws://{TTS_HOST}:{TTS_PORT}/v1/audio/stream"
TTS_EMBED_URL = f"http://{TTS_HOST}:{TTS_PORT}/v1/audio/embedding"

logger = logging.getLogger(__name__)


async def synthesize(
    text: str,
    voice: str = "default",
    voice_clone_data_url: Optional[str] = None,
    voice_reference_text: str = "",
    speed: float = 1.0,
    language: str = "Auto",
) -> Optional[bytes]:
    """Generate speech from text via faster-qwen3-tts (batch mode).

    Returns audio bytes or None on failure.
    If voice_clone_data_url is provided (base64 data URL), use it for voice cloning.
    """
    payload = {
        "model": "qwen3-tts",
        "input": text,
        "voice": voice,
        "response_format": "wav",
        "speed": speed,
        "language": language,
    }

    if voice_clone_data_url:
        # Extract raw base64 from data URL (strip "data:audio/wav;base64," prefix)
        if ";base64," in voice_clone_data_url:
            raw_b64 = voice_clone_data_url.split(";base64,", 1)[1]
        else:
            raw_b64 = voice_clone_data_url
        payload["voice_reference"] = raw_b64
        if voice_reference_text:
            payload["voice_reference_text"] = voice_reference_text

    for attempt in range(2):
        try:
            async with httpx.AsyncClient(timeout=180.0) as client:
                resp = await client.post(TTS_URL, json=payload)
                resp.raise_for_status()
                return resp.content
        except httpx.ConnectError:
            logger.error("TTS server not reachable at %s", TTS_URL)
            return None
        except httpx.HTTPStatusError as e:
            if attempt == 0:
                logger.warning("TTS returned %s, retrying in 3s (model may be reloading)", e.response.status_code)
                await asyncio.sleep(3)
                continue
            logger.error("TTS error after retry: %s %s", e.response.status_code, e.response.text[:200])
            return None
        except Exception as e:
            logger.error("TTS error: %s", e)
            return None
    return None


async def stream_tts_sentence(
    text: str,
    voice_id: Optional[str] = None,
    voice_clone_data_url: Optional[str] = None,
    speed: float = 1.0,
    language: str = "Auto",
) -> AsyncGenerator[tuple[bytes, int], None]:
    """Stream TTS audio chunks for a single sentence via WebSocket.

    Connects to TTS server, sends config, yields (pcm_bytes, sample_rate) tuples.
    """
    config: dict = {"text": text, "language": language, "speed": speed}

    if voice_id:
        config["voice_id"] = voice_id
    elif voice_clone_data_url:
        if ";base64," in voice_clone_data_url:
            config["voice_reference"] = voice_clone_data_url.split(";base64,", 1)[1]
        else:
            config["voice_reference"] = voice_clone_data_url

    try:
        async with websockets.connect(TTS_WS_URL, close_timeout=5) as ws:
            await ws.send(json.dumps(config))

            pending_meta = None
            async for msg in ws:
                if isinstance(msg, str):
                    data = json.loads(msg)
                    if data.get("done"):
                        return
                    if data.get("error"):
                        logger.error("TTS stream error: %s", data["error"])
                        return
                    pending_meta = data
                elif isinstance(msg, bytes) and pending_meta:
                    yield (msg, pending_meta.get("sample_rate", 24000))
                    pending_meta = None
    except websockets.exceptions.ConnectionClosed:
        logger.warning("TTS WebSocket closed unexpectedly")
    except Exception as e:
        logger.error("TTS streaming error: %s", e)


async def extract_voice_embedding(voice_id: str) -> bool:
    """Ask TTS server to extract and save a speaker embedding for a voice."""
    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            resp = await client.post(TTS_EMBED_URL, json={"voice_id": voice_id})
            if resp.status_code == 200:
                logger.info("Speaker embedding extracted for voice %s", voice_id)
                return True
            logger.warning("Embedding extraction failed: %s", resp.text[:200])
    except Exception as e:
        logger.error("Embedding extraction error for %s: %s", voice_id, e)
    return False


async def check_health() -> bool:
    """Check if TTS server is available."""
    try:
        url = f"http://{TTS_HOST}:{TTS_PORT}/health"
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(url)
            return resp.status_code == 200
    except Exception:
        return False
