"""Pattern library — Fabric-inspired cognitive templates for structured LLM output."""

import logging
import os
import re
import threading
from pathlib import Path
from typing import Optional

import yaml

logger = logging.getLogger(__name__)

PATTERNS_DIR = Path(os.getenv("PATTERNS_DIR", "/app/config/patterns"))

# Cache: pattern_name → {name, description, keywords, tools, system_prompt}
_pattern_cache: dict[str, dict] = {}
_cache_loaded = False
_cache_lock = threading.Lock()


def _load_patterns():
    """Load all patterns from the patterns directory."""
    global _cache_loaded
    _pattern_cache.clear()

    if not PATTERNS_DIR.exists():
        logger.warning("Patterns directory not found: %s", PATTERNS_DIR)
        _cache_loaded = True
        return

    for pattern_dir in sorted(PATTERNS_DIR.iterdir()):
        if not pattern_dir.is_dir():
            continue

        config_path = pattern_dir / "config.yaml"
        system_path = pattern_dir / "system.md"

        if not system_path.exists():
            logger.warning("Pattern '%s' missing system.md, skipping", pattern_dir.name)
            continue

        # Load config (optional — defaults if missing)
        config = {}
        if config_path.exists():
            try:
                config = yaml.safe_load(config_path.read_text(encoding="utf-8")) or {}
            except Exception as e:
                logger.warning("Failed to parse config.yaml for '%s': %s", pattern_dir.name, e)

        # Load system prompt
        try:
            system_prompt = system_path.read_text(encoding="utf-8").strip()
        except Exception as e:
            logger.warning("Failed to read system.md for '%s': %s", pattern_dir.name, e)
            continue

        pattern = {
            "name": config.get("name", pattern_dir.name),
            "description": config.get("description", ""),
            "keywords": [k.lower() for k in config.get("keywords", [])],
            "tools": config.get("tools", []),  # empty = use default tools
            "system_prompt": system_prompt,
        }

        _pattern_cache[pattern_dir.name] = pattern

    _cache_loaded = True
    logger.info("Loaded %d patterns from %s", len(_pattern_cache), PATTERNS_DIR)


def _ensure_loaded():
    """Load patterns if not already loaded (double-checked locking)."""
    if _cache_loaded:
        return
    with _cache_lock:
        if not _cache_loaded:
            _load_patterns()


def get_pattern(name: str) -> Optional[dict]:
    """Get a pattern by exact name."""
    _ensure_loaded()
    return _pattern_cache.get(name)


def match_pattern(user_message: str) -> tuple[Optional[dict], str]:
    """Find the best matching pattern for a user message via keyword matching.
    Returns (pattern_dict_or_None, cleaned_message). The cleaned message has
    the [pattern:name] prefix stripped if it was used for explicit invocation.
    """
    _ensure_loaded()

    msg_lower = user_message.lower()

    # Check for explicit pattern invocation: [pattern:name] prefix
    if msg_lower.startswith("[pattern:"):
        end = msg_lower.index("]") if "]" in msg_lower else -1
        if end > 0:
            pattern_name = msg_lower[9:end].strip()
            cleaned = user_message[end + 1:].strip()
            return _pattern_cache.get(pattern_name), cleaned or user_message

    # Keyword matching — word-boundary regex, longest match wins
    best_match = None
    best_length = 0

    for pattern in _pattern_cache.values():
        for keyword in pattern["keywords"]:
            if len(keyword) > best_length and re.search(r'\b' + re.escape(keyword) + r'\b', msg_lower):
                best_match = pattern
                best_length = len(keyword)

    return best_match, user_message


def list_patterns() -> list[dict]:
    """List all available patterns (name + description only)."""
    _ensure_loaded()
    return [
        {"name": p["name"], "description": p["description"]}
        for p in _pattern_cache.values()
    ]


def reload_patterns():
    """Force-reload all patterns from disk."""
    with _cache_lock:
        global _cache_loaded
        _cache_loaded = False
        _load_patterns()
