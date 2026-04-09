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
- TTS: Qwen3-TTS-12Hz-1.7B-Base via faster-qwen3-tts (~4GB VRAM, bfloat16, CUDA graph streaming), requires transformers==4.57.3
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
- Android APK: `bash mobile/build-apk.sh` (containerized Podman build, no Android Studio)

## Mobile App (Android)
- Directory: `mobile/android/` — Kotlin + XML layouts, WebView wrapper
- Package: `ai.gizmo.app`, minSdk 26 (Android 8.0), targetSdk 35
- Build: `bash mobile/build-apk.sh` (Podman-containerized, outputs debug APK)
- CI: `.github/workflows/build-android.yml` — builds on `v*` tags, attaches APK to GitHub Release
- Dependencies: AndroidX only (core-ktx, appcompat, lifecycle, material, swiperefreshlayout, constraintlayout)
- Activities: LauncherActivity (router), OnboardingActivity (welcome), AddServerActivity (add/edit), ServerListActivity (multi-server), MainActivity (WebView), ErrorActivity (connectivity)
- ServerManager: SharedPreferences + org.json for server profile CRUD
- GizmoBridge: @JavascriptInterface for blob URL downloads via MediaStore
- Build-time defaults: `mobile/android/gizmo-defaults.json` (gitignored) pre-populates servers if present
- No server-side changes required — WebView loads server URL directly (same-origin)
- VRAM impact: zero (client-side only)

## File Map — Where to Edit
- New REST endpoint → `main.py` (add route + handler)
- New LLM tool → `tools.py` (add to TOOL_DEFINITIONS + TOOL_REGISTRY + execute_tool dispatch)
- New pattern → `config/patterns/<name>/` (create config.yaml + system.md)
- New mode → `config/modes/<name>/` (create config.yaml + system.md) + REST via /api/modes
- New UI route → `services/ui/src/routes/<name>/+page.svelte`
- New UI component → `services/ui/src/lib/components/`
- New Svelte action → `services/ui/src/lib/actions/` (e.g., focusTrap.ts, swipe.ts, highlight.ts)
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
Each user message passes through a 5-step routing pipeline before the LLM:
1. **Recitation detection** (recite.py) — regex matches recitation intent (poems, lyrics, speeches). If matched, fetches full text via SearXNG + web_fetch.py, injects as `<recitation-content>` in system prompt, lowers temperature to 0.2
1b. **Character analysis** (charmap.py) — regex detects letter-counting, spelling, and character-position questions. Pre-computes a character breakdown and injects as `<character-analysis>` in system prompt
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
- Package: faster-qwen3-tts (andimarafioti fork) — CUDA graph acceleration, streaming support
- Qwen3-TTS Base model — voice cloning only (no default voice built in), uses xvec_only=True
- Default voice: bundled espeak-ng WAV at /app/assets/default_voice.wav
- **Streaming mode**: sentence-level TTS interleaved with LLM token generation (~3s to first audio vs 7-45s batch)
  - SentenceBuffer in orchestrator detects sentence boundaries during LLM streaming
  - Each completed sentence dispatched to TTS server via WebSocket at ws://gizmo-tts:8400/v1/audio/stream
  - TTS server streams PCM float32 chunks (chunk_size=8, ~667ms per chunk) via WebSocket binary frames
  - Orchestrator forwards chunks to browser: JSON metadata + binary PCM frame pairs
  - GPU access serialized via asyncio.Lock — sentences queue and process one at a time
  - StreamingAudioPlayer component plays chunks via AudioContext with gapless scheduling
  - After all sentences complete, PCM chunks assembled into WAV file for message history
  - Falls back to batch mode if streaming fails (tts_streaming_failed flag)
- Batch mode: POST /v1/audio/speech (OpenAI-compatible JSON body) — used by Voice Studio preview and as fallback
- Voice cloning: accepts voice_reference (base64 WAV) or voice_id (direct file lookup via shared volume)
- Precomputed speaker embeddings: extracted on voice save, stored at /app/voices/embeddings/{voice_id}.pt (~4KB)
  - POST /v1/audio/embedding endpoint extracts x-vector from reference audio
  - Streaming endpoint checks for cached embedding before loading WAV
- Long text chunking: split at ~200 char sentence boundaries (batch mode only)
- Speed control: 0.5x-2.0x via scipy resampling (per-chunk in streaming, post-synthesis in batch)
- Language selection: Auto, English, Chinese, Japanese, Korean, German, French, Russian, Portuguese, Spanish, Italian
- Auto-unloads from VRAM after TTS_IDLE_UNLOAD_SECONDS (default 60) — full model destruction (CUDA graphs cannot migrate)
- Container base: docker.io/pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime
- Shared voices volume: ./voices:/app/voices:rw,Z mounted on both TTS and orchestrator containers

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
- Constraints: --network none, 256MB memory, 1 CPU, 256 PID limit, read-only rootfs, tmpfs /tmp:size=150m, MAX_OUTPUT 8000 bytes per stream
- Direct execution via /api/run-code REST endpoint (Code Playground at /code route)
- LLM execution via run_code tool (chat + code chat) and generate_document tool
- Code Playground: /code route with split-pane layout, line numbers, AI chat overlay (/ws/code-chat)
- Syntax highlighting: highlight.js overlay (transparent textarea over highlighted pre/code layer, debounced 50ms)
- Resizable split pane: drag handle between editor and output, persisted to localStorage (25-85% range, double-click resets to 55/45)
- Auto-save: debounced 2s save to localStorage with "Saved" indicator in footer
- Output files: sandbox-generated files displayed as downloadable links with inline image preview
- Copy/download buttons: copy code to clipboard, download as file with correct extension
- Word wrap toggle: persisted preference, applies to both textarea and highlight layer
- Code chat: isolated WebSocket, code-focused prompt (config/code-prompt.txt), run_code tool only, no memory
- Code chat reuses ThinkingBlock and ToolCallBlock components for expandable thinking and inline tool results
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

## Session History RAG
- Semantic recall within a single conversation using fastembed (BAAI/bge-small-en-v1.5, 384-dim, ONNX CPU)
- Storage: session_embeddings table in conversations.db (conversation_id, message_index, role, content, embedding BLOB)
- Indexing: fire-and-forget background tasks via asyncio.to_thread after each save_message()
- Retrieval: cosine similarity search when conversation has 15+ messages, excludes recent 10 (already in sliding window), similarity threshold > 0.3, top 5 results
- Injection: `<session-recall>` XML block in system prompt, deduplicated against windowed messages (content prefix matching)
- Dependency: fastembed>=0.4.0, numpy>=1.26.0 (ONNX Runtime, zero VRAM impact)
- Cache: ./memory/.fastembed-cache mounted to /root/.cache/fastembed (model downloaded once, persists across rebuilds)
- Pre-warm: embedding model loaded at startup via background thread (avoids cold-start delay)
- Cleanup: session_embeddings deleted on conversation prune, single delete, and partial delete (edit/regenerate)
- File: session_memory.py (embed_text, store_turn, retrieve_relevant, format_recalled, get_query_embedding, get_stored_embeddings)

## Smart History Windowing (importance-aware)
- window_messages() uses semantic scoring when query embedding is available
- Always keeps the last 6 messages (3 user-assistant pairs) for recency
- Scores older messages by cosine similarity to the current query using stored embeddings
- **Importance-aware**: adjusted_sim = sim * (0.5 + 0.5 * importance) — tool call messages score higher than casual "ok" at similar relevance
- Fills remaining token budget with highest-scoring older messages in chronological order
- Falls back to FIFO (drop oldest) when embeddings are unavailable or conversation is short
- Query embedding computed via asyncio.to_thread to avoid blocking the event loop

## Repetition Detection
- Rolling check every 200 chars of accumulated response in both initial and tool-round streaming loops
- Detects 3+ consecutive identical segments of 50+ chars at the tail of the response
- Stops generation and appends user-friendly notice: "Response reached a repetitive pattern and was stopped"
- Conservative: checks segment lengths from 50 to 500 chars to avoid false positives on legitimate repeated content

## Response Truncation
- When llama.cpp returns `finish_reason: "length"` (max_tokens hit), `llm.py` yields a `{"type": "truncated"}` event
- `ws_chat` appends a notice: "Response reached the maximum length. Ask me to continue if you'd like the rest."
- Handled in both initial and tool-round streaming loops

## Importance Scoring (importance.py)
- Heuristic-based message importance scoring (0.0-1.0), no LLM calls
- Used by V6 cross-conversation memory to prioritize indexing and recall
- Additive scoring: base 0.3, tool calls +0.2, questions +0.1, code blocks +0.1, corrections +0.2, short messages -0.1, URLs/paths +0.1

## Recitation Content Cleaning
- `strip_line_numbers()` in recite.py removes leading line numbers from fetched web content
- Only strips if >50% of non-empty lines match `^\s*\d{1,4}[\s\t]+` (prevents false positives on numbered lists)
- Applied in `build_recitation_context()` before system prompt injection

## Cross-Conversation Semantic Search (cross_memory.py)
- Searches all past conversations for semantically relevant exchanges using fastembed (same BAAI/bge-small-en-v1.5 model as session recall)
- Per-exchange indexing: each user+assistant pair embedded and stored in `cross_conv_embeddings` table
- Background indexing: fire-and-forget after each response save via `asyncio.create_task(asyncio.to_thread(...))`
- Startup backfill: indexes existing conversations from session_embeddings on first run (idempotent)
- Search: cosine similarity > 0.45 threshold, top 3 results, max 300 chars each, excludes current conversation
- Optional room filter: if query strongly matches a topic room (3+ keyword hits), searches that room first
- Injection: `<cross-conversation-recall>` XML block in system prompt between session_recall and charmap
- Runs parallel with session recall via `asyncio.gather`

## Conversation Compaction (compaction.py)
- Rolling LLM summaries of older conversation segments to prevent context rot
- Threshold: 20+ messages, segment size ~10 messages, skips most recent 12
- Incremental: compacts one oldest uncovered segment per message cycle (not all at once)
- Summarization: non-streaming local LLM call (same pattern as generate_title), 200 max tokens, temperature 0.3
- Summaries stored in `conversation_summaries` table with topic classification
- Summary embeddings stored in `cross_conv_embeddings` as `chunk_type='summary'` for two-tier search
- Injection: `<conversation-summary>` XML block in system prompt, max 400 tokens budget
- Fire-and-forget: `asyncio.create_task(maybe_compact(...))` after each response save
- Escape hatch: `COMPACTION_MIN_MESSAGES` env var (set to 999999 to disable)

## Two-Tier Cross-Conversation Search
- When summary embeddings exist, search summaries first (~1 per conversation, ~50 vectors max)
- Top-5 conversations by summary similarity, then drill into exchange-level embeddings
- Falls back to flat exchange search if no summaries exist yet
- Significantly better scaling as conversation count grows

## Room Categorization (MemPalace-inspired)
- Keyword-based topic classification: technical, architecture, planning, decisions, problems, general
- Each exchange pair classified at index time, stored in `topic_category` column
- No LLM calls — pure keyword counting, highest wins, tie → 'general'
- Used as optional filter in cross-conv search (strong match filters, weak match searches all)

## Temporal Knowledge Graph (knowledge.py)
- Automatic fact extraction from every non-trivial conversation exchange via local LLM
- Extraction prompt: JSON array of {subject, predicate, object, confidence} — max 5 facts per exchange
- LLM call: non-streaming, temperature 0.2, max_tokens 300, enable_thinking false
- Entity normalization: lowercase, strip titles (Dr/Mr/Ms/Prof), spaces to underscores
- Fact invalidation: when a new fact contradicts an old one (same subject+predicate, different object), old fact gets `valid_to` set
- Only current facts (valid_to IS NULL) with confidence >= 0.6 are injected
- Injection: `<knowledge-facts>` XML block, max 8 facts, scored by keyword overlap with query
- Fire-and-forget: `asyncio.create_task(maybe_extract_facts(...))` after each response save
- Escape hatch: `KNOWLEDGE_EXTRACTION_ENABLED` env var (set to "false" to disable extraction; injection still works)
- Duplicate detection: skips inserting facts that already exist as current

## V6 Database Tables
- `conversation_summaries` — rolling LLM summaries of conversation segments (for compaction, Prompt 3)
- `cross_conv_embeddings` — semantic chunks indexed across all conversations (active, populated by cross_memory.py)
- `knowledge_facts` — temporal knowledge graph with validity windows (active, populated by knowledge.py)
- `message_analytics` — per-message token counts, response times, tool rounds, mode (populated after each assistant response)
- Cascade deletes in `prune_conversations()`, `delete_conversation()`, and related cleanup paths

## Character Analysis (charmap.py)
- Detects letter-counting, spelling, and character-position questions via regex
- Pre-computes character breakdown: positions, total count, per-letter frequency
- Injected as `<character-analysis>` XML block in system prompt between session_recall and vision
- Solves tokenizer blindness: LLMs see subword tokens, not individual characters

## Identity Reinforcement
- Identity reminder block (~80 tokens) injected for conversations with 30+ messages
- Reinforces Gizmo identity, model name, and communication style
- Gate: `IDENTITY_REINFORCE_MIN_MESSAGES` env var (default 30, set to 999999 to disable)

## Constitution & Epistemic Honesty
- XML-tagged sections: `<identity>`, `<style>`, `<capabilities>`, `<tool-discipline>`, `<patterns>`, `<memory-rules>`, `<code-execution>`, `<document-generation>`, `<web-search>`, `<response-quality>`, `<precision-awareness>`, `<epistemic-honesty>`, `<memory-awareness>`, `<topic-awareness>`, `<knowledge-awareness>`
- `<memory-awareness>`: teaches model how to use conversation summaries, cross-conv recall, and knowledge facts; prevents hallucination on missing context
- `<topic-awareness>`: prevents topic lock-in — model follows new topics directly without forcing connections to previous topics
- `<knowledge-awareness>`: teaches model to use knowledge facts naturally, avoid re-asking known answers, gently verify potentially outdated facts
- `<epistemic-honesty>` section: distinguishes retrieved content (present exactly), session recall (authoritative record), and training knowledge (confident but flag genuine uncertainty)

## Mode System
- 6 built-in behavioral modes: Chat (default), Brainstorm, Coder, Research, Planner, Roleplay
- Stored as `config/modes/{name}/config.yaml + system.md` (mirrors pattern directory structure)
- Modes are additive behavioral frames — constitution always applies underneath
- Modes do NOT scope tools — that stays with patterns only
- Chat mode has empty system.md (default behavior via constitution alone)
- Mode prompts wrapped in `<mode:{name}>` XML tags, 10-15 lines each
- Global localStorage persistence via `persistedWritable('gizmo:mode', 'chat')`
- Sent to backend on every WebSocket message as `mode` field
- Backend module: `modes.py` (thread-safe cache, double-checked locking, CRUD operations)
- REST endpoints: GET/POST /api/modes, GET/PUT/DELETE /api/modes/{name}
- Built-in modes cannot be deleted; custom modes can be created/edited/deleted
- Path traversal protection: regex-validated names + `is_relative_to()` check
- docker-compose.yml: `./config/modes:/app/config/modes:rw,Z` overrides the `ro` parent mount
- ModeSelector: pill button in ChatInput toolbar (popover dropdown with all modes)
- ModeEditor: modal from Settings → Modes (left list + right editor, create/edit/delete)

## System Prompt Layers (build_system_prompt)
Explicit layered assembly with per-layer token budget logging:
- **L0 (IMMUNE)**: constitution + identity reminder (30+ msgs)
- **L0.5 (Mode)**: mode_prompt (behavioral frame, e.g., "think creatively") — skipped for Chat mode
- **L1 (Context)**: conversation_summary, pattern, recitation, charmap, vision
- **L2 (Memory recall)**: unified block combining session_recall + cross_conv_recall
- **L3 (Knowledge)**: knowledge_facts + BM25 memories

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
- Task search: free-text search across title, description, and tags in filter bar
- Collapsible subtasks: subtask count badge toggles inline expansion (default collapsed)
- Inline title editing: double-click task title to edit in place (desktop only, Enter saves, Escape cancels)
- Undo delete: optimistic removal with 5-second undo toast, DELETE API deferred until timeout
- Keyboard navigation: j/k (move), Enter (open detail), x (cycle status), n (focus quick-add), / (focus search)
- Escape key closes tracker overlays in z-index order (TrackerChat → TaskDetail/NoteEditor)
- TrackerChat uses ThinkingBlock component for expandable thinking (replaces 120-char truncation)

## UI Features
- Mode switcher: pill button in ChatInput toolbar with popover dropdown, ModeEditor modal from Settings
- 9 Nintendo console themes with per-console sounds, screen effects, boot animations
- CSS targeting uses data-role attribute on msg-appear (Lightning CSS strips :has() selectors)
- Toast notification system: global toast store + component, supports optional action button (used for undo)
- Keyboard shortcuts: Ctrl+Shift+N (new chat), Ctrl+Shift+T (toggle think), Ctrl+Shift+S (toggle sidebar), Ctrl+/ (focus input), Escape (close modals/overlays)
- Regenerate response: hover last assistant message → regenerate button
- Message editing: hover user message → edit button → inline textarea → Save truncates and resubmits
- Response history: regenerate/edit preserves variants with < 1/N > navigation arrows
- Streaming markdown: debounced at 150ms, final parse on stream end
- Icon rail navigation: Chat, Tasks, Code, Stats, Settings in left sidebar
- Scroll-to-bottom FAB: floating chevron button appears when user scrolls up, click to return to latest
- Mobile swipe gestures: swipe right from left edge opens sidebar, swipe left closes (via swipe.ts action)
- Mobile message actions: edit/copy/regenerate always visible at 50% opacity on touch devices (no hover needed)
- Sidebar skeleton loaders: 5 animated placeholder items shown during initial conversation list load
- Conversation loading indicator: centered spinner when switching conversations
- Modal focus trapping: Tab key wraps within modal, focus restored on close (via focusTrap.ts action)
- prefers-reduced-motion: all CSS animations disabled for users who request reduced motion
- Responsive message width: user bubbles max-w-[85%] on mobile, 75% on tablet, 65% on desktop
- Animation optimization: msg-appear class only applied to last 3 messages when conversation exceeds 20 messages
- Streaming TTS audio: StreamingAudioPlayer component uses AudioContext for gapless playback of PCM chunks during generation; transitions to standard `<audio>` tag when final WAV is available

## Usage Analytics (analytics.py)
- Captures real token counts from llama.cpp streaming responses (usage field in final chunk)
- Falls back to estimate_tokens() heuristic when llama.cpp doesn't provide usage data
- Per-message storage: prompt_tokens, completion_tokens, total_tokens, response_time_ms, context_build_ms, tool_rounds, mode
- Cost comparison model: 6 cloud providers (OpenAI GPT-4o/mini, Claude Sonnet/Opus 4, Gemini 2.5 Pro/Flash)
- REST: GET /api/analytics/summary, /api/analytics/daily?days=N, /api/analytics/conversations, /api/analytics/costs, /api/analytics/modes
- Dashboard: /analytics route with summary cards, daily usage chart (CSS bars), cost comparison, response time chart, top conversations, mode breakdown
- Time range selector: 7d / 30d / 90d buttons in header
- All data local — no telemetry or external reporting

## V6 Background Processing
After each response is saved, five fire-and-forget background tasks run:
1. `store_analytics()` — persist token counts, timing, and mode to message_analytics table
2. `index_conversation_turns()` — embed user+assistant turns for session recall
3. `index_cross_conversation()` — embed exchange pair for cross-conv search (via asyncio.to_thread)
4. `maybe_compact()` — generate summary if conversation has 20+ messages (async, LLM call)
5. `maybe_extract_facts()` — extract knowledge facts from exchange (async, LLM call)

At startup, three additional background tasks run:
1. `_prewarm_embeddings()` — load fastembed model
2. `backfill_cross_conv_embeddings()` — index existing conversations for cross-conv search
3. `_backfill_v6_systems()` — backfill compaction summaries and knowledge facts for historical conversations

## V6 Escape Hatches
All V6 features independently disableable via environment variables:
- `COMPACTION_MIN_MESSAGES` — set to 999999 to disable compaction (default 20)
- `IDENTITY_REINFORCE_MIN_MESSAGES` — set to 999999 to disable identity reminder (default 30)
- `KNOWLEDGE_EXTRACTION_ENABLED` — set to "false" to disable fact extraction (default "true")
- Cross-conv search: always runs but returns empty if no embeddings exist
- Session recall top_k reduced to 3 (from 5) to accommodate cross-conv results

## V6 Timing Instrumentation
The ws_chat handler logs per-system latency for each request:
`Context build: embed=Xms recall=Xms compact=Xms knowledge=Xms`

## V6 Health Endpoint
`/health` now includes `memory_stats` object with:
- `conversation_summaries` count
- `cross_conv_embeddings` count
- `knowledge_facts_active` and `knowledge_facts_invalidated` counts
- `embed_failures` count

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
- WebSocket origin validation: origins.py allowlist rejects connections from unknown origins (code 4003)
- CORS: explicit allowlist (ALLOWED_ORIGINS env var), not wildcard
- nginx security headers: X-Content-Type-Options nosniff, X-Frame-Options SAMEORIGIN
- faster-qwen3-tts CUDA graphs cannot be moved to CPU — idle unload destroys and recreates the entire model object
- TTS WebSocket streaming uses binary frames for PCM data (float32, 24kHz) paired with JSON metadata frames
- SentenceBuffer minimum 20 chars per sentence to avoid firing TTS for fragments

## Documentation Sync Mandate (MANDATORY)

Every code change MUST include corresponding documentation updates. No exceptions.

### Files that must stay in sync:
- **README.md** — Features, architecture diagram, hardware requirements, quick start
- **.github/wiki/architecture.md** — Container table, WebSocket protocol, REST API table, file tree
- **.github/wiki/setup.md** — Download steps, verification, troubleshooting
- **.github/wiki/usage.md** — Feature descriptions, settings reference
- **.github/wiki/development.md** — Tool definitions, contributing guide
- **.github/wiki/model-reference.md** — Model specs, quantization, TTS details
- **config/models.yaml** — Must match the actual model being served
- **config/services.yaml** — Must match docker-compose.yml ports and service names
- **CLAUDE.md** — System facts, architecture, known issues

### Wiki Publish Step (MANDATORY)
The GitHub Wiki tab is served from a separate repo cloned at `.wiki-published/`.
When ANY `.github/wiki/*.md` file changes, you MUST also:
1. Copy the changed file(s) to `.wiki-published/` (filenames are Title-Case: `architecture.md` → `Architecture.md`)
2. Commit and push `.wiki-published/` so the live Wiki tab stays current

File name mapping: `home.md` → `Home.md`, `architecture.md` → `Architecture.md`, `usage.md` → `Usage.md`, `setup.md` → `Setup.md`, `development.md` → `Development.md`, `model-reference.md` → `Model-Reference.md`, `how-ai-works.md` → `How-the-AI-Works.md`, `_Sidebar.md` → `_Sidebar.md`

### Checklist:
1. New/changed user-facing feature? → README.md + .github/wiki/usage.md
2. Architecture change (service, port, endpoint)? → README.md diagram + .github/wiki/architecture.md
3. Setup change (dependency, download)? → .github/wiki/setup.md
4. New/changed tool? → .github/wiki/architecture.md tool table + tools.py
5. Model or TTS change? → .github/wiki/model-reference.md + README.md + CLAUDE.md
6. docker-compose.yml change? → .github/wiki/architecture.md + config/services.yaml
7. VRAM change? → README.md hardware requirements + CLAUDE.md
8. Any .github/wiki/ change? → Copy to .wiki-published/ and push (see Wiki Publish Step above)
