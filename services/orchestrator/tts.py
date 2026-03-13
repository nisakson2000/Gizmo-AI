"""Qwen3-TTS proxy — OpenAI-compatible text-to-speech."""

import os
import httpx
import base64
import logging
from typing import Optional

TTS_HOST = os.getenv("TTS_HOST", "gizmo-tts")
TTS_PORT = os.getenv("TTS_PORT", "8400")
TTS_URL = f"http://{TTS_HOST}:{TTS_PORT}/v1/audio/speech"

logger = logging.getLogger(__name__)


async def synthesize(
    text: str,
    voice: str = "default",
    voice_reference_path: Optional[str] = None,
) -> Optional[bytes]:
    """Generate speech from text via Qwen3-TTS.

    Returns audio bytes or None on failure.
    If voice_reference_path is provided, use it for voice cloning.
    """
    payload = {
        "model": "qwen3-tts",
        "input": text,
        "voice": voice,
        "response_format": "wav",
        "speed": 1.0,
    }

    if voice_reference_path and os.path.exists(voice_reference_path):
        with open(voice_reference_path, "rb") as f:
            payload["voice_reference"] = base64.b64encode(f.read()).decode()

    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            resp = await client.post(TTS_URL, json=payload)
            resp.raise_for_status()
            return resp.content
    except httpx.ConnectError:
        logger.error("Qwen3-TTS not reachable at %s", TTS_URL)
        return None
    except httpx.HTTPStatusError as e:
        logger.error("TTS error: %s %s", e.response.status_code, e.response.text[:200])
        return None
    except Exception as e:
        logger.error("TTS error: %s", e)
        return None


async def check_health() -> bool:
    """Check if Qwen3-TTS is available."""
    try:
        url = f"http://{TTS_HOST}:{TTS_PORT}/health"
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(url)
            return resp.status_code == 200
    except Exception:
        return False
