"""Gizmo-AI Orchestrator — FastAPI backend for the local AI assistant."""

import asyncio
import base64
import json
import logging
import os
import re
import sqlite3
import subprocess
import tempfile
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
from tracker import router as tracker_router
from tracker_db import init_tracker_db
from tts import synthesize

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
def voice_data_url(voice_id: str) -> str | None:
    """Build a base64 data URL from a saved voice WAV file."""
    wav_path = VOICES_DIR / f"{voice_id}.wav"
    if not wav_path.exists():
        return None
    b64 = base64.b64encode(wav_path.read_bytes()).decode()
    return f"data:audio/wav;base64,{b64}"

MAX_CONVERSATIONS = int(os.getenv("MAX_CONVERSATIONS", "500"))
CONSTITUTION_PATH = Path("/app/config/constitution.txt")
DB_PATH = Path("/app/memory/conversations.db")
LOGS_DIR = Path("/app/logs")
VOICES_DIR = Path("/app/voices")
MEDIA_DIR = Path("/app/media")


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
                  tool_calls: list | None = None):
    """Save a message to the database."""
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
        conn.execute(
            "INSERT INTO messages (conversation_id, role, content, thinking, timestamp, audio_url, image_url, video_url, tool_calls) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (conversation_id, role, content, thinking, now, audio_url or None, image_url or None, video_url or None, tool_calls_json),
        )
        conn.commit()
    finally:
        conn.close()
    if is_new:
        prune_conversations()


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
    """Load and clean the constitution file."""
    if not CONSTITUTION_PATH.exists():
        return "You are Gizmo, a helpful local AI assistant."
    text = CONSTITUTION_PATH.read_text(encoding="utf-8")
    lines = [line for line in text.splitlines() if not line.strip().startswith("#")]
    return "\n".join(lines).strip()


def build_system_prompt(user_message: str = "", has_vision: bool = False) -> str:
    """Build the full system prompt with constitution and relevant memories."""
    constitution = load_constitution()
    parts = [constitution]

    if has_vision:
        parts.append(f"\n\n{VISION_PROMPT}")

    memories = get_relevant_memories(user_message)
    if memories:
        parts.append("\n\n--- Relevant memories ---")
        for mem in memories:
            parts.append(f"- {mem}")

    return "\n".join(parts)


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


def window_messages(history: list[dict], system_prompt: str, context_length: int) -> list[dict]:
    """Trim conversation history to fit within the token budget.

    Always keeps: system prompt + latest user message.
    Drops oldest messages first when budget is exceeded.
    """
    system_tokens = estimate_tokens(system_prompt)
    response_reserve = MAX_RESPONSE_TOKENS + 256
    budget = context_length - system_tokens - response_reserve

    if budget <= 0 or not history:
        # Budget too tight — just keep the latest user message
        return history[-1:] if history else []

    # Walk backwards through history, accumulating tokens
    kept: list[dict] = []
    used = 0
    for msg in reversed(history):
        msg_tokens = estimate_tokens(msg.get("content", ""))
        if used + msg_tokens > budget and kept:
            # Over budget and we already have the latest message — stop
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


async def stream_chat(messages, thinking_enabled=False, tools=True):
    """Stream chat from llama.cpp. Wrapper around llm.stream_chat that passes chat tool definitions."""
    tool_defs = TOOL_DEFINITIONS if tools else None
    async for event in _llm_stream_chat(messages, tools=tool_defs, thinking_enabled=thinking_enabled):
        yield event


# --- App ---


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    init_tracker_db()
    prune_conversations()
    LOGS_DIR.mkdir(parents=True, exist_ok=True)
    yield


app = FastAPI(title="Gizmo-AI Orchestrator", version="1.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(tracker_router)


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
    return {"status": "ok", "service": "gizmo-orchestrator", "model": MODEL_NAME}


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
            image_data = msg.get("image")  # base64 data URL for vision
            video_frames = msg.get("video_frames")  # list of base64 data URLs
            thinking = msg.get("thinking", False)
            conversation_id = msg.get("conversation_id") or str(uuid.uuid4())
            request_tts = msg.get("tts", False)
            voice_clone_ref = msg.get("voice_clone_ref")  # base64 data URL for voice cloning
            voice_id = msg.get("voice_id")  # saved voice ID for TTS
            context_length = max(2048, min(int(msg.get("context_length", 32768)), 131072))
            regenerate = msg.get("regenerate", False)
            # Resolve voice_id to voice_clone_ref if provided
            if voice_id and not voice_clone_ref:
                voice_clone_ref = voice_data_url(voice_id)
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
                save_message(conversation_id, "user", user_text,
                             image_url=persisted_image_url, video_url=persisted_video_url)

            # Build prompt
            has_vision = bool(image_data or video_frames)
            system_prompt = build_system_prompt(user_text, has_vision=has_vision)
            history_msgs = window_messages(history_msgs, system_prompt, context_length)
            messages = build_messages(history_msgs, system_prompt)

            # Stream response
            full_response = ""
            full_thinking = ""
            executed_tool_calls = []  # Accumulate for DB persistence
            tool_calls_pending = []

            async for event in stream_chat(messages, thinking_enabled=thinking):
                if event["type"] == "thinking":
                    full_thinking += event["content"]
                    await ws.send_json(event)
                elif event["type"] == "token":
                    full_response += event["content"]
                    await ws.send_json(event)
                elif event["type"] == "tool_call":
                    tool_calls_pending.append(event)
                    await ws.send_json({
                        "type": "tool_call",
                        "tool": event["name"],
                        "status": "running",
                    })
                elif event["type"] == "error":
                    error_log.error("[%s] Stream error: %s", trace_id, event["error"])
                    await ws.send_json({
                        "type": "error",
                        "error": event["error"],
                        "trace_id": trace_id,
                    })
                    break

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
                tool_calls_pending = []
                async for event in stream_chat(messages, thinking_enabled=False, tools=True):
                    if event["type"] == "token":
                        full_response += event["content"]
                        await ws.send_json(event)
                    elif event["type"] == "thinking":
                        full_thinking += event["content"]
                        await ws.send_json(event)
                    elif event["type"] == "tool_call":
                        tool_calls_pending.append(event)
                        await ws.send_json({
                            "type": "tool_call",
                            "tool": event["name"],
                            "status": "running",
                        })
                    elif event["type"] == "error":
                        error_log.error("[%s] Stream error (tool round %d): %s", trace_id, tool_round, event["error"])
                        await ws.send_json({
                            "type": "error",
                            "error": event["error"],
                            "trace_id": trace_id,
                        })
                        tool_calls_pending = []  # stop looping on error
                        break

            # TTS if requested
            audio_file_url = ""
            if request_tts and full_response:
                if len(full_response) > 4000:
                    await ws.send_json({
                        "type": "tts_info",
                        "message": f"Audio covers the first ~4,000 of {len(full_response)} characters",
                    })
                audio_data = await synthesize(
                    full_response[:4000],
                    voice_clone_data_url=voice_clone_ref,
                )
                if audio_data:
                    MEDIA_DIR.mkdir(parents=True, exist_ok=True)
                    audio_filename = f"tts-{trace_id}.wav"
                    (MEDIA_DIR / audio_filename).write_bytes(audio_data)
                    audio_file_url = f"/api/media/{audio_filename}"
                    await ws.send_json({
                        "type": "audio",
                        "url": audio_file_url,
                    })

            # Save assistant response (skip if stream errored before any tokens)
            if full_response:
                save_message(conversation_id, "assistant", full_response, full_thinking,
                             audio_url=audio_file_url,
                             tool_calls=executed_tool_calls if executed_tool_calls else None)
                conv_log.info("[%s] ASSISTANT (%s): %s", trace_id, conversation_id, full_response[:500])

            await ws.send_json({
                "type": "done",
                "trace_id": trace_id,
                "conversation_id": conversation_id,
            })

            # Generate title on first exchange (exactly 2 messages: user + assistant)
            msg_count = len(get_conversation_messages(conversation_id))
            if msg_count == 2 and not regenerate:
                asyncio.create_task(generate_title(conversation_id, user_text, full_response, ws))

    except WebSocketDisconnect:
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
):
    """Non-streaming chat endpoint."""
    if not conversation_id:
        conversation_id = str(uuid.uuid4())

    conv_log.info("[REST] USER (%s): %s", conversation_id, message[:500])

    history = get_conversation_messages(conversation_id)
    history_msgs = [{"role": m["role"], "content": m["content"]} for m in history]
    history_msgs.append({"role": "user", "content": message})

    save_message(conversation_id, "user", message)

    system_prompt = build_system_prompt(message)
    context_length = max(2048, min(context_length, 131072))
    history_msgs = window_messages(history_msgs, system_prompt, context_length)
    messages = build_messages(history_msgs, system_prompt)

    full_response = ""
    full_thinking = ""
    max_tool_rounds = 5

    for _ in range(max_tool_rounds):
        tool_calls_pending = []

        async for event in stream_chat(messages, thinking):
            if event["type"] == "token":
                full_response += event["content"]
            elif event["type"] == "thinking":
                full_thinking += event["content"]
            elif event["type"] == "tool_call":
                tool_calls_pending.append(event)
            elif event["type"] == "error":
                error_log.error("[REST] Stream error: %s", event["error"])
                return JSONResponse(status_code=502, content={"error": event["error"]})

        if not tool_calls_pending:
            break

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

    save_message(conversation_id, "assistant", full_response, full_thinking)
    conv_log.info("[REST] ASSISTANT (%s): %s", conversation_id, full_response[:500])

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
            capture_output=True, text=True
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
                capture_output=True
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
    """Serve uploaded media files (videos, etc.)."""
    filepath = (MEDIA_DIR / filename).resolve()
    if not filepath.is_relative_to(MEDIA_DIR.resolve()):
        return JSONResponse(status_code=400, content={"error": "Invalid path"})
    if not filepath.exists():
        return JSONResponse(status_code=404, content={"error": "Not found"})
    # Determine content type from extension
    ext = filepath.suffix.lower()
    ct_map = {".mp4": "video/mp4", ".webm": "video/webm", ".mov": "video/quicktime", ".avi": "video/x-msvideo"}
    content_type = ct_map.get(ext, "application/octet-stream")
    return FileResponse(filepath, media_type=content_type)


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
    conn = get_db()
    try:
        rows = conn.execute(
            """SELECT DISTINCT c.id, c.title, c.updated_at, m.content
               FROM conversations c
               JOIN messages m ON c.id = m.conversation_id
               WHERE LOWER(m.content) LIKE '%' || LOWER(?) || '%'
               ORDER BY c.updated_at DESC
               LIMIT 20""",
            (q,),
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
    lines = [f"# {title}", f"*Exported from Gizmo-AI on {export_date}*", "", "---", ""]
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
    """Execute Python code in the sandbox container."""
    from sandbox import run_code as sandbox_run
    try:
        body = await request.json()
    except Exception:
        return JSONResponse(status_code=400, content={"error": "Invalid JSON"})
    code = body.get("code", "")
    if not code.strip():
        return JSONResponse(status_code=400, content={"error": "No code provided"})
    timeout = min(max(int(body.get("timeout", 10)), 1), 30)
    try:
        result = await sandbox_run(code, timeout)
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
    """Synthesize speech from text via Qwen3-TTS."""
    try:
        body = await request.json()
    except Exception:
        return JSONResponse(status_code=400, content={"error": "Invalid JSON"})

    text = body.get("text", "")
    voice = body.get("voice", "default")
    voice_clone_data_url = body.get("voice_clone_data_url")
    voice_id = body.get("voice_id")
    if not text:
        return JSONResponse(status_code=400, content={"error": "Missing 'text' field"})

    # If voice_id is provided, load the saved voice reference
    if voice_id and not voice_clone_data_url:
        voice_clone_data_url = voice_data_url(voice_id)

    audio = await synthesize(text, voice, voice_clone_data_url=voice_clone_data_url)
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
            ["ffmpeg", "-i", raw_path, "-t", str(dur), "-ar", "16000", "-ac", "1", "-y", wav_path],
            capture_output=True,
        )

    if not os.path.exists(wav_path):
        return JSONResponse(status_code=400, content={"error": "Could not process audio file"})

    wav_size = (VOICES_DIR / f"{voice_id}.wav").stat().st_size

    meta = {
        "id": voice_id,
        "name": name,
        "filename": file.filename,
        "size": wav_size,
    }
    meta_path = VOICES_DIR / f"{voice_id}.json"
    meta_path.write_text(json.dumps(meta))

    return meta


@app.delete("/api/voices/{voice_id}")
async def delete_voice(voice_id: str):
    """Delete a saved voice reference."""
    import re
    if not re.match(r'^[a-f0-9]{8}$', voice_id):
        raise HTTPException(status_code=400, detail="Invalid voice ID format")
    for f in VOICES_DIR.glob(f"{voice_id}.*"):
        f.unlink()
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

    audio = await synthesize(text, voice_clone_data_url=data_url)
    if audio is None:
        return JSONResponse(status_code=503, content={"error": "TTS service unavailable"})
    return Response(content=audio, media_type="audio/wav")


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
