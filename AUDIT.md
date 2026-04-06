# Gizmo-AI — Audit Report

**Date:** 2026-04-06
**Auditor:** Claude Code
**Build:** Gizmo-AI V5.7
**Scope:** Full codebase review — pattern system (router.py, patterns.py, 30 pattern configs), orchestrator (main.py, llm.py, tools.py, tracker.py, tracker_db.py, tracker_tools.py, memory.py, sandbox.py, search.py, tts.py, code_chat.py), all UI components (.svelte, .ts), docker-compose.yml, constitution.txt

---

## Summary

| Severity | Count |
|----------|-------|
| Warning  | 3     |
| Suggestion | 7   |
| **Total** | **10** |

---

## Open Issues — Warning

### W1. Substring keyword matching causes false-positive pattern activation

**Location:** `patterns.py:94`
**Code:** `if keyword in msg_lower and len(keyword) > best_length`

The keyword matcher uses plain Python `in` (substring containment), not word-boundary matching. Short or common keywords embedded inside unrelated words cause incorrect pattern activation.

**Concrete examples:**

| Keyword | Pattern | False match in | Result |
|---------|---------|----------------|--------|
| `vs` (2 chars) | compare_options | "plot on a **canvas**" | Comparison pattern activates on unrelated message |
| `vs` | compare_options | "check **devs** tools" | Same |
| `what is` (7 chars) | explain_technical | "**what is** the weather today" | Technical explanation pattern activates on casual question |
| `how does` (8 chars) | explain_technical | "**how does** the pasta taste" | Same — not a technical question |
| `edit this` (9 chars) | improve_writing | "**edit this** code to add logging" | Writing pattern activates on a code editing request |
| `not working` (11 chars) | debug_code | "my **not working** hours schedule" | Code debug pattern activates on non-code message |

**Impact:** The LLM receives the wrong pattern's system prompt (e.g., "You are a writing improvement specialist" when the user asked about code), producing off-topic structured output instead of a normal response.

**Fix:** Replace substring matching with word-boundary regex: `re.search(r'\b' + re.escape(keyword) + r'\b', msg_lower)`. Additionally, audit the keyword lists to remove or lengthen keywords under 4 characters (see S1 and S2).

---

### W2. REST `/api/chat` endpoint not integrated with pattern router

**Location:** `main.py:737` and `main.py:749`

The WebSocket handler correctly uses the pattern router:
```python
# main.py:552-573 (WebSocket — correct)
route_result = route(user_text)
system_prompt = build_system_prompt(user_text, has_vision=has_vision, pattern=route_result.pattern)
async for event in stream_chat(messages, ..., tool_schemas=route_result.tool_schemas):
```

The REST handler does not:
```python
# main.py:737-749 (REST — missing router)
system_prompt = build_system_prompt(message)           # no pattern parameter
async for event in stream_chat(messages, thinking):    # no tool_schemas parameter
```

**Impact:** Patterns never activate on REST API requests. Tool scoping (the core feature of V5.7) doesn't apply. The REST endpoint always sends all 6 tool definitions regardless of intent, and never injects pattern system prompts.

**Fix:** Add `route_result = route(message)` and pass `pattern=route_result.pattern` and `tool_schemas=route_result.tool_schemas` in the REST handler, mirroring the WebSocket handler.

---

### W3. Unhandled exception reading `system.md` can crash app startup

**Location:** `patterns.py:49`

The `_load_patterns()` function wraps `config.yaml` parsing in try/except (line 43-46) but does NOT wrap `system.md` reading:

```python
# Line 43-46 — config.yaml: has try/except (good)
try:
    config = yaml.safe_load(config_path.read_text(encoding="utf-8")) or {}
except Exception as e:
    logger.warning(...)

# Line 49 — system.md: NO try/except (bad)
system_prompt = system_path.read_text(encoding="utf-8").strip()
```

Since `reload_patterns()` is called during FastAPI lifespan startup (`main.py:362`), any unreadable `system.md` (permissions error, encoding issue, disk error) crashes the entire orchestrator container.

**Impact:** One bad pattern file prevents all 6 services from functioning (orchestrator won't start, UI has no backend).

**Fix:** Wrap line 49 in try/except with a `logger.warning()` and `continue`, matching the config.yaml error handling pattern.

---

## Open Issues — Suggestion

### S1. Keyword matching should use word-boundary regex

**Location:** `patterns.py:88-96`

Replace the current substring approach:
```python
if keyword in msg_lower and len(keyword) > best_length:
```
With word-boundary regex:
```python
if re.search(r'\b' + re.escape(keyword) + r'\b', msg_lower) and len(keyword) > best_length:
```

This prevents "vs" from matching inside "canvas" while still matching "python vs javascript".

---

### S2. Remove or lengthen short/generic keywords

**Location:** Various `config/patterns/*/config.yaml`

| Pattern | Keyword | Problem | Suggested replacement |
|---------|---------|---------|----------------------|
| compare_options | `vs` | 2 chars, matches inside many words | Remove (keep `versus`, `pros and cons`) |
| explain_technical | `what is` | Matches virtually any question | Remove (keep `eli5`, `teach me`) |
| explain_technical | `how does` | Matches non-technical questions | Remove (keep `teach me`, `eli5`) |
| explain_technical | `explain` | Ambiguous with explain_code | Change to `explain concept` or `explain topic` |
| improve_writing | `edit this` | Matches code editing requests | Change to `edit this writing` or `edit this text` |
| improve_writing | `rewrite` | Matches "overwrite" as substring | Change to `rewrite this` |
| debug_code | `not working` | Matches non-code complaints | Change to `code not working` or `is not working` |
| debug_code | `error in` | Matches "terror in" etc. | Change to `error in my code` or `error in this` |

---

### S3. Strip `[pattern:name]` prefix from user message after matching

**Location:** `patterns.py:82-86` (match only), `main.py:531,548` (sent to LLM and saved to DB)

When a user sends `[pattern:extract_wisdom] key takeaways from this article`, the `[pattern:extract_wisdom]` prefix is:
- Sent to the LLM in the user message (line 531)
- Saved to the conversation database (line 548)
- Used for BM25 memory retrieval (line 558 via build_system_prompt)

The prefix is noise for the LLM and pollutes memory matching. The router should strip the prefix after matching, passing only the actual user message downstream.

---

### S4. Google Fonts loaded from CDN

**Location:** `themes.css`

Self-hosted app depends on external Google Fonts CDN. Fonts unavailable on isolated LAN. Self-host in `static/fonts/`.

---

### S5. Console sounds toggle missing `aria-label`

**Location:** `Settings.svelte`

`role="switch"` element has no label for screen readers. Add `aria-label="Toggle console sounds"`.

---

### S6. Sidebar mobile overlay has no keyboard dismiss

**Location:** `Sidebar.svelte`

Escape key doesn't close sidebar on mobile. Add `sidebarOpen` to the layout's Escape handler chain.

---

### S7. No unsaved-changes guard in TaskDetail/NoteEditor

**Location:** `TaskDetail.svelte`, `NoteEditor.svelte`

Auto-save mitigates data loss, but clicking another task/note during the 800ms debounce window can discard edits. Low risk due to `onDestroy` save.

---

## Things That Are Correct

The following areas were audited and found to be clean:

- **All imports valid** — No circular imports, no missing modules across all orchestrator Python files and UI TypeScript/Svelte files
- **async/await correctness** — All async generators properly consumed, no missing awaits
- **Tool registry consistency** — All 6 TOOL_DEFINITIONS have matching TOOL_REGISTRY entries; all pattern config.yaml `tools` lists reference valid tool names only
- **Pattern loading** — 30 patterns load correctly, cache system works, `list_patterns()` and `get_pattern()` return expected data
- **Router three-step logic** — Keyword pre-routing, pattern matching, and default fallback work correctly for the documented cases; tool sets properly merge via set union
- **Tool scoping** — `get_default_tools()` returns always_available tools; patterns add their scoped tools on top; keyword routes add their tools on top; no tool is accidentally excluded
- **WebSocket protocol** — All message types (trace_id, thinking, token, tool_call, tool_result, tts_info, audio, done, title, error) properly handled in all three WS clients (chat, code, tracker)
- **ToolCallBlock.svelte** — Correct Svelte 5 $props usage, media detection works, copy-to-clipboard functional, all tool labels present
- **Constitution** — New `<patterns>` section properly instructs the LLM to follow active patterns
- **Docker/Podman** — pyyaml>=6.0 in requirements.txt, config/ mounted read-only at /app/config, patterns directory accessible at /app/config/patterns
- **VRAM safety** — No new GPU consumers added; pattern system is CPU-only (text matching + YAML parsing)
- **Security** — Pattern system prompts loaded from read-only mount; `[pattern:name]` lookup is cache-key only (no path traversal); no user input reaches filesystem paths
- **Code chat and tracker isolation** — Intentionally do NOT use the pattern router; they have their own dedicated tool sets (run_code for code chat, tracker tools for tracker). This is correct by design.
- **Multi-round tool loop** — Both WebSocket (line 596-664) and continuation streams (line 641-642) pass `route_result.tool_schemas` consistently
- **persistedWritable extraction** — Clean dedup into `persisted.ts`, all 5 consumers properly import, try/catch on JSON.parse present
- **LIKE escaping** — Both `main.py:963` and `tracker_db.py:351-353` properly escape SQL LIKE metacharacters with `ESCAPE '\'` clause
