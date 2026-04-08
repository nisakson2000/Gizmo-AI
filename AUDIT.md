# Gizmo-AI — Audit Report

**Date:** 2026-04-07
**Auditor:** Claude Code (Opus 4.6)
**Build:** Gizmo-AI V5.12
**Scope:** Full codebase audit — orchestrator (17 files, ~4,800 LOC), UI (44 files, ~7,800 LOC), infrastructure (6 services, all configs, all scripts)

---

## Summary

| Severity   | Count |
|------------|-------|
| Warning    | 0     |
| Suggestion | 0     |
| **Total**  | **0** |

---

## Resolved Issues (V5.12 — Full Codebase Audit)

| ID | Issue | Fix |
|----|-------|-----|
| W1 | Missing ffmpeg timeout in voice processing | Added `timeout=30` to subprocess.run() in voice upload handler |
| W2 | Cross-site WebSocket hijacking — no origin check | Added origin validation via shared `origins.py` module; all 3 WebSocket handlers reject unknown origins with code 4003 |
| W3 | CORS wildcard enables cross-origin data leakage | Replaced `allow_origins=["*"]` with explicit allowlist from `ALLOWED_ORIGINS` env var |
| S1 | tracker_db.py LIMIT uses f-string instead of parameter | Changed to parameterized `LIMIT ?` with `params.append(int(limit))` |
| S2 | web_fetch.py logs failures at DEBUG level | Changed `logger.debug` to `logger.warning` |
| S3 | nginx.conf missing standard security headers | Added `X-Content-Type-Options: nosniff` and `X-Frame-Options: SAMEORIGIN` |
| S4 | Markdown parse error fallback skips DOMPurify sanitization | Wrapped catch fallbacks in `sanitize()` in ChatMessage.svelte and ChatArea.svelte |

---

<!-- Original audit details preserved below for reference -->
<!--
## (Resolved) Open Issues — Warning

### W1. Missing ffmpeg timeout in voice processing

**Location:** `services/orchestrator/main.py:1521-1525`

```python
subprocess.run(
    ["ffmpeg", "-i", raw_path, "-t", str(dur), "-ar", "24000", "-ac", "1",
     "-y", wav_path],
    capture_output=True,
)
```

Every other `subprocess.run()` in the codebase uses `timeout=30` (video ffprobe at line 1079, video ffmpeg at line 1106). This one does not. If ffmpeg hangs on malformed audio input, the request handler blocks indefinitely, tying up the async worker.

**Fix:** Add `timeout=30` to match the existing pattern.

### W2. Cross-site WebSocket hijacking — no origin check

**Location:** `services/orchestrator/main.py:602` (ws_chat), `services/orchestrator/code_chat.py:102` (ws_code_chat), `services/orchestrator/tracker.py:274` (ws_tracker)

WebSocket endpoints accept connections from any origin. FastAPI's CORSMiddleware only applies to HTTP requests, not WebSocket upgrades. Any website visited in the user's browser can silently open `ws://localhost:3100/ws/chat` and:
- Send messages to the LLM
- Execute code in the sandbox via tool calling
- Read/write memories
- Read streaming responses

The browser itself acts as the proxy — no network access to the machine is required.

**Fix:** Add origin validation in each WebSocket handler. Check the `Origin` header against an allowlist (e.g., `localhost:3100`, Tailscale hostname) and reject connections from unknown origins.

### W3. CORS wildcard enables cross-origin data leakage

**Location:** `services/orchestrator/main.py:502-508`

```python
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)
```

With `allow_origins=["*"]`, any website the user visits can make fetch requests to `http://localhost:3100/api/*` and read the responses. Conversation history, memories, voice data, and tracker content are all accessible. `allow_credentials=False` prevents cookie abuse but is irrelevant since there is no auth — the data is readable without credentials.

**Fix:** Replace `"*"` with an explicit allowlist: `["http://localhost:3100", "https://<tailscale-hostname>"]`. Consider loading allowed origins from an environment variable for flexibility.

---

## Open Issues — Suggestion

### S1. tracker_db.py LIMIT uses f-string instead of parameter

**Location:** `services/orchestrator/tracker_db.py:358`

```python
query += f" LIMIT {int(limit)}"
```

While `int(limit)` prevents SQL injection, every other dynamic value in the query builder uses parameterized `?` placeholders (status, priority, tag, search at lines 222-234, 349-354). The LIMIT clause should follow the same pattern for consistency.

**Fix:** Change to `query += " LIMIT ?"` and `params.append(int(limit))`.

### S2. web_fetch.py logs failures at DEBUG level

**Location:** `services/orchestrator/web_fetch.py:36`

```python
logger.debug("fetch_page failed for %s: %s", url, e)
```

When the recitation pipeline fetches pages and all fail, zero evidence appears at INFO or WARNING level. The V5.11 audit already upgraded `store_turn` failures from DEBUG to WARNING (resolved issue W6) for the same reason — invisible failures harm debuggability.

**Fix:** Change `logger.debug` to `logger.warning`.

### S3. nginx.conf missing standard security headers

**Location:** `services/ui/nginx.conf` (server block, lines 19-68)

No security headers are set. While this is a local system, adding basic headers is low effort and provides defense-in-depth, especially when accessed via Tailscale HTTPS from other devices.

**Fix:** Add to the server block:
```nginx
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "SAMEORIGIN" always;
```

CSP is intentionally omitted — the app uses inline styles (Tailwind), `{@html}` rendering, and data URLs extensively, making a useful CSP impractical.

### S4. Markdown parse error fallback skips DOMPurify sanitization

**Location:** `services/ui/src/lib/components/ChatMessage.svelte:37-39`, `services/ui/src/lib/components/ChatArea.svelte:68-69`

```typescript
// ChatMessage.svelte
try {
    return sanitize(marked.parse(displayContent) as string);
} catch {
    return displayContent;  // raw content, unsanitized → rendered via {@html}
}

// ChatArea.svelte
try {
    parsedStreamingHtml = sanitize(marked.parse(raw) as string);
} catch {
    parsedStreamingHtml = raw;  // raw content, unsanitized → rendered via {@html}
}
```

Both catch blocks return raw content that is then rendered via `{@html}`. If `marked.parse()` ever throws (extremely unlikely but possible with pathological input), LLM output would render as unsanitized HTML. Trivial one-line fix in each file.

**Fix:** Wrap the catch return in `sanitize()`: `return sanitize(displayContent)` and `parsedStreamingHtml = sanitize(raw)`.
-->

---

## Resolved Issues (V5.11 — Phase 4, including final suggestions)

### Suggestions (fixed after initial audit)

| ID | Issue | Fix |
|----|-------|-----|
| S1 | httpx redirect limit implicit default (20) | Added `max_redirects=5` to web_fetch.py AsyncClient |
| S2 | index_conversation_turns uses COUNT for message_index | save_message() now returns 0-based index from DB; indices passed directly to index_conversation_turns |
| S3 | store_turn failures not surfaced | Added embed_failure_count counter in session_memory.py; exposed in /health endpoint when > 0 |

### Issues found and fixed during Phase 4 audit

### Issues found and fixed during this audit:

| ID | Issue | Fix |
|----|-------|-----|
| W1 | session_embeddings not cleaned up on conversation deletion (prune, delete, partial delete) | Added DELETE FROM session_embeddings in prune_conversations(), delete_conversation(), and delete_messages_from() |
| W2 | First request after container restart delayed 2-3s by lazy embedding model load | Added _prewarm_embeddings() called via asyncio.to_thread during lifespan startup |
| W3 | Session recall and smart windowing could inject the same message content twice | Reordered flow: window first, then format session recall excluding messages already in window |
| W4 | Charmap regex extracted wrong word from "how many e's in the word 'coffee'" | Added `(?:the\s+word\s+)?` optional group before word capture + anchored to end of message |
| W5 | Multimodal content arrays (list) caused dedup to fail (str(list) != plain string) | Added _extract_text() helper that extracts text from multimodal content arrays |
| W6 | store_turn failures logged at DEBUG (invisible) | Upgraded to WARNING level |

---

## Resolved Issues (V5.8)

All issues from the V5.8 audit have been resolved.

### Warnings (fixed)

| ID | Issue | Fix |
|----|-------|-----|
| W1 | gizmo-searxng healthcheck has no start_period | Added `start_period: 30s` to docker-compose.yml |
| W2 | gizmo-orchestrator healthcheck has no start_period | Added `start_period: 10s` to docker-compose.yml |
| W3 | TTS failure is silent — no error sent to client | Added `else` branch sending `{"type": "error", "error": "TTS synthesis failed"}` to WebSocket |
| W4 | ffmpeg subprocess has no timeout in upload_video() | Added `timeout=30` to both `ffprobe` and `ffmpeg` `subprocess.run()` calls |
| W5 | stopGeneration() can create overlapping WebSocket connections | Null all handlers on old socket before closing, defer `connect()` to next tick via `setTimeout` |

### Suggestions (fixed)

| ID | Issue | Fix |
|----|-------|-----|
| S1 | DOMPurify SVG allow-list lacks explicit foreignObject block | Added `FORBID_TAGS: ['foreignObject', 'script', 'iframe']` to sanitize.ts |
| S2 | loadConversations() silently swallows fetch errors | Added toast notification on catch |
| S3 | Upload fetch calls have no timeout | Added `AbortController` with 60s timeout via fetchWithTimeout utility |
| S4 | Pattern cache globals have no synchronization primitive | Added `threading.Lock()` with double-checked locking in patterns.py |

---

## Previously Resolved (V5.7)

| ID | Issue | Fix |
|----|-------|-----|
| W1 | Substring keyword matching false positives | Word-boundary regex |
| W2 | REST `/api/chat` not integrated with pattern router | Added route() call |
| W3 | Unhandled exception reading system.md | try/except with continue |
| S1-S7 | Various false positives | Verified and documented |

---

## Things That Are Correct

The following areas were audited and found to be clean:

- **SQL injection protection** — All 40+ queries across main.py, tracker_db.py, session_memory.py use parameterized `?` placeholders. LIKE clauses properly escape `\`, `%`, `_` with `ESCAPE '\'`
- **Path traversal protection** — memory.py uses `is_relative_to()` + filename sanitization; main.py media endpoint uses `.resolve()` + `is_relative_to()`; sandbox uses UUID-based filenames
- **Subprocess safety** — All 6 subprocess calls use list arguments (no `shell=True`). 5 of 6 have `timeout=30` (W1 above is the exception)
- **HTTP client error handling** — All httpx calls use `async with` context managers. Timeouts range from 5s (health) to 300s (LLM streaming). `raise_for_status()` called where appropriate
- **Thread safety** — Pattern cache, embedder singleton, and failure counter all use proper double-checked locking with `threading.Lock()`
- **Resource cleanup** — All SQLite connections closed in `finally` blocks. All httpx clients use async context managers. Temp directories cleaned via `with tempfile.TemporaryDirectory()`
- **Async correctness** — Blocking operations (embeddings, DB reads) properly wrapped in `asyncio.to_thread()`. Pre-warm runs in background thread at startup. No event loop blocking detected
- **Sandbox isolation** — Network disabled, 256MB memory, 1 CPU, 256 PID limit, read-only rootfs, `no-new-privileges`, tmpfs, runs as `nobody`. Excellent defense-in-depth
- **XSS protection** — DOMPurify applied to all `{@html}` rendered content. Forbids `script`, `iframe`, `foreignObject`. SVG allowed with safe subset
- **WebSocket reconnection** — Exponential backoff (1s-30s). Old socket handlers nulled before close. Connection state properly tracked
- **Event listener cleanup** — All `onMount`/`$effect` handlers return cleanup functions. Actions implement `destroy` callbacks. No leaks found
- **Tool execution** — 5-round limit prevents infinite loops. Results truncated to 800 chars. Document generation base64-encodes content to prevent injection
- **Session memory** — UNIQUE constraint prevents duplicates. Cosine similarity correct. Failure counter exposed via `/health`. Deletion cascades handle all three paths
- **Smart windowing** — FIFO fallback when embeddings unavailable. Always keeps last 6 messages. Chronological order restored after scoring
- **Container health** — All 6 services have healthchecks with appropriate intervals (30s), retries (3-5), and start periods (10-90s)
- **GPU memory management** — TTS auto-unloads after 60s idle with `torch.cuda.empty_cache()`. LLM uses Q8_0 KV cache. Peak ~20.7GB within 24GB budget
- **Dependencies** — Python (fastapi, httpx, fastembed) and Node (svelte, marked, dompurify) all current with no known CVEs as of April 2026
- **Recitation pipeline** — Conservative regex detection, concurrent page fetches via asyncio.gather, graceful fallback, 12K char limit
- **Character analysis** — Pre-compiled regexes, handles Unicode, conservative detection, end-of-message anchoring
- **Router integration** — Recitation and charmap coexist without conflict, correct priority ordering
- **Prompt layer order** — constitution → pattern → recitation → session_recall → charmap → vision → memories (documented and correct)
