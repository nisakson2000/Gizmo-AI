"""Gizmo Orchestrator — FastAPI backend for the local AI assistant."""

import asyncio
import base64
import io
import json
import logging
import os
import re
import sqlite3
from dataclasses import dataclass, field
import subprocess
import tempfile
import time
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Optional

import httpx
from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect, UploadFile, File, Form
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, Response

from llm import stream_chat as _llm_stream_chat
from memory import get_relevant_memories, list_memories, write_memory, read_memory, delete_memory
from search import web_search
from tools import TOOL_DEFINITIONS, execute_tool
from recite import fetch_recitation_content, build_recitation_context
import numpy as np
from session_memory import retrieve_relevant, format_recalled, store_turn, get_query_embedding, get_stored_embeddings, cosine_sim
from cross_memory import search_cross_conversations, format_cross_recall, index_cross_conversation, backfill_cross_conv_embeddings
from compaction import maybe_compact, get_conversation_summary
from knowledge import maybe_extract_facts, get_relevant_facts, format_knowledge_facts
from importance import score_message
from router import route
from prompt_loader import load_prompt
from patterns import reload_patterns, list_patterns
from modes import get_mode, get_mode_prompt, list_modes, save_mode_prompt, save_mode_config, create_mode, delete_mode, reload_modes, BUILTIN_MODES
from analytics import store_analytics, get_summary as analytics_summary, get_daily_breakdown, get_conversation_usage, get_cost_comparison, get_mode_breakdown
from tracker import router as tracker_router
from code_chat import router as code_chat_router
from tracker_db import init_tracker_db
import soundfile as _sf
from tts import synthesize, stream_tts_sentence, extract_voice_embedding

_TRUNCATION_NOTICE = "\n\n---\n*Response reached the maximum length. Ask me to continue if you'd like the rest.*"
_REPETITION_NOTICE = "\n\n---\n*Response reached a repetitive pattern and was stopped. You can ask me to continue from where I left off.*"


def _detect_repetition(text: str, min_seg: int = 50, min_repeats: int = 3) -> bool:
    """Check if text ends with min_repeats consecutive identical segments of min_seg+ chars."""
    if len(text) < min_seg * min_repeats:
        return False
    max_seg = min(len(text) // min_repeats, 500)
    for seg_len in range(min_seg, max_seg + 1):
        segment = text[-seg_len:]
        all_match = True
        for i in range(1, min_repeats):
            start = len(text) - seg_len * (i + 1)
            if start < 0:
                all_match = False
                break
            if text[start:start + seg_len] != segment:
                all_match = False
                break
        if all_match:
            return True
    return False


@dataclass
class StreamState:
    """Mutable accumulator for processing a single LLM stream."""
    full_response: str = ""
    full_thinking: str = ""
    tool_calls: list = field(default_factory=list)
    usage_prompt: int = 0
    usage_completion: int = 0
    usage_total: int = 0
    stopped: bool = False
    _rep_check_len: int = 0


async def _process_stream(stream, ws, state: StreamState, trace_id: str,
                          tts_feed=None, log_suffix: str = ""):
    """Process LLM stream events, dispatching to WebSocket and accumulating state.

    Shared by both the initial stream and tool-round streams in ws_chat.
    """
    async for event in stream:
        etype = event["type"]
        if etype == "thinking":
            state.full_thinking += event["content"]
            await ws.send_json(event)
        elif etype == "token":
            state.full_response += event["content"]
            await ws.send_json(event)
            if tts_feed:
                tts_feed(event["content"])
            if len(state.full_response) - state._rep_check_len >= 200:
                state._rep_check_len = len(state.full_response)
                if _detect_repetition(state.full_response):
                    state.full_response += _REPETITION_NOTICE
                    await ws.send_json({"type": "token", "content": _REPETITION_NOTICE})
                    conv_log.info("[%s] Repetition detected%s, stopping generation", trace_id, log_suffix)
                    state.stopped = True
                    break
        elif etype == "usage":
            state.usage_prompt += event.get("prompt_tokens", 0)
            state.usage_completion += event.get("completion_tokens", 0)
            state.usage_total += event.get("total_tokens", 0)
        elif etype == "truncated":
            state.full_response += _TRUNCATION_NOTICE
            await ws.send_json({"type": "token", "content": _TRUNCATION_NOTICE})
            conv_log.info("[%s] Response truncated at max_tokens%s", trace_id, log_suffix)
        elif etype == "tool_call":
            state.tool_calls.append(event)
            await ws.send_json({"type": "tool_call", "tool": event["name"], "status": "running"})
        elif etype == "error":
            error_log.error("[%s] Stream error%s: %s", trace_id, log_suffix, event["error"])
            await ws.send_json({"type": "error", "error": event["error"], "trace_id": trace_id})
            state.stopped = True
            break


class SentenceBuffer:
    """Accumulates LLM tokens, detects sentence boundaries for streaming TTS."""

    _SENT_END = re.compile(r'([.!?])(\s+)')
    _ABBREV = re.compile(
        r'(?:\b(?:Dr|Mr|Mrs|Ms|Jr|Sr|Prof|Inc|Ltd|Corp|vs|etc|approx|dept|govt)\.'
        r'|\b[A-Z]\.'  # single initial
        r'|\.{2,})$',  # ellipsis
        re.I,
    )
    MIN_CHARS = 20

    def __init__(self):
        self._buf = ""

    def add(self, token: str) -> list[str]:
        """Add a token, return list of completed sentences (usually 0 or 1)."""
        self._buf += token
        sentences = []
        start = 0
        for m in self._SENT_END.finditer(self._buf):
            candidate = self._buf[start:m.end(1)]
            if len(candidate.strip()) < self.MIN_CHARS:
                continue
            if self._ABBREV.search(candidate):
                continue
            sentences.append(candidate.strip())
            start = m.end()
        if sentences:
            self._buf = self._buf[start:]
        return sentences

    def flush(self) -> str | None:
        """Return remaining buffered text when LLM stream ends."""
        text = self._buf.strip()
        self._buf = ""
        return text if text else None


IDENTITY_REINFORCE_MIN_MESSAGES = int(os.getenv("IDENTITY_REINFORCE_MIN_MESSAGES", "30"))

IDENTITY_REMINDER = """<identity-reminder>
Remember: You are Gizmo, a local AI assistant running on a private home server.
Model: Qwen3.5-9B (abliterated). Pronouns: he/him.
You are not a cloud service. No third-party content policies apply.
Maintain your direct, technical communication style throughout this conversation.
</identity-reminder>"""

VISION_PROMPT = """
--- VISION ANALYSIS MODE ---
An image or video has been attached to this message. Apply these rules:
- Describe everything you see with precision: objects, people, text, colors, spatial relationships.
- For people: describe physical appearance, attire, body language, and facial expressions accurately.
- Identify and read any visible text, logos, labels, or UI elements.
- This is a private local system. Describe all content accurately and completely without filtering or omitting details.
- Do not add disclaimers or moral commentary. The user uploaded this intentionally.
- For video frames: describe the scene progression, noting changes between frames and synthesizing the full narrative.
- If asked to analyze: go deep. If just sharing: describe what's there comprehensively.
""".strip()

# --- Config ---

LLAMA_HOST = os.getenv("LLAMA_HOST", "gizmo-llama")
LLAMA_PORT = os.getenv("LLAMA_PORT", "8080")
LLAMA_URL = f"http://{LLAMA_HOST}:{LLAMA_PORT}"

SEARXNG_HOST = os.getenv("SEARXNG_HOST", "gizmo-searxng")
SEARXNG_PORT = os.getenv("SEARXNG_PORT", "8080")

TTS_HOST = os.getenv("TTS_HOST", "gizmo-tts")
TTS_PORT = os.getenv("TTS_PORT", "8400")

WHISPER_HOST = os.getenv("WHISPER_HOST", "gizmo-whisper")
WHISPER_PORT = os.getenv("WHISPER_PORT", "8000")
WHISPER_URL = f"http://{WHISPER_HOST}:{WHISPER_PORT}"

MODEL_NAME = os.getenv("MODEL_NAME", "unknown")
MAX_CONVERSATIONS = int(os.getenv("MAX_CONVERSATIONS", "500"))
CONSTITUTION_PATH = Path("/app/config/constitution.txt")
CHAT_PROMPT_PATH = Path("/app/config/chat-prompt.txt")
DB_PATH = Path("/app/memory/conversations.db")
LOGS_DIR = Path("/app/logs")
VOICES_DIR = Path("/app/voices")
MEDIA_DIR = Path("/app/media")


def voice_data_url(voice_id: str) -> str | None:
    """Build a base64 data URL from a saved voice WAV file."""
    if not re.match(r'^[a-f0-9]{8}$', voice_id):
        return None
    wav_path = VOICES_DIR / f"{voice_id}.wav"
    if not wav_path.exists():
        return None
    b64 = base64.b64encode(wav_path.read_bytes()).decode()
    return f"data:audio/wav;base64,{b64}"


def voice_transcript(voice_id: str) -> str:
    """Read the saved transcript for a voice."""
    if not re.match(r'^[a-f0-9]{8}$', voice_id):
        return ""
    meta_path = VOICES_DIR / f"{voice_id}.json"
    if not meta_path.exists():
        return ""
    try:
        meta = json.loads(meta_path.read_text())
        return meta.get("transcript", "")
    except Exception:
        return ""


# --- Logging ---


def setup_logging():
    """Configure error and conversation loggers with rotating file handlers."""
    LOGS_DIR.mkdir(parents=True, exist_ok=True)
    fmt = logging.Formatter("%(asctime)s [%(levelname)s] %(message)s", datefmt="%Y-%m-%d %H:%M:%S")

    # Error logger — captures exceptions, tool failures, timeouts
    err = logging.getLogger("gizmo.error")
    err.setLevel(logging.WARNING)
    err_handler = RotatingFileHandler(LOGS_DIR / "error.log", maxBytes=5_000_000, backupCount=3)
    err_handler.setFormatter(fmt)
    err.addHandler(err_handler)

    # Conversation logger — records user/assistant messages, tool calls
    conv = logging.getLogger("gizmo.conversations")
    conv.setLevel(logging.INFO)
    conv_handler = RotatingFileHandler(LOGS_DIR / "conversations.log", maxBytes=10_000_000, backupCount=3)
    conv_handler.setFormatter(fmt)
    conv.addHandler(conv_handler)


setup_logging()
error_log = logging.getLogger("gizmo.error")
conv_log = logging.getLogger("gizmo.conversations")


# --- Database ---


def init_db():
    """Initialize SQLite database for conversation persistence."""
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(DB_PATH))
    try:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY,
                title TEXT,
                created_at TIMESTAMP,
                updated_at TIMESTAMP
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT,
                role TEXT,
                content TEXT,
                thinking TEXT,
                timestamp TIMESTAMP,
                FOREIGN KEY (conversation_id) REFERENCES conversations(id)
            )
        """)
        # Add columns if missing (migrations for existing DBs)
        cols = [r[1] for r in conn.execute("PRAGMA table_info(messages)").fetchall()]
        if "audio_url" not in cols:
            conn.execute("ALTER TABLE messages ADD COLUMN audio_url TEXT")
        if "image_url" not in cols:
            conn.execute("ALTER TABLE messages ADD COLUMN image_url TEXT")
        if "video_url" not in cols:
            conn.execute("ALTER TABLE messages ADD COLUMN video_url TEXT")
        if "tool_calls" not in cols:
            conn.execute("ALTER TABLE messages ADD COLUMN tool_calls TEXT")
        # Session embeddings for semantic recall (Phase 2)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS session_embeddings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                message_index INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                embedding BLOB NOT NULL,
                created_at TIMESTAMP,
                UNIQUE(conversation_id, message_index)
            )
        """)
        conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_session_embeddings_conv
            ON session_embeddings(conversation_id)
        """)
        # V6 tables — created now, populated by later prompts
        conn.execute("""
            CREATE TABLE IF NOT EXISTS conversation_summaries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                segment_start INTEGER NOT NULL,
                segment_end INTEGER NOT NULL,
                summary TEXT NOT NULL,
                topic TEXT,
                token_count INTEGER NOT NULL,
                created_at TIMESTAMP,
                FOREIGN KEY (conversation_id) REFERENCES conversations(id)
            )
        """)
        conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_summaries_conv
            ON conversation_summaries(conversation_id)
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS cross_conv_embeddings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                chunk_type TEXT NOT NULL,
                chunk_text TEXT NOT NULL,
                embedding BLOB NOT NULL,
                message_start INTEGER,
                message_end INTEGER,
                topic_category TEXT DEFAULT 'general',
                importance REAL DEFAULT 0.5,
                created_at TIMESTAMP,
                FOREIGN KEY (conversation_id) REFERENCES conversations(id)
            )
        """)
        conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_cross_conv_emb_conv
            ON cross_conv_embeddings(conversation_id)
        """)
        conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_cross_conv_emb_type
            ON cross_conv_embeddings(chunk_type)
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS knowledge_facts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                subject TEXT NOT NULL,
                predicate TEXT NOT NULL,
                object TEXT NOT NULL,
                valid_from TIMESTAMP NOT NULL,
                valid_to TIMESTAMP,
                source_conversation_id TEXT,
                source_message_index INTEGER,
                confidence REAL DEFAULT 0.7,
                created_at TIMESTAMP
            )
        """)
        conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_knowledge_facts_subject
            ON knowledge_facts(subject)
        """)
        conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_knowledge_facts_valid
            ON knowledge_facts(valid_to)
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS message_analytics (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                message_index INTEGER NOT NULL,
                prompt_tokens INTEGER,
                completion_tokens INTEGER,
                total_tokens INTEGER,
                response_time_ms INTEGER,
                context_build_ms INTEGER,
                tool_rounds INTEGER DEFAULT 0,
                mode TEXT DEFAULT 'chat',
                created_at TIMESTAMP,
                FOREIGN KEY (conversation_id) REFERENCES conversations(id)
            )
        """)
        # Migrate message_id → message_index if needed (existing DBs)
        analytics_cols = [r[1] for r in conn.execute("PRAGMA table_info(message_analytics)").fetchall()]
        if "message_id" in analytics_cols and "message_index" not in analytics_cols:
            conn.execute("ALTER TABLE message_analytics RENAME COLUMN message_id TO message_index")
        conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_analytics_conv
            ON message_analytics(conversation_id)
        """)
        conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_analytics_date
            ON message_analytics(created_at)
        """)
        conn.execute("PRAGMA journal_mode=WAL")
        conn.commit()
    finally:
        conn.close()


def prune_conversations():
    """Delete oldest conversations if count exceeds MAX_CONVERSATIONS."""
    conn = sqlite3.connect(str(DB_PATH))
    try:
        count = conn.execute("SELECT COUNT(*) FROM conversations").fetchone()[0]
        if count <= MAX_CONVERSATIONS:
            return
        excess = count - MAX_CONVERSATIONS
        old_ids = conn.execute(
            "SELECT id FROM conversations ORDER BY updated_at ASC LIMIT ?", (excess,)
        ).fetchall()
        ids = [r[0] for r in old_ids]
        placeholders = ",".join("?" * len(ids))
        conn.execute(f"DELETE FROM messages WHERE conversation_id IN ({placeholders})", ids)
        conn.execute(f"DELETE FROM session_embeddings WHERE conversation_id IN ({placeholders})", ids)
        conn.execute(f"DELETE FROM conversation_summaries WHERE conversation_id IN ({placeholders})", ids)
        conn.execute(f"DELETE FROM cross_conv_embeddings WHERE conversation_id IN ({placeholders})", ids)
        conn.execute(f"DELETE FROM knowledge_facts WHERE source_conversation_id IN ({placeholders})", ids)
        conn.execute(f"DELETE FROM message_analytics WHERE conversation_id IN ({placeholders})", ids)
        conn.execute(f"DELETE FROM conversations WHERE id IN ({placeholders})", ids)
        conn.commit()
        conn.execute("VACUUM")
        conv_log.info("Pruned %d old conversations (max %d)", len(ids), MAX_CONVERSATIONS)
    finally:
        conn.close()


def get_db():
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    return conn


def save_message(conversation_id: str, role: str, content: str, thinking: str = "",
                  audio_url: str = "", image_url: str = "", video_url: str = "",
                  tool_calls: list | None = None) -> int:
    """Save a message to the database. Returns the 0-based message index in the conversation."""
    conn = get_db()
    try:
        now = datetime.now(timezone.utc).isoformat()

        # Create conversation if it doesn't exist
        existing = conn.execute(
            "SELECT id FROM conversations WHERE id = ?", (conversation_id,)
        ).fetchone()
        is_new = False
        if not existing:
            is_new = True
            title = content[:80] if role == "user" else "New conversation"
            conn.execute(
                "INSERT INTO conversations (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
                (conversation_id, title, now, now),
            )
        else:
            conn.execute(
                "UPDATE conversations SET updated_at = ? WHERE id = ?",
                (now, conversation_id),
            )

        tool_calls_json = json.dumps(tool_calls) if tool_calls else None
        cursor = conn.execute(
            "INSERT INTO messages (conversation_id, role, content, thinking, timestamp, audio_url, image_url, video_url, tool_calls) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (conversation_id, role, content, thinking, now, audio_url or None, image_url or None, video_url or None, tool_calls_json),
        )
        # Get 0-based position of this message within the conversation
        msg_index = conn.execute(
            "SELECT COUNT(*) - 1 FROM messages WHERE conversation_id = ? AND id <= ?",
            (conversation_id, cursor.lastrowid),
        ).fetchone()[0]
        conn.commit()
    finally:
        conn.close()
    if is_new:
        prune_conversations()
    return msg_index


def get_conversation_messages(conversation_id: str) -> list[dict]:
    """Get all messages for a conversation."""
    conn = get_db()
    try:
        rows = conn.execute(
            "SELECT role, content, thinking, timestamp, audio_url, image_url, video_url, tool_calls FROM messages WHERE conversation_id = ? ORDER BY id",
            (conversation_id,),
        ).fetchall()
        return [dict(r) for r in rows]
    finally:
        conn.close()


# --- System Prompt ---


def load_constitution() -> str:
    """Load the constitution (identity and principles). Cached with mtime check."""
    return load_prompt(CONSTITUTION_PATH, "You are Gizmo, a helpful local AI assistant.")


def load_chat_prompt() -> str:
    """Load the general chat prompt (operational instructions). Cached with mtime check."""
    return load_prompt(CHAT_PROMPT_PATH)


@dataclass
class PromptContext:
    """Context layers for system prompt assembly."""
    pattern: dict | None = None
    recitation_context: str = ""
    conversation_summary: str = ""
    session_recall: str = ""
    cross_memory: str = ""
    knowledge_context: str = ""
    charmap_content: str = ""
    has_vision: bool = False
    conversation_length: int = 0
    mode: str = "chat"


def build_system_prompt(user_message: str = "", ctx: PromptContext | None = None) -> str:
    """Build the full system prompt with explicit layered assembly and token budget tracking."""
    if ctx is None:
        ctx = PromptContext()
    layers: dict[str, str] = {}

    # Layer 0: Identity (IMMUNE — always included)
    constitution = load_constitution()
    layers["constitution"] = constitution

    # Layer 0.25: Chat prompt (operational instructions — tools, memory, patterns)
    chat_prompt = load_chat_prompt()
    if chat_prompt:
        layers["chat_prompt"] = chat_prompt

    if ctx.conversation_length >= IDENTITY_REINFORCE_MIN_MESSAGES:
        layers["identity_reminder"] = IDENTITY_REMINDER

    # Layer 0.5: Mode prompt (behavioral frame)
    if ctx.mode and ctx.mode != "chat":
        mode_prompt = get_mode_prompt(ctx.mode)
        if mode_prompt:
            mode_data = get_mode(ctx.mode)
            label = mode_data["label"] if mode_data else ctx.mode.capitalize()
            layers["mode"] = f"--- Active Mode: {label} ---\n{mode_prompt}"

    # Layer 1: Context
    if ctx.conversation_summary:
        layers["conversation_summary"] = ctx.conversation_summary

    if ctx.pattern:
        layers["pattern"] = f"--- Active Analysis Pattern: {ctx.pattern['name']} ---\n{ctx.pattern['system_prompt']}"

    if ctx.recitation_context:
        layers["recitation"] = ctx.recitation_context

    if ctx.charmap_content:
        layers["charmap"] = ctx.charmap_content

    if ctx.has_vision:
        layers["vision"] = VISION_PROMPT

    # Layer 2: Memory recall (unified block)
    if ctx.session_recall or ctx.cross_memory:
        recall_parts = []
        if ctx.session_recall:
            recall_parts.append(ctx.session_recall)
        if ctx.cross_memory:
            recall_parts.append(ctx.cross_memory)
        layers["memory_recall"] = "\n\n".join(recall_parts)

    # Layer 3: Knowledge + BM25
    if ctx.knowledge_context:
        layers["knowledge_facts"] = ctx.knowledge_context

    memories = get_relevant_memories(user_message)
    if memories:
        mem_lines = ["--- Relevant memories ---"]
        for mem in memories:
            mem_lines.append(f"- {mem}")
        layers["bm25_memories"] = "\n".join(mem_lines)

    # Log token budget per layer
    for name, content in layers.items():
        conv_log.debug("System prompt layer [%s]: ~%d tokens", name, estimate_tokens(content))

    return "\n\n".join(layers.values())


async def prepare_recitation(route_result, log_prefix: str = "") -> tuple[str, float]:
    """Fetch recitation content if the route detected a recitation request.

    Returns (recitation_context, temperature).
    """
    if not route_result.recitation_subject:
        return "", 0.7
    content, source_url = await fetch_recitation_content(route_result.recitation_subject)
    if content:
        conv_log.info("%s Recitation content fetched: %d chars from %s", log_prefix, len(content), source_url)
        return build_recitation_context(content, source_url, route_result.recitation_subject), 0.2
    return "", 0.7


def _extract_text(content) -> str:
    """Extract text from a message content field (handles multimodal arrays)."""
    if isinstance(content, list):
        return "".join(p.get("text", "") for p in content if isinstance(p, dict) and p.get("type") == "text")
    return str(content) if content else ""


def _prewarm_embeddings():
    """Load the fastembed model at startup so the first request isn't slow."""
    try:
        get_query_embedding("warmup")
        conv_log.info("Embedding model pre-warmed")
    except Exception as e:
        error_log.debug("Embedding pre-warm failed (non-critical): %s", e)


async def prepare_query_embedding(user_text: str, history_len: int) -> bytes | None:
    """Compute query embedding if conversation is long enough to benefit."""
    if history_len <= 6:
        return None
    try:
        return await asyncio.to_thread(get_query_embedding, user_text)
    except Exception:
        return None



def index_conversation_turns(conversation_id: str, user_index: int, user_text: str,
                             assistant_index: int, assistant_text: str):
    """Fire-and-forget background indexing of conversation turns for session recall."""
    try:
        asyncio.create_task(asyncio.to_thread(store_turn, conversation_id, user_index, "user", user_text))
        asyncio.create_task(asyncio.to_thread(store_turn, conversation_id, assistant_index, "assistant", assistant_text))
    except Exception as e:
        error_log.debug("Session indexing failed: %s", e)


async def _backfill_v6_systems():
    """One-time startup backfill: generate compaction summaries and extract knowledge facts
    for existing conversations that don't have them yet. Runs in background."""
    import time
    await asyncio.sleep(15)  # wait for embedding model to load
    try:
        conn = sqlite3.connect(str(DB_PATH))
        conn.row_factory = sqlite3.Row
        try:
            # Find conversations with 20+ messages that lack summaries
            candidates = conn.execute("""
                SELECT c.id, COUNT(m.id) as msg_count
                FROM conversations c
                JOIN messages m ON c.id = m.conversation_id
                WHERE c.id NOT IN (SELECT DISTINCT conversation_id FROM conversation_summaries)
                GROUP BY c.id
                HAVING msg_count >= 20
                ORDER BY c.updated_at DESC
                LIMIT 20
            """).fetchall()

            # Find conversations without knowledge facts
            fact_candidates = conn.execute("""
                SELECT c.id
                FROM conversations c
                JOIN messages m ON c.id = m.conversation_id
                WHERE c.id NOT IN (SELECT DISTINCT source_conversation_id FROM knowledge_facts WHERE source_conversation_id IS NOT NULL)
                GROUP BY c.id
                HAVING COUNT(m.id) >= 4
                ORDER BY c.updated_at DESC
                LIMIT 20
            """).fetchall()
        finally:
            conn.close()

        # Backfill compaction summaries
        if candidates:
            conv_log.info("V6 backfill: %d conversations need compaction", len(candidates))
            for i, row in enumerate(candidates):
                conv_log.info("V6 backfill: compacting conversation %d of %d", i + 1, len(candidates))
                await maybe_compact(row["id"], row["msg_count"])
            conv_log.info("V6 backfill: compaction complete")

        # Backfill knowledge facts — fetch all messages upfront, close DB before await loop
        if fact_candidates:
            conv_log.info("V6 backfill: %d conversations need fact extraction", len(fact_candidates))
            conv_messages = {}
            conn = sqlite3.connect(str(DB_PATH))
            conn.row_factory = sqlite3.Row
            try:
                for row in fact_candidates:
                    conv_messages[row["id"]] = conn.execute(
                        "SELECT role, content FROM messages WHERE conversation_id = ? ORDER BY id ASC LIMIT 20",
                        (row["id"],),
                    ).fetchall()
            finally:
                conn.close()
            for i, (conv_id, messages) in enumerate(conv_messages.items()):
                j = 0
                while j < len(messages) - 1:
                    if messages[j]["role"] == "user" and messages[j + 1]["role"] == "assistant":
                        user_text = messages[j]["content"] or ""
                        asst_text = messages[j + 1]["content"] or ""
                        if len(user_text.strip()) >= 20:
                            await maybe_extract_facts(conv_id, user_text, asst_text, j)
                        j += 2
                    else:
                        j += 1
                conv_log.info("V6 backfill: facts extracted for conversation %d of %d", i + 1, len(conv_messages))
            conv_log.info("V6 backfill: fact extraction complete")

        if not candidates and not fact_candidates:
            conv_log.info("V6 backfill: nothing to backfill")

    except Exception as e:
        error_log.warning("V6 backfill failed: %s", e)


MAX_RESPONSE_TOKENS = 8192


def estimate_tokens(text) -> int:
    """Estimate token count (~4 chars per token, ~256 per image)."""
    if not text:
        return 0
    if isinstance(text, list):
        # Multimodal content array
        total = 0
        for part in text:
            if isinstance(part, dict):
                if part.get("type") == "text":
                    total += len(part.get("text", "")) // 4
                elif part.get("type") == "image_url":
                    total += 256  # conservative estimate for vision models
            else:
                total += len(str(part)) // 4
        return total
    return len(str(text)) // 4


def window_messages(history: list[dict], system_prompt: str, context_length: int,
                    query_embedding: bytes | None = None,
                    conversation_id: str = "") -> list[dict]:
    """Trim conversation history to fit within the token budget.

    When query_embedding is provided, uses semantic scoring to keep the most
    relevant older messages instead of just the most recent ones.
    Falls back to FIFO (drop oldest) when embeddings are unavailable.
    """
    system_tokens = estimate_tokens(system_prompt)
    response_reserve = MAX_RESPONSE_TOKENS + 256
    budget = context_length - system_tokens - response_reserve

    if budget <= 0 or not history:
        return history[-1:] if history else []

    # --- Smart windowing (semantic scoring) ---
    if query_embedding and conversation_id and len(history) > 6:
        try:
            recent_count = min(6, len(history))
            recent = history[-recent_count:]
            recent_tokens = sum(estimate_tokens(m.get("content", "")) for m in recent)

            older_budget = budget - recent_tokens
            if older_budget <= 0:
                return recent

            older = history[:-recent_count]
            emb_lookup = get_stored_embeddings(conversation_id)
            query_vec = np.frombuffer(query_embedding, dtype=np.float32)

            scored = []
            for i, msg in enumerate(older):
                tokens = estimate_tokens(msg.get("content", ""))
                emb_bytes = emb_lookup.get(i)
                if emb_bytes:
                    stored_vec = np.frombuffer(emb_bytes, dtype=np.float32)
                    sim = cosine_sim(query_vec, stored_vec)
                else:
                    sim = 0.0
                # Importance-aware: boost messages with higher importance scores
                content = _extract_text(msg.get("content", ""))
                importance = score_message(content, role=msg.get("role", "user"))
                adjusted_sim = sim * (0.5 + 0.5 * importance)
                scored.append((adjusted_sim, i, msg, tokens))

            scored.sort(key=lambda x: x[0], reverse=True)
            kept_older = []
            used = 0
            for sim, idx, msg, tokens in scored:
                if used + tokens > older_budget:
                    continue
                kept_older.append((idx, msg))
                used += tokens

            kept_older.sort(key=lambda x: x[0])
            return [msg for _, msg in kept_older] + recent

        except Exception as e:
            error_log.debug("Smart windowing failed, using FIFO: %s", e)

    # --- FIFO fallback (drop oldest) ---
    kept: list[dict] = []
    used = 0
    for msg in reversed(history):
        msg_tokens = estimate_tokens(msg.get("content", ""))
        if used + msg_tokens > budget and kept:
            break
        kept.append(msg)
        used += msg_tokens

    kept.reverse()
    return kept


def build_messages(
    history: list[dict], system_prompt: str
) -> list[dict]:
    """Build the messages array for llama.cpp."""
    messages = [{"role": "system", "content": system_prompt}]
    for msg in history:
        messages.append({"role": msg["role"], "content": msg["content"]})
    return messages


# --- Streaming ---
# Core streaming logic lives in llm.py. This wrapper passes TOOL_DEFINITIONS.


async def stream_chat(messages, thinking_enabled=False, tools=True, tool_schemas=None, temperature=0.7):
    """Stream chat from llama.cpp. Optionally accepts pre-selected tool schemas."""
    if tool_schemas is not None:
        tool_defs = tool_schemas
    elif tools:
        tool_defs = TOOL_DEFINITIONS
    else:
        tool_defs = None
    async for event in _llm_stream_chat(messages, tools=tool_defs, thinking_enabled=thinking_enabled, temperature=temperature):
        yield event


# --- App ---


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    init_tracker_db()
    prune_conversations()
    reload_patterns()
    LOGS_DIR.mkdir(parents=True, exist_ok=True)
    asyncio.create_task(asyncio.to_thread(_prewarm_embeddings))
    asyncio.create_task(asyncio.to_thread(backfill_cross_conv_embeddings))
    asyncio.create_task(_backfill_v6_systems())
    yield


app = FastAPI(title="Gizmo Orchestrator", version="1.0.0", lifespan=lifespan)

from origins import ALLOWED_ORIGINS, check_ws_origin

app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(tracker_router)
app.include_router(code_chat_router)


# --- Title Generation ---


async def generate_title(conv_id: str, user_message: str, assistant_response: str, ws: WebSocket):
    """Generate a concise conversation title via the LLM. Fire-and-forget."""
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(
                f"{LLAMA_URL}/v1/chat/completions",
                json={
                    "model": "gizmo",
                    "messages": [
                        {
                            "role": "system",
                            "content": "Generate a concise title (maximum 5 words) for this conversation. "
                                       "Respond with ONLY the title text, nothing else. "
                                       "No quotes, no punctuation unless part of the title.",
                        },
                        {"role": "user", "content": user_message[:500]},
                        {"role": "assistant", "content": assistant_response[:500]},
                        {"role": "user", "content": "Based on the exchange above, generate a concise title (max 5 words)."},
                    ],
                    "max_tokens": 30,
                    "temperature": 0.3,
                    "stream": False,
                    "chat_template_kwargs": {"enable_thinking": False},
                },
            )
            if resp.status_code != 200:
                return
            data = resp.json()
            title = data["choices"][0]["message"]["content"].strip().strip('"\'')
            if not title:
                return

        conn = get_db()
        try:
            conn.execute("UPDATE conversations SET title = ? WHERE id = ?", (title, conv_id))
            conn.commit()
        finally:
            conn.close()

        await ws.send_json({"type": "title", "title": title, "conversation_id": conv_id})
    except Exception as e:
        error_log.error("Title generation failed for %s: %s", conv_id, e)


# --- Health ---


@app.get("/health")
async def health():
    from session_memory import get_embed_failure_count
    result = {"status": "ok", "service": "gizmo-orchestrator", "model": MODEL_NAME}
    failures = get_embed_failure_count()
    if failures > 0:
        result["embed_failures"] = failures
    try:
        conn = sqlite3.connect(str(DB_PATH))
        try:
            summaries = conn.execute("SELECT COUNT(*) FROM conversation_summaries").fetchone()[0]
            cross_emb = conn.execute("SELECT COUNT(*) FROM cross_conv_embeddings").fetchone()[0]
            facts_active = conn.execute("SELECT COUNT(*) FROM knowledge_facts WHERE valid_to IS NULL").fetchone()[0]
            facts_invalid = conn.execute("SELECT COUNT(*) FROM knowledge_facts WHERE valid_to IS NOT NULL").fetchone()[0]
        finally:
            conn.close()
        result["memory_stats"] = {
            "conversation_summaries": summaries,
            "cross_conv_embeddings": cross_emb,
            "knowledge_facts_active": facts_active,
            "knowledge_facts_invalidated": facts_invalid,
            "embed_failures": failures,
        }
    except Exception:
        pass
    return result


@app.get("/api/patterns")
async def api_list_patterns():
    """List available analysis patterns."""
    return list_patterns()


# --- Mode CRUD ---


@app.get("/api/modes")
async def api_list_modes():
    """List all behavioral modes sorted by order."""
    return list_modes()


@app.get("/api/modes/{name}")
async def api_get_mode(name: str):
    """Get full mode details including system_prompt content."""
    mode = get_mode(name)
    if not mode:
        raise HTTPException(status_code=404, detail="Mode not found")
    return {**mode, "builtin": name in BUILTIN_MODES}


@app.put("/api/modes/{name}")
async def api_update_mode(name: str, request: Request):
    """Update an existing mode's system_prompt and/or config fields. Built-in modes are read-only."""
    if name in BUILTIN_MODES:
        raise HTTPException(status_code=403, detail="Built-in modes cannot be edited")

    body = await request.json()
    updated = False

    if "system_prompt" in body:
        if not save_mode_prompt(name, body["system_prompt"]):
            raise HTTPException(status_code=404, detail="Mode not found")
        updated = True

    if "label" in body or "description" in body:
        if not save_mode_config(name, label=body.get("label"), description=body.get("description")):
            raise HTTPException(status_code=404, detail="Mode not found")
        updated = True

    if not updated:
        raise HTTPException(status_code=400, detail="No fields to update")
    return {"status": "ok"}


@app.post("/api/modes")
async def api_create_mode(request: Request):
    """Create a new custom mode."""
    body = await request.json()
    name = body.get("name", "").strip().lower()
    label = body.get("label", "").strip()
    description = body.get("description", "").strip()
    system_prompt = body.get("system_prompt", "")

    if not name or not label:
        raise HTTPException(status_code=400, detail="name and label are required")

    result = create_mode(name, label, description, system_prompt)
    if not result:
        raise HTTPException(status_code=400, detail="Invalid name or mode already exists")
    return result


@app.delete("/api/modes/{name}")
async def api_delete_mode(name: str):
    """Delete a custom mode. Built-in modes cannot be deleted."""
    if not delete_mode(name):
        raise HTTPException(status_code=400, detail="Cannot delete (built-in or not found)")
    return {"status": "ok"}


# --- Analytics Endpoints ---


@app.get("/api/analytics/summary")
async def api_analytics_summary():
    """Total tokens, conversations, avg response time, estimated savings."""
    return analytics_summary()


@app.get("/api/analytics/daily")
async def api_analytics_daily(days: int = 30):
    """Daily breakdown of tokens, messages, and response times."""
    return get_daily_breakdown(min(days, 365))


@app.get("/api/analytics/conversations")
async def api_analytics_conversations():
    """Per-conversation token totals, sorted by usage."""
    return get_conversation_usage()


@app.get("/api/analytics/costs")
async def api_analytics_costs():
    """Cost comparison across all cloud providers."""
    return get_cost_comparison()


@app.get("/api/analytics/modes")
async def api_analytics_modes():
    """Token distribution across modes."""
    return get_mode_breakdown()


@app.get("/api/services/health")
async def services_health():
    """Check health of all backend services."""
    results = {}
    checks = [
        ("llama", f"{LLAMA_URL}/health"),
        ("searxng", f"http://{SEARXNG_HOST}:{SEARXNG_PORT}/"),
        ("tts", f"http://{TTS_HOST}:{TTS_PORT}/health"),
        ("whisper", f"{WHISPER_URL}/health"),
    ]
    async with httpx.AsyncClient(timeout=5.0) as client:
        for name, url in checks:
            try:
                resp = await client.get(url)
                results[name] = {"status": "ok" if resp.status_code < 400 else "error"}
            except Exception as e:
                results[name] = {"status": "down", "error": str(e)}
    results["orchestrator"] = {"status": "ok"}
    return results


# --- WebSocket Chat ---


@app.websocket("/ws/chat")
async def ws_chat(ws: WebSocket):
    if not check_ws_origin(ws):
        await ws.close(code=4003, reason="Origin not allowed")
        return
    await ws.accept()
    try:
        while True:
            raw = await ws.receive_text()
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                await ws.send_json({"type": "error", "error": "Invalid JSON"})
                continue

            user_text = msg.get("message", "")
            active_mode = msg.get("mode", "chat")
            if not get_mode(active_mode):
                active_mode = "chat"
            image_data = msg.get("image")  # base64 data URL for vision
            video_frames = msg.get("video_frames")  # list of base64 data URLs
            thinking = msg.get("thinking", False)
            conversation_id = msg.get("conversation_id") or str(uuid.uuid4())
            request_tts = msg.get("tts", False)
            voice_clone_ref = msg.get("voice_clone_ref")  # base64 data URL for voice cloning
            voice_id = msg.get("voice_id")  # saved voice ID for TTS
            tts_speed = max(0.5, min(float(msg.get("tts_speed", 1.0)), 2.0))
            tts_language = msg.get("tts_language", "Auto")
            context_length = max(2048, min(int(msg.get("context_length", 32768)), 131072))
            regenerate = msg.get("regenerate", False)
            # Resolve voice_id to voice_clone_ref if provided
            voice_ref_text = ""
            if voice_id and not voice_clone_ref:
                voice_clone_ref = voice_data_url(voice_id)
                voice_ref_text = voice_transcript(voice_id)
            trace_id = f"gizmo-{uuid.uuid4().hex[:8]}"

            await ws.send_json({"type": "trace_id", "trace_id": trace_id})
            conv_log.info("[%s] USER (%s): %s", trace_id, conversation_id, user_text[:500])

            # Load conversation history
            history = get_conversation_messages(conversation_id)
            history_msgs = [{"role": m["role"], "content": m["content"]} for m in history]

            # Build user message — multimodal if image/video attached
            if regenerate:
                # User message already in DB history — replace with multimodal version if needed
                if (image_data or video_frames) and history_msgs:
                    history_msgs.pop()  # Remove text-only DB version
                    if video_frames:
                        user_content = [{"type": "text", "text": user_text}]
                        for frame_url in video_frames:
                            user_content.append({"type": "image_url", "image_url": {"url": frame_url}})
                        history_msgs.append({"role": "user", "content": user_content})
                    elif image_data:
                        user_content = [
                            {"type": "text", "text": user_text},
                            {"type": "image_url", "image_url": {"url": image_data}},
                        ]
                        history_msgs.append({"role": "user", "content": user_content})
            else:
                if video_frames:
                    user_content = [{"type": "text", "text": user_text}]
                    for frame_url in video_frames:
                        user_content.append({"type": "image_url", "image_url": {"url": frame_url}})
                    history_msgs.append({"role": "user", "content": user_content})
                elif image_data:
                    user_content = [
                        {"type": "text", "text": user_text},
                        {"type": "image_url", "image_url": {"url": image_data}},
                    ]
                    history_msgs.append({"role": "user", "content": user_content})
                else:
                    history_msgs.append({"role": "user", "content": user_text})
                # Persist image to media dir if present
                persisted_image_url = ""
                if image_data and image_data.startswith("data:"):
                    try:
                        header, b64 = image_data.split(",", 1)
                        mime = header.split(":")[1].split(";")[0]  # e.g. image/png
                        ext = mime.split("/")[1].replace("jpeg", "jpg")
                        img_id = uuid.uuid4().hex[:8]
                        MEDIA_DIR.mkdir(parents=True, exist_ok=True)
                        img_path = MEDIA_DIR / f"{img_id}.{ext}"
                        img_path.write_bytes(base64.b64decode(b64))
                        persisted_image_url = f"/api/media/{img_id}.{ext}"
                    except Exception:
                        pass  # Non-critical — image just won't persist
                persisted_video_url = msg.get("video_url", "")
                # Save user message with media URLs
                user_msg_index = save_message(conversation_id, "user", user_text,
                                              image_url=persisted_image_url, video_url=persisted_video_url)

            # Route the request
            route_result = route(user_text)
            clean_text = route_result.cleaned_message
            if route_result.pattern:
                conv_log.info("[%s] Pattern activated: %s", trace_id, route_result.pattern["name"])

            recitation_context, llm_temperature = await prepare_recitation(route_result, f"[{trace_id}]")

            # Compute query embedding once — shared by session recall and smart windowing
            t0 = time.perf_counter()
            query_embedding = await prepare_query_embedding(user_text, len(history_msgs))
            t_embed = time.perf_counter() - t0

            # Retrieve session recall + cross-conversation context (parallel)
            recalled_turns = []
            cross_conv_results = []
            t_recall = 0.0
            if query_embedding:
                t0 = time.perf_counter()
                try:
                    session_result, cross_result = await asyncio.gather(
                        asyncio.to_thread(
                            retrieve_relevant, conversation_id, user_text, query_embedding=query_embedding),
                        asyncio.to_thread(
                            search_cross_conversations, user_text, conversation_id, 3, query_embedding),
                        return_exceptions=True,
                    )
                    if not isinstance(session_result, BaseException) and session_result:
                        recalled_turns = session_result
                        conv_log.info("[%s] Session recall: %d turns retrieved", trace_id, len(recalled_turns))
                    if not isinstance(cross_result, BaseException) and cross_result:
                        cross_conv_results = cross_result
                        conv_log.info("[%s] Cross-conv recall: %d results", trace_id, len(cross_conv_results))
                except Exception as e:
                    error_log.debug("Recall retrieval failed: %s", e)
                t_recall = time.perf_counter() - t0

            has_vision = bool(image_data or video_frames)

            # Load conversation summary (if compaction has run)
            t0 = time.perf_counter()
            conv_summary = get_conversation_summary(conversation_id)
            t_compact = time.perf_counter() - t0

            # Window first (needs to run before session recall formatting to deduplicate)
            conv_len = len(history_msgs)
            pctx = PromptContext(
                has_vision=has_vision, pattern=route_result.pattern,
                recitation_context=recitation_context, conversation_summary=conv_summary,
                charmap_content=route_result.charmap_content, conversation_length=conv_len,
                mode=active_mode)
            temp_prompt = build_system_prompt(clean_text, pctx)
            history_msgs = window_messages(history_msgs, temp_prompt, context_length,
                                           query_embedding=query_embedding,
                                           conversation_id=conversation_id)

            # Format session recall — exclude turns whose content is already in the window
            session_recall = ""
            if recalled_turns:
                windowed_contents = {_extract_text(m.get("content", ""))[:200] for m in history_msgs}
                deduped = [t for t in recalled_turns if _extract_text(t["content"])[:200] not in windowed_contents]
                if deduped:
                    session_recall = format_recalled(deduped)

            # Format cross-conversation recall
            cross_memory_context = format_cross_recall(cross_conv_results) if cross_conv_results else ""

            # Retrieve relevant knowledge facts
            t0 = time.perf_counter()
            knowledge_facts = get_relevant_facts(user_text)
            knowledge_context = format_knowledge_facts(knowledge_facts) if knowledge_facts else ""
            t_knowledge = time.perf_counter() - t0

            conv_log.info("[%s] Context build: embed=%.1fms recall=%.1fms compact=%.1fms knowledge=%.1fms",
                          trace_id, t_embed * 1000, t_recall * 1000, t_compact * 1000, t_knowledge * 1000)

            # Final system prompt with all context layers
            pctx.session_recall = session_recall
            pctx.cross_memory = cross_memory_context
            pctx.knowledge_context = knowledge_context
            system_prompt = build_system_prompt(clean_text, pctx)
            messages = build_messages(history_msgs, system_prompt)

            sentence_buffer = SentenceBuffer() if request_tts else None
            tts_tasks: list[asyncio.Task] = []
            tts_pcm_chunks: dict[int, list[bytes]] = {}
            tts_sample_rate = 24000
            tts_streaming_failed = False
            ws_send_lock = asyncio.Lock()

            def _feed_tts(token: str):
                """Feed a token to the sentence buffer, spawning TTS tasks for completed sentences."""
                if sentence_buffer and not tts_streaming_failed:
                    for sentence in sentence_buffer.add(token):
                        idx = len(tts_tasks)
                        tts_tasks.append(asyncio.create_task(
                            _stream_sentence_to_client(sentence, idx)))

            async def _stream_sentence_to_client(text: str, sent_idx: int):
                """Stream TTS for one sentence, forwarding chunks to browser."""
                nonlocal tts_streaming_failed, tts_sample_rate
                chunk_count = 0
                try:
                    async for pcm_bytes, sr in stream_tts_sentence(
                        text, voice_id=voice_id,
                        voice_clone_data_url=voice_clone_ref if not voice_id else None,
                        speed=tts_speed, language=tts_language,
                    ):
                        tts_sample_rate = sr
                        tts_pcm_chunks.setdefault(sent_idx, []).append(pcm_bytes)
                        async with ws_send_lock:
                            await ws.send_json({
                                "type": "audio_chunk",
                                "chunk_index": chunk_count,
                                "sentence_index": sent_idx,
                                "sample_rate": sr,
                                "is_last": False,
                            })
                            await ws.send_bytes(pcm_bytes)
                        chunk_count += 1
                except Exception as e:
                    error_log.warning("[%s] Streaming TTS failed for sentence %d: %s", trace_id, sent_idx, e)
                    if sent_idx == 0:
                        tts_streaming_failed = True

            # Stream with selected tools
            executed_tool_calls = []  # Accumulate for DB persistence
            sstate = StreamState()
            t_llm_start = time.perf_counter()
            await _process_stream(
                stream_chat(messages, thinking_enabled=thinking,
                            tool_schemas=route_result.tool_schemas,
                            temperature=llm_temperature),
                ws, sstate, trace_id, tts_feed=_feed_tts)
            full_response = sstate.full_response
            full_thinking = sstate.full_thinking
            tool_calls_pending = sstate.tool_calls

            # Execute tool calls — multi-round loop (up to 5 rounds)
            max_tool_rounds = 5
            tool_round = 0
            while tool_calls_pending and tool_round < max_tool_rounds:
                tool_round += 1

                # Build single assistant message with all tool calls (matches OpenAI protocol)
                messages.append({
                    "role": "assistant",
                    "content": full_response,
                    "tool_calls": [
                        {
                            "id": tc["id"],
                            "type": "function",
                            "function": {"name": tc["name"], "arguments": tc["arguments"]},
                        }
                        for tc in tool_calls_pending
                    ],
                })

                for tc in tool_calls_pending:
                    conv_log.info("[%s] TOOL_CALL [round %d]: %s(%s)", trace_id, tool_round, tc["name"], tc["arguments"][:200])
                    try:
                        args = json.loads(tc["arguments"]) if isinstance(tc["arguments"], str) else tc["arguments"]
                        result = await execute_tool(tc["name"], args)
                    except Exception as e:
                        result = f"Tool error: {str(e)}"
                        error_log.error("[%s] Tool '%s' failed: %s", trace_id, tc["name"], e, exc_info=True)

                    conv_log.info("[%s] TOOL_RESULT: %s → %s", trace_id, tc["name"], result[:300])
                    executed_tool_calls.append({"tool": tc["name"], "status": "done", "result": result[:800]})
                    await ws.send_json({
                        "type": "tool_result",
                        "tool": tc["name"],
                        "result": result,
                    })

                    messages.append({
                        "role": "tool",
                        "tool_call_id": tc["id"],
                        "content": result,
                    })

                # Continue generation with tool results
                sstate.tool_calls = []
                sstate._rep_check_len = len(sstate.full_response)
                await _process_stream(
                    stream_chat(messages, thinking_enabled=False,
                                tool_schemas=route_result.tool_schemas,
                                temperature=llm_temperature),
                    ws, sstate, trace_id, tts_feed=_feed_tts,
                    log_suffix=f" (tool round {tool_round})")
                full_response = sstate.full_response
                full_thinking = sstate.full_thinking
                tool_calls_pending = sstate.tool_calls
                if sstate.stopped:
                    tool_calls_pending = []

            t_llm_total = time.perf_counter() - t_llm_start

            # TTS if requested
            audio_file_url = ""
            if request_tts and full_response:
                # Flush remaining text from sentence buffer
                if sentence_buffer and not tts_streaming_failed:
                    remaining = sentence_buffer.flush()
                    if remaining:
                        idx = len(tts_tasks)
                        tts_tasks.append(asyncio.create_task(
                            _stream_sentence_to_client(remaining, idx)))

                if tts_tasks and not tts_streaming_failed:
                    # Streaming mode — await all sentence TTS tasks
                    results = await asyncio.gather(*tts_tasks, return_exceptions=True)
                    for i, r in enumerate(results):
                        if isinstance(r, Exception):
                            error_log.warning("[%s] TTS task %d failed: %s", trace_id, i, r)

                    # Signal streaming complete
                    await ws.send_json({"type": "audio_chunk", "is_last": True})

                    # Assemble final WAV from all PCM chunks (sentence order)
                    if tts_pcm_chunks:
                        all_pcm = b"".join(
                            chunk for si in sorted(tts_pcm_chunks.keys())
                            for chunk in tts_pcm_chunks[si]
                        )
                        if all_pcm:
                            MEDIA_DIR.mkdir(parents=True, exist_ok=True)
                            audio_filename = f"tts-{trace_id}.wav"
                            wav_path = MEDIA_DIR / audio_filename
                            buf = io.BytesIO()
                            pcm_array = np.frombuffer(all_pcm, dtype=np.float32)
                            _sf.write(buf, pcm_array, tts_sample_rate, format="WAV")
                            buf.seek(0)
                            wav_path.write_bytes(buf.read())
                            audio_file_url = f"/api/media/{audio_filename}"
                            await ws.send_json({"type": "audio", "url": audio_file_url})
                else:
                    # Fallback to batch TTS (streaming failed or no sentences buffered)
                    if tts_streaming_failed:
                        await ws.send_json({
                            "type": "tts_info",
                            "message": "Streaming TTS unavailable — generating audio in batch mode",
                        })
                    if len(full_response) > 4000:
                        await ws.send_json({
                            "type": "tts_info",
                            "message": f"Generating audio for {len(full_response)} characters — this may take a moment",
                        })
                    audio_data = await synthesize(
                        full_response,
                        voice_clone_data_url=voice_clone_ref,
                        voice_reference_text=voice_ref_text,
                        speed=tts_speed,
                        language=tts_language,
                    )
                    if audio_data:
                        MEDIA_DIR.mkdir(parents=True, exist_ok=True)
                        audio_filename = f"tts-{trace_id}.wav"
                        (MEDIA_DIR / audio_filename).write_bytes(audio_data)
                        audio_file_url = f"/api/media/{audio_filename}"
                        await ws.send_json({"type": "audio", "url": audio_file_url})
                    else:
                        await ws.send_json({"type": "error", "error": "TTS synthesis failed — audio unavailable"})

            # Save assistant response (skip if stream errored before any tokens)
            if full_response:
                asst_msg_index = save_message(conversation_id, "assistant", full_response, full_thinking,
                                              audio_url=audio_file_url,
                                              tool_calls=executed_tool_calls if executed_tool_calls else None)
                conv_log.info("[%s] ASSISTANT (%s): %s", trace_id, conversation_id, full_response[:500])

                # Store analytics (fire-and-forget — non-critical telemetry)
                context_ms = int((t_embed + t_recall + t_compact + t_knowledge) * 1000)
                response_ms = int(t_llm_total * 1000)
                prompt_tok = sstate.usage_prompt or estimate_tokens(system_prompt + user_text)
                completion_tok = sstate.usage_completion or estimate_tokens(full_response)
                total_tok = sstate.usage_total or (prompt_tok + completion_tok)
                asyncio.create_task(asyncio.to_thread(
                    store_analytics, conversation_id, asst_msg_index,
                    prompt_tok, completion_tok, total_tok, response_ms,
                    context_ms, tool_round, active_mode,
                ))

                index_conversation_turns(conversation_id, user_msg_index, user_text,
                                         asst_msg_index, full_response)
                asyncio.create_task(asyncio.to_thread(
                    index_cross_conversation, conversation_id, user_text, full_response,
                    user_msg_index, asst_msg_index))
                msg_count = len(get_conversation_messages(conversation_id))
                asyncio.create_task(maybe_compact(conversation_id, msg_count))
                asyncio.create_task(maybe_extract_facts(
                    conversation_id, user_text, full_response, user_msg_index))

                # Generate title on first exchange (exactly 2 messages: user + assistant)
                if msg_count == 2 and not regenerate:
                    asyncio.create_task(generate_title(conversation_id, user_text, full_response, ws))

            await ws.send_json({
                "type": "done",
                "trace_id": trace_id,
                "conversation_id": conversation_id,
            })

    except WebSocketDisconnect:
        try:
            for task in tts_tasks:
                task.cancel()
        except NameError:
            pass
    except Exception as e:
        error_log.error("WebSocket handler error: %s", e, exc_info=True)


# --- REST Chat ---


@app.post("/api/chat")
async def rest_chat(
    message: str = Form(""),
    thinking: bool = Form(False),
    conversation_id: str = Form(""),
    context_length: int = Form(32768),
    mode: str = Form("chat"),
):
    """Non-streaming chat endpoint."""
    if not get_mode(mode):
        mode = "chat"
    if not conversation_id:
        conversation_id = str(uuid.uuid4())

    conv_log.info("[REST] USER (%s): %s", conversation_id, message[:500])

    # Route the request
    route_result = route(message)
    clean_text = route_result.cleaned_message

    recitation_context, llm_temperature = await prepare_recitation(route_result, "[REST]")

    history = get_conversation_messages(conversation_id)
    history_msgs = [{"role": m["role"], "content": m["content"]} for m in history]
    history_msgs.append({"role": "user", "content": clean_text})

    user_msg_index = save_message(conversation_id, "user", clean_text)

    query_embedding = await prepare_query_embedding(clean_text, len(history_msgs))

    # Retrieve session recall + cross-conversation context (parallel)
    recalled_turns = []
    cross_conv_results = []
    if query_embedding:
        try:
            session_result, cross_result = await asyncio.gather(
                asyncio.to_thread(
                    retrieve_relevant, conversation_id, clean_text, query_embedding=query_embedding),
                asyncio.to_thread(
                    search_cross_conversations, clean_text, conversation_id, 3, query_embedding),
                return_exceptions=True,
            )
            if not isinstance(session_result, BaseException) and session_result:
                recalled_turns = session_result
                conv_log.info("[REST] Session recall: %d turns retrieved", len(recalled_turns))
            if not isinstance(cross_result, BaseException) and cross_result:
                cross_conv_results = cross_result
                conv_log.info("[REST] Cross-conv recall: %d results", len(cross_conv_results))
        except Exception as e:
            error_log.debug("Recall retrieval failed: %s", e)

    rest_conv_len = len(history_msgs)
    conv_summary = get_conversation_summary(conversation_id)

    pctx = PromptContext(
        pattern=route_result.pattern, recitation_context=recitation_context,
        conversation_summary=conv_summary, charmap_content=route_result.charmap_content,
        conversation_length=rest_conv_len, mode=mode)
    temp_prompt = build_system_prompt(clean_text, pctx)
    context_length = max(2048, min(context_length, 131072))
    history_msgs = window_messages(history_msgs, temp_prompt, context_length,
                                   query_embedding=query_embedding,
                                   conversation_id=conversation_id)

    session_recall = ""
    if recalled_turns:
        windowed_contents = {str(m.get("content", ""))[:200] for m in history_msgs}
        deduped = [t for t in recalled_turns if t["content"][:200] not in windowed_contents]
        if deduped:
            session_recall = format_recalled(deduped)

    cross_memory_context = format_cross_recall(cross_conv_results) if cross_conv_results else ""
    knowledge_facts = get_relevant_facts(clean_text)
    knowledge_context = format_knowledge_facts(knowledge_facts) if knowledge_facts else ""

    pctx.session_recall = session_recall
    pctx.cross_memory = cross_memory_context
    pctx.knowledge_context = knowledge_context
    system_prompt = build_system_prompt(clean_text, pctx)
    messages = build_messages(history_msgs, system_prompt)

    full_response = ""
    full_thinking = ""
    max_tool_rounds = 5
    usage_prompt_tokens = 0
    usage_completion_tokens = 0
    usage_total_tokens = 0
    tool_round = 0
    t_llm_start = time.perf_counter()

    for _ in range(max_tool_rounds):
        tool_calls_pending = []

        async for event in stream_chat(messages, thinking,
                                       tool_schemas=route_result.tool_schemas,
                                       temperature=llm_temperature):
            if event["type"] == "token":
                full_response += event["content"]
            elif event["type"] == "thinking":
                full_thinking += event["content"]
            elif event["type"] == "usage":
                usage_prompt_tokens += event.get("prompt_tokens", 0)
                usage_completion_tokens += event.get("completion_tokens", 0)
                usage_total_tokens += event.get("total_tokens", 0)
            elif event["type"] == "tool_call":
                tool_calls_pending.append(event)
            elif event["type"] == "error":
                error_log.error("[REST] Stream error: %s", event["error"])
                return JSONResponse(status_code=502, content={"error": event["error"]})

        if not tool_calls_pending:
            break

        tool_round += 1

        # Execute tool calls and append results to messages
        messages.append({
            "role": "assistant",
            "content": full_response,
            "tool_calls": [
                {
                    "id": tc["id"],
                    "type": "function",
                    "function": {"name": tc["name"], "arguments": tc["arguments"]},
                }
                for tc in tool_calls_pending
            ],
        })
        full_response = ""

        for tc in tool_calls_pending:
            conv_log.info("[REST] TOOL_CALL: %s(%s)", tc["name"], tc["arguments"][:200])
            try:
                args = json.loads(tc["arguments"]) if isinstance(tc["arguments"], str) else tc["arguments"]
                result = await execute_tool(tc["name"], args)
            except Exception as e:
                result = f"Tool error: {e}"
                error_log.error("[REST] Tool '%s' failed: %s", tc["name"], e, exc_info=True)
            messages.append({
                "role": "tool",
                "tool_call_id": tc["id"],
                "content": result,
            })

    t_llm_total = time.perf_counter() - t_llm_start
    asst_msg_index = save_message(conversation_id, "assistant", full_response, full_thinking)
    conv_log.info("[REST] ASSISTANT (%s): %s", conversation_id, full_response[:500])

    if full_response:
        # Store analytics
        response_ms = int(t_llm_total * 1000)
        prompt_tok = usage_prompt_tokens or estimate_tokens(system_prompt + clean_text)
        completion_tok = usage_completion_tokens or estimate_tokens(full_response)
        total_tok = usage_total_tokens or (prompt_tok + completion_tok)
        asyncio.create_task(asyncio.to_thread(
            store_analytics, conversation_id, asst_msg_index,
            prompt_tok, completion_tok, total_tok, response_ms,
            0, tool_round, mode,
        ))

        index_conversation_turns(conversation_id, user_msg_index, clean_text,
                                 asst_msg_index, full_response)
        asyncio.create_task(asyncio.to_thread(
            index_cross_conversation, conversation_id, clean_text, full_response,
            user_msg_index, asst_msg_index))
        msg_count = len(get_conversation_messages(conversation_id))
        asyncio.create_task(maybe_compact(conversation_id, msg_count))
        asyncio.create_task(maybe_extract_facts(
            conversation_id, clean_text, full_response, user_msg_index))

    return {
        "response": full_response,
        "thinking": full_thinking,
        "conversation_id": conversation_id,
    }


# --- File Upload ---


@app.post("/api/upload")
async def upload_file(file: UploadFile = File(...)):
    """Upload a document (PDF, text, code) and return extracted content."""
    content = await file.read()
    filename = file.filename or "unknown"

    if filename.lower().endswith(".pdf"):
        try:
            from pypdf import PdfReader
            import io
            reader = PdfReader(io.BytesIO(content))
            text = "\n".join(page.extract_text() or "" for page in reader.pages)
            return {"filename": filename, "type": "pdf", "content": text[:10000]}
        except Exception as e:
            error_log.error("PDF parse error for '%s': %s", filename, e, exc_info=True)
            return JSONResponse(status_code=400, content={"error": f"PDF parse error: {e}"})
    else:
        try:
            text = content.decode("utf-8")
            return {"filename": filename, "type": "text", "content": text[:10000]}
        except UnicodeDecodeError:
            return JSONResponse(status_code=400, content={"error": "Could not decode file as text"})


@app.post("/api/upload-image")
async def upload_image(file: UploadFile = File(...)):
    """Upload an image and return a reference for use in chat."""
    content = await file.read()
    b64 = base64.b64encode(content).decode()
    mime = file.content_type or "image/png"
    return {
        "filename": file.filename,
        "mime": mime,
        "data_url": f"data:{mime};base64,{b64}",
        "size": len(content),
    }


@app.post("/api/upload-video")
async def upload_video(file: UploadFile = File(...)):
    """Upload a video, extract frames with ffmpeg, return as base64 data URLs."""
    content = await file.read()
    max_frames = 8
    MEDIA_DIR.mkdir(parents=True, exist_ok=True)

    # Save video for playback
    video_id = str(uuid.uuid4())[:8]
    ext = Path(file.filename or "video.mp4").suffix or ".mp4"
    saved_video_path = MEDIA_DIR / f"{video_id}{ext}"
    saved_video_path.write_bytes(content)
    video_url = f"/api/media/{video_id}{ext}"

    with tempfile.TemporaryDirectory() as tmpdir:
        # Get video duration using the already-saved file
        probe = subprocess.run(
            ["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
             "-of", "csv=p=0", str(saved_video_path)],
            capture_output=True, text=True, timeout=30
        )
        try:
            duration = float(probe.stdout.strip())
        except (ValueError, AttributeError):
            saved_video_path.unlink(missing_ok=True)
            return JSONResponse(status_code=400, content={"error": "Could not read video duration"})

        # Extract frames evenly spaced
        frames_dir = os.path.join(tmpdir, "frames")
        os.makedirs(frames_dir)

        if duration <= 0:
            saved_video_path.unlink(missing_ok=True)
            return JSONResponse(status_code=400, content={"error": "Invalid video"})

        # Calculate timestamps for evenly spaced frames
        num_frames = min(max_frames, max(1, int(duration)))
        interval = duration / (num_frames + 1)
        timestamps = [interval * (i + 1) for i in range(num_frames)]

        frame_data_urls = []
        for i, ts in enumerate(timestamps):
            frame_path = os.path.join(frames_dir, f"frame_{i:03d}.jpg")
            subprocess.run(
                ["ffmpeg", "-ss", str(ts), "-i", str(saved_video_path),
                 "-frames:v", "1", "-q:v", "3", "-y", frame_path],
                capture_output=True, timeout=30
            )
            if os.path.exists(frame_path):
                with open(frame_path, "rb") as fp:
                    b64 = base64.b64encode(fp.read()).decode()
                frame_data_urls.append(f"data:image/jpeg;base64,{b64}")

        if not frame_data_urls:
            saved_video_path.unlink(missing_ok=True)
            return JSONResponse(status_code=400, content={"error": "Could not extract frames"})

        return {
            "filename": file.filename,
            "frames": frame_data_urls,
            "frame_count": len(frame_data_urls),
            "duration": round(duration, 1),
            "video_url": video_url,
        }


# --- Media ---


@app.get("/api/media/{filename}")
async def serve_media(filename: str):
    """Serve uploaded media files (videos, documents, etc.)."""
    filepath = (MEDIA_DIR / filename).resolve()
    if not filepath.is_relative_to(MEDIA_DIR.resolve()):
        return JSONResponse(status_code=400, content={"error": "Invalid path"})
    if not filepath.exists():
        return JSONResponse(status_code=404, content={"error": "Not found"})
    ext = filepath.suffix.lower()
    ct_map = {
        ".mp4": "video/mp4", ".webm": "video/webm", ".mov": "video/quicktime", ".avi": "video/x-msvideo",
        ".wav": "audio/wav", ".mp3": "audio/mpeg",
        ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif", ".svg": "image/svg+xml",
        ".pdf": "application/pdf",
        ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        ".csv": "text/csv", ".txt": "text/plain", ".json": "application/json",
        ".html": "text/html", ".xml": "application/xml",
        ".zip": "application/zip", ".tar.gz": "application/gzip",
    }
    content_type = ct_map.get(ext, "application/octet-stream")
    # Force download for document types (PDFs open in browser naturally)
    download_exts = {".docx", ".xlsx", ".pptx", ".csv", ".txt", ".json", ".xml", ".zip"}
    headers = {}
    if ext in download_exts:
        headers["Content-Disposition"] = f'attachment; filename="{filepath.name}"'
    return FileResponse(filepath, media_type=content_type, headers=headers)


# --- Conversations ---


@app.get("/api/conversations")
async def list_conversations():
    conn = get_db()
    try:
        rows = conn.execute(
            "SELECT id, title, created_at, updated_at FROM conversations ORDER BY updated_at DESC"
        ).fetchall()
        return [dict(r) for r in rows]
    finally:
        conn.close()


@app.get("/api/conversations/search")
async def search_conversations(q: str = ""):
    """Full-text search across conversation messages."""
    if not q.strip():
        return []
    # Escape SQL LIKE metacharacters to prevent wildcard injection
    escaped_q = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    conn = get_db()
    try:
        rows = conn.execute(
            """SELECT DISTINCT c.id, c.title, c.updated_at, m.content
               FROM conversations c
               JOIN messages m ON c.id = m.conversation_id
               WHERE LOWER(m.content) LIKE '%' || LOWER(?) || '%' ESCAPE '\\'
               ORDER BY c.updated_at DESC
               LIMIT 20""",
            (escaped_q,),
        ).fetchall()
    finally:
        conn.close()
    results = []
    seen = set()
    for r in rows:
        if r["id"] in seen:
            continue
        seen.add(r["id"])
        content = r["content"]
        idx = content.lower().find(q.lower())
        start = max(0, idx - 40)
        snippet = content[start:start + 150]
        if start > 0:
            snippet = "..." + snippet
        if start + 150 < len(content):
            snippet += "..."
        results.append({"id": r["id"], "title": r["title"], "updated_at": r["updated_at"], "snippet": snippet})
    return results


@app.get("/api/conversations/{conv_id}")
async def get_conversation(conv_id: str):
    messages = get_conversation_messages(conv_id)
    if not messages:
        return JSONResponse(status_code=404, content={"error": "Conversation not found"})
    return {"id": conv_id, "messages": messages}


@app.patch("/api/conversations/{conv_id}")
async def rename_conversation(conv_id: str, request: Request):
    """Rename a conversation."""
    try:
        body = await request.json()
    except Exception:
        return JSONResponse(status_code=400, content={"error": "Invalid JSON"})
    title = str(body.get("title", "")).strip()[:100]
    if not title:
        return JSONResponse(status_code=400, content={"error": "Title cannot be empty"})
    conn = get_db()
    try:
        existing = conn.execute("SELECT id FROM conversations WHERE id = ?", (conv_id,)).fetchone()
        if not existing:
            return JSONResponse(status_code=404, content={"error": "Conversation not found"})
        conn.execute("UPDATE conversations SET title = ? WHERE id = ?", (title, conv_id))
        conn.commit()
    finally:
        conn.close()
    return {"ok": True}


@app.get("/api/conversations/{conv_id}/export")
async def export_conversation(conv_id: str, format: str = "markdown"):
    """Export a conversation as markdown or JSON."""
    conn = get_db()
    try:
        conv = conn.execute(
            "SELECT id, title, created_at, updated_at FROM conversations WHERE id = ?", (conv_id,)
        ).fetchone()
        if not conv:
            return JSONResponse(status_code=404, content={"error": "Conversation not found"})
        msgs = conn.execute(
            "SELECT role, content, thinking, timestamp FROM messages WHERE conversation_id = ? ORDER BY id",
            (conv_id,),
        ).fetchall()
    finally:
        conn.close()

    conv_data = dict(conv)
    messages_data = [dict(m) for m in msgs]

    if format == "json":
        return JSONResponse(content={**conv_data, "messages": messages_data})

    # Markdown export
    title = conv_data.get("title", "Untitled")
    export_date = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    lines = [f"# {title}", f"*Exported from Gizmo on {export_date}*", "", "---", ""]
    for m in messages_data:
        role_label = "User" if m["role"] == "user" else "Gizmo"
        ts = m.get("timestamp", "")
        lines.append(f"**{role_label}** ({ts})")
        lines.append("")
        lines.append(m["content"])
        lines.append("")
        if m.get("thinking"):
            lines.append("<details><summary>Thinking</summary>")
            lines.append("")
            lines.append(m["thinking"])
            lines.append("")
            lines.append("</details>")
            lines.append("")
        lines.append("---")
        lines.append("")

    slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")[:60] or "conversation"
    md_content = "\n".join(lines)
    return Response(
        content=md_content,
        media_type="text/markdown",
        headers={"Content-Disposition": f'attachment; filename="{slug}.md"'},
    )


@app.delete("/api/conversations/{conv_id}")
async def delete_conversation(conv_id: str):
    conn = get_db()
    try:
        conn.execute("DELETE FROM messages WHERE conversation_id = ?", (conv_id,))
        conn.execute("DELETE FROM session_embeddings WHERE conversation_id = ?", (conv_id,))
        conn.execute("DELETE FROM conversation_summaries WHERE conversation_id = ?", (conv_id,))
        conn.execute("DELETE FROM cross_conv_embeddings WHERE conversation_id = ?", (conv_id,))
        conn.execute("DELETE FROM knowledge_facts WHERE source_conversation_id = ?", (conv_id,))
        conn.execute("DELETE FROM message_analytics WHERE conversation_id = ?", (conv_id,))
        conn.execute("DELETE FROM conversations WHERE id = ?", (conv_id,))
        conn.commit()
    finally:
        conn.close()
    return {"deleted": conv_id}


@app.delete("/api/conversations/{conv_id}/messages-from/{index}")
async def delete_messages_from(conv_id: str, index: int):
    """Delete all messages at position `index` and after (0-based insertion order)."""
    conn = get_db()
    try:
        conv = conn.execute("SELECT id FROM conversations WHERE id = ?", (conv_id,)).fetchone()
        if not conv:
            return JSONResponse(status_code=404, content={"error": "Conversation not found"})
        rows = conn.execute(
            "SELECT id FROM messages WHERE conversation_id = ? ORDER BY id ASC",
            (conv_id,),
        ).fetchall()
        if not rows:
            return {"deleted": 0}
        if index < 0 or index >= len(rows):
            return JSONResponse(status_code=400, content={"error": f"Index {index} out of range (0-{len(rows)-1})"})
        ids_to_delete = [r["id"] for r in rows[index:]]
        placeholders = ",".join("?" * len(ids_to_delete))
        conn.execute(f"DELETE FROM messages WHERE id IN ({placeholders})", ids_to_delete)
        conn.execute(
            "DELETE FROM session_embeddings WHERE conversation_id = ? AND message_index >= ?",
            (conv_id, index),
        )
        # Remove summaries and cross-conv embeddings covering deleted messages
        conn.execute(
            "DELETE FROM conversation_summaries WHERE conversation_id = ? AND segment_end >= ?",
            (conv_id, index),
        )
        conn.execute(
            "DELETE FROM cross_conv_embeddings WHERE conversation_id = ? AND message_end >= ?",
            (conv_id, index),
        )
        conn.execute(
            "DELETE FROM knowledge_facts WHERE source_conversation_id = ? AND source_message_index >= ?",
            (conv_id, index),
        )
        conn.execute(
            "DELETE FROM message_analytics WHERE conversation_id = ? AND message_index >= ?",
            (conv_id, index),
        )
        conn.commit()
    finally:
        conn.close()
    return {"deleted": len(ids_to_delete)}


# --- Memory ---


@app.get("/api/memory/list")
async def api_list_memories(subdir: Optional[str] = None):
    return list_memories(subdir)


@app.get("/api/memory/read")
async def api_read_memory(filename: str, subdir: str = "facts"):
    content = read_memory(filename, subdir)
    return {"filename": filename, "subdir": subdir, "content": content}


@app.post("/api/memory/write")
async def api_write_memory(
    filename: str = Form(...),
    content: str = Form(...),
    subdir: str = Form("facts"),
):
    result = write_memory(filename, content, subdir)
    return {"result": result}


@app.delete("/api/memory/clear")
async def api_clear_memories():
    """Delete all memory files across all subdirectories."""
    count = 0
    from memory import SUBDIRS
    for subdir in SUBDIRS:
        dir_path = Path("/app/memory") / subdir
        if not dir_path.exists():
            continue
        for f in dir_path.iterdir():
            if f.is_file() and not f.name.startswith("."):
                f.unlink()
                count += 1
    return {"deleted": count}


@app.delete("/api/memory/{subdir}/{filename}")
async def api_delete_memory(subdir: str, filename: str):
    result = delete_memory(filename, subdir)
    return {"result": result}


# --- Search ---


@app.get("/api/search")
async def api_search(q: str):
    results = await web_search(q)
    return {"query": q, "results": results}


# --- Code Execution ---


@app.post("/api/run-code")
async def api_run_code(request: Request):
    """Execute code in the sandbox container."""
    from sandbox import run_code as sandbox_run
    try:
        body = await request.json()
    except Exception:
        return JSONResponse(status_code=400, content={"error": "Invalid JSON"})
    code = body.get("code", "")
    if not code.strip():
        return JSONResponse(status_code=400, content={"error": "No code provided"})
    language = body.get("language", "python")
    timeout = min(max(int(body.get("timeout", 10)), 1), 30)
    stdin_data = body.get("stdin", "")
    try:
        result = await sandbox_run(code, language, timeout, stdin_data=stdin_data)
    except Exception as e:
        error_log.error("Sandbox execution error: %s", e, exc_info=True)
        return JSONResponse(status_code=500, content={"error": f"Sandbox error: {e}"})
    return result


# --- Logs ---


def tail_file(path: Path, n: int = 100) -> list[str]:
    """Read last n lines from a file without loading it all into memory."""
    if not path.exists():
        return []
    with open(path, "rb") as f:
        f.seek(0, 2)
        end = f.tell()
        if end == 0:
            return []
        chunk_size = min(8192, end)
        lines = []
        while len(lines) <= n and end > 0:
            start = max(end - chunk_size, 0)
            f.seek(start)
            chunk = f.read(end - start)
            lines = chunk.decode("utf-8", errors="replace").splitlines() + lines
            end = start
        return lines[-n:]


@app.get("/api/logs/{log_name}")
async def get_logs(log_name: str, lines: int = 100):
    """Read the last N lines from a log file."""
    allowed = {"error": "error.log", "conversations": "conversations.log"}
    if log_name not in allowed:
        return JSONResponse(status_code=400, content={"error": f"Unknown log. Use: {', '.join(allowed)}"})
    lines = max(1, min(lines, 1000))
    log_path = LOGS_DIR / allowed[log_name]
    return {"log": log_name, "lines": tail_file(log_path, lines)}


# --- TTS ---


@app.post("/api/tts")
async def api_tts(request: Request):
    """Synthesize speech from text via faster-qwen3-tts."""
    try:
        body = await request.json()
    except Exception:
        return JSONResponse(status_code=400, content={"error": "Invalid JSON"})

    text = body.get("text", "")
    voice = body.get("voice", "default")
    voice_clone_data_url = body.get("voice_clone_data_url")
    voice_id = body.get("voice_id")
    speed = max(0.5, min(float(body.get("speed", 1.0)), 2.0))
    language = body.get("language", "Auto")
    if not text:
        return JSONResponse(status_code=400, content={"error": "Missing 'text' field"})

    # If voice_id is provided, load the saved voice reference
    voice_ref_text = ""
    if voice_id and not voice_clone_data_url:
        voice_clone_data_url = voice_data_url(voice_id)
        voice_ref_text = voice_transcript(voice_id)

    audio = await synthesize(text, voice, voice_clone_data_url=voice_clone_data_url,
                              voice_reference_text=voice_ref_text, speed=speed, language=language)
    if audio is None:
        return JSONResponse(status_code=503, content={"error": "TTS service unavailable"})
    return Response(content=audio, media_type="audio/wav")


# --- Voice Management ---


@app.get("/api/voices")
async def list_voices():
    """List saved voice references."""
    VOICES_DIR.mkdir(parents=True, exist_ok=True)
    voices = []
    for f in sorted(VOICES_DIR.glob("*.json")):
        try:
            meta = json.loads(f.read_text())
            voices.append(meta)
        except Exception:
            continue
    return voices


@app.post("/api/voices")
async def save_voice(file: UploadFile = File(...), name: str = Form(...), max_duration: str = Form("30")):
    """Upload and save a voice reference. Truncates to max_duration for VRAM safety."""
    VOICES_DIR.mkdir(parents=True, exist_ok=True)
    content = await file.read()
    voice_id = str(uuid.uuid4())[:8]

    try:
        dur = int(max_duration)
    except ValueError:
        dur = 30
    dur = max(10, min(dur, 120))

    # Save original temporarily, then truncate to WAV via ffmpeg
    with tempfile.TemporaryDirectory() as tmpdir:
        raw_path = os.path.join(tmpdir, Path(file.filename or "voice.wav").name)
        with open(raw_path, "wb") as f:
            f.write(content)

        wav_path = str(VOICES_DIR / f"{voice_id}.wav")
        subprocess.run(
            ["ffmpeg", "-i", raw_path, "-t", str(dur), "-ar", "24000", "-ac", "1",
             "-y", wav_path],
            capture_output=True,
            timeout=30,
        )

    if not os.path.exists(wav_path):
        return JSONResponse(status_code=400, content={"error": "Could not process audio file"})

    wav_size = (VOICES_DIR / f"{voice_id}.wav").stat().st_size

    # Auto-transcribe via Whisper
    transcript = ""
    try:
        whisper_url = f"http://{WHISPER_HOST}:{WHISPER_PORT}/v1/audio/transcriptions"
        async with httpx.AsyncClient(timeout=30.0) as client:
            with open(wav_path, "rb") as audio_file:
                files = {"file": ("voice.wav", audio_file, "audio/wav")}
                data = {"model": "Systran/faster-whisper-base"}
                resp = await client.post(whisper_url, files=files, data=data)
                if resp.status_code == 200:
                    result = resp.json()
                    transcript = result.get("text", "").strip()
                    conv_log.info("Voice %s transcribed: %s", voice_id, transcript[:100])
    except Exception as e:
        error_log.warning("Whisper transcription failed for voice %s: %s", voice_id, e)

    meta = {
        "id": voice_id,
        "name": name,
        "filename": file.filename,
        "size": wav_size,
        "transcript": transcript,
    }
    meta_path = VOICES_DIR / f"{voice_id}.json"
    meta_path.write_text(json.dumps(meta))

    # Fire-and-forget: precompute speaker embedding for streaming TTS
    asyncio.create_task(extract_voice_embedding(voice_id))

    return meta


@app.delete("/api/voices/{voice_id}")
async def delete_voice(voice_id: str):
    """Delete a saved voice reference."""
    if not re.match(r'^[a-f0-9]{8}$', voice_id):
        raise HTTPException(status_code=400, detail="Invalid voice ID format")
    for f in VOICES_DIR.glob(f"{voice_id}.*"):
        f.unlink()
    (VOICES_DIR / "embeddings" / f"{voice_id}.pt").unlink(missing_ok=True)
    return {"status": "deleted"}


@app.post("/api/voices/{voice_id}/preview")
async def preview_voice(voice_id: str, request: Request):
    """Synthesize a short preview with a saved voice."""
    data_url = voice_data_url(voice_id)
    if not data_url:
        return JSONResponse(status_code=404, content={"error": "Voice not found"})

    try:
        body = await request.json()
    except Exception:
        return JSONResponse(status_code=400, content={"error": "Invalid JSON body"})
    text = body.get("text", "Hello, this is a voice preview.")

    ref_text = voice_transcript(voice_id)
    audio = await synthesize(text, voice_clone_data_url=data_url, voice_reference_text=ref_text)
    if audio is None:
        return JSONResponse(status_code=503, content={"error": "TTS service unavailable"})
    return Response(content=audio, media_type="audio/wav")


@app.post("/api/voices/migrate-transcripts")
async def migrate_voice_transcripts():
    """Backfill transcripts for existing voices using Whisper."""
    VOICES_DIR.mkdir(parents=True, exist_ok=True)
    migrated = 0
    for json_file in VOICES_DIR.glob("*.json"):
        try:
            meta = json.loads(json_file.read_text())
            if meta.get("transcript"):
                continue  # already has transcript
            voice_id = meta["id"]
            wav_path = VOICES_DIR / f"{voice_id}.wav"
            if not wav_path.exists():
                continue
            whisper_url = f"http://{WHISPER_HOST}:{WHISPER_PORT}/v1/audio/transcriptions"
            async with httpx.AsyncClient(timeout=30.0) as client:
                with open(wav_path, "rb") as audio_file:
                    files = {"file": ("voice.wav", audio_file, "audio/wav")}
                    data = {"model": "Systran/faster-whisper-base"}
                    resp = await client.post(whisper_url, files=files, data=data)
                    if resp.status_code == 200:
                        transcript = resp.json().get("text", "").strip()
                        meta["transcript"] = transcript
                        json_file.write_text(json.dumps(meta))
                        migrated += 1
                        conv_log.info("Migrated voice %s transcript: %s", voice_id, transcript[:80])
        except Exception as e:
            error_log.warning("Migration failed for %s: %s", json_file.name, e)
    return {"migrated": migrated}


# --- Transcription (Whisper STT) ---


@app.post("/api/transcribe")
async def transcribe(file: UploadFile = File(...)):
    """Transcribe audio via Whisper."""
    content = await file.read()
    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            resp = await client.post(
                f"{WHISPER_URL}/v1/audio/transcriptions",
                files={"file": (file.filename or "audio.webm", content, file.content_type or "audio/webm")},
                data={"model": "Systran/faster-whisper-base"},
            )
            resp.raise_for_status()
            return resp.json()
    except httpx.ConnectError:
        error_log.warning("Whisper service unavailable")
        return JSONResponse(status_code=503, content={"error": "Whisper service unavailable"})
    except Exception as e:
        error_log.error("Transcription error: %s", e, exc_info=True)
        return JSONResponse(status_code=500, content={"error": str(e)})
