# Gizmo-AI — Audit Report

**Date:** 2026-03-25
**Auditor:** Claude Code
**Build:** Gizmo-AI V5.1, Huihui-Qwen3.5-9B-abliterated.Q8_0.gguf + Qwen3-TTS-12Hz-1.7B-Base + Whisper (faster-whisper-base)
**Last Commit:** `8586058` — docs: update wiki for tracker, enhanced themes, and UX changes
**Scope:** Full codebase review — orchestrator (main.py, llm.py, tracker.py, tracker_db.py, tracker_tools.py, memory.py, tools.py, sandbox.py, search.py, tts.py), all UI components (.svelte, .ts), nginx config, docker-compose, documentation

---

## Summary

| Severity | Count |
|----------|-------|
| Critical | 5 |
| Warning | 17 |
| Suggestion | 10 |
| **Total** | **32** |

---

## Critical Issues

| # | Issue | Location | Description | Fix |
|---|-------|----------|-------------|-----|
| C1 | **Tracker endpoints may reject JSON bodies** | `tracker.py:109,133,190,214` | All four POST/PATCH tracker endpoints use bare `dict` type hint for `request_body`. Depending on FastAPI version, this may be treated as a query parameter instead of request body, returning 422. | Use `request: Request` + `await request.json()`, or annotate with `Body(...)`. **Verify against running stack first.** |
| C2 | **`openFilePicker` drops the selected file** | `ConsoleButtons.svelte:24-28` | SNES "Eject", GameCube "Disc cover", and Wii "Eject" buttons create a file input and click it, but attach no `onchange` handler. User picks a file, nothing happens. Three console buttons are non-functional. | Wire to `ChatInput`'s file upload logic, or expose a shared action via store. |
| C3 | **Concurrent `loadTasks()`/`loadNotes()` race on `tool_result`** | `tracker-client.ts:113-114` | Each `tool_result` event fires parallel fetches. With 3 tool calls in one AI turn, 3 concurrent loads race — last to resolve wins, may show stale data. | Debounce, or move refresh to the `done` event only. |
| C4 | **Tag LIKE search false-positive matches** | `tracker_db.py:219,334` | `json.dumps(tag)[1:-1]` strips boundary quotes from the JSON string. Searching for tag `"wo"` matches any task tagged `["workflow"]`. | Use SQLite `json_each()`: `EXISTS (SELECT 1 FROM json_each(tags) WHERE value = ?)`. |
| C5 | **`JSON.parse` on persisted localStorage with no try/catch** | `tracker.ts:7` (via `persistedWritable`) | Corrupt or schema-mismatched localStorage crashes the page on startup. All `persistedWritable` callers are vulnerable. | Wrap `JSON.parse(stored)` in try/catch, fall back to `defaultValue`. |

---

## Warning Issues — Backend

| # | Issue | Location | Description | Fix |
|---|-------|----------|-------------|-----|
| W1 | **`LLAMA_URL` imported then shadowed** | `main.py:24,48` | Imported from `llm.py` on line 24, immediately redefined locally on line 48. Both compute the same value today. Dead import that will silently diverge if defaults change. | Remove `LLAMA_URL` from the line-24 import, or remove the local redefinition on line 48. |
| W2 | **`arguments.pop()` mutates caller's dict** | `tracker_tools.py:335,380` | `update_task` and `update_note` handlers pop `task_id`/`note_id` from the arguments dict in-place. Safe today (fresh `json.loads()` per call), but fragile for any refactor. | Use `.get()` and filter with dict comprehension. |
| W3 | **Synchronous SQLite on async WebSocket** | `tracker.py:283` | `_build_context_summary()` calls synchronous `list_tasks()`/`list_notes()` on the async event loop. Blocks all other async work during DB queries. | Wrap in `asyncio.to_thread()`. |
| W4 | **`delete_task` orphans grandchild tasks** | `tracker_db.py:183-185` | Only deletes one level of subtasks (`WHERE parent_id = ?`). Nested subtasks become orphaned rows. | Recursive CTE delete, or enable `PRAGMA foreign_keys = ON` with `ON DELETE CASCADE`. |
| W5 | **`complete_task` recurrence is non-atomic** | `tracker_db.py:146-168` | Mark-done and create-next-occurrence use separate connections and commits. A crash between them loses the recurrence permanently. | Use a single connection and transaction. |
| W6 | **Partial response saved to history on stream error** | `tracker.py:398-400` | If LLM stream errors mid-token, the truncated `full_response` is truthy and saved to `message_history`, corrupting future turns. | Track a `stream_errored` flag; skip history save on error. |

## Warning Issues — UI

| # | Issue | Location | Description | Fix |
|---|-------|----------|-------------|-----|
| W7 | **All tracker API errors silently swallowed** | `tracker.ts:83-85,99-101,115-117` | Every `catch` block in store functions is empty. Failed creates, updates, and deletes produce no user feedback. Users lose edits without knowing. | Return success/failure from store functions; surface errors via toast. |
| W8 | **`$effect` sync clobbers in-progress edits** | `TaskDetail.svelte:21-32`, `NoteEditor.svelte:15-23` | Any `loadTasks()` call (e.g. from a status cycle click) re-runs the effect and resets all form fields to server values, silently discarding unsaved edits. | Only sync when selected ID changes, not on every store update. |
| W9 | **Sort `<select>` not bound to store value** | `TaskList.svelte:57-62` | No `value={$taskFilter.sort}` on the select element. On page load, visual sort selection doesn't reflect persisted filter from localStorage. | Add `value={$taskFilter.sort}` to the `<select>`. |
| W10 | **`connectTracker()` doesn't cancel pending reconnect** | `tracker-client.ts:44` | Early-return guard doesn't clear `reconnectTimeout`. A scheduled reconnect can fire after a manual connect, creating a duplicate socket. | Clear `reconnectTimeout` at top of `connectTracker()`. |
| W11 | **`reconnectDelay` never reset** | `tracker-client.ts:78-84` | `disconnectTracker()` doesn't reset `reconnectDelay`. After a maxed-out backoff session, next visit waits up to 30s on first connect. | Reset `reconnectDelay = 1000` in `disconnectTracker()`. |
| W12 | **`replayBoot` in ConsoleButtons is fragile** | `ConsoleButtons.svelte:13-22` | Reimplements boot replay via theme toggle (`set('default')` then `set(current)` on rAF) instead of calling `BootSequence`'s exported `replayBoot()`. Causes a visible flash to default theme during the toggle. | Call `BootSequence.replayBoot()` via component binding or store signal. |
| W13 | **`playCancel` imported but never called** | `ChatInput.svelte:7` | Dead import — suggests cancel sounds were planned for stop-gen or dismiss-attachment but never wired up. | Remove the import, or wire `playCancel()` to the stop-generation and dismiss-attachment actions. |
| W14 | **`playBootSound` bypasses `soundsEnabled` guard** | `sounds.ts:285-290` | Boot sound plays even when user has explicitly disabled console sounds in Settings. | Add `if (!get(soundsEnabled)) return;` to `playBootSound`. |
| W15 | **`disconnectTracker` doesn't reset streaming state** | `tracker-client.ts:172-176` | Mid-generation disconnect leaves `trackerStreamingContent` populated and `finalizeTrackerMessage()` never called. Partial content lingers in UI. | Call `finalizeTrackerMessage()` or clear streaming stores on disconnect. |
| W16 | **BootSequence dismiss `setTimeout` not tracked** | `BootSequence.svelte:26-28` | 300ms fade-out timer isn't stored or cleared on destroy. Fires on unmounted component during rapid theme switches. | Store the timeout handle; clear in `onDestroy`. |

## Warning Issues — Data

| # | Issue | Location | Description | Fix |
|---|-------|----------|-------------|-----|
| W17 | **`loadConversation` drops variant/media state** | `chat.ts:152-175` | Loaded conversations don't restore `variants`, `variantIndex`, `videoFrames`, or `ttsInfo`. Variant arrows and media attachments disappear after page reload. | Persist and restore these fields from DB. |

---

## Suggestions

| # | Issue | Location | Description |
|---|-------|----------|-------------|
| S1 | Cache `_load_tracker_prompt()` | `tracker.py:30-35` | Reads disk on every WebSocket message. Cache with mtime check. |
| S2 | `_build_context_summary` truncates mid-line | `tracker.py:84` | `summary[:char_budget]` can cut a task entry in half. Truncate at line boundary. |
| S3 | `list_notes()` fetches all then slices `[:5]` in Python | `tracker.py:74` | Should use `LIMIT 5` in the SQL query. |
| S4 | Google Fonts loaded from CDN | `themes.css:7` | Self-hosted app depends on external CDN. Fonts unavailable on isolated LAN. Self-host in `static/fonts/`. |
| S5 | Console sounds toggle missing `aria-label` | `Settings.svelte:99-106` | `role="switch"` has no label for screen readers. Add `aria-label="Toggle console sounds"`. |
| S6 | Sidebar mobile overlay has no keyboard dismiss | `Sidebar.svelte:161-165` | Escape doesn't close sidebar. Add `sidebarOpen` to the layout's Escape handler chain. |
| S7 | No unsaved-changes guard in TaskDetail/NoteEditor | `TaskDetail.svelte`, `NoteEditor.svelte` | Clicking another task/note silently discards edits. Track `isDirty` and prompt or indicate. |
| S8 | `_new_id()` uses 8 hex chars (32 bits) | `tracker_db.py:24` | Collision probability becomes meaningful above ~65k items. Consider 12+ chars. |
| S9 | CLAUDE.md Future Roadmap stale | `CLAUDE.md:208-218` | Some implemented features may still be listed as future. Review and prune. |
| S10 | VRAM docs inconsistent | `wiki/setup.md` vs `CLAUDE.md:23` | setup.md says ~16.8GB, CLAUDE.md says ~20.7GB. Reconcile to one accurate figure. |
