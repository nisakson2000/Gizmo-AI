"""Kokoro TTS proxy — OpenAI-compatible text-to-speech."""

import os
import httpx
from typing import Optional

KOKORO_HOST = os.getenv("KOKORO_HOST", "gizmo-kokoro")
KOKORO_PORT = os.getenv("KOKORO_PORT", "8880")
KOKORO_URL = f"http://{KOKORO_HOST}:{KOKORO_PORT}/v1/audio/speech"

DEFAULT_VOICE = "af_heart"
DEFAULT_MODEL = "kokoro"


async def synthesize(
    text: str,
    voice: str = DEFAULT_VOICE,
    response_format: str = "mp3",
) -> Optional[bytes]:
    """Generate speech from text via Kokoro TTS.

    Returns audio bytes or None on failure.
    """
    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            resp = await client.post(KOKORO_URL, json={
                "model": DEFAULT_MODEL,
                "input": text,
                "voice": voice,
                "response_format": response_format,
            })
            resp.raise_for_status()
            return resp.content
    except httpx.ConnectError:
        return None
    except Exception:
        return None


async def check_health() -> bool:
    """Check if Kokoro TTS is available."""
    try:
        url = f"http://{KOKORO_HOST}:{KOKORO_PORT}/health"
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(url)
            return resp.status_code == 200
    except Exception:
        return False
