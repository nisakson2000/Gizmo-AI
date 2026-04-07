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
- SELinux active — GPU containers and Whisper need security_opt: ["label=disable"]
- Python packages: pip install --break-system-packages or use venv
- LLM: Huihui-Qwen3.5-9B-abliterated.Q8_0.gguf (~9.5GB VRAM)
- TTS: Qwen3-TTS-12Hz-1.7B-Base (~4GB VRAM, bfloat16)
- STT: faster-whisper-base (CPU, no VRAM)
- Total peak VRAM: ~20.7GB (LLM with Q8_0 KV cache + TTS loaded)
- mmproj enabled: --mmproj flag active in docker-compose.yml, vision fully functional
- Chat template: chat_template.jinja (handles thinking, vision, tool calling)
- llama.cpp minimum version: b8148 (required for Qwen3.5)
- Thinking mode: native llama.cpp enable_thinking API (reasoning_content field)
- All container images must use fully qualified names (docker.io/..., ghcr.io/...)
- TTS package: qwen-tts>=0.1.1, requires transformers==4.57.3

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

## Thinking Mode Implementation
- Uses llama.cpp native `enable_thinking` API parameter (not token injection)
- Streaming deltas use `reasoning_content` field for thinking, `content` for response
- Model always thinks regardless of enable_thinking value — parameter controls separation
- Thinking blocks rendered as collapsible in UI, visually distinct from response
- Think toggle is in the input area (pill button next to Voice Studio)

## TTS Implementation
- Qwen3-TTS Base model only does voice cloning (no default voice built in)
- Default voice: bundled espeak-ng generated WAV at /app/assets/default_voice.wav
- API: POST /v1/audio/speech (OpenAI-compatible JSON body)
- Voice cloning: accepts voice_reference (base64 WAV) + voice_reference_text
- Voice cloning uses x_vector_only_mode=True — ICL mode caused an "aww" warmup artifact and was reverted
- Auto-transcribe: Whisper transcribes voice reference audio on upload (transcript stored but not used for generation in x_vector_only mode; kept for future use)
- Long text chunking: text split at ~200 char sentence boundaries for cloned voices — full responses get TTS now
- Speed control: 0.5x–2.0x speech speed via scipy resampling (applied post-synthesis)
- Language selection: Auto, English, Chinese, Japanese, Korean, German, French, Russian, Portuguese, Spanish, Italian
- Proxy forwards voice_reference_text, speed, language to TTS server; timeout increased 60→180s
- Auto-unloads from VRAM after TTS_IDLE_UNLOAD_SECONDS (default 60)
- Reloads automatically on next request
- Container base: docker.io/pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime
- scipy>=1.12.0 required in TTS container for speed control resampling

## Voice Studio
- Dedicated TTS playground component (VoiceStudio.svelte)
- Upload voice reference audio, name it, save to server (/api/voices endpoints)
- Auto-transcription: Whisper transcribes reference audio on upload (transcripts stored but not used for generation — x_vector_only mode)
- Transcript display: shows transcribed text for each saved voice
- Migrate endpoint: POST /api/voices/migrate-transcripts backfills transcripts for existing voices
- Select from saved voices when synthesizing
- Clip duration selector: 30s, 60s, 90s, 120s (ffmpeg truncation + 24kHz mono downsample — was 16kHz, now matches model's speaker encoder)
- 0.5s silence padding appended to reference audio to prevent phoneme bleed
- Voices stored server-side in /app/voices/ (JSON metadata + processed WAV data URL)
- Opens via pill button in input area or from Settings panel

## STT / Whisper Implementation
- Container: docker.io/fedirz/faster-whisper-server:0.5.0-cpu
- Endpoint: POST /api/transcribe (proxied through orchestrator)
- Microphone button in chat input — records audio, sends to Whisper
- Audio file uploads (M4A, MP3, WAV) also transcribed via Whisper before LLM analysis
- Requires HTTPS for mic access from non-localhost (Tailscale HTTPS handles this)

## Video Analysis
- Upload video files (up to 500MB)
- First frame extracted via ffmpeg as thumbnail
- Video saved to /app/media/ and served via /api/media/{filename}
- Video player shown in chat messages (not just thumbnail)
- Vision model analyzes extracted frames

## Constitution System
- Single file: config/constitution.txt (prose base prompt defining identity and capabilities)
- Lines starting with # are stripped as comments, everything else injected as system prompt
- XML-tagged sections include `<code-execution>`, `<document-generation>`, `<patterns>` (separate sections for code sandbox, document tool, and pattern awareness)

## Pattern System
- 30 Fabric-inspired cognitive templates in config/patterns/<name>/{config.yaml, system.md}
- Loaded on startup by patterns.py, cached in memory, listed via GET /api/patterns
- Router (router.py) intercepts each user message before LLM processing:
  1. Keyword pre-routing: regex matches tool-specific intent (e.g., "generate pdf" → generate_document)
  2. Pattern matching: longest keyword match wins (e.g., "key takeaways" → extract_wisdom)
  3. Default fallback: unmatched messages get always_available tools (web_search, memory, run_code)
- Pattern system prompt injected between constitution and memories in build_system_prompt()
- Each pattern declares scoped tools — model only sees 3-8 tools per request
- Explicit invocation: prefix message with [pattern:name]
- Tool registry (TOOL_REGISTRY in tools.py): metadata layer over TOOL_DEFINITIONS with category, keywords, always_available flag
- _TOOL_SCHEMA_MAP provides O(1) lookup from tool name to schema
- get_default_tools() returns always_available tools; get_tool_schemas() converts names to schemas
- Categories: information_extraction (3), analysis (3), code (4), writing (3), research (5), security (4), productivity (3), data (3), planning (2)
- Dependency: pyyaml>=6.0 for config.yaml parsing

## Recitation Pipeline
- Intercepts recitation requests (poems, speeches, lyrics) before the LLM sees the message
- Detection: regex-based in recite.py — triggers on "recite", "full text of", "lyrics to/of", "words to", "how does X go", "quote from", "the Nth amendment", "word for word", "verbatim"
- Retrieval: searches SearXNG for full text, fetches top 3 pages via web_fetch.py (httpx + BeautifulSoup), picks longest result > 200 chars
- Injection: XML-tagged `<recitation-content>` block inserted into system prompt between pattern and vision layers
- Temperature: lowered to 0.2 for faithful reproduction (default 0.7)
- Fallback: if SearXNG is down or fetch fails, model responds normally from training memory (no crash)
- Files: recite.py (detection + retrieval), web_fetch.py (page fetcher + text extraction)
- Dependencies: beautifulsoup4>=4.12.0 added to requirements.txt
- No new LLM tools, no UI changes, no VRAM impact

## Server-Side Conversations
- SQLite database at /app/memory/conversations.db (two tables: conversations + messages)
- Accessible from any origin/device (solves localStorage per-origin limitation)
- REST: GET /api/conversations (list), GET /api/conversations/{id}, DELETE /api/conversations/{id}

## Non-Obvious Facts
- Qwen3.5 chat template key: tokenizer.ggml.pre = qwen35
- Vision requires separate mmproj file alongside the main GGUF — now enabled
- Model always thinks — enable_thinking controls whether reasoning is in separate field
- SearXNG config must disable rate limiting for local use
- Constitution lines starting with # are stripped as comments
- GitHub user: nisakson2000
- huggingface-cli not on PATH — use Python hf_hub_download/snapshot_download APIs
- Podman requires fully qualified image names (docker.io/...)
- Q8_0 only available in static repo (not imatrix): mradermacher/Huihui-Qwen3.5-9B-abliterated-GGUF
- Context window: 32768 tokens configured (native 262144)
- UI built at image build time — must `podman compose build gizmo-ui` for changes to take effect
- Tailscale HTTPS: `tailscale serve --https=443 http://127.0.0.1:3100` provides valid cert at `https://<your-tailscale-hostname>/`
- File upload limits: docs/images 50MB, video 500MB
- Voice reference audio truncated via ffmpeg to prevent TTS CUDA OOM
- scipy>=1.12.0 is required in the TTS container for speech speed control (resampling)
- Whisper container needs security_opt: label=disable for SELinux (HuggingFace cache mount)
- MEDIA_HOST_DIR env var (set to ${PWD}/media in docker-compose.yml) used by orchestrator for sandbox bind mount extraction
- Document types (PDF, DOCX, XLSX, PPTX, CSV, TXT, JSON, HTML, XML, ZIP) served via /api/media/ with Content-Disposition: attachment headers
- Voice cloning uses x_vector_only_mode=True — ICL mode reverted due to "aww" warmup artifact

## V5 UX Enhancements
- Waiting indicator: pulsing "Gizmo is thinking..." with accent-colored dots shown during pre-stream delay
- Upload guard: send button and Enter disabled during file uploads
- Scroll-to-bottom: auto-scrolls on conversation load via $activeConversationId watch
- LLM-generated titles: first exchange triggers async title generation (5-word max), sent via WebSocket "title" event
- Regenerate response: hover last assistant message → regenerate button. Uses DELETE /api/conversations/{id}/messages-from/{index}
- Message editing: hover user message → edit button → inline textarea → Save truncates history and resubmits
- Response history: regenerate/edit preserves all previous responses as variants with < 1/N > navigation arrows
- Prompt-aware variants: each response tracks which prompt generated it (promptVariantIndex). Navigating responses syncs the prompt display; navigating prompts jumps to the latest response for that prompt.
- Backend regenerate flag: WS payload `regenerate: true` skips saving duplicate user message and preserves multimodal content
- Streaming markdown: debounced at 150ms (~7 parses/sec) instead of rAF (~60/sec), final parse on stream end
- Conversation export: GET /api/conversations/{id}/export?format=markdown|json, download button in sidebar
- Full-text search: GET /api/conversations/search?q=, press Enter in sidebar to search message content
- TTS long-text chunking: full response synthesized via ~200 char sentence-boundary chunking (replaced 4,000 char truncation)
- Constitution: added Output Formatting and Web Search sections, tightened tool discipline and conciseness rules
- Toast notification system: global toast store + component, replaces inline error/success states across all components
- Keyboard shortcuts: Ctrl+Shift+N (new chat), Ctrl+Shift+T (toggle think), Ctrl+Shift+S (toggle sidebar), Ctrl+/ (focus input), Escape (close modals)
- Conversation rename: double-click title in sidebar, PATCH /api/conversations/{id}
- Voice metadata cleanup: WAV files on disk, no base64 in JSON metadata, data URL built on-the-fly
- Conversation pruning: MAX_CONVERSATIONS env var (default 500), prunes oldest on startup and new conversation creation
- Hardcoded Tailscale URL removed from UI code, wiki docs use placeholder syntax

## Known Issues
- SELinux requires :Z suffix on ALL volume mounts in docker-compose.yml
- GPU container requires security_opt: ["label=disable"] for NVML access
- --flash-attn requires value (on/off/auto), not bare flag
- llama.cpp GGML_BACKEND_DL=ON needed for CUDA backend as runtime-loaded .so
- podman-compose has no `rm` subcommand — use stop+rm via podman directly
- Model always produces reasoning_content even with enable_thinking: false
- Context length slider (2,048–131,072 tokens) controls conversation history windowing via WebSocket payload; orchestrator drops oldest messages to fit budget

## Spec Deviations
- Thinking mode: uses native enable_thinking API, not token injection as originally planned
- Download script: uses Python heredoc, not huggingface-cli (not available on PATH)
- TTS Base model has no built-in voice — requires bundled reference audio
- Conversations stored in SQLite (conversations.db), not individual files

## Documentation Sync Mandate (MANDATORY)

Every code change, feature addition, or configuration update MUST include corresponding updates to ALL affected documentation before committing. No exceptions.

### Files that must stay in sync with code:
- **README.md** — Features list, architecture diagram, hardware requirements, quick start steps
- **wiki/architecture.md** — Container reference table, WebSocket protocol, REST API table, file tree, models.yaml example
- **wiki/setup.md** — Download steps, verification commands, troubleshooting
- **wiki/usage.md** — Feature descriptions, settings reference, supported file types
- **wiki/development.md** — Tool definitions, future features list, contributing guide
- **wiki/model-reference.md** — Model specs, quantization table, TTS details
- **wiki/how-ai-works.md** — Educational explanations (update if concepts change)
- **AUDIT.md** — Version status tables (update when issues are fixed or new ones found)
- **config/models.yaml** — Must match the actual model being served
- **config/services.yaml** — Must match docker-compose.yml ports and service names
- **CLAUDE.md** — System facts, architecture, known issues sections

### Checklist for every change:
1. Does this add/remove/modify a user-facing feature? → Update README.md features list + wiki/usage.md
2. Does this change architecture (new service, port change, new endpoint)? → Update README.md diagram + wiki/architecture.md container table + REST API table + file tree
3. Does this change setup steps (new dependency, different download)? → Update wiki/setup.md
4. Does this add/modify a tool? → Update wiki/architecture.md tool table + tools.py definitions
5. Does this change model or TTS? → Update wiki/model-reference.md + README.md badges + CLAUDE.md system facts
6. Does this change docker-compose.yml? → Update wiki/architecture.md container reference + config/services.yaml
7. Does this change VRAM usage? → Update README.md hardware requirements + CLAUDE.md system facts
8. Does this fix a known issue from AUDIT.md? → Move it from "Open Issues" to a "Resolved" row

### Never:
- Add a feature without documenting it in README.md and the relevant wiki page
- Remove a feature without removing it from all documentation
- Change a port, endpoint, or config value without updating all references
- Merge code that makes any documentation inaccurate
- Claim a feature works when it doesn't

## Code Execution Sandbox
- Podman container (gizmo-sandbox:latest) executed via Unix socket API
- Client: services/orchestrator/sandbox.py (httpx AsyncHTTPTransport with UDS)
- Socket path (inside container): /run/podman/podman.sock
- Constraints: --network none, 256MB memory, 1 CPU, 256 PID limit, read-only rootfs, tmpfs /tmp:size=150m, USER nobody
- Languages: python (numpy/pandas/matplotlib/sympy/scipy), javascript (Node.js), bash, c, cpp, go, lua
- Document generation packages: reportlab, openpyxl, python-docx, python-pptx (installed in sandbox Dockerfile)
- MAX_OUTPUT: 8000 chars (truncated)
- Default timeout: 10s, max 30s
- Direct execution via /api/run-code REST endpoint (Code Playground at /code route)
- LLM execution via run_code tool (chat + code chat)
- LLM execution via generate_document tool: LLM provides format, title, and content; orchestrator runs pre-tested Python templates in sandbox
  - Supported formats: PDF (reportlab), DOCX (python-docx), XLSX (openpyxl), PPTX (python-pptx), CSV, TXT
  - File extraction: sandbox.py creates temp bind mount at MEDIA_DIR/.sandbox-{uuid}/, mounted into container at /tmp/output
  - Files collected after execution, moved to /app/media/doc-{uuid}.{ext}, temp dir cleaned up
  - Served via /api/media/ with Content-Disposition: attachment headers
- Code Playground: dedicated /code route with split-pane layout, line numbers, AI chat overlay (/ws/code-chat)
- Code chat: isolated WebSocket, code-focused system prompt (config/code-prompt.txt), run_code tool only, no memory
- Auto language detection on paste (detects C/C++/Go/JS/Bash/Lua/HTML/SVG/CSS/Markdown/Python from code signatures)
- Markup languages (HTML/CSS/SVG/Markdown) auto-preview in iframe, no Run button

## Memory System (V4)
- BM25 ranking via rank_bm25 (replaces keyword matching)
- Stop-word filtering (~50 common English words)
- Recency boost: score *= 1.0 + 0.5 * max(0, 1 - age_days/30)
- Top 5 matches, 800 chars each (up from 300)
- Path traversal protection: Path.is_relative_to()
- MemoryManager UI modal: view, add, delete, clear
- REST endpoints: GET /api/memory/read, DELETE /api/memory/clear, DELETE /api/memory/{subdir}/{filename}

## Vision Prompt Injection
- VISION_PROMPT constant in main.py injected only when has_vision=True
- Triggered by image_data or video_frames in request
- Keeps constitution.txt clean from detailed vision instructions

## Tracker Module
- SQLite database at /app/tracker/tracker.db (tasks + notes tables)
- REST: CRUD for tasks and notes under /api/tracker/
- Tasks: title, description, status, priority, tags, due_date, recurrence, subtasks
- Notes: title, content, tags, pinned
- LLM chat via /ws/tracker WebSocket — natural language task creation
- Tracker prompt: config/tracker-prompt.txt (includes CRITICAL ID verification instruction)
- Backend: tracker.py (FastAPI router), tracker_db.py (SQLite), tracker_tools.py (LLM tools), llm.py (LLM helper)
- Context format: `id=xxx | [priority] "Title"` — ID leads each line to prevent LLM ID-title confusion
- Frontend: /tracker route, 9 Svelte components (TagFilter deleted — tags now in TaskList dropdown), dedicated store and WS client
- Layout: full-width list with slide-in overlay panels (TaskDetail, NoteEditor, TrackerChat) from right edge
- Auto-save: TaskDetail and NoteEditor debounce at 800ms, save on close/unmount via onDestroy
- TaskItem: card-based with colored priority left border, SVG status circles, overdue ring, subtask progress
- TaskDetail: segmented status/priority controls, tag pill input (Enter/comma/Backspace)
- Icon rail navigation: Chat, Tasks, Code, Settings in left sidebar

## Enhanced Console Themes
- Per-console sound system: 9 unique sonic identities (NES harsh square, SNES warm echo, GBA tinny, N64 bassy pitch-bend, GameCube crystalline, Wii bubbly, Switch tactile, DS cute, 3DS refined delay)
- Screen technology overlays: CRT vignette (NES), phosphor glow (SNES), LCD dot matrix (GBA), distance fog (N64), indigo glow (GameCube), horizontal stripes (Wii), neon bleed (Switch), touch crosshair (DS), stereoscopic parallax (3DS)
- Per-console message bubble styling, input area theming, sidebar hover effects
- Enhanced boot sequences with sound integration (static, stars, 3D cubes, ring ripples, etc.)
- CSS targeting uses data-role attribute on msg-appear (Lightning CSS strips :has() selectors)

## Future Roadmap
- Image generation (Stable Diffusion integration)
- RAG pipeline (document ingestion + vector search)
- Multi-model support (hot-swap between different GGUFs)
- Mobile-optimized UI (PWA)
- Conversation branching / forking
- Streaming TTS (chunk audio as it generates)
- ChromaDB semantic memory (upgrade from BM25)

## Session Log
- [2026-03-12] Project created from scratch, greenfield build
- [2026-03-12] All 6 services deployed and healthy, streaming chat verified
- [2026-03-12] V1 complete — GitHub repo + wiki published
- [2026-03-13] V2 upgrade — 27B→9B LLM, Kokoro→Qwen3-TTS, all docs updated
- [2026-03-14] V3 upgrade — Voice Studio, video/audio analysis, Whisper STT, vision enabled, server-side conversations, Tailscale HTTPS, constitution split, all docs updated
- [2026-03-14] V4 upgrade — BM25 memory, Memory Manager UI, code execution sandbox, Code Playground, vision prompt injection, per-token timeout, audio suggestion card, TTS voice selection, tool discipline, all docs updated
- [2026-03-23] V5 upgrade — Toast notifications, keyboard shortcuts, conversation rename/export/search, streaming perf, response history, regenerate/edit, conversation pruning, Nintendo theme system (9 console themes), stack audit (KV cache quantization, constitution rewrite with XML tags, multi-round WS tool loop, tool schema tightening)
- [2026-03-25] V5.1 — Enhanced console themes (per-console sounds, screen effects, message styling, boot animations), Tracker module (tasks + notes + LLM chat), icon rail navigation, copy buttons on code output, Code Playground state reset, input padding fix, constitution code block softening
- [2026-03-26] V5.2 — Tracker redesign (overlay panels, card-based items, auto-save, segmented controls, tag pills, progress ring), AI task completion fix (ID-first context format, verification prompt)
- [2026-03-26] V5.3 — Multi-language code execution (Python, JavaScript, Bash, C, C++, Go, Lua) + markup preview (HTML, CSS, SVG, Markdown), language selector in Code Playground, 150MB tmpfs for Go compilation
- [2026-03-26] V5.4 — Code Playground promoted to /code route (split-pane, line numbers, AI chat overlay with /ws/code-chat), boot animations on every theme switch + opt-out toggle, conversation name in header, auto language detection on paste, icon rail Code nav
- [2026-03-26] V5.5 — TTS enhancements: auto-transcribe voice uploads via Whisper (ICL mode), clone prompt caching, long text chunking (no more 4,000 char truncation), speech speed control (0.5x–2.0x via scipy), language selection (10 languages), 24kHz sample rate fix, silence padding, tuned generation params, ICL/x-vec quality badges in Voice Studio, migrate-transcripts endpoint
- [2026-04-06] V5.8 — Recitation pipeline: pre-LLM interception of recitation requests (poems, speeches, lyrics), web fetch via SearXNG + BeautifulSoup text extraction, XML-tagged system prompt injection, temperature lowering to 0.2, graceful fallback when search unavailable, beautifulsoup4 dependency, all docs updated
- [2026-04-06] V5.7 — Pattern system + scalable tool architecture: 30 Fabric-inspired cognitive templates (config/patterns/), router.py with keyword pre-routing + pattern matching + tool scoping, tool registry (TOOL_REGISTRY in tools.py), dynamic tool selection (model sees 3-8 tools per request), ToolCallBlock rich media rendering (image/video/audio), pyyaml dependency, all docs updated
- [2026-03-26] V5.6 — Document generation tool (generate_document: PDF, DOCX, XLSX, PPTX, CSV, TXT via pre-tested Python templates in sandbox), sandbox Dockerfile updated with reportlab/openpyxl/python-docx/python-pptx, bind mount file extraction to /app/media/, extended media serving with Content-Disposition headers, TTS voice cloning fix (reverted to x_vector_only_mode=True to fix ICL warmup artifact), constitution split code-execution and document-generation into separate XML sections, MEDIA_HOST_DIR env var in docker-compose.yml
