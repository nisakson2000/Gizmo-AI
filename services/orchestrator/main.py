"""Gizmo-AI Orchestrator — FastAPI backend for the local AI assistant."""

import json
import os
import re
import sqlite3
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import httpx
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, UploadFile, File, Form
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response

from memory import (
    get_relevant_memories,
    list_memories,
    read_memory,
    write_memory,
    delete_memory,
)
from search import web_search, format_search_results
from tools import TOOL_DEFINITIONS, execute_tool
from tts import synthesize, check_health as tts_health

# --- Config ---

LLAMA_HOST = os.getenv("LLAMA_HOST", "gizmo-llama")
LLAMA_PORT = os.getenv("LLAMA_PORT", "8080")
LLAMA_URL = f"http://{LLAMA_HOST}:{LLAMA_PORT}"

WHISPER_HOST = os.getenv("WHISPER_HOST", "gizmo-whisper")
WHISPER_PORT = os.getenv("WHISPER_PORT", "8000")
WHISPER_URL = f"http://{WHISPER_HOST}:{WHISPER_PORT}"

SEARXNG_HOST = os.getenv("SEARXNG_HOST", "gizmo-searxng")
SEARXNG_PORT = os.getenv("SEARXNG_PORT", "8080")

KOKORO_HOST = os.getenv("KOKORO_HOST", "gizmo-kokoro")
KOKORO_PORT = os.getenv("KOKORO_PORT", "8880")

CONSTITUTION_PATH = Path("/app/config/constitution.txt")
DB_PATH = Path("/app/memory/conversations.db")
LOGS_DIR = Path("/app/logs")


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


def build_system_prompt(user_message: str = "") -> str:
    """Build the full system prompt with constitution and relevant memories."""
    constitution = load_constitution()
    parts = [constitution]

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
        "enable_thinking": thinking_enabled,
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

            async for line in resp.aiter_lines():
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
        ("whisper", f"{WHISPER_URL}/health"),
        ("searxng", f"http://{SEARXNG_HOST}:{SEARXNG_PORT}/"),
        ("kokoro", f"http://{KOKORO_HOST}:{KOKORO_PORT}/health"),
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
            thinking = msg.get("thinking", False)
            conversation_id = msg.get("conversation_id") or str(uuid.uuid4())
            request_tts = msg.get("tts", False)
            trace_id = f"gizmo-{uuid.uuid4().hex[:8]}"

            await ws.send_json({"type": "trace_id", "trace_id": trace_id})

            # Load conversation history
            history = get_conversation_messages(conversation_id)
            history_msgs = [{"role": m["role"], "content": m["content"]} for m in history]
            history_msgs.append({"role": "user", "content": user_text})

            # Save user message
            save_message(conversation_id, "user", user_text)

            # Build prompt
            system_prompt = build_system_prompt(user_text)
            messages = build_messages(history_msgs, system_prompt)

            # Stream response
            full_response = ""
            full_thinking = ""
            tool_calls_pending = []

            async for event in stream_chat(messages, thinking):
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
                    await ws.send_json({
                        "type": "error",
                        "error": event["error"],
                        "trace_id": trace_id,
                    })
                    break

            # Execute tool calls if any
            if tool_calls_pending:
                for tc in tool_calls_pending:
                    try:
                        args = json.loads(tc["arguments"]) if isinstance(tc["arguments"], str) else tc["arguments"]
                        result = await execute_tool(tc["name"], args)
                    except Exception as e:
                        result = f"Tool error: {str(e)}"

                    await ws.send_json({
                        "type": "tool_result",
                        "tool": tc["name"],
                        "result": result,
                    })

                    # Add tool result to messages and continue generation
                    messages.append({"role": "assistant", "content": full_response, "tool_calls": [{
                        "id": tc["id"],
                        "type": "function",
                        "function": {"name": tc["name"], "arguments": tc["arguments"]},
                    }]})
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
                audio_data = await synthesize(full_response[:1000])
                if audio_data:
                    import base64
                    audio_b64 = base64.b64encode(audio_data).decode()
                    await ws.send_json({
                        "type": "audio",
                        "url": f"data:audio/mp3;base64,{audio_b64}",
                    })

            # Save assistant response
            save_message(conversation_id, "assistant", full_response, full_thinking)

            await ws.send_json({
                "type": "done",
                "trace_id": trace_id,
                "conversation_id": conversation_id,
            })

    except WebSocketDisconnect:
        pass


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
            try:
                args = json.loads(tc["arguments"])
                result = await execute_tool(tc["name"], args)
            except Exception as e:
                result = f"Tool error: {e}"
            messages.append({
                "role": "tool",
                "tool_call_id": tc["id"],
                "content": result,
            })

    save_message(conversation_id, "assistant", full_response, full_thinking)

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
    import base64
    content = await file.read()
    b64 = base64.b64encode(content).decode()
    mime = file.content_type or "image/png"
    return {
        "filename": file.filename,
        "mime": mime,
        "data_url": f"data:{mime};base64,{b64}",
        "size": len(content),
    }


# --- Transcription ---


@app.post("/api/transcribe")
async def transcribe(file: UploadFile = File(...)):
    """Transcribe audio via Whisper."""
    content = await file.read()
    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            resp = await client.post(
                f"{WHISPER_URL}/v1/audio/transcriptions",
                files={"file": (file.filename or "audio.webm", content, file.content_type or "audio/webm")},
                data={"model": "Systran/faster-whisper-large-v3"},
            )
            resp.raise_for_status()
            return resp.json()
    except httpx.ConnectError:
        return JSONResponse(status_code=503, content={"error": "Whisper service unavailable"})
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})


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


@app.post("/api/memory/write")
async def api_write_memory(
    filename: str = Form(...),
    content: str = Form(...),
    subdir: str = Form("facts"),
):
    result = write_memory(filename, content, subdir)
    return {"result": result}


# --- Search ---


@app.get("/api/search")
async def api_search(q: str):
    results = await web_search(q)
    return {"query": q, "results": results}


# --- TTS ---


@app.post("/api/tts")
async def api_tts(
    text: str = Form(...),
    voice: str = Form("af_heart"),
):
    audio = await synthesize(text, voice)
    if audio is None:
        return JSONResponse(status_code=503, content={"error": "TTS service unavailable"})
    return Response(content=audio, media_type="audio/mp3")
