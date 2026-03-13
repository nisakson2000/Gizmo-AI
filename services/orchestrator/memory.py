"""File-based memory system with path traversal protection."""

import os
import re
from pathlib import Path
from typing import Optional

MEMORY_ROOT = Path("/app/memory")
SUBDIRS = ["facts", "conversations", "notes"]
MAX_INJECT = 5
MAX_INJECT_CHARS = 300


def _safe_path(filename: str, subdir: str = "facts") -> Optional[Path]:
    """Resolve filename to a safe path within the memory directory."""
    if subdir not in SUBDIRS:
        return None
    safe_name = re.sub(r"[^a-zA-Z0-9_\-.]", "", filename)
    if not safe_name or safe_name.startswith("."):
        return None
    target = (MEMORY_ROOT / subdir / safe_name).resolve()
    if not str(target).startswith(str(MEMORY_ROOT.resolve())):
        return None
    return target


def write_memory(filename: str, content: str, subdir: str = "facts") -> str:
    """Write content to a memory file."""
    path = _safe_path(filename, subdir)
    if path is None:
        return "Error: invalid filename or path"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return f"Saved to {subdir}/{filename}"


def read_memory(filename: str, subdir: str = "facts") -> str:
    """Read content from a memory file."""
    path = _safe_path(filename, subdir)
    if path is None:
        return "Error: invalid filename or path"
    if not path.exists():
        return f"Memory file '{filename}' not found in {subdir}/"
    return path.read_text(encoding="utf-8")


def list_memories(subdir: Optional[str] = None) -> list[dict]:
    """List all memory files, optionally filtered by subdirectory."""
    results = []
    dirs = [subdir] if subdir and subdir in SUBDIRS else SUBDIRS
    for d in dirs:
        dir_path = MEMORY_ROOT / d
        if not dir_path.exists():
            continue
        for f in sorted(dir_path.iterdir()):
            if f.is_file() and not f.name.startswith("."):
                results.append({
                    "filename": f.name,
                    "subdir": d,
                    "size": f.stat().st_size,
                })
    return results


def delete_memory(filename: str, subdir: str = "facts") -> str:
    """Delete a memory file."""
    path = _safe_path(filename, subdir)
    if path is None:
        return "Error: invalid filename or path"
    if not path.exists():
        return f"Memory file '{filename}' not found in {subdir}/"
    path.unlink()
    return f"Deleted {subdir}/{filename}"


def get_relevant_memories(query: str) -> list[str]:
    """Simple keyword-based memory retrieval for system prompt injection.

    v1: basic substring matching. v2 will use ChromaDB for semantic search.
    """
    query_lower = query.lower()
    keywords = set(query_lower.split())
    scored = []

    for subdir in SUBDIRS:
        dir_path = MEMORY_ROOT / subdir
        if not dir_path.exists():
            continue
        for f in dir_path.iterdir():
            if not f.is_file() or f.name.startswith("."):
                continue
            try:
                content = f.read_text(encoding="utf-8")
            except Exception:
                continue
            content_lower = content.lower()
            score = sum(1 for kw in keywords if kw in content_lower)
            name_lower = f.name.lower().replace(".txt", "").replace("_", " ")
            score += sum(1 for kw in keywords if kw in name_lower)
            if score > 0:
                snippet = content[:MAX_INJECT_CHARS]
                scored.append((score, f"{subdir}/{f.name}: {snippet}"))

    scored.sort(key=lambda x: x[0], reverse=True)
    return [s[1] for s in scored[:MAX_INJECT]]
