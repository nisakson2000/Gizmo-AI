"""Qwen3-TTS proxy — OpenAI-compatible text-to-speech."""

import asyncio
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
    voice_clone_data_url: Optional[str] = None,
) -> Optional[bytes]:
    """Generate speech from text via Qwen3-TTS.

    Returns audio bytes or None on failure.
    If voice_clone_data_url is provided (base64 data URL), use it for voice cloning.
    """
    payload = {
        "model": "qwen3-tts",
        "input": text,
        "voice": voice,
        "response_format": "wav",
        "speed": 1.0,
    }

    if voice_clone_data_url:
        # Extract raw base64 from data URL (strip "data:audio/wav;base64," prefix)
        if ";base64," in voice_clone_data_url:
            raw_b64 = voice_clone_data_url.split(";base64,", 1)[1]
        else:
            raw_b64 = voice_clone_data_url
        payload["voice_reference"] = raw_b64

    for attempt in range(2):
        try:
            async with httpx.AsyncClient(timeout=60.0) as client:
                resp = await client.post(TTS_URL, json=payload)
                resp.raise_for_status()
                return resp.content
        except httpx.ConnectError:
            logger.error("Qwen3-TTS not reachable at %s", TTS_URL)
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


async def check_health() -> bool:
    """Check if Qwen3-TTS is available."""
    try:
        url = f"http://{TTS_HOST}:{TTS_PORT}/health"
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(url)
            return resp.status_code == 200
    except Exception:
        return False
