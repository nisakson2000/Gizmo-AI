# Architecture

Full technical reference for Gizmo-AI. Assumes familiarity with containers and REST APIs.

---

## System Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                         HOST MACHINE                              │
│                    (Bazzite OS, RTX 4090)                         │
│                                                                    │
│  ┌─────────────────────── gizmo-net ───────────────────────┐      │
│  │                      10.90.0.0/24                        │      │
│  │                                                          │      │
│  │  ┌──────────┐    ┌──────────────┐    ┌───────────────┐  │      │
│  │  │ gizmo-ui │───▶│ gizmo-       │───▶│ gizmo-llama   │  │      │
│  │  │ :3100    │    │ orchestrator │    │ :8080         │  │      │
│  │  │ SvelteKit│    │ :9100 FastAPI│    │ llama.cpp     │  │      │
│  │  │ nginx    │    └──────┬───────┘    │ Q8_0 9B      │  │      │
│  │  └──────────┘           │            │ + mmproj      │  │      │
│  │              ┌──────────┼────────┐   │ [GPU]         │  │      │
│  │              │          │        │   └───────────────┘  │      │
│  │         ┌────▼─────┐ ┌─▼──────┐ ┌▼────────────┐        │      │
│  │         │gizmo-    │ │gizmo-  │ │gizmo-       │        │      │
│  │         │searxng   │ │tts     │ │whisper      │        │      │
│  │         │:8300     │ │:8400   │ │:8200        │        │      │
│  │         │(8080)    │ │[GPU]   │ │[CPU]        │        │      │
│  │         └──────────┘ └────────┘ └─────────────┘        │      │
│  └────────────────────────────────────────────────────────┘      │
│                                                                    │
│  Volumes: ~/gizmo-ai/models, ~/gizmo-ai/memory, ~/gizmo-ai/logs  │
│           ~/gizmo-ai/voices, ~/gizmo-ai/media                     │
└──────────────────────────────────────────────────────────────────┘
         ▲                                    ▲
  Browser/App                           Tailscale HTTPS
  localhost:3100                 <your-tailscale-hostname>
```

## Container Reference

| Container | Image | Role | Container Port | Host Port | GPU | Depends On |
|-----------|-------|------|---------------|-----------|-----|------------|
| gizmo-llama | gizmo-llama:latest (built from source) | LLM + vision inference | 8080 | 8080 | Yes (RTX 4090) | — |
| gizmo-orchestrator | gizmo-orchestrator:latest (built) | API gateway, routing, tools | 9100 | 9100 | No | gizmo-llama |
| gizmo-ui | gizmo-ui:latest (built) | Web UI (SvelteKit + nginx) | 3100 | 3100 | No | gizmo-orchestrator |
| gizmo-searxng | searxng/searxng:latest | Web search engine | 8080 | 8300 | No | — |
| gizmo-tts | gizmo-tts:latest (built) | Text-to-speech (Qwen3-TTS) | 8400 | 8400 | Yes (RTX 4090) | — |
| gizmo-whisper | fedirz/faster-whisper-server:0.5.0-cpu | Speech-to-text (Whisper) | 8000 | 8200 | No (CPU) | — |

**Volumes:**
- `./models:/models:ro` — Model files (llama, TTS, Whisper cache)
- `./config:/app/config:ro` — Constitution and configs (orchestrator)
- `./memory:/app/memory:rw` — Memory files (orchestrator)
- `./logs:/app/logs:rw` — Runtime logs (orchestrator)
- `./voices:/app/voices:rw` — Saved voice profiles (orchestrator)
- `./media:/app/media:rw` — Uploaded video files and generated documents (orchestrator)
- `MEDIA_HOST_DIR=${PWD}/media` — env var in docker-compose.yml, used by orchestrator for sandbox bind mount extraction
- `./tracker:/app/tracker:rw` — Tracker tasks and notes database (orchestrator)
- `./services/searxng/config:/etc/searxng:rw` — SearXNG config
- `./models/whisper-cache:/root/.cache/huggingface:Z` — Whisper model cache

## Request Lifecycle

Step-by-step walkthrough: user sends "Search for AI news" with thinking mode ON.

1. User types message and clicks Send in the SvelteKit UI
2. UI sends JSON via WebSocket to `ws://gizmo-orchestrator:9100/ws/chat`
3. Message payload: `{"message": "Search for AI news", "thinking": true, "conversation_id": "uuid"}`
4. Orchestrator receives message, loads conversation history from SQLite database
5. Orchestrator loads constitution file, scans memory for relevant files
5a. Router checks for recitation intent (regex detection) — if matched, fetches authoritative text from the web via SearXNG + page scraping and injects it into the system prompt; LLM temperature lowered to 0.2
6. System prompt assembled: constitution.txt + pattern (if any) + recitation content (if any) + relevant memories
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
22. Orchestrator saves messages to SQLite database

## WebSocket Event Protocol

### Server → Client

| Event Type | Fields | Description |
|-----------|--------|-------------|
| `trace_id` | `trace_id` | Unique ID for this request (gizmo-{8hex}) |
| `thinking` | `content` | Thinking block content (streamed incrementally) |
| `token` | `content` | Response token (streamed incrementally) |
| `tool_call` | `tool`, `status` | Tool execution started |
| `tool_result` | `tool`, `result` | Tool execution result |
| `tts_info` | `message` | TTS synthesis status info |
| `audio` | `url` | Audio file URL (server-stored WAV) |
| `title` | `title`, `conversation_id` | LLM-generated conversation title (async, after first exchange) |
| `done` | `trace_id`, `conversation_id` | Generation complete |
| `error` | `error`, `trace_id` | Error occurred |

### Client → Server

```json
{
  "message": "user message text",
  "thinking": false,
  "conversation_id": "uuid-or-null",
  "tts": false,
  "voice_id": "optional-voice-id",
  "tts_speed": 1.0,
  "tts_language": "auto",
  "context_length": 32768,
  "regenerate": false
}
```

## REST API

The orchestrator exposes a non-streaming REST endpoint for programmatic access plus various management endpoints.

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

Supports up to 5 rounds of automatic tool calling per request.

### All REST Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Orchestrator health check |
| `/api/services/health` | GET | Health of all backend services |
| `/api/conversations` | GET | List all conversations (SQLite) |
| `/api/conversations/search` | GET | Full-text search across messages (`?q=query`) |
| `/api/conversations/{id}` | GET | Get conversation messages |
| `/api/conversations/{id}/export` | GET | Export as markdown or JSON (`?format=markdown\|json`) |
| `/api/conversations/{id}` | PATCH | Rename a conversation (JSON: `{"title": "..."}`) |
| `/api/conversations/{id}` | DELETE | Delete a conversation |
| `/api/conversations/{id}/messages-from/{index}` | DELETE | Truncate messages from index onward (0-based) |
| `/api/upload` | POST | Upload document (PDF, text, code — up to 50MB) |
| `/api/upload-image` | POST | Upload image (returns base64 data URL — up to 50MB) |
| `/api/upload-video` | POST | Upload video (frame extraction + server storage — up to 500MB) |
| `/api/transcribe` | POST | Transcribe audio via Whisper |
| `/api/tts` | POST | Synthesize speech (JSON: `text`, `voice_id`, `speed?`, `language?`) |
| `/api/voices` | GET | List saved voice profiles |
| `/api/voices` | POST | Upload and save a voice profile (FormData: file, name, max_duration) |
| `/api/voices/{id}` | DELETE | Delete a saved voice profile |
| `/api/voices/migrate-transcripts` | POST | Backfill Whisper transcripts for existing voice profiles (stored for future use) |
| `/api/tracker/tasks` | GET | List tasks (query: `?status=`, `?tag=`, `?priority=`) |
| `/api/tracker/tasks` | POST | Create task (JSON: title, description, priority, tags, due_date, recurrence) |
| `/api/tracker/tasks/{id}` | GET | Get single task |
| `/api/tracker/tasks/{id}` | PATCH | Update task fields |
| `/api/tracker/tasks/{id}/complete` | PATCH | Toggle task completion |
| `/api/tracker/tasks/{id}` | DELETE | Delete task |
| `/api/tracker/notes` | GET | List notes (query: `?tag=`) |
| `/api/tracker/notes` | POST | Create note (JSON: title, content, tags) |
| `/api/tracker/notes/{id}` | GET | Get single note |
| `/api/tracker/notes/{id}` | PATCH | Update note |
| `/api/tracker/notes/{id}` | DELETE | Delete note |
| `/api/tracker/tags` | GET | List all tags across tasks and notes |
| `ws://…/ws/tracker` | WS | Tracker LLM chat for natural language task creation |
| `ws://…/ws/code-chat` | WS | Code Playground AI assistant (isolated, run_code tool only) |
| `/api/voices/{id}/preview` | POST | Synthesize a short preview with a saved voice (JSON: `text`) |
| `/api/media/{filename}` | GET | Serve uploaded video/media files and generated documents (Content-Disposition: attachment for document types) |
| `/api/logs/{log_name}` | GET | Tail log file (`?lines=100`, max 1000) |
| `/api/search` | GET | Web search via SearXNG (`?q=query`) |
| `/api/memory/list` | GET | List memory files |
| `/api/memory/write` | POST | Write memory file |
| `/api/memory/read` | GET | Read a memory file (`?subdir=&filename=`) |
| `/api/memory/clear` | DELETE | Delete all memory files |
| `/api/memory/{subdir}/{filename}` | DELETE | Delete a specific memory file |
| `/api/run-code` | POST | Execute code in sandbox (JSON: `code`, `language?`, `timeout?`). Languages: python, javascript, bash, c, cpp, go, lua |
| `/api/patterns` | GET | List available analysis patterns (name + description) |

## Pattern System

Gizmo-AI includes a Fabric-inspired pattern library — 30 cognitive templates that give the model structured rubrics for complex tasks.

### How It Works

1. **Router** (`router.py`) — intercepts each user message before LLM processing
   - **Keyword pre-routing**: regex patterns match tool-specific intent (e.g., "generate pdf" → loads `generate_document` tool)
   - **Pattern matching**: keyword matching activates analysis patterns (e.g., "key takeaways" → `extract_wisdom`)
   - **Default fallback**: unmatched messages get the core tool set (web_search, memory, run_code)

2. **Pattern injection** — matched pattern's system prompt is inserted between the constitution and memories in the system prompt

3. **Tool scoping** — each pattern declares which tools should be available, preventing the model from seeing irrelevant tools

### Pattern Directory Structure

```
config/patterns/<name>/
├── config.yaml    # name, description, keywords, tools
└── system.md      # Fabric-style system prompt (IDENTITY, STEPS, OUTPUT INSTRUCTIONS, INPUT)
```

### Explicit Invocation

Prefix any message with `[pattern:name]` to force a specific pattern:
```
[pattern:summarize] <paste article text here>
```

## Thinking Mode Implementation

Qwen3.5-9B is a hybrid thinking model — it always performs chain-of-thought reasoning internally. The orchestrator controls how this reasoning is exposed using llama.cpp's native `enable_thinking` API.

When `enable_thinking` is `true`, llama.cpp separates the model's internal reasoning into a dedicated `reasoning_content` field in the streaming delta. When `false`, the model still thinks internally but the reasoning is not surfaced.

The Think toggle is a pill button in the chat input area (similar to Claude and ChatGPT).

## Memory System

### File Structure
```
memory/
├── conversations.db  # SQLite database (conversations + messages tables)
├── facts/            # Persistent facts (user's name, preferences)
├── conversations/    # Conversation summaries (future use)
└── notes/            # General notes
```

### Injection Logic
1. On each message, the orchestrator tokenizes the user's input (lowercase, stop-word filtered)
2. Memory files are scored via BM25 (TF-IDF ranking) against the tokenized query
3. A recency boost is applied: `score *= 1.0 + 0.5 * max(0, 1 - age_days/30)`
4. Top 5 matches (max 800 chars each) are injected into the system prompt
5. Path traversal protection: `Path.is_relative_to()` validation on all file operations

## Tool Calling

Tools follow the OpenAI function-calling format. llama.cpp supports this natively.

### Available Tools

| Tool | Parameters | Description |
|------|-----------|-------------|
| `web_search` | `query: string` | Search the web via SearXNG |
| `read_memory` | `filename: string, subdir?: string` | Read a memory file |
| `write_memory` | `filename: string, content: string, subdir?: string` | Write a memory file (only when user explicitly asks) |
| `list_memories` | `subdir?: string` | List all memory files |
| `run_code` | `code: string, language?: string, timeout?: int` | Execute code in a sandboxed container. Languages: python, javascript, bash, c, cpp, go, lua (default: python) |
| `generate_document` | `format: string, title: string, content: string` | Generate a document file (PDF, DOCX, XLSX, PPTX, CSV, TXT) via pre-tested Python templates in sandbox. File returned as download link. |

### Execution Flow
1. Tool definitions are included in the API request to llama.cpp
2. Model outputs a structured tool call in the response
3. Orchestrator detects the tool call (via API `finish_reason: "tool_calls"` or JSON parsing)
4. Tool is executed asynchronously
5. Result is added to messages as a `tool` role message
6. Generation resumes with tool results in context — model may issue additional tool calls
7. Up to **5 rounds** of chained tool calls are supported (both WebSocket and REST paths), enabling multi-step workflows like "search for X then save a note about it"

## Constitution System

The model's persona and behavior are defined by a single constitution file:

- **`config/constitution.txt`** — System prompt with XML-tagged sections (`<identity>`, `<style>`, `<capabilities>`, `<tool-discipline>`, `<memory-rules>`, `<code-execution>`, `<document-generation>`, `<web-search>`, `<response-quality>`, `<precision-awareness>`). Lines starting with `#` are stripped as comments. The XML structure helps the 9B model parse and follow different instruction sets without cross-contamination. Includes an explicit 5-step tool decision tree and abliteration-aware precision rules. The `<document-generation>` section instructs the LLM to use the `generate_document` tool (not `run_code`) for file creation. Relevant memories from the BM25 memory system are appended after the constitution content.

## Configuration Files

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
├── AUDIT.md                               # Version audit reports
├── LICENSE                                # MIT license
├── .gitignore                             # Git ignore rules
├── docker-compose.yml                     # Podman compose — all 6 services
├── config/
│   ├── constitution.txt                   # System prompt / persona (prose, # = comments)
│   ├── models.yaml                        # Model configuration
│   ├── services.yaml                      # Service endpoints
│   └── patterns/                          # Fabric-inspired cognitive templates (30 patterns)
│       └── <name>/                        # Each pattern has config.yaml + system.md
│           ├── config.yaml                # Name, description, keywords, scoped tools
│           └── system.md                  # System prompt (IDENTITY, STEPS, OUTPUT, INPUT)
├── services/
│   ├── llama/
│   │   └── Dockerfile                     # llama.cpp from source with CUDA
│   ├── orchestrator/
│   │   ├── Dockerfile                     # Python 3.12 slim
│   │   ├── requirements.txt               # Python dependencies (includes rank_bm25)
│   │   ├── main.py                        # FastAPI app, WebSocket, REST, voice/video/transcribe endpoints
│   │   ├── memory.py                      # BM25-ranked memory system with recency weighting
│   │   ├── sandbox.py                     # Podman sandbox client (7-language code execution via Unix socket)
│   │   ├── code_chat.py                  # Code Playground AI chat WebSocket handler (isolated, run_code only)
│   │   ├── patterns.py                    # Pattern library — loading, caching, keyword matching
│   │   ├── router.py                      # Request router — keyword pre-routing + pattern matching + tool selection
│   │   ├── recite.py                      # Recitation detection and web retrieval pipeline
│   │   ├── search.py                      # SearXNG proxy
│   │   ├── tts.py                         # Qwen3-TTS proxy (voice cloning support)
│   │   ├── web_fetch.py                   # Page fetcher — HTTP GET + BeautifulSoup text extraction
│   │   └── tools.py                       # Tool definitions, registry, and dispatch (web_search, memory, run_code, generate_document)
│   ├── ui/
│   │   ├── Dockerfile                     # Node build → nginx serve
│   │   ├── nginx.conf                     # Static + API/WS proxy (500MB upload limit)
│   │   ├── package.json                   # SvelteKit dependencies
│   │   ├── svelte.config.js               # SvelteKit + static adapter
│   │   ├── vite.config.ts                 # Vite + TailwindCSS
│   │   └── src/
│   │       ├── app.html                   # HTML shell
│   │       ├── app.css                    # TailwindCSS + design tokens
│   │       ├── themes.css                 # Nintendo console theme system (9 themes + console frames)
│   │       ├── routes/+page.svelte        # Main page (mounts all modals)
│   │       ├── routes/+layout.svelte      # Root layout
│   │       └── lib/
│   │           ├── stores/chat.ts         # Conversation state (videoUrl support)
│   │           ├── stores/settings.ts     # User preferences (voiceStudioOpen, memoryManagerOpen, ttsVoiceId)
│   │           ├── stores/code.ts        # Code Playground state (language, persistence helpers)
│   │           ├── stores/connection.ts   # WebSocket state
│   │           ├── stores/theme.ts        # Nintendo theme store (persisted to localStorage)
│   │           ├── stores/toast.ts        # Global toast notification store
│   │           ├── ws/client.ts           # WebSocket manager (sends voice_id when set)
│   │           ├── ws/code-client.ts     # Code Playground AI chat WebSocket client
│   │           ├── utils/sanitize.ts      # HTML sanitization
│   │           ├── actions/highlight.ts   # Code syntax highlighting
│   │           └── components/
│   │               ├── ChatArea.svelte    # Main chat area (8 suggestion cards)
│   │               ├── ChatInput.svelte   # Input with Think/Voice Studio pills
│   │               ├── ChatMessage.svelte # Message display (video player, audio)
│   │               ├── Header.svelte      # Top bar with settings
│   │               ├── Sidebar.svelte     # Conversation list
│   │               ├── Settings.svelte    # Settings panel (TTS voice dropdown, Memory Manager shortcut)
│   │               ├── VoiceStudio.svelte # Voice Studio modal
│   │               ├── MemoryManager.svelte # Memory CRUD modal
│   │               ├── CodePlayground.svelte # Legacy modal (unused — Code Playground is now at /code route)
│   │       ├── routes/code/+page.svelte   # Code Playground page (split-pane, AI chat overlay)
│   │               ├── ThinkingBlock.svelte # Collapsible thinking display
│   │               ├── ToolCallBlock.svelte # Tool call display (includes run_code)
│   │               └── Toast.svelte       # Global toast notification overlay
│   ├── tts/
│   │   ├── Dockerfile                     # Qwen3-TTS container (PyTorch + CUDA)
│   │   ├── requirements.txt               # qwen-tts, fastapi, uvicorn, scipy
│   │   ├── main.py                        # TTS server with voice cloning, caching, chunking, speed control
│   │   └── assets/default_voice.wav       # Default reference voice
│   ├── sandbox/
│   │   └── Dockerfile                     # Python 3.12 slim (numpy, pandas, matplotlib, sympy, scipy, reportlab, openpyxl, python-docx, python-pptx)
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
├── voices/                                # Saved voice profiles (gitignored)
├── media/                                 # Uploaded videos (gitignored)
└── logs/                                  # Runtime logs (gitignored)
```
