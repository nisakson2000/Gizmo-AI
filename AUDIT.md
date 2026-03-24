# Gizmo-AI — Audit Report

---

## V5 Status (2026-03-23)

**Build:** Gizmo-AI V5, Huihui-Qwen3.5-9B-abliterated.Q8_0.gguf + Qwen3-TTS-12Hz-1.7B-Base + Whisper (faster-whisper-base)

### Changes from V4

| Change | V4 | V5 |
|--------|----|----|
| **Waiting indicator** | No feedback during pre-stream delay | Pulsing "Gizmo is thinking..." with accent dots |
| **Upload guard** | Send button active during uploads | Send/Enter disabled while uploading |
| **Scroll on load** | No auto-scroll on conversation load | Auto-scrolls to latest message |
| **Conversation titles** | First 80 chars of first message (truncated) | LLM-generated concise titles (max 5 words) |
| **Regenerate response** | Not supported | Hover last assistant message → regenerate button |
| **Message editing** | Not supported | Hover user message → edit → resubmit |
| **Message truncation API** | Not supported | `DELETE /api/conversations/{id}/messages-from/{index}` |
| **Response history** | Not supported | Variant navigation with `< 1/N >` arrows, prompt-aware sync |
| **Streaming perf** | rAF parse (~60/sec) | Debounced 150ms (~7/sec), final parse on done |
| **Conversation export** | Not supported | Markdown/JSON export from sidebar |
| **Full-text search** | Title-only filter | SQLite LIKE search across all messages |
| **TTS truncation** | Silent truncation at 4,000 chars | Info message below audio player |
| **Constitution** | 61 lines | 71 lines — added formatting and web search guidance |
| **Toast system** | Per-component inline errors | Global toast notifications (bottom-right, auto-dismiss) |
| **Keyboard shortcuts** | None | Ctrl+Shift+N/T/S, Ctrl+/, Escape |
| **Conversation rename** | Not supported | Double-click title, PATCH endpoint |
| **Voice metadata** | Base64 stored in JSON (~1MB each) | WAV on disk, no base64 in metadata |
| **Conversation pruning** | Unbounded growth | MAX_CONVERSATIONS=500, auto-prune oldest |
| **Hardcoded URLs** | Tailscale hostname in UI code | Generic HTTPS message, placeholder in docs |

### V4 Issues Resolved in V5

All V4 code issues were resolved in the previous commit (b313c53). V5 focuses on UX enhancements.

---

## V4 Audit (2026-03-17)

**Auditor:** Claude Code
**Build:** Gizmo-AI V4, Huihui-Qwen3.5-9B-abliterated.Q8_0.gguf + Qwen3-TTS-12Hz-1.7B-Base + Whisper (faster-whisper-base)
**Last Commit:** `edde7af` — feat: add error and conversation logging to orchestrator
**Stack Status:** All 6 services healthy and running

### Audit Summary

| Category | Status | Notes |
|----------|--------|-------|
| Infrastructure Health | PASS | All 6 services healthy. VRAM comfortable at ~16.8GB peak. |
| Orchestrator (main.py) | WARN | 2 critical, 4 warning issues found. See below. |
| Tool System (tools.py) | PASS | All 5 tools defined, dispatch correct. |
| Memory System (memory.py) | PASS | BM25 retrieval, path traversal protection, CRUD all correct. |
| Sandbox (sandbox.py) | PASS | Resource limits enforced, cleanup in finally block. |
| Search (search.py) | PASS | SearXNG proxy with error handling. |
| TTS (tts.py) | PASS | Voice cloning, health check, error handling all present. |
| WebSocket Client (client.ts) | PASS | Reconnect with exponential backoff (1s → 30s). |
| Chat Store (chat.ts) | PASS | UUID fallback for non-secure contexts, proper state management. |
| Nginx (nginx.conf) | PASS | DNS resolver configured, 500MB upload limit, WS proxy correct. |
| Docker Compose | WARN | 2 services missing healthchecks. |
| CLAUDE.md Accuracy | FAIL | Major documentation drift — 4 sections describe features that don't exist. |

**Overall: 9/12 categories passing (2 WARN, 1 FAIL)**

### Critical Issues

| # | Issue | File:Line | Description |
|---|-------|-----------|-------------|
| 1 | **Media endpoint OOM risk** | `main.py:733` | `serve_media` uses `filepath.read_bytes()` — loads entire video (up to 500MB) into memory. Should use `FileResponse` for streaming. |
| 2 | **CLAUDE.md documentation drift** | `CLAUDE.md` | Multiple sections describe gizmo project features that don't exist in gizmo-ai: constitution split files, pattern library, JSON conversation files, injection points. Every future Claude Code session will operate on false assumptions. |

### Warning Issues

| # | Issue | File:Line | Description |
|---|-------|-----------|-------------|
| 3 | **Log endpoint reads full file** | `main.py:856` | `/api/logs/{log_name}` calls `read_text()` on up to 10MB file. Should seek from end. |
| 4 | **SQLite connections leak on exception** | `main.py:128-165` | `save_message`, `get_conversation_messages`, `list_conversations`, `delete_conversation` don't use `try/finally` around `conn.close()`. |
| 5 | **Missing healthchecks** | `docker-compose.yml` | `gizmo-ui` and `gizmo-searxng` have no healthchecks. Both have endpoints that could be checked. |
| 6 | **REST chat ignores stream errors** | `main.py:557-607` | `/api/chat` iterates `stream_chat` events but doesn't log or handle `error` type events. Errors are silently lost. |
| 7 | **Double video write** | `main.py:654-668` | `upload_video` writes content to both `MEDIA_DIR` and a temp dir. Can use saved path for ffmpeg. |

### Suggestions

| # | Issue | File:Line | Description |
|---|-------|-----------|-------------|
| 8 | **No `lines` param cap** | `main.py:848` | `/api/logs` accepts unbounded `lines` param. Consider capping at 1000. |
| 9 | **Hardcoded subdirs** | `main.py:795` | `api_clear_memories` repeats `["facts", "conversations", "notes"]` — should import `SUBDIRS` from memory.py. |
| 10 | **Stop status misleading** | `client.ts:161` | `stopGeneration` sets connectionStatus to `'connecting'` during reconnect. |

### V4 Open Issues (Carried Forward + New)

| Issue | Severity | Notes |
|-------|----------|-------|
| **Media endpoint OOM risk** | ~~Critical~~ | **Fixed** — `serve_media` now uses `FileResponse` for streaming. |
| **CLAUDE.md drift** | ~~Critical~~ | **Fixed** — Constitution, conversations, spec deviations, and known issues sections corrected. |
| **Log endpoint full-file read** | ~~Medium~~ | **Fixed** — `tail_file()` helper seeks from end; `lines` param capped at 1000. |
| **SQLite connection leaks** | ~~Medium~~ | **Fixed** — All 4 database functions wrapped in `try/finally`. |
| **Missing healthchecks** | ~~Medium~~ | **Fixed** — `gizmo-ui` and `gizmo-searxng` healthchecks added to docker-compose.yml. |
| **REST chat silent errors** | ~~Medium~~ | **Fixed** — Stream errors now logged and returned as 502 in `/api/chat`. |
| **Context length slider UI-only** | ~~Low~~ | **Fixed** — Slider value sent via WebSocket; orchestrator windows conversation history to fit within token budget. |
| **Double video write** | ~~Low~~ | **Fixed** — `upload_video` reuses saved path for ffmpeg instead of writing twice. |
| **Whisper runs on CPU** | Low | Intentional — avoids VRAM contention. Not a bug. |
| **Add model info to orchestrator health** | ~~Low~~ | **Fixed** — `MODEL_NAME` env var added; `/health` now returns model name. |

---

## V4 Status (2026-03-14)

**Build:** Gizmo-AI V4, Huihui-Qwen3.5-9B-abliterated.Q8_0.gguf + Qwen3-TTS-12Hz-1.7B-Base + Whisper (faster-whisper-base)

### Changes from V3

| Change | V3 | V4 |
|--------|----|----|
| **Memory system** | Keyword matching (crude, 300-char snippets) | BM25 ranking with stop-word filtering, recency weighting, 800-char snippets |
| **Memory management** | File system only | Full UI modal: view, add, delete, clear memories |
| **Code execution** | Not supported | Sandboxed Python execution via Podman container (no network, 256MB RAM, read-only FS) |
| **Code Playground** | Not supported | Dedicated modal with direct Run and Ask Gizmo modes |
| **Vision prompting** | Static constitution instructions for all messages | Conditional VISION_PROMPT injected only when images/video present |
| **Model timeout** | Flat 300s httpx timeout | Per-token 60s inactivity timeout with user-visible error |
| **Audio suggestion** | Buried in file picker | Dedicated suggestion card on home screen |
| **TTS voice selection** | Default voice only in chat | Choose any cloned voice from Voice Studio for chat TTS |
| **System prompt** | Basic 29-line constitution | Expanded with Response Quality, Vision, Memory, Code Execution sections |
| **Tool discipline** | Tools triggered freely | Tightened descriptions + constitution rules prevent spurious tool calls |
| **Logging** | None | Rotating file loggers for errors and conversations + `/api/logs` endpoint |

### V3 Issues Resolved in V4

| V3 Issue | Status |
|----------|--------|
| Model hangs on stalled llama.cpp (300s wait) | **Fixed** — per-token 60s timeout with asyncio.timeout() |
| Memory keyword matching misses semantic matches | **Fixed** — BM25 ranking with TF-IDF scoring |
| No way to manage memories from UI | **Fixed** — MemoryManager modal with full CRUD |
| No code execution capability | **Fixed** — Sandboxed Podman container + run_code tool |
| Audio upload not discoverable | **Fixed** — Dedicated Audio suggestion card |

---

## V3 Status (2026-03-14)

**Build:** Gizmo-AI V3, Huihui-Qwen3.5-9B-abliterated.Q8_0.gguf + Qwen3-TTS-12Hz-1.7B-Base + Whisper (faster-whisper-base)

### Changes from V2

| Change | V2 | V3 |
|--------|----|----|
| **Services** | 5 containers | 6 containers (added Whisper STT) |
| **Vision** | mmproj downloaded but not enabled | Enabled via `--mmproj` flag, fully functional |
| **Voice Studio** | Basic TTS toggle only | Full Voice Studio: upload, name, save, select voices; clip duration selector (30/60/90/120s) |
| **Video** | Not supported | Upload, frame extraction, vision analysis, in-chat video playback |
| **Audio** | Not supported | Upload M4A/MP3/WAV, Whisper transcription, LLM analysis |
| **Speech-to-text** | Not supported | Microphone dictation via Whisper |
| **Conversations** | SQLite (single-origin) | SQLite persisted to host volume (accessible from any origin/device) |
| **Thinking toggle** | Header button | Input area pill (like Claude/ChatGPT) |
| **Constitution** | Basic `constitution.txt` | Expanded `constitution.txt` with vision, memory, code execution, tool discipline sections |
| **File limits** | Default (small) | 50MB docs/images, 500MB video |
| **Tailscale** | HTTP only | HTTPS via `tailscale serve` with Let's Encrypt cert |

### V2 Issues Resolved in V3

| V2 Issue | Status |
|----------|--------|
| Vision not enabled (mmproj not in compose command) | **Fixed** — `--mmproj` flag added to gizmo-llama command. Vision fully functional. |
| Thinking mode always active at model level | **Resolved** — Behavior is by design (model always thinks). UI toggle correctly controls whether reasoning is surfaced. Documented accurately. |
| Context length slider not wired | **Known limitation** — documented as UI-only. Model uses 32,768 configured in compose. |
| No stop generation button | **Fixed** — Stop button present in UI during generation. |
| Nginx DNS cache on restart | **Mitigated** — Container restart behavior improved. Nginx configured with appropriate proxy settings. |

### V3 Open Issues

| Issue | Severity | Notes |
|-------|----------|-------|
| **Context length slider UI-only** | Low | Settings slider exists but value not sent to backend. Model always uses 32,768 from docker-compose.yml. |
| **Whisper runs on CPU** | Low | Not a bug — intentional to avoid VRAM contention. Transcription takes a few seconds for short clips. |

---

## V2 Status (2026-03-13)

**Build:** Gizmo-AI V2, Huihui-Qwen3.5-9B-abliterated.Q8_0.gguf + Qwen3-TTS-12Hz-1.7B-Base

### Changes from V1

| Change | V1 | V2 |
|--------|----|----|
| **LLM** | Qwen3.5-27B Q5_K_M (~22GB VRAM) | Qwen3.5-9B Q8_0 (~12GB VRAM) |
| **TTS** | Kokoro (CPU, Form-data API) | Qwen3-TTS (GPU, JSON API, voice cloning) |
| **Peak VRAM** | ~22.1GB (dangerously tight) | ~16.8GB (comfortable on 24GB) |
| **TTS VRAM** | N/A (CPU) | ~4GB (auto-unloads after 60s idle) |

### V1 Issues Resolved in V2

| V1 Issue | Status |
|----------|--------|
| VRAM at 22120 MiB (~92%), OOM risk | **Fixed** — 9B Q8_0 uses ~12GB, TTS adds ~4GB peak = ~16.8GB (70%) |
| Kokoro TTS used Form data, inconsistent with JSON API | **Fixed** — Qwen3-TTS `/api/tts` accepts JSON |
| `--parallel 4` compounding VRAM pressure | **Mitigated** — reduced to `--parallel 2`, 7.2GB headroom vs 1.9GB in V1 |

---

## V1 Audit (Historical)

**Date:** 2026-03-13
**Auditor:** Claude Code
**Build:** Gizmo-AI v1, Huihui-Qwen3.5-27B-abliterated.i1-Q5_K_M.gguf

## Summary

| Category | Status | Notes |
|----------|--------|-------|
| 1. Infrastructure Health | WARN | All services healthy. VRAM at 22120 MiB (over 22GB threshold). |
| 2. Model Response | PASS | Non-streaming and streaming both work. Model auto-thinks so needs adequate max_tokens. |
| 3. Thinking Mode | FAIL | Thinking ON works. Thinking OFF (`enable_thinking: false`) does NOT suppress thinking — model thinks regardless. |
| 4. WebSocket Streaming | PASS | All 5 protocol checks pass. Event order correct. Orchestrator restart recovery <5s. |
| 5. Tool Calling | PASS | web_search, write_memory, read_memory, list_memories all functional. SearXNG returns results. |
| 7. Kokoro TTS | PASS | Direct: 16,941 byte audio. Orchestrator proxy: 16,869 byte MPEG audio. Endpoint uses Form data, not JSON. |
| 8. Vision/Image Upload | FAIL | mmproj GGUF exists on disk but llama.cpp not started with `--mmproj` flag. Upload endpoint works, but model cannot process images. |
| 9. Conversation Persistence | PASS | SQLite survives restart. Context recalled correctly across restart boundary. |
| 10. UI Functionality | PASS* | Page loads, API/WS proxy works. *Manual browser testing required for interactive elements. Known nginx DNS cache issue on container restart. |
| 11. GitHub Presence | PASS* | Repo public, all files present, profile README updated. *No repository topics set. |
| 12. Tailscale Remote Access | PASS | UI and orchestrator reachable at http://100.69.89.10:3100. Firewall zone: trusted. |

**Overall: 9/12 categories passing (1 WARN, 2 FAIL)**

## Fixes Applied During Audit

- **Nginx reload** — Ran `nginx -s reload` in `gizmo-ui` container to clear stale DNS cache after orchestrator restart. This is a temporary fix; the nginx config should be updated to avoid DNS caching.

## Recommendations for V2

1. **Fix vision:** Add `--mmproj` flag — **Done in V2/V3**
2. **Handle thinking mode properly:** Filter reasoning_content when thinking=false — **Resolved: behavior is by design**
3. **Add nginx DNS resolver** — **Mitigated in V3**
4. **Reduce VRAM pressure:** Lower `--parallel` — **Done in V2 (reduced to 2)**
5. **Set GitHub topics** — **Done**
6. **Normalize API format:** Make `/api/tts` accept JSON — **Done in V2**
7. **Add model info to orchestrator health** — Still pending
