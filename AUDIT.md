# Gizmo-AI — Audit Report

**Date:** 2026-04-06
**Auditor:** Claude Code
**Build:** Gizmo-AI V5.8
**Scope:** Full codebase review — orchestrator (main.py, llm.py, tools.py, router.py, patterns.py, sandbox.py, tts.py, search.py, memory.py, tracker.py, tracker_db.py, tracker_tools.py, code_chat.py, recite.py, web_fetch.py), all UI components (.svelte, .ts), docker-compose.yml, Dockerfiles, constitution.txt, config files, all 30 pattern configs

---

## Summary

| Severity | Count |
|----------|-------|
| Warning  | 5     |
| Suggestion | 4   |
| **Total** | **9** |

---

## Open Issues — Warning

### W1. gizmo-searxng healthcheck has no start_period

**Location:** `docker-compose.yml:63-67`

```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/"]
  interval: 30s
  timeout: 10s
  retries: 3
```

No `start_period` defined (default is 0s). SearXNG takes several seconds to initialize — the healthcheck fires immediately on container start, which can cause premature failure and restart before the service is ready.

**Fix:** Add `start_period: 30s` to match other services (llama has 60s, tts has 90s, whisper has 60s).

---

### W2. gizmo-orchestrator healthcheck has no start_period

**Location:** `docker-compose.yml:131-134`

```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:9100/health"]
  interval: 30s
  timeout: 10s
  retries: 3
```

Same issue as W1. FastAPI needs a few seconds to initialize (import modules, load patterns, init databases). Without `start_period`, the first healthcheck can fire before the app is listening.

**Fix:** Add `start_period: 10s`.

---

### W3. TTS failure is silent — no error sent to client

**Location:** `main.py:693-709`

```python
audio_data = await synthesize(
    full_response,
    voice_clone_data_url=voice_clone_ref,
    voice_reference_text=voice_ref_text,
    speed=tts_speed,
    language=tts_language,
)
if audio_data:
    # ... save and send audio ...
```

If `synthesize()` returns `None` (TTS container down, CUDA OOM, model unloaded), the code silently skips audio. The user receives their text response but gets no audio and no indication that TTS failed. The `tts.py` error paths (lines 55-67) all return `None`.

**Fix:** Add an `else` branch that sends an error event to the WebSocket:
```python
else:
    await ws.send_json({"type": "error", "error": "TTS synthesis failed — audio unavailable"})
```

---

### W4. ffmpeg subprocess has no timeout in upload_video()

**Location:** `main.py:923-927`

```python
subprocess.run(
    ["ffmpeg", "-ss", str(ts), "-i", str(saved_video_path),
     "-frames:v", "1", "-q:v", "3", "-y", frame_path],
    capture_output=True
)
```

No `timeout` parameter. A malformed or adversarial video file could hang ffmpeg indefinitely, blocking the async worker thread (this is a sync `subprocess.run` inside an async endpoint). With `--parallel 2` on the LLM and limited async workers, one hung upload could degrade the whole service.

**Fix:** Add `timeout=30` to the `subprocess.run()` call. The `ffprobe` call at line 896 should also get a timeout.

---

### W5. stopGeneration() can create overlapping WebSocket connections

**Location:** `services/ui/src/lib/ws/client.ts:201-208`

```typescript
if (ws) {
    ws.onclose = null; // Prevent double reconnect
    ws.close();
    ws = null;
}
reconnectDelay = 1000;
connect();
```

Setting `ws.onclose = null` prevents the reconnect handler from firing, but `ws.close()` is asynchronous — the underlying connection may not be fully closed before `connect()` creates a new one on line 207. During that window, two WebSocket connections can coexist. If the old socket's pending messages arrive on the new socket's handlers (they won't — `ws` is already reassigned), or if the server sends a final message on the old connection, it's silently dropped.

In practice this is low-risk because `ws` is immediately set to `null` and `connect()` creates a fresh reference. But the pattern is fragile.

**Fix:** Use a `stopped` flag checked in handlers rather than nulling them, and await the close before reconnecting.

---

## Open Issues — Suggestion

### S1. DOMPurify SVG allow-list lacks explicit foreignObject block

**Location:** `services/ui/src/lib/utils/sanitize.ts:4-7`

```typescript
return DOMPurify.sanitize(html, {
    ADD_ATTR: ['class'],
    ADD_TAGS: ['svg', 'path', 'circle'],
});
```

DOMPurify blocks `foreignObject` by default, so this is safe today. But `ADD_TAGS` with SVG elements widens the surface area. Adding `FORBID_TAGS: ['foreignObject', 'script', 'iframe']` explicitly would be defense-in-depth against future DOMPurify version changes.

---

### S2. loadConversations() silently swallows fetch errors

**Location:** `services/ui/src/lib/stores/chat.ts:176-178`

The catch block in `loadConversations()` is empty. If the orchestrator is unreachable (network error, container restarting), the sidebar shows stale data with no user-visible error. Should emit a toast notification.

---

### S3. Upload fetch calls have no timeout

**Location:** `services/ui/src/lib/components/ChatInput.svelte:133-174`

Fetch calls for `/api/transcribe`, `/api/upload-video`, and `/api/upload-image` have no `AbortController` timeout. If the server hangs (e.g., Whisper processing a very long audio file, ffmpeg stuck on a video), the `uploading = true` state never clears, leaving the send button permanently disabled until page reload.

**Fix:** Add `AbortController` with a 60s timeout on each fetch call.

---

### S4. Pattern cache globals have no synchronization primitive

**Location:** `services/orchestrator/patterns.py:15-17`

```python
_pattern_cache: dict[str, dict] = {}
_cache_loaded = False
```

These module-level globals are read/written without any `asyncio.Lock` or `threading.Lock`. In practice this is not a real risk because `reload_patterns()` runs eagerly during FastAPI lifespan startup (before any requests are accepted), and the cache is only written during startup. But if `reload_patterns()` were ever called at runtime (e.g., hot-reload), concurrent reads could see partial state.

---

## Verified Correct (False Positives Rejected)

The following claims from the automated sweep were investigated and found to be non-issues:

| Claim | Verdict | Reason |
|-------|---------|--------|
| XSS via SVG `foreignObject` | **Safe** | DOMPurify blocks `foreignObject` by default even with `ADD_TAGS: ['svg']` — downgraded to S1 suggestion |
| `recitation_context` parameter missing from `build_system_prompt()` | **False** | Parameter exists in the function signature at `main.py:254-256` |
| Audio double-finalize data corruption in `client.ts` | **False** | `audioFinalized` flag works correctly — set in `audio` handler, checked in `done` handler, reset on line 135 |
| Symlink traversal in `serve_media()` | **False** | `resolve()` + `is_relative_to()` at `main.py:952-954` catches symlinks — `resolve()` follows symlinks before the boundary check |
| Sandbox code injection via env vars | **False** | `printenv SOURCE_CODE > /tmp/file` outputs literal env var value — shell metacharacters in the value are not interpreted |
| Pattern cache race condition (CRITICAL) | **Overstated** | Eager loading at startup eliminates the race window — downgraded to S4 suggestion |
| SQL injection in tracker `LIMIT` clause | **False** | `int(limit)` cast at `tracker_db.py:358` prevents injection; unbounded limit is a style issue, not a vulnerability |
| `httpx.AsyncClient` resource leak in `llm.py` | **False** | Context manager (`async with`) guarantees cleanup on all exit paths including early returns |
| `generate_title()` silent failure | **False** | Wrapped in try/except at `main.py:422` with error logging — fire-and-forget is intentional for a non-critical background task |

---

## Previously Resolved Issues (V5.7)

All issues from the initial V5.7 audit were resolved in commits `cafb628` and prior.

### Warnings (fixed in cafb628)

| ID | Issue | Fix |
|----|-------|-----|
| W1 | Substring keyword matching caused false-positive pattern activation | Replaced with word-boundary regex (`re.search(r'\b' + re.escape(keyword) + r'\b')`) |
| W2 | REST `/api/chat` not integrated with pattern router | Added `route()` call and `pattern`/`tool_schemas` passthrough, mirroring WebSocket handler |
| W3 | Unhandled exception reading `system.md` could crash startup | Wrapped in try/except with `logger.warning()` and `continue` |

### Suggestions (fixed in cafb628 or already implemented)

| ID | Issue | Resolution |
|----|-------|------------|
| S1 | Keyword matching should use word-boundary regex | Fixed with W1 |
| S2 | Short/generic keywords cause false matches | Removed `vs`, `what is`, `how does`, `explain`; lengthened `edit this` → `edit this writing`, `not working` → `code not working`, etc. |
| S3 | `[pattern:name]` prefix sent to LLM and DB | Router now strips prefix, returns cleaned message; both WS and REST handlers use cleaned text |
| S4 | Google Fonts loaded from CDN | Already self-hosted in `static/fonts/` — was a false positive |
| S5 | Console sounds toggle missing `aria-label` | Already present: `aria-label="Toggle console sounds"` on `Settings.svelte:104` |
| S6 | Sidebar mobile overlay has no keyboard dismiss | Already handled: `+layout.svelte:29` global Escape handler chain includes `sidebarOpen` |
| S7 | No unsaved-changes guard in TaskDetail/NoteEditor | Already implemented: `onDestroy` handlers flush dirty state; 800ms debounce with `capturedTaskId`/`capturedNoteId` |

---

## Things That Are Correct

The following areas were audited and found to be clean:

- **All imports valid** — No circular imports, no missing modules across all orchestrator Python files and UI TypeScript/Svelte files
- **async/await correctness** — All async generators properly consumed, no missing awaits
- **Tool registry consistency** — All 6 TOOL_DEFINITIONS have matching TOOL_REGISTRY entries; all pattern config.yaml `tools` lists reference valid tool names only
- **Pattern loading** — 30 patterns load correctly, cache system works, `list_patterns()` and `get_pattern()` return expected data
- **Router three-step logic** — Keyword pre-routing, pattern matching, and default fallback work correctly; tool sets properly merge via set union
- **Tool scoping** — `get_default_tools()` returns always_available tools; patterns add their scoped tools on top; no tool is accidentally excluded
- **WebSocket protocol** — All message types properly handled in all three WS clients (chat, code, tracker)
- **ToolCallBlock.svelte** — Correct Svelte 5 $props usage, media detection works, copy-to-clipboard functional
- **Constitution** — `<patterns>` section properly instructs the LLM to follow active patterns
- **Docker/Podman** — All volume mounts include `:Z` for SELinux, all containers have healthchecks, all have restart policies, all ports unique and documented
- **VRAM safety** — No new GPU consumers added; pattern system is CPU-only
- **Security** — Pattern system prompts loaded from read-only mount; `[pattern:name]` lookup is cache-key only (no path traversal); no user input reaches filesystem paths; serve_media uses resolve() + is_relative_to(); sandbox has network disabled, read-only rootfs, memory/CPU/PID limits
- **Code chat and tracker isolation** — Intentionally do NOT use the pattern router; dedicated tool sets by design
- **Multi-round tool loop** — Both WebSocket and continuation streams pass `route_result.tool_schemas` consistently
- **persistedWritable** — Clean dedup into `persisted.ts`, all consumers properly import, try/catch on JSON.parse present
- **LIKE escaping** — Both `main.py:963` and `tracker_db.py:351-353` properly escape SQL LIKE metacharacters
- **Sandbox execution** — Proper isolation (read-only rootfs, no network, 256MB memory, 1 CPU, 256 PID limit, tmpfs /tmp)
- **Tool argument validation** — `execute_tool()` uses explicit function name whitelist, no dynamic dispatch
- **No hardcoded secrets** — No credentials, API keys, or tokens in any source or config file
