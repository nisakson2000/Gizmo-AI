# Architecture

Full technical reference for Gizmo-AI. Assumes familiarity with containers and REST APIs.

---

## System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         HOST MACHINE                            │
│                    (Bazzite OS, RTX 4090)                       │
│                                                                 │
│  ┌──────────────────────────── gizmo-net ──────────────────┐   │
│  │                        10.90.0.0/24                      │   │
│  │                                                          │   │
│  │  ┌──────────┐    ┌──────────────┐    ┌───────────────┐  │   │
│  │  │ gizmo-ui │───▶│ gizmo-       │───▶│ gizmo-llama   │  │   │
│  │  │ :3100    │    │ orchestrator │    │ :8080         │  │   │
│  │  │ SvelteKit│    │ :9100 FastAPI│    │ llama.cpp     │  │   │
│  │  │ nginx    │    └──────┬───────┘    │ Q8_0 9B      │  │   │
│  │  └──────────┘           │            │ [GPU]         │  │   │
│  │              ┌──────────┼────────┐   └───────────────┘  │   │
│  │              │          │        │                       │   │
│  │   ┌──────────▼──┐  ┌───▼─────┐  ┌▼──────────┐          │   │
│  │   │gizmo-whisper│  │gizmo-   │  │gizmo-     │          │   │
│  │   │:8200 (8000) │  │searxng  │  │qwen3-tts  │          │   │
│  │   │Whisper STT  │  │:8300    │  │:8400      │          │   │
│  │   │[CPU]        │  │(8080)   │  │TTS [GPU]  │          │   │
│  │   └─────────────┘  └─────────┘  └───────────┘          │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│  Volumes: ~/gizmo-ai/models, ~/gizmo-ai/memory, ~/gizmo-ai/logs│
└─────────────────────────────────────────────────────────────────┘
         ▲                                    ▲
  Browser/App                           Tailscale
  localhost:3100                        (remote access)
```

## Container Reference

| Container | Image | Role | Container Port | Host Port | GPU | Depends On |
|-----------|-------|------|---------------|-----------|-----|------------|
| gizmo-llama | gizmo-llama:latest (built from source) | LLM inference server | 8080 | 8080 | Yes (RTX 4090) | — |
| gizmo-orchestrator | gizmo-orchestrator:latest (built) | API gateway, routing, tools | 9100 | 9100 | No | gizmo-llama |
| gizmo-ui | gizmo-ui:latest (built) | Web UI (SvelteKit + nginx) | 3100 | 3100 | No | gizmo-orchestrator |
| gizmo-whisper | fedirz/faster-whisper-server:latest-cpu | Speech-to-text | 8000 | 8200 | No | — |
| gizmo-searxng | searxng/searxng:latest | Web search engine | 8080 | 8300 | No | — |
| gizmo-tts | gizmo-tts:latest (built) | Text-to-speech (Qwen3-TTS) | 8400 | 8400 | Yes (RTX 4090) | — |

**Volumes:**
- `./models:/models:ro` — Model files (llama container)
- `./config:/app/config:ro` — Constitution and configs (orchestrator)
- `./memory:/app/memory:rw` — SQLite DB and memory files (orchestrator)
- `./logs:/app/logs:rw` — Runtime logs (orchestrator)
- `./services/searxng/config:/etc/searxng:rw` — SearXNG config

## Request Lifecycle

Step-by-step walkthrough: user sends "Search for AI news" with thinking mode ON.

1. User types message and clicks Send in the SvelteKit UI
2. UI sends JSON via WebSocket to `ws://gizmo-orchestrator:9100/ws/chat`
3. Message payload: `{"message": "Search for AI news", "thinking": true, "conversation_id": "uuid"}`
4. Orchestrator receives message, loads conversation history from SQLite
5. Orchestrator loads `constitution.txt`, scans memory for relevant files
6. System prompt assembled: constitution + relevant memories
7. Messages array built in OpenAI format: `[system, ...history, user]`
8. Orchestrator POSTs to `http://gizmo-llama:8080/v1/chat/completions` with `stream: true`, `enable_thinking: true`, and tool definitions
9. Model begins generating — llama.cpp separates reasoning into `reasoning_content` field
10. Thinking tokens streamed as `{"type": "thinking", "content": "..."}` events to UI
11. Model finishes reasoning, orchestrator switches to `{"type": "token"}` events for response content
13. Model outputs tool call: `web_search({"query": "AI news"})`
14. Orchestrator sends `{"type": "tool_call", "tool": "web_search", "status": "running"}` to UI
15. Orchestrator queries SearXNG at `http://gizmo-searxng:8080/search?q=AI+news&format=json`
16. Top 5 results formatted and injected as tool result message
17. Orchestrator sends `{"type": "tool_result", "tool": "web_search", "result": "..."}` to UI
18. Orchestrator resumes llama.cpp with tool results in context
19. Final response tokens stream as `{"type": "token"}` events
20. Stream ends → orchestrator sends `{"type": "done", "trace_id": "gizmo-abc123"}`
21. UI renders complete message with collapsed thinking block above response
22. Orchestrator saves user message and assistant response to SQLite

## WebSocket Event Protocol

### Server → Client

| Event Type | Fields | Description |
|-----------|--------|-------------|
| `trace_id` | `trace_id` | Unique ID for this request (gizmo-{8hex}) |
| `thinking` | `content` | Thinking block content (streamed incrementally) |
| `token` | `content` | Response token (streamed incrementally) |
| `tool_call` | `tool`, `status` | Tool execution started |
| `tool_result` | `tool`, `result` | Tool execution result |
| `audio` | `url` | Audio data URL (base64 WAV) |
| `done` | `trace_id`, `conversation_id` | Generation complete |
| `error` | `error`, `trace_id` | Error occurred |

### Client → Server

```json
{
  "message": "user message text",
  "thinking": false,
  "conversation_id": "uuid-or-null",
  "tts": false
}
```

## REST API

The orchestrator also exposes a non-streaming REST endpoint for programmatic access.

### `POST /api/chat`

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `message` | Form string | `""` | User message text |
| `thinking` | Form bool | `false` | Enable thinking mode |
| `conversation_id` | Form string | `""` | Conversation ID (auto-generated if empty) |

**Response:**
```json
{
  "response": "assistant response text",
  "thinking": "reasoning content (if thinking enabled)",
  "conversation_id": "uuid"
}
```

Supports up to 5 rounds of automatic tool calling per request. The model can chain tool calls (e.g., search the web, then use the results to answer).

### Other REST Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Orchestrator health check |
| `/api/services/health` | GET | Health of all 6 backend services |
| `/api/conversations` | GET | List all conversations |
| `/api/conversations/{id}` | GET | Get conversation messages |
| `/api/conversations/{id}` | DELETE | Delete a conversation |
| `/api/upload` | POST | Upload document (PDF, text, code) |
| `/api/upload-image` | POST | Upload image (returns base64 data URL) |
| `/api/transcribe` | POST | Transcribe audio via Whisper |
| `/api/tts` | POST | Synthesize speech (JSON body: `text`, `voice`) |
| `/api/search` | GET | Web search via SearXNG (`?q=query`) |
| `/api/memory/list` | GET | List memory files |
| `/api/memory/write` | POST | Write memory file |

## Thinking Mode Implementation

Qwen3.5-9B is a hybrid thinking model — it always performs chain-of-thought reasoning internally. The orchestrator controls how this reasoning is exposed using llama.cpp's native `enable_thinking` API.

```python
payload = {
    "model": "gizmo",
    "messages": messages,
    "stream": True,
    "enable_thinking": thinking_enabled,  # Controls reasoning separation
}
```

When `enable_thinking` is `true`, llama.cpp separates the model's internal reasoning into a dedicated `reasoning_content` field in the streaming delta, distinct from the `content` field that holds the actual response. When `false`, the model still thinks internally but the reasoning is not surfaced.

The orchestrator's stream parser yields different event types based on these fields:

- `delta.reasoning_content` → `{"type": "thinking", "content": "..."}` — rendered as a collapsible block in the UI
- `delta.content` → `{"type": "token", "content": "..."}` — rendered as the assistant's response

**Note:** The model always produces `reasoning_content` regardless of the `enable_thinking` setting — the parameter controls whether llama.cpp separates it into its own field or folds it into the response.

## Memory System

### File Structure
```
memory/
├── facts/          # Persistent facts (user's name, preferences)
├── conversations/  # Conversation summaries (future use)
├── notes/          # General notes
└── conversations.db  # SQLite — conversation history
```

### Injection Logic
1. On each message, the orchestrator extracts keywords from the user's input
2. Memory files are scanned for keyword matches (filename and content)
3. Top 5 matches (max 300 chars each) are injected into the system prompt
4. Path traversal protection: filenames sanitized to `[a-zA-Z0-9_\-.]` only

### v2 Plan
Replace keyword matching with ChromaDB vector store for semantic similarity search. Memories would be embedded as vectors and retrieved by meaning, not literal keyword overlap.

## Tool Calling

Tools follow the OpenAI function-calling format. llama.cpp supports this natively.

### Available Tools

| Tool | Parameters | Description |
|------|-----------|-------------|
| `web_search` | `query: string` | Search the web via SearXNG |
| `read_memory` | `filename: string, subdir?: string` | Read a memory file |
| `write_memory` | `filename: string, content: string, subdir?: string` | Write a memory file |
| `list_memories` | `subdir?: string` | List all memory files |

### Execution Flow
1. Tool definitions are included in the API request to llama.cpp
2. Model outputs a structured tool call in the response
3. Orchestrator detects the tool call (via API `finish_reason: "tool_calls"` or JSON parsing)
4. Tool is executed asynchronously
5. Result is added to messages as a `tool` role message
6. Generation resumes with tool results in context

## Configuration Files

### constitution.txt
Plain text system prompt. Lines starting with `#` are stripped as comments. Defines model identity, capabilities, and communication style.

### models.yaml
```yaml
default_model: huihui-qwen35-9b
models:
  huihui-qwen35-9b:
    name: "Huihui-Qwen3.5-9B Abliterated"
    file: "Huihui-Qwen3.5-9B-abliterated.Q8_0.gguf"
    mmproj: "mmproj/Huihui-Qwen3.5-9B-abliterated.mmproj-Q8_0.gguf"
    architecture: qwen3_5
    parameters: 9B
    quantization: Q8_0
    context_window: 262144
    context_limit: 32768
    thinking_capable: true
    vision_capable: true
    gpu_layers: 99
    vram_required_gb: 12
```

### services.yaml
Defines all service endpoints, ports, and health check paths. Used by scripts and future service discovery.

## File Tree

```
~/gizmo-ai/
├── CLAUDE.md                              # Claude Code session knowledge
├── README.md                              # Public-facing documentation
├── LICENSE                                # MIT license
├── .gitignore                             # Git ignore rules
├── docker-compose.yml                     # Podman compose — all 6 services
├── config/
│   ├── constitution.txt                   # System prompt / persona
│   ├── models.yaml                        # Model configuration
│   └── services.yaml                      # Service endpoints
├── services/
│   ├── llama/
│   │   └── Dockerfile                     # llama.cpp from source with CUDA
│   ├── orchestrator/
│   │   ├── Dockerfile                     # Python 3.12 slim
│   │   ├── requirements.txt               # Python dependencies
│   │   ├── main.py                        # FastAPI app, WebSocket, REST
│   │   ├── router.py                      # Route placeholder (v2)
│   │   ├── memory.py                      # File-based memory system
│   │   ├── search.py                      # SearXNG proxy
│   │   ├── tts.py                         # Qwen3-TTS proxy
│   │   └── tools.py                       # Tool definitions and dispatch
│   ├── ui/
│   │   ├── Dockerfile                     # Node build → nginx serve
│   │   ├── nginx.conf                     # Static + API/WS proxy
│   │   ├── package.json                   # SvelteKit dependencies
│   │   ├── svelte.config.js               # SvelteKit + static adapter
│   │   ├── vite.config.ts                 # Vite + TailwindCSS
│   │   └── src/
│   │       ├── app.html                   # HTML shell
│   │       ├── app.css                    # TailwindCSS + design tokens
│   │       ├── routes/+page.svelte        # Main page
│   │       ├── routes/+layout.svelte      # Root layout
│   │       └── lib/
│   │           ├── stores/chat.ts         # Conversation state
│   │           ├── stores/settings.ts     # User preferences
│   │           ├── stores/connection.ts   # WebSocket state
│   │           ├── ws/client.ts           # WebSocket manager
│   │           └── components/            # UI components
│   ├── tts/
│   │   ├── Dockerfile                     # Qwen3-TTS container (PyTorch + CUDA)
│   │   ├── requirements.txt               # qwen-tts, fastapi, uvicorn
│   │   ├── main.py                        # TTS server with voice cloning
│   │   └── assets/default_voice.wav       # Default reference voice
│   └── searxng/
│       └── config/settings.yml            # SearXNG configuration
├── scripts/
│   ├── start.sh                           # Start all services (ordered)
│   ├── stop.sh                            # Stop all services
│   ├── health.sh                          # Check all service health
│   ├── build-llamacpp.sh                  # Build llama.cpp image
│   └── download-model.sh                  # Download model from HuggingFace
├── wiki/                                  # Documentation
├── models/                                # Model files (gitignored)
├── memory/                                # Persistent memory (gitignored)
└── logs/                                  # Runtime logs (gitignored)
```
