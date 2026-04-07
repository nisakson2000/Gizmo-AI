"""Session-level semantic recall using fastembed embeddings stored in SQLite.

Embeds conversation turns and retrieves the most relevant earlier messages
when a conversation grows long enough that the sliding window drops older turns.
"""

import logging
import sqlite3
import threading
from pathlib import Path

import numpy as np

logger = logging.getLogger(__name__)

DB_PATH = Path("/app/memory/conversations.db")

_embedder = None
_embedder_lock = threading.Lock()


def _get_embedder():
    """Lazy-load the fastembed embedding model (first call downloads ~33MB)."""
    global _embedder
    if _embedder is not None:
        return _embedder
    with _embedder_lock:
        if _embedder is None:
            from fastembed import TextEmbedding
            _embedder = TextEmbedding("BAAI/bge-small-en-v1.5")
            logger.info("Embedding model loaded: BAAI/bge-small-en-v1.5")
    return _embedder


def embed_text(text: str) -> bytes:
    """Embed text into a 384-dim float32 vector, returned as raw bytes."""
    embedder = _get_embedder()
    vector = list(embedder.embed([text]))[0]
    return np.asarray(vector, dtype=np.float32).tobytes()


def get_query_embedding(text: str) -> bytes | None:
    """Embed a query for smart windowing. Returns None if embedding fails."""
    try:
        return embed_text(text[:2000])
    except Exception:
        return None


def get_stored_embeddings(conversation_id: str) -> dict[int, bytes]:
    """Load all stored embeddings for a conversation as {message_index: embedding_bytes}."""
    try:
        conn = sqlite3.connect(str(DB_PATH))
        try:
            rows = conn.execute(
                "SELECT message_index, embedding FROM session_embeddings WHERE conversation_id = ?",
                (conversation_id,),
            ).fetchall()
        finally:
            conn.close()
        return {r[0]: r[1] for r in rows}
    except Exception:
        return {}


def cosine_sim(a: np.ndarray, b: np.ndarray) -> float:
    dot = np.dot(a, b)
    norm = np.linalg.norm(a) * np.linalg.norm(b)
    return float(dot / norm) if norm > 0 else 0.0


def store_turn(conversation_id: str, message_index: int, role: str, content: str):
    """Embed and store a conversation turn. Safe to call from a background thread."""
    try:
        embedding = embed_text(content[:2000])
        conn = sqlite3.connect(str(DB_PATH))
        try:
            conn.execute(
                """INSERT OR REPLACE INTO session_embeddings
                   (conversation_id, message_index, role, content, embedding, created_at)
                   VALUES (?, ?, ?, ?, ?, datetime('now'))""",
                (conversation_id, message_index, role, content, embedding),
            )
            conn.commit()
        finally:
            conn.close()
        logger.debug("Stored embedding: conv=%s idx=%d role=%s", conversation_id, message_index, role)
    except Exception as e:
        logger.debug("store_turn failed: %s", e)


def retrieve_relevant(conversation_id: str, query: str, top_k: int = 5,
                      exclude_recent: int = 10,
                      query_embedding: bytes | None = None) -> list[dict]:
    """Retrieve the most semantically relevant earlier turns from this conversation.

    Returns up to top_k results with similarity > 0.3, excluding the most recent
    `exclude_recent` turns (which are already in the sliding window).
    If query_embedding is provided, skips re-embedding the query text.
    """
    try:
        if query_embedding:
            query_vec = np.frombuffer(query_embedding, dtype=np.float32)
        else:
            query_vec = np.frombuffer(embed_text(query), dtype=np.float32)

        conn = sqlite3.connect(str(DB_PATH))
        conn.row_factory = sqlite3.Row
        try:
            rows = conn.execute(
                """SELECT message_index, role, content, embedding
                   FROM session_embeddings
                   WHERE conversation_id = ?
                   ORDER BY message_index""",
                (conversation_id,),
            ).fetchall()
        finally:
            conn.close()

        if len(rows) <= exclude_recent:
            return []

        # Exclude the most recent turns — they're in the sliding window
        candidates = rows[:-exclude_recent]

        scored = []
        for row in candidates:
            stored_vec = np.frombuffer(row["embedding"], dtype=np.float32)
            sim = cosine_sim(query_vec, stored_vec)
            if sim > 0.3:
                scored.append({
                    "message_index": row["message_index"],
                    "role": row["role"],
                    "content": row["content"],
                    "similarity": sim,
                })

        scored.sort(key=lambda x: x["similarity"], reverse=True)
        return scored[:top_k]

    except Exception as e:
        logger.debug("retrieve_relevant failed: %s", e)
        return []


def format_recalled(turns: list[dict]) -> str:
    """Format retrieved turns as an XML block for system prompt injection."""
    if not turns:
        return ""

    lines = [
        "<session-recall>",
        "Relevant earlier messages from this conversation (retrieved by semantic similarity",
        "because they may be relevant to the current question):",
        "",
    ]
    for t in turns:
        content_preview = t["content"][:600]
        lines.append(f"[Turn {t['message_index']}] {t['role'].title()}: {content_preview}")

    lines.append("")
    lines.append("Use this recalled context to answer questions about earlier parts of the conversation.")
    lines.append("If the user asks about something not found in these recalled messages or the recent")
    lines.append("conversation history, say you don't have that context available rather than guessing.")
    lines.append("</session-recall>")

    return "\n".join(lines)
