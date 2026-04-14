"""Shared prompt file loader with mtime-based caching."""

import os
from pathlib import Path

_cache: dict[str, tuple[str, float]] = {}


def load_prompt(path: Path, fallback: str = "") -> str:
    """Load a prompt file, stripping # comment lines. Cached by mtime."""
    if not path.exists():
        return fallback
    mtime = os.path.getmtime(path)
    key = str(path)
    cached = _cache.get(key)
    if cached and cached[1] == mtime:
        return cached[0]
    text = path.read_text(encoding="utf-8")
    lines = [line for line in text.splitlines() if not line.strip().startswith("#")]
    result = "\n".join(lines).strip()
    _cache[key] = (result, mtime)
    return result
