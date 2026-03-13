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
│  │  │ nginx    │    └──────┬───────┘    │ Q5_K_M 27B   │  │   │
│  │  └──────────┘           │            │ [GPU]         │  │   │
│  │              ┌──────────┼────────┐   └───────────────┘  │   │
│  │              │          │        │                       │   │
│  │   ┌──────────▼──┐  ┌───▼─────┐  ┌▼──────────┐          │   │
│  │   │gizmo-whisper│  │gizmo-   │  │gizmo-     │          │   │
│  │   │:8200 (8000) │  │searxng  │  │kokoro     │          │   │
│  │   │Whisper STT  │  │:8300    │  │:8400(8880)│          │   │
│  │   │[CPU]        │  │(8080)   │  │TTS [CPU]  │          │   │
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
| gizmo-kokoro | ghcr.io/remsky/kokoro-fastapi-cpu:v0.2.2 | Text-to-speech | 8880 | 8400 | No | — |

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
8. Thinking mode is ON → orchestrator appends partial assistant message: `{"role": "assistant", "content": "<think>\n"}`
9. Orchestrator POSTs to `http://gizmo-llama:8080/v1/chat/completions` with `stream: true` and tool definitions
10. Model begins generating — orchestrator detects `<think>` block
11. Thinking tokens streamed as `{"type": "thinking", "content": "..."}` events to UI
12. Model closes think block with `</think>`, orchestrator switches to `{"type": "token"}` events
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
| `image` | `url` | Image data URL |
| `audio` | `url` | Audio data URL (base64 MP3) |
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

## Thinking Mode Implementation

Qwen3.5-27B uses `<think>...</think>` tags for chain-of-thought reasoning.

```python
THINK_START = "<think>\n"    # Opens thinking block
THINK_STOP = "\n</think>\n\n"  # Immediately closes thinking block
```

**Token injection** works by appending a partial assistant message to the messages array before sending to llama.cpp:

- **Thinking ON**: `{"role": "assistant", "content": "<think>\n"}` — model continues reasoning inside the think block
- **Thinking OFF**: `{"role": "assistant", "content": "\n</think>\n\n"}` — model sees the think block as already closed and jumps to the response

The orchestrator's stream parser separates content inside `<think>...</think>` from the final response, sending them as different event types.

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

### Available Tools (v1)

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
default_model: huihui-qwen35-27b
models:
  huihui-qwen35-27b:
    name: "Huihui-Qwen3.5-27B Abliterated"
    file: "Huihui-Qwen3.5-27B-abliterated.i1-Q5_K_M.gguf"
    architecture: qwen3_5
    parameters: 27B
    quantization: Q5_K_M
    context_limit: 16384
    thinking_capable: true
    vision_capable: true
    gpu_layers: 99
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
│   │   ├── tts.py                         # Kokoro TTS proxy
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
