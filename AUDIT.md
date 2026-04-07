# Gizmo-AI — Audit Report

**Date:** 2026-04-07
**Auditor:** Claude Code
**Build:** Gizmo-AI V5.11
**Scope:** Full audit of Phase 1-4 memory/recall code — recite.py, web_fetch.py, session_memory.py, charmap.py, router.py integration points, main.py integration points (deletion cascades, windowing, deduplication, pre-warm), constitution.txt epistemic-honesty section

---

## Summary

| Severity | Count |
|----------|-------|
| Warning  | 0     |
| Suggestion | 3   |
| **Total** | **3** |

---

## Open Issues — Suggestion

### S1. httpx redirect limit is implicit default (20)

**Location:** `web_fetch.py:21`

httpx follows up to 20 redirects by default. A server with many redirects would consume time before failing. Setting `max_redirects=5` explicitly would be safer. Low risk since fetch_page has a 15s timeout.

### S2. index_conversation_turns uses COUNT for message_index

**Location:** `main.py:365-368`

`_count_messages()` returns the total count; `msg_count - 2` and `msg_count - 1` are used as message indices. If two concurrent requests for the same conversation overlapped, indices could be wrong. In practice this doesn't happen because WebSocket connections are single-threaded per conversation and REST calls for the same conversation are sequential. Monitoring recommended if concurrent access is added.

### S3. store_turn failures only logged at WARNING

**Location:** `session_memory.py:89`

Embedding failures are logged at WARNING but not surfaced to the user. If the fastembed model fails to load or ONNX Runtime crashes, all indexing silently stops. Session recall degrades without user-visible indication. Acceptable for fire-and-forget design but could benefit from a health check counter.

---

## Resolved Issues (V5.11 — Phase 4)

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

- **Recitation pipeline** — Conservative regex detection, concurrent page fetches via asyncio.gather, graceful fallback when SearXNG down, 12K char limit correctly applied
- **Session memory** — Thread-safe embedder initialization (double-checked locking), correct cosine similarity, proper SQLite parameterized queries, UNIQUE constraint prevents duplicate embeddings
- **Smart windowing** — FIFO fallback when embeddings unavailable, always keeps last 6 messages, chronological order restored after scoring, budget correctly computed
- **Character analysis** — Pre-compiled regexes, handles Unicode, conservative detection (len >= 2), end-of-message anchoring prevents mid-sentence false positives
- **Router integration** — Recitation and charmap can coexist without conflict, correct priority ordering (recitation → charmap → keyword → pattern → default)
- **Deletion cascades** — session_embeddings cleaned up in all three deletion paths (prune, single delete, partial delete), all within same transaction
- **Constitution epistemic-honesty** — Clear, non-contradictory with existing sections, works when no session recall injected, properly scoped to three specific scenarios
- **Deduplication** — Content-based 200-char prefix matching handles both string and multimodal content, runs after windowing for correct results
- **Pre-warm** — Background thread doesn't block startup, failure is non-critical, embedding model cached on disk via volume mount
- **Prompt layer order** — constitution → pattern → recitation → session_recall → charmap → vision → memories (documented and correct)
