# Gizmo-AI — Audit Report

**Date:** 2026-04-06
**Auditor:** Claude Code
**Build:** Gizmo-AI V5.7
**Scope:** Full codebase review — pattern system (router.py, patterns.py, 30 pattern configs), orchestrator (main.py, llm.py, tools.py, tracker.py, tracker_db.py, tracker_tools.py, memory.py, sandbox.py, search.py, tts.py, code_chat.py), all UI components (.svelte, .ts), docker-compose.yml, constitution.txt

---

## Summary

| Severity | Count |
|----------|-------|
| Warning  | 0     |
| Suggestion | 0   |
| **Total** | **0** |

---

## Resolved Issues

All issues from the V5.7 audit have been resolved. Fixes shipped in commits `cafb628` and prior.

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
