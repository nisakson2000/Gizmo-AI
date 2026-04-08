"""Mode system — behavioral presets that layer on top of the constitution.

Modes set behavioral posture (e.g., "think creatively", "lead with code")
without replacing the constitution or scoping tools. Each mode is a directory
under config/modes/{name}/ containing config.yaml and system.md.
"""

import logging
import os
import re
import shutil
import threading
from pathlib import Path
from typing import Optional

import yaml

logger = logging.getLogger(__name__)

MODES_DIR = Path(os.getenv("MODES_DIR", "/app/config/modes"))

BUILTIN_MODES = frozenset({"chat", "brainstorm", "coder", "research", "planner", "roleplay"})

_SAFE_NAME_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,48}[a-z0-9]$")

# Cache: mode_name → {name, label, description, icon, order, system_prompt}
_mode_cache: dict[str, dict] = {}
_cache_loaded = False
_cache_lock = threading.Lock()


def _load_modes():
    """Load all modes from the modes directory."""
    global _cache_loaded
    _mode_cache.clear()

    if not MODES_DIR.exists():
        logger.warning("Modes directory not found: %s", MODES_DIR)
        _cache_loaded = True
        return

    for mode_dir in sorted(MODES_DIR.iterdir()):
        if not mode_dir.is_dir():
            continue

        config_path = mode_dir / "config.yaml"
        system_path = mode_dir / "system.md"

        config = {}
        if config_path.exists():
            try:
                config = yaml.safe_load(config_path.read_text(encoding="utf-8")) or {}
            except Exception as e:
                logger.warning("Failed to parse config.yaml for mode '%s': %s", mode_dir.name, e)

        system_prompt = ""
        if system_path.exists():
            try:
                system_prompt = system_path.read_text(encoding="utf-8").strip()
            except Exception as e:
                logger.warning("Failed to read system.md for mode '%s': %s", mode_dir.name, e)

        mode = {
            "name": config.get("name", mode_dir.name),
            "label": config.get("label", mode_dir.name.capitalize()),
            "description": config.get("description", ""),
            "icon": config.get("icon", "chat"),
            "order": config.get("order", 99),
            "system_prompt": system_prompt,
        }

        _mode_cache[mode_dir.name] = mode

    _cache_loaded = True
    logger.info("Loaded %d modes from %s", len(_mode_cache), MODES_DIR)


def _ensure_loaded():
    """Load modes if not already loaded (double-checked locking)."""
    if _cache_loaded:
        return
    with _cache_lock:
        if not _cache_loaded:
            _load_modes()


def get_mode(name: str) -> Optional[dict]:
    """Get a mode by exact name."""
    _ensure_loaded()
    return _mode_cache.get(name)


def get_mode_prompt(name: str) -> str:
    """Get a mode's system prompt text. Returns empty string for unknown modes."""
    _ensure_loaded()
    mode = _mode_cache.get(name)
    return mode["system_prompt"] if mode else ""


def list_modes() -> list[dict]:
    """List all modes sorted by order field (metadata only, no system_prompt)."""
    _ensure_loaded()
    modes = [
        {
            "name": m["name"],
            "label": m["label"],
            "description": m["description"],
            "icon": m["icon"],
            "order": m["order"],
        }
        for m in _mode_cache.values()
    ]
    modes.sort(key=lambda m: m["order"])
    return modes


def save_mode_prompt(name: str, system_prompt: str) -> bool:
    """Update an existing mode's system.md content. Returns True on success."""
    _ensure_loaded()
    if name not in _mode_cache:
        return False

    mode_dir = MODES_DIR / name
    system_path = mode_dir / "system.md"
    try:
        system_path.write_text(system_prompt, encoding="utf-8")
        _mode_cache[name]["system_prompt"] = system_prompt.strip()
        logger.info("Saved mode prompt: %s", name)
        return True
    except Exception as e:
        logger.error("Failed to save mode prompt '%s': %s", name, e)
        return False


def save_mode_config(name: str, label: str | None = None,
                     description: str | None = None) -> bool:
    """Update an existing mode's config.yaml fields. Returns True on success."""
    _ensure_loaded()
    if name not in _mode_cache:
        return False

    mode_dir = MODES_DIR / name
    config_path = mode_dir / "config.yaml"

    mode = _mode_cache[name]
    config = {
        "name": mode["name"],
        "label": label if label is not None else mode["label"],
        "description": description if description is not None else mode["description"],
        "icon": mode["icon"],
        "order": mode["order"],
    }

    try:
        config_path.write_text(yaml.dump(config, default_flow_style=False), encoding="utf-8")
        mode["label"] = config["label"]
        mode["description"] = config["description"]
        logger.info("Saved mode config: %s", name)
        return True
    except Exception as e:
        logger.error("Failed to save mode config '%s': %s", name, e)
        return False


def create_mode(name: str, label: str, description: str,
                system_prompt: str) -> Optional[dict]:
    """Create a new custom mode. Returns the mode dict or None on failure."""
    _ensure_loaded()

    if not _SAFE_NAME_RE.match(name):
        logger.warning("Invalid mode name: %s", name)
        return None

    if name in _mode_cache:
        logger.warning("Mode already exists: %s", name)
        return None

    mode_dir = MODES_DIR / name
    if not mode_dir.resolve().is_relative_to(MODES_DIR.resolve()):
        logger.warning("Path traversal attempt blocked: %s", name)
        return None

    try:
        mode_dir.mkdir(parents=True, exist_ok=True)

        order = max((m["order"] for m in _mode_cache.values()), default=5) + 1
        config = {
            "name": name,
            "label": label,
            "description": description,
            "icon": "custom",
            "order": order,
        }

        (mode_dir / "config.yaml").write_text(
            yaml.dump(config, default_flow_style=False), encoding="utf-8")
        (mode_dir / "system.md").write_text(system_prompt, encoding="utf-8")

        mode = {**config, "system_prompt": system_prompt.strip()}
        _mode_cache[name] = mode
        logger.info("Created mode: %s", name)
        return {k: v for k, v in mode.items() if k != "system_prompt"}

    except Exception as e:
        logger.error("Failed to create mode '%s': %s", name, e)
        return None


def delete_mode(name: str) -> bool:
    """Delete a custom mode. Built-in modes cannot be deleted."""
    _ensure_loaded()

    if name in BUILTIN_MODES:
        logger.warning("Cannot delete built-in mode: %s", name)
        return False

    if name not in _mode_cache:
        return False

    mode_dir = MODES_DIR / name
    if not mode_dir.resolve().is_relative_to(MODES_DIR.resolve()):
        return False

    try:
        shutil.rmtree(mode_dir)
        del _mode_cache[name]
        logger.info("Deleted mode: %s", name)
        return True
    except Exception as e:
        logger.error("Failed to delete mode '%s': %s", name, e)
        return False


def reload_modes():
    """Force-reload all modes from disk."""
    with _cache_lock:
        global _cache_loaded
        _cache_loaded = False
        _load_modes()
