# Gizmo-AI — Claude Code Session Knowledge

This file is maintained automatically across all Claude Code sessions.
Read it at the start of every session. Update it at the end of every session.

## Project Overview
Gizmo-AI is a local AI assistant built on Huihui-Qwen3.5-9B-abliterated (Q8_0),
served via llama.cpp, orchestrated by a Python FastAPI backend, and accessed
via a custom SvelteKit web UI. Text-to-speech uses Qwen3-TTS-12Hz-1.7B-Base
(GPU-accelerated, voice cloning). Speech-to-text uses faster-whisper (CPU).
Everything is containerized via Podman.

## System Facts
- Host: Bazzite OS (immutable Fedora) — use rpm-ostree not dnf for system packages
- GPU: RTX 4090, 24GB VRAM
- Podman rootless v5.8.0 — socket at /run/user/1000/podman/podman.sock
- podman-compose v1.5.0 — no depends_on conditions, use simple lists
- SELinux active — all volume mounts need `:Z` suffix, GPU containers need `security_opt: ["label=disable"]`
- Podman requires fully qualified image names (docker.io/..., ghcr.io/...)
- podman-compose has no `rm` subcommand — use stop+rm via podman directly
- Python packages: pip install --break-system-packages or use venv
- LLM: Huihui-Qwen3.5-9B-abliterated.Q8_0.gguf (~9.5GB VRAM)
- TTS: Qwen3-TTS-12Hz-1.7B-Base (~4GB VRAM, bfloat16), requires transformers==4.57.3
- STT: faster-whisper-base (CPU, no VRAM)
- Total peak VRAM: ~20.7GB (LLM with Q8_0 KV cache + TTS loaded)
- mmproj enabled: --mmproj flag active in docker-compose.yml, vision fully functional
- Chat template: chat_template.jinja (handles thinking, vision, tool calling)
- llama.cpp minimum version: b8148 (required for Qwen3.5)
- All container images must use fully qualified names (docker.io/..., ghcr.io/...)
- GitHub user: nisakson2000
- huggingface-cli not on PATH — use Python hf_hub_download/snapshot_download APIs
- Q8_0 only available in static repo (not imatrix): mradermacher/Huihui-Qwen3.5-9B-abliterated-GGUF
- Context window: 32768 tokens configured (native 262144)
- UI built at image build time — must `podman compose build gizmo-ui` for changes to take effect

## Architecture
- gizmo-net: Podman network, subnet 10.90.0.0/24
- Ports: llama=8080, orchestrator=9100, ui=3100, searxng=8300, tts=8400, whisper=8200
- All inter-container communication by service name
- GPU passthrough via CDI: nvidia.com/gpu=all + security_opt label=disable
- gizmo-llama and gizmo-tts use GPU (shared RTX 4090)
- gizmo-whisper runs on CPU (avoids VRAM contention)

## Container Startup Order
1. gizmo-searxng (infrastructure, no GPU)
2. gizmo-llama (GPU — LLM server with vision via mmproj)
3. gizmo-tts (GPU — TTS, loads model on startup, auto-unloads after 60s idle)
4. gizmo-whisper (CPU — speech-to-text via faster-whisper)
5. gizmo-orchestrator (depends on llama)
6. gizmo-ui (depends on orchestrator)

## Build & Deploy
- Orchestrator: `podman compose build gizmo-orchestrator && podman compose up -d gizmo-orchestrator`
- UI: `podman compose build gizmo-ui && podman compose up -d gizmo-ui`
- TTS: `podman compose build gizmo-tts && podman compose up -d gizmo-tts`
- Sandbox: `podman build -t gizmo-sandbox:latest services/sandbox/`
- Full stack: `podman compose up -d --build`
- Single restart (no rebuild): `podman compose restart gizmo-orchestrator`
- Logs: `podman logs -f gizmo-orchestrator`
- Health: `curl http://localhost:9100/api/services/health`
- Access: `http://localhost:3100` or `https://<tailscale-hostname>/` via `tailscale serve --https=443 http://127.0.0.1:3100`

## File Map — Where to Edit
- New REST endpoint → `main.py` (add route + handler)
- New LLM tool → `tools.py` (add to TOOL_DEFINITIONS + TOOL_REGISTRY + execute_tool dispatch)
- New pattern → `config/patterns/<name>/` (create config.yaml + system.md)
- New UI route → `services/ui/src/routes/<name>/+page.svelte`
- New UI component → `services/ui/src/lib/components/`
- New WebSocket handler → dedicated module + register in `main.py` lifespan
- System prompt changes → `config/constitution.txt` (lines starting with # are stripped as comments)
- Docker/infra changes → `docker-compose.yml` + `config/services.yaml`
- Code Playground prompt → `config/code-prompt.txt`
- Tracker prompt → `config/tracker-prompt.txt`

## Coding Conventions
- Python: async/await throughout, httpx for HTTP calls, no requests library
- Error handling: try/except with `logger.error()` on all external HTTP calls (llama, TTS, whisper, searxng)
- Database: raw SQLite with connection context manager, no ORM
- SQL safety: parameterized queries for user input, `ESCAPE '\'` on LIKE clauses
- Svelte: Svelte 5 runes ($state, $derived, $effect) — not legacy stores for new code
- CSS: `data-role` attribute targeting on msg-appear (Lightning CSS strips `:has()` selectors)
- Tool definitions: OpenAI function-calling format
- Dependencies: add to requirements.txt with `>=` minimum version
- File paths: use `Path.resolve()` + `is_relative_to()` for any user-supplied paths
- Sandbox code: base64-encode user content injected into Python template strings

## Thinking Mode
- Uses llama.cpp native `enable_thinking` API parameter (not token injection)
- Streaming deltas use `reasoning_content` field for thinking, `content` for response
- Model always thinks regardless of enable_thinking value — parameter controls separation
- Thinking blocks rendered as collapsible in UI, visually distinct from response
- Think toggle is in the input area (pill button next to Voice Studio)

## Request Pipeline (router.py)
Each user message passes through a 4-step routing pipeline before the LLM:
1. **Recitation detection** (recite.py) — regex matches recitation intent (poems, lyrics, speeches). If matched, fetches full text via SearXNG + web_fetch.py, injects as `<recitation-content>` in system prompt, lowers temperature to 0.2
2. **Keyword pre-routing** — regex matches tool-specific intent (e.g., "generate pdf" → generate_document tool)
3. **Pattern matching** (patterns.py) — longest keyword match wins from 30 Fabric-inspired templates in `config/patterns/<name>/`. Each pattern injects its system prompt and scopes available tools (model sees 3-8 tools per request)
4. **Default fallback** — unmatched messages get always_available tools (web_search, memory, run_code)

Explicit pattern invocation: prefix message with `[pattern:name]` (stripped before sending to LLM)

## Tool System
- 6 tools defined in tools.py: web_search, read_memory, write_memory, list_memories, run_code, generate_document
- TOOL_REGISTRY: metadata layer with category, keywords, always_available flag
- _TOOL_SCHEMA_MAP: O(1) lookup from tool name to OpenAI schema
- get_default_tools() returns always_available tools; patterns add scoped tools on top
- Multi-round tool calling: up to 5 iterations of call → execute → inject result → re-generate
- Tool calls persisted as JSON in messages table for audit/replay

## TTS Implementation
- Qwen3-TTS Base model — voice cloning only (no default voice built in), uses x_vector_only_mode=True
- Default voice: bundled espeak-ng WAV at /app/assets/default_voice.wav
- API: POST /v1/audio/speech (OpenAI-compatible JSON body)
- Voice cloning: accepts voice_reference (base64 WAV) + voice_reference_text
- Long text chunking: split at ~200 char sentence boundaries for full response synthesis
- Speed control: 0.5x-2.0x via scipy resampling (post-synthesis)
- Language selection: Auto, English, Chinese, Japanese, Korean, German, French, Russian, Portuguese, Spanish, Italian
- Auto-unloads from VRAM after TTS_IDLE_UNLOAD_SECONDS (default 60), reloads on next request
- Container base: docker.io/pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime
- scipy>=1.12.0 required in TTS container for speed control

## Voice Studio
- Dedicated TTS playground component (VoiceStudio.svelte)
- Upload voice reference audio, name it, save to server (/api/voices endpoints)
- Auto-transcription via Whisper on upload (transcript stored for display, not used in x_vector_only generation)
- Clip duration selector: 30s, 60s, 90s, 120s (ffmpeg truncation + 24kHz mono downsample)
- 0.5s silence padding appended to reference audio to prevent phoneme bleed
- Voices stored server-side in /app/voices/ (JSON metadata + processed WAV data URL)

## STT / Whisper
- Container: docker.io/fedirz/faster-whisper-server:0.5.0-cpu
- Endpoint: POST /api/transcribe (proxied through orchestrator)
- Microphone button in chat input — records audio, sends to Whisper
- Audio file uploads (M4A, MP3, WAV) also transcribed via Whisper before LLM analysis
- Requires HTTPS for mic access from non-localhost (Tailscale HTTPS handles this)
- Whisper container needs security_opt: label=disable for SELinux (HuggingFace cache mount)

## Video Analysis
- Upload video files (up to 500MB), ffmpeg extracts frames evenly spaced (max 8)
- Video saved to /app/media/ and served via /api/media/{filename}
- Video player shown in chat messages (not just thumbnail)
- Vision model analyzes extracted frames
- VISION_PROMPT constant in main.py injected only when has_vision=True

## Code Execution Sandbox
- Podman container (gizmo-sandbox:latest) via Unix socket API
- Client: services/orchestrator/sandbox.py (httpx AsyncHTTPTransport with UDS)
- Languages: python (numpy/pandas/matplotlib/sympy/scipy), javascript (Node.js), bash, c, cpp, go, lua
- Document generation packages: reportlab, openpyxl, python-docx, python-pptx
- Constraints: --network none, 256MB memory, 1 CPU, 256 PID limit, read-only rootfs, tmpfs /tmp:size=150m
- Direct execution via /api/run-code REST endpoint (Code Playground at /code route)
- LLM execution via run_code tool (chat + code chat) and generate_document tool
- Code Playground: /code route with split-pane layout, line numbers, AI chat overlay (/ws/code-chat)
- Code chat: isolated WebSocket, code-focused prompt (config/code-prompt.txt), run_code tool only, no memory
- Auto language detection on paste (C/C++/Go/JS/Bash/Lua/HTML/SVG/CSS/Markdown/Python)
- Markup languages (HTML/CSS/SVG/Markdown) auto-preview in iframe, no Run button
- MEDIA_HOST_DIR env var (set to ${PWD}/media in docker-compose.yml) used for sandbox bind mount extraction

## Document Generation
- LLM tool: generate_document (format, title, content → Python template in sandbox)
- Supported formats: PDF (reportlab), DOCX (python-docx), XLSX (openpyxl), PPTX (python-pptx), CSV, TXT
- File extraction: sandbox bind mount at MEDIA_DIR/.sandbox-{uuid}/, moved to /app/media/doc-{uuid}.{ext}
- Served via /api/media/ with Content-Disposition: attachment headers

## Server-Side Conversations
- SQLite database at /app/memory/conversations.db (two tables: conversations + messages)
- Accessible from any origin/device (solves localStorage per-origin limitation)
- REST: GET/DELETE /api/conversations/{id}, GET /api/conversations, GET /api/conversations/search?q=
- Export: GET /api/conversations/{id}/export?format=markdown|json
- Rename: PATCH /api/conversations/{id}
- Pruning: MAX_CONVERSATIONS env var (default 500), prunes oldest on startup and new creation
- LLM-generated titles: first exchange triggers async fire-and-forget title generation (5-word max)

## Memory System
- BM25 ranking via rank_bm25 with recency boost and stop-word filtering
- Top 5 matches, 800 chars each, injected into system prompt
- Subdirectories: facts/, conversations/, notes/
- Path traversal protection: filename sanitization + Path.is_relative_to()
- MemoryManager UI modal: view, add, delete, clear
- REST: GET /api/memory/read, DELETE /api/memory/clear, DELETE /api/memory/{subdir}/{filename}

## Tracker Module
- SQLite database at /app/tracker/tracker.db (tasks + notes tables)
- REST: CRUD for tasks and notes under /api/tracker/
- Tasks: title, description, status, priority, tags, due_date, recurrence (none/daily/weekly/biweekly/monthly/yearly), subtasks
- Notes: title, content, tags, pinned
- LLM chat via /ws/tracker WebSocket — natural language task creation
- Tracker prompt: config/tracker-prompt.txt (includes CRITICAL ID verification instruction)
- Context format: `id=xxx | [priority] "Title"` — ID leads each line to prevent LLM ID-title confusion
- Frontend: /tracker route with full-width list + slide-in overlay panels (TaskDetail, NoteEditor, TrackerChat)
- Auto-save: debounce at 800ms, save on close/unmount via onDestroy

## UI Features
- 9 Nintendo console themes with per-console sounds, screen effects, boot animations
- CSS targeting uses data-role attribute on msg-appear (Lightning CSS strips :has() selectors)
- Toast notification system: global toast store + component
- Keyboard shortcuts: Ctrl+Shift+N (new chat), Ctrl+Shift+T (toggle think), Ctrl+Shift+S (toggle sidebar), Ctrl+/ (focus input), Escape (close modals)
- Regenerate response: hover last assistant message → regenerate button
- Message editing: hover user message → edit button → inline textarea → Save truncates and resubmits
- Response history: regenerate/edit preserves variants with < 1/N > navigation arrows
- Streaming markdown: debounced at 150ms, final parse on stream end
- Icon rail navigation: Chat, Tasks, Code, Settings in left sidebar

## Non-Obvious Facts
- Model always thinks — enable_thinking controls whether reasoning is in a separate field
- SearXNG config must disable rate limiting for local use
- --flash-attn requires value (on/off/auto), not bare flag
- llama.cpp GGML_BACKEND_DL=ON needed for CUDA backend as runtime-loaded .so
- Context length slider (2,048-131,072 tokens) controls conversation history windowing via WebSocket payload; orchestrator drops oldest messages to fit budget
- File upload limits: docs/images 50MB, video 500MB
- Document types (PDF, DOCX, XLSX, PPTX, CSV, TXT, JSON, HTML, XML, ZIP) served via /api/media/
- Recitation pipeline is CPU-only (SearXNG search + web fetch), no VRAM impact
- Pattern system is CPU-only (text matching + YAML parsing), no VRAM impact
- Code chat and tracker have isolated WebSocket handlers — they do NOT use the pattern router

## Documentation Sync Mandate (MANDATORY)

Every code change MUST include corresponding documentation updates. No exceptions.

### Files that must stay in sync:
- **README.md** — Features, architecture diagram, hardware requirements, quick start
- **wiki/architecture.md** — Container table, WebSocket protocol, REST API table, file tree
- **wiki/setup.md** — Download steps, verification, troubleshooting
- **wiki/usage.md** — Feature descriptions, settings reference
- **wiki/development.md** — Tool definitions, contributing guide
- **wiki/model-reference.md** — Model specs, quantization, TTS details
- **AUDIT.md** — Update when issues are fixed or new ones found
- **config/models.yaml** — Must match the actual model being served
- **config/services.yaml** — Must match docker-compose.yml ports and service names
- **CLAUDE.md** — System facts, architecture, known issues

### Checklist:
1. New/changed user-facing feature? → README.md + wiki/usage.md
2. Architecture change (service, port, endpoint)? → README.md diagram + wiki/architecture.md
3. Setup change (dependency, download)? → wiki/setup.md
4. New/changed tool? → wiki/architecture.md tool table + tools.py
5. Model or TTS change? → wiki/model-reference.md + README.md + CLAUDE.md
6. docker-compose.yml change? → wiki/architecture.md + config/services.yaml
7. VRAM change? → README.md hardware requirements + CLAUDE.md
8. Fixed audit issue? → AUDIT.md resolved table
