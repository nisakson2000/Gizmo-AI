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
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect, UploadFile, File, Form
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response

from memory import get_relevant_memories, list_memories, write_memory, read_memory, delete_memory
from search import web_search
from tools import TOOL_DEFINITIONS, execute_tool
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
    conn.commit()
    conn.close()


def get_db():
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    return conn


def save_message(conversation_id: str, role: str, content: str, thinking: str = ""):
    """Save a message to the database."""
    conn = get_db()
    now = datetime.now(timezone.utc).isoformat()

    # Create conversation if it doesn't exist
    existing = conn.execute(
        "SELECT id FROM conversations WHERE id = ?", (conversation_id,)
    ).fetchone()
    if not existing:
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

    conn.execute(
        "INSERT INTO messages (conversation_id, role, content, thinking, timestamp) VALUES (?, ?, ?, ?, ?)",
        (conversation_id, role, content, thinking, now),
    )
    conn.commit()
    conn.close()


def get_conversation_messages(conversation_id: str) -> list[dict]:
    """Get all messages for a conversation."""
    conn = get_db()
    rows = conn.execute(
        "SELECT role, content, thinking, timestamp FROM messages WHERE conversation_id = ? ORDER BY id",
        (conversation_id,),
    ).fetchall()
    conn.close()
    return [dict(r) for r in rows]


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


def build_messages(
    history: list[dict], system_prompt: str
) -> list[dict]:
    """Build the messages array for llama.cpp."""
    messages = [{"role": "system", "content": system_prompt}]
    for msg in history:
        messages.append({"role": msg["role"], "content": msg["content"]})
    return messages


# --- Streaming ---


async def stream_chat(
    messages: list[dict],
    thinking_enabled: bool = False,
    tools: bool = True,
):
    """Stream chat completion from llama.cpp, yielding parsed events.

    Uses llama.cpp's native enable_thinking API which separates thinking
    into reasoning_content field in the streaming delta.
    """
    payload = {
        "model": "gizmo",
        "messages": messages,
        "stream": True,
        "max_tokens": 8192,
        "temperature": 0.7,
        "top_p": 0.9,
        "chat_template_kwargs": {"enable_thinking": thinking_enabled},
    }

    if tools:
        payload["tools"] = TOOL_DEFINITIONS

    async with httpx.AsyncClient(timeout=300.0) as client:
        async with client.stream(
            "POST",
            f"{LLAMA_URL}/v1/chat/completions",
            json=payload,
        ) as resp:
            if resp.status_code != 200:
                body = await resp.aread()
                yield {"type": "error", "error": f"llama.cpp error {resp.status_code}: {body.decode()}"}
                return

            tool_calls_accum = {}
            line_iter = resp.aiter_lines().__aiter__()

            while True:
                try:
                    async with asyncio.timeout(60):
                        line = await line_iter.__anext__()
                except StopAsyncIteration:
                    break
                except TimeoutError:
                    yield {"type": "error", "error": "Model response timed out (no activity for 60s). Try again."}
                    return

                if not line.startswith("data: "):
                    continue
                data_str = line[6:]
                if data_str.strip() == "[DONE]":
                    break

                try:
                    data = json.loads(data_str)
                except json.JSONDecodeError:
                    continue

                choice = data.get("choices", [{}])[0]
                delta = choice.get("delta", {})
                finish = choice.get("finish_reason")

                # Handle tool calls from structured API response
                if "tool_calls" in delta:
                    for tc in delta["tool_calls"]:
                        idx = tc.get("index", 0)
                        if idx not in tool_calls_accum:
                            tool_calls_accum[idx] = {
                                "id": tc.get("id", f"call_{idx}"),
                                "name": "",
                                "arguments": "",
                            }
                        if "function" in tc:
                            if "name" in tc["function"]:
                                tool_calls_accum[idx]["name"] = tc["function"]["name"]
                            if "arguments" in tc["function"]:
                                tool_calls_accum[idx]["arguments"] += tc["function"]["arguments"]
                    continue

                # Handle reasoning_content (thinking tokens)
                reasoning = delta.get("reasoning_content")
                if reasoning:
                    yield {"type": "thinking", "content": reasoning}

                # Handle content (response tokens)
                content = delta.get("content")
                if content:
                    yield {"type": "token", "content": content}

                # Handle finish with tool calls
                if finish == "tool_calls" and tool_calls_accum:
                    for idx in sorted(tool_calls_accum.keys()):
                        tc = tool_calls_accum[idx]
                        yield {
                            "type": "tool_call",
                            "id": tc["id"],
                            "name": tc["name"],
                            "arguments": tc["arguments"],
                        }


# --- App ---


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    LOGS_DIR.mkdir(parents=True, exist_ok=True)
    yield


app = FastAPI(title="Gizmo-AI Orchestrator", version="1.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# --- Health ---


@app.get("/health")
async def health():
    return {"status": "ok", "service": "gizmo-orchestrator"}


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
            # Resolve voice_id to voice_clone_ref if provided
            if voice_id and not voice_clone_ref:
                meta_path = VOICES_DIR / f"{voice_id}.json"
                if meta_path.exists():
                    try:
                        meta = json.loads(meta_path.read_text())
                        voice_clone_ref = meta.get("data_url")
                    except Exception:
                        pass
            trace_id = f"gizmo-{uuid.uuid4().hex[:8]}"

            await ws.send_json({"type": "trace_id", "trace_id": trace_id})
            conv_log.info("[%s] USER (%s): %s", trace_id, conversation_id, user_text[:500])

            # Load conversation history
            history = get_conversation_messages(conversation_id)
            history_msgs = [{"role": m["role"], "content": m["content"]} for m in history]

            # Build user message — multimodal if image/video attached
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

            # Save user message
            save_message(conversation_id, "user", user_text)

            # Build prompt
            has_vision = bool(image_data or video_frames)
            system_prompt = build_system_prompt(user_text, has_vision=has_vision)
            messages = build_messages(history_msgs, system_prompt)

            # Stream response
            full_response = ""
            full_thinking = ""
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

            # Execute tool calls if any
            if tool_calls_pending:
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
                    conv_log.info("[%s] TOOL_CALL: %s(%s)", trace_id, tc["name"], tc["arguments"][:200])
                    try:
                        args = json.loads(tc["arguments"]) if isinstance(tc["arguments"], str) else tc["arguments"]
                        result = await execute_tool(tc["name"], args)
                    except Exception as e:
                        result = f"Tool error: {str(e)}"
                        error_log.error("[%s] Tool '%s' failed: %s", trace_id, tc["name"], e, exc_info=True)

                    conv_log.info("[%s] TOOL_RESULT: %s → %s", trace_id, tc["name"], result[:300])
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
                async for event in stream_chat(messages, thinking_enabled=False, tools=False):
                    if event["type"] == "token":
                        full_response += event["content"]
                        await ws.send_json(event)
                    elif event["type"] == "thinking":
                        full_thinking += event["content"]
                        await ws.send_json(event)

            # TTS if requested
            if request_tts and full_response:
                audio_data = await synthesize(
                    full_response[:4000],
                    voice_clone_data_url=voice_clone_ref,
                )
                if audio_data:
                    audio_b64 = base64.b64encode(audio_data).decode()
                    await ws.send_json({
                        "type": "audio",
                        "url": f"data:audio/wav;base64,{audio_b64}",
                    })

            # Save assistant response
            save_message(conversation_id, "assistant", full_response, full_thinking)
            conv_log.info("[%s] ASSISTANT (%s): %s", trace_id, conversation_id, full_response[:500])

            await ws.send_json({
                "type": "done",
                "trace_id": trace_id,
                "conversation_id": conversation_id,
            })

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
        video_path = os.path.join(tmpdir, file.filename or "video.mp4")
        with open(video_path, "wb") as f:
            f.write(content)

        # Get video duration
        probe = subprocess.run(
            ["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
             "-of", "csv=p=0", video_path],
            capture_output=True, text=True
        )
        try:
            duration = float(probe.stdout.strip())
        except (ValueError, AttributeError):
            return JSONResponse(status_code=400, content={"error": "Could not read video duration"})

        # Extract frames evenly spaced
        frames_dir = os.path.join(tmpdir, "frames")
        os.makedirs(frames_dir)

        if duration <= 0:
            return JSONResponse(status_code=400, content={"error": "Invalid video"})

        # Calculate timestamps for evenly spaced frames
        num_frames = min(max_frames, max(1, int(duration)))
        interval = duration / (num_frames + 1)
        timestamps = [interval * (i + 1) for i in range(num_frames)]

        frame_data_urls = []
        for i, ts in enumerate(timestamps):
            frame_path = os.path.join(frames_dir, f"frame_{i:03d}.jpg")
            subprocess.run(
                ["ffmpeg", "-ss", str(ts), "-i", video_path,
                 "-frames:v", "1", "-q:v", "3", "-y", frame_path],
                capture_output=True
            )
            if os.path.exists(frame_path):
                with open(frame_path, "rb") as fp:
                    b64 = base64.b64encode(fp.read()).decode()
                frame_data_urls.append(f"data:image/jpeg;base64,{b64}")

        if not frame_data_urls:
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
    return Response(content=filepath.read_bytes(), media_type=content_type)


# --- Conversations ---


@app.get("/api/conversations")
async def list_conversations():
    conn = get_db()
    rows = conn.execute(
        "SELECT id, title, created_at, updated_at FROM conversations ORDER BY updated_at DESC"
    ).fetchall()
    conn.close()
    return [dict(r) for r in rows]


@app.get("/api/conversations/{conv_id}")
async def get_conversation(conv_id: str):
    messages = get_conversation_messages(conv_id)
    if not messages:
        return JSONResponse(status_code=404, content={"error": "Conversation not found"})
    return {"id": conv_id, "messages": messages}


@app.delete("/api/conversations/{conv_id}")
async def delete_conversation(conv_id: str):
    conn = get_db()
    conn.execute("DELETE FROM messages WHERE conversation_id = ?", (conv_id,))
    conn.execute("DELETE FROM conversations WHERE id = ?", (conv_id,))
    conn.commit()
    conn.close()
    return {"deleted": conv_id}


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
    for subdir in ["facts", "conversations", "notes"]:
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


@app.get("/api/logs/{log_name}")
async def get_logs(log_name: str, lines: int = 100):
    """Read the last N lines from a log file."""
    allowed = {"error": "error.log", "conversations": "conversations.log"}
    if log_name not in allowed:
        return JSONResponse(status_code=400, content={"error": f"Unknown log. Use: {', '.join(allowed)}"})
    log_path = LOGS_DIR / allowed[log_name]
    if not log_path.exists():
        return {"log": log_name, "lines": []}
    all_lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
    return {"log": log_name, "lines": all_lines[-lines:]}


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
        meta_path = VOICES_DIR / f"{voice_id}.json"
        if meta_path.exists():
            meta = json.loads(meta_path.read_text())
            voice_clone_data_url = meta.get("data_url")

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
        raw_path = os.path.join(tmpdir, file.filename or "voice.wav")
        with open(raw_path, "wb") as f:
            f.write(content)

        wav_path = str(VOICES_DIR / f"{voice_id}.wav")
        subprocess.run(
            ["ffmpeg", "-i", raw_path, "-t", str(dur), "-ar", "16000", "-ac", "1", "-y", wav_path],
            capture_output=True,
        )

    if not os.path.exists(wav_path):
        return JSONResponse(status_code=400, content={"error": "Could not process audio file"})

    trimmed = Path(wav_path).read_bytes()
    b64_data = base64.b64encode(trimmed).decode()

    meta = {
        "id": voice_id,
        "name": name,
        "filename": file.filename,
        "size": len(trimmed),
        "data_url": f"data:audio/wav;base64,{b64_data}",
    }
    meta_path = VOICES_DIR / f"{voice_id}.json"
    meta_path.write_text(json.dumps(meta))

    return meta


@app.delete("/api/voices/{voice_id}")
async def delete_voice(voice_id: str):
    """Delete a saved voice reference."""
    # Remove audio file (any extension)
    for f in VOICES_DIR.glob(f"{voice_id}.*"):
        f.unlink()
    return {"status": "deleted"}


@app.post("/api/voices/{voice_id}/preview")
async def preview_voice(voice_id: str, request: Request):
    """Synthesize a short preview with a saved voice."""
    meta_path = VOICES_DIR / f"{voice_id}.json"
    if not meta_path.exists():
        return JSONResponse(status_code=404, content={"error": "Voice not found"})

    meta = json.loads(meta_path.read_text())
    body = await request.json()
    text = body.get("text", "Hello, this is a voice preview.")

    audio = await synthesize(text, voice_clone_data_url=meta["data_url"])
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
