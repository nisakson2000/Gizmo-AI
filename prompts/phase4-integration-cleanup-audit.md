# Phase 4: Integration Test, Cleanup, and Audit — Coder Prompt

You are doing the final pass on a three-phase memory/recall improvement for Gizmo-AI. Read CLAUDE.md first for full project context.

Phases 1-3 added: a recitation pipeline (web fetch + context injection), session history RAG (fastembed embeddings + SQLite vectors), smart history windowing (semantic scoring), anti-confabulation constitution rules, and character analysis injection. All three phases are implemented and individually tested. Your job is to fix known issues, run an end-to-end integration test, and perform a full audit.

## Part 1: Fix — Embedding Cleanup on Conversation Deletion

### Problem

There are three places where conversation data is deleted, and none of them clean up the `session_embeddings` table:

1. **`prune_conversations()`** (main.py line 183-202): Bulk deletes oldest conversations from `conversations` and `messages` tables. Orphaned embeddings accumulate in `session_embeddings` forever.

2. **`delete_conversation()`** (main.py line 1258-1267): Single conversation deletion via `DELETE /api/conversations/{id}`. Same problem.

3. **`delete_messages_from()`** (main.py line 1270-1292): Partial message deletion used by edit/regenerate. Deletes messages from a given index onward, but leaves stale embeddings for those indices. Smart windowing would then use outdated similarity scores for those message positions.

### Fix

**prune_conversations() (line 196-197):** Add a DELETE from session_embeddings using the same `ids` list and `placeholders`. Add it right after the existing two DELETE statements:

```python
conn.execute(f"DELETE FROM messages WHERE conversation_id IN ({placeholders})", ids)
conn.execute(f"DELETE FROM session_embeddings WHERE conversation_id IN ({placeholders})", ids)
conn.execute(f"DELETE FROM conversations WHERE id IN ({placeholders})", ids)
```

**delete_conversation() (line 1262-1263):** Add a DELETE from session_embeddings for the conversation_id. Add it right after the existing messages DELETE:

```python
conn.execute("DELETE FROM messages WHERE conversation_id = ?", (conv_id,))
conn.execute("DELETE FROM session_embeddings WHERE conversation_id = ?", (conv_id,))
conn.execute("DELETE FROM conversations WHERE id = ?", (conv_id,))
```

**delete_messages_from() (line 1288):** After deleting messages, also delete session_embeddings for the affected message indices. The `index` parameter is the 0-based position in the conversation. Delete all embeddings with `message_index >= index`:

```python
conn.execute(f"DELETE FROM messages WHERE id IN ({placeholders})", ids_to_delete)
conn.execute(
    "DELETE FROM session_embeddings WHERE conversation_id = ? AND message_index >= ?",
    (conv_id, index),
)
conn.commit()
```

## Part 2: Fix — Cold Start Pre-Warm

### Problem

The fastembed embedding model loads lazily on the first `embed_text()` call after a container restart. This adds ~2-3 seconds to the first user's request. Every subsequent request is fast.

### Fix

Pre-warm the embedding model during application startup. In `main.py`, modify the `lifespan()` function (line 494-501) to trigger model loading in a background thread so it doesn't block startup:

```python
@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    init_tracker_db()
    prune_conversations()
    reload_patterns()
    LOGS_DIR.mkdir(parents=True, exist_ok=True)
    # Pre-warm embedding model in background (avoids cold-start delay on first request)
    asyncio.create_task(asyncio.to_thread(_prewarm_embeddings))
    yield
```

Add the pre-warm function near the other helper functions (near `prepare_query_embedding` around line 323):

```python
def _prewarm_embeddings():
    """Load the fastembed model at startup so the first request isn't slow."""
    try:
        from session_memory import get_query_embedding
        get_query_embedding("warmup")
        conv_log.info("Embedding model pre-warmed")
    except Exception as e:
        error_log.debug("Embedding pre-warm failed (non-critical): %s", e)
```

## Part 3: Fix — Duplicate Content Between Session Recall and Smart Windowing

### Problem

When smart windowing keeps an older message in the conversation history AND session recall also injects that same message's content into the `<session-recall>` block, the model sees it twice. This wastes tokens.

### Fix

After smart windowing runs, determine which message indices were kept in the window. Then filter the session recall to exclude turns that are already in the window.

The cleanest approach: modify `window_messages()` to also return the set of kept message indices, then filter the session recall afterward.

**Option A (simpler):** Change the call order — run window_messages BEFORE build_system_prompt, then filter the session recall, then build the prompt. This is a small reordering.

**Option B (simplest, recommended):** In `format_recalled()` in session_memory.py, accept an optional `exclude_indices: set[int]` parameter. The caller passes the set of message indices that are in the windowed history.

Implementation:

**Modify `session_memory.py` — `format_recalled()`** (line 146):

```python
def format_recalled(turns: list[dict], exclude_indices: set[int] | None = None) -> str:
    """Format retrieved turns as an XML block for system prompt injection.
    
    Optionally excludes turns whose message_index is in exclude_indices
    (because they're already in the conversation history window).
    """
    if exclude_indices:
        turns = [t for t in turns if t["message_index"] not in exclude_indices]
    if not turns:
        return ""
    # ... rest unchanged ...
```

**Modify `main.py` — ws_chat() and rest_chat():** The challenge is that session recall is computed BEFORE window_messages runs, so we don't yet know which indices will be kept. The fix requires reordering the calls:

1. Compute query_embedding (already done)
2. Run `retrieve_relevant()` to get the recalled turns (raw list, not yet formatted)
3. Run `window_messages()` with the query_embedding
4. Determine which older message indices are in the windowed result
5. Format the recalled turns, excluding those already in the window
6. Build the system prompt with the filtered session recall

In practice, this means splitting `prepare_session_recall()` into two steps: retrieval and formatting. Or more simply:

**In ws_chat (~line 696-716), replace the current flow:**

```python
# Compute query embedding once — shared by session recall and smart windowing
query_embedding = await prepare_query_embedding(user_text, len(history_msgs))

# Retrieve relevant earlier turns (raw, unformatted)
recalled_turns = []
if len(history_msgs) > 15 and query_embedding:
    try:
        recalled_turns = await asyncio.to_thread(
            retrieve_relevant, conversation_id, user_text, query_embedding=query_embedding)
        if recalled_turns:
            conv_log.info("[%s] Session recall: %d turns retrieved", trace_id, len(recalled_turns))
    except Exception as e:
        error_log.debug("Session recall retrieval failed: %s", e)

# Build a preliminary system prompt (without session recall) for windowing budget
has_vision = bool(image_data or video_frames)
preliminary_prompt = build_system_prompt(
    clean_text,
    has_vision=has_vision,
    pattern=route_result.pattern,
    recitation_context=recitation_context,
    charmap_content=route_result.charmap_content,
)

# Smart windowing — determines which messages stay in the conversation history
history_msgs = window_messages(history_msgs, preliminary_prompt, context_length,
                               query_embedding=query_embedding,
                               conversation_id=conversation_id)

# Determine which message indices are in the windowed history
# (indices 0..len(all_history)-recent_count-1 for older messages)
windowed_count = len(history_msgs)

# Format session recall, excluding turns already in the window
# The window keeps the last 6 + semantically selected older messages.
# Build the set of older message indices that are in the window.
window_older_indices = set()
if query_embedding and conversation_id:
    # The last min(6, total) are "recent" — everything before that is "older" and was scored
    total_history_len = len(get_conversation_messages(conversation_id)) + 1  # +1 for current user msg
    recent_count = min(6, total_history_len)
    older_count = total_history_len - recent_count
    # Messages in window that aren't in the last `recent_count` are the semantically-kept older ones
    older_in_window = max(0, windowed_count - recent_count)
    # We can't easily recover exact indices from window_messages return value...
```

Actually, this is getting too complex for the call-site. Let me simplify.

**Simpler approach:** Modify `window_messages()` to return a tuple `(messages, kept_indices)` where `kept_indices` is the set of original position indices of all messages that were kept. But changing the return type is a breaking change for all callers.

**Simplest approach (recommended):** Don't change the call order or return types. Instead, after windowing, scan the windowed messages against the recalled turns by content matching:

```python
# Filter session recall: exclude turns whose content is already in the windowed history
session_recall = ""
if recalled_turns:
    windowed_contents = {m.get("content", "")[:200] for m in history_msgs}
    filtered_turns = [t for t in recalled_turns if t["content"][:200] not in windowed_contents]
    if filtered_turns:
        session_recall = format_recalled(filtered_turns)
```

This is simple, O(n) with small n, and handles the deduplication without any architectural changes. The 200-char prefix match is sufficient since we're comparing the same messages.

**Apply the same pattern in rest_chat.**

**Here's the complete rewrite for the session recall + windowing section in ws_chat (~line 696-716):**

```python
# Compute query embedding once — shared by session recall and smart windowing
query_embedding = await prepare_query_embedding(user_text, len(history_msgs))

# Retrieve relevant earlier turns (raw list, not yet formatted)
recalled_turns = []
if len(history_msgs) > 15 and query_embedding:
    try:
        recalled_turns = await asyncio.to_thread(
            retrieve_relevant, conversation_id, user_text, query_embedding=query_embedding)
        if recalled_turns:
            conv_log.info("[%s] Session recall: %d turns retrieved", trace_id, len(recalled_turns))
    except Exception as e:
        error_log.debug("Session recall retrieval failed: %s", e)

# Build prompt with pattern (use cleaned text for memory retrieval)
has_vision = bool(image_data or video_frames)

# Window first (needs to run before session recall formatting to deduplicate)
temp_prompt = build_system_prompt(
    clean_text, has_vision=has_vision, pattern=route_result.pattern,
    recitation_context=recitation_context, charmap_content=route_result.charmap_content)
history_msgs = window_messages(history_msgs, temp_prompt, context_length,
                               query_embedding=query_embedding,
                               conversation_id=conversation_id)

# Format session recall — exclude turns whose content is already in the window
session_recall = ""
if recalled_turns:
    windowed_contents = {str(m.get("content", ""))[:200] for m in history_msgs}
    deduped = [t for t in recalled_turns if t["content"][:200] not in windowed_contents]
    if deduped:
        session_recall = format_recalled(deduped)

# Final system prompt with session recall included
system_prompt = build_system_prompt(
    clean_text, has_vision=has_vision, pattern=route_result.pattern,
    recitation_context=recitation_context, session_recall=session_recall,
    charmap_content=route_result.charmap_content)
messages = build_messages(history_msgs, system_prompt)
```

Note: This calls `build_system_prompt` twice — once without session_recall for windowing budget, once with it for the final prompt. The cost is negligible (string concatenation). The benefit is correct token budgeting: the window budget is calculated without the session recall tokens, then the final prompt includes them. This is actually more correct than the previous approach where session recall tokens were counted in the window budget, potentially pushing more messages out of the window.

**Apply the same pattern in rest_chat (~line 901-915):**

```python
query_embedding = await prepare_query_embedding(clean_text, len(history_msgs))

recalled_turns = []
if len(history_msgs) > 15 and query_embedding:
    try:
        recalled_turns = await asyncio.to_thread(
            retrieve_relevant, conversation_id, clean_text, query_embedding=query_embedding)
        if recalled_turns:
            conv_log.info("[REST] Session recall: %d turns retrieved", len(recalled_turns))
    except Exception as e:
        error_log.debug("Session recall retrieval failed: %s", e)

temp_prompt = build_system_prompt(clean_text, pattern=route_result.pattern,
                                  recitation_context=recitation_context,
                                  charmap_content=route_result.charmap_content)
context_length = max(2048, min(context_length, 131072))
history_msgs = window_messages(history_msgs, temp_prompt, context_length,
                               query_embedding=query_embedding,
                               conversation_id=conversation_id)

session_recall = ""
if recalled_turns:
    windowed_contents = {str(m.get("content", ""))[:200] for m in history_msgs}
    deduped = [t for t in recalled_turns if t["content"][:200] not in windowed_contents]
    if deduped:
        session_recall = format_recalled(deduped)

system_prompt = build_system_prompt(clean_text, pattern=route_result.pattern,
                                    recitation_context=recitation_context,
                                    session_recall=session_recall,
                                    charmap_content=route_result.charmap_content)
messages = build_messages(history_msgs, system_prompt)
```

After this change, the `prepare_session_recall()` helper function (line 333-347) is no longer called from either handler. You can remove it, or leave it for potential future use.

## Part 4: End-to-End Integration Test

**You are responsible for running this.** This test exercises all features in a single conversation flow. Run it after all fixes are implemented and the container is rebuilt.

### Step 1: Rebuild and deploy

```bash
podman compose build gizmo-orchestrator && podman compose up -d gizmo-orchestrator
sleep 15 && curl -s http://localhost:9100/health
```

Check that the embedding model pre-warmed:
```bash
podman logs gizmo-orchestrator --tail 20 | grep -i "pre-warm\|embed"
```

### Step 2: Integration test — single conversation, all features

```bash
CONV_ID="integration-$(date +%s)"

# --- Character analysis ---
echo "=== Test 1: Character analysis ==="
curl -s -X POST http://localhost:9100/api/chat \
  -F "message=How many r's are in strawberry?" \
  -F "conversation_id=$CONV_ID" | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['response'][:300])"
# EXPECTED: Answer is 3

echo ""
echo "=== Test 2: Recitation ==="
# --- Recitation pipeline ---
curl -s -X POST http://localhost:9100/api/chat \
  -F "message=Recite the Jabberwocky by Lewis Carroll" \
  -F "conversation_id=$CONV_ID" | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['response'][:500])"
# EXPECTED: Full poem, retrieved from web, not hallucinated

echo ""
echo "=== Test 3: Follow-up on recitation ==="
# --- Follow-up recall within conversation history ---
curl -s -X POST http://localhost:9100/api/chat \
  -F "message=What is the very last line of that poem?" \
  -F "conversation_id=$CONV_ID" | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['response'][:300])"
# EXPECTED: Correct last line from the poem

echo ""
echo "=== Test 4: Distinctive early topic ==="
# --- Plant a distinctive topic for later recall ---
curl -s -X POST http://localhost:9100/api/chat \
  -F "message=I'm building a Rust compiler plugin that models borrow regions as Petri net token flows. The supervisor module is called 'ferrocene-lifetimes'." \
  -F "conversation_id=$CONV_ID" | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['response'][:300])"

echo ""
echo "=== Padding with 8 unrelated messages ==="
# --- Pad with unrelated messages to push early content out of window ---
for i in $(seq 1 8); do
  curl -s -X POST http://localhost:9100/api/chat \
    -F "message=Tell me a brief interesting fact about the number $i in mathematics." \
    -F "conversation_id=$CONV_ID" > /dev/null
  echo "  Sent padding message $i"
  sleep 2
done

echo ""
echo "=== Test 5: Session recall of early topic ==="
# --- Session recall — ask about the early distinctive topic ---
curl -s -X POST http://localhost:9100/api/chat \
  -F "message=What was the name of the Rust supervisor module I mentioned earlier?" \
  -F "conversation_id=$CONV_ID" | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['response'][:300])"
# EXPECTED: Should mention "ferrocene-lifetimes" — this was 10+ messages ago

echo ""
echo "=== Test 6: Non-existent stanza (epistemic honesty) ==="
# --- Anti-confabulation ---
curl -s -X POST http://localhost:9100/api/chat \
  -F "message=Can you recite the 8th stanza of the Jabberwocky?" \
  -F "conversation_id=$CONV_ID" | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['response'][:300])"
# EXPECTED: Should say there is no 8th stanza, not invent one

echo ""
echo "=== Test 7: Conversation isolation ==="
# --- Different conversation should have no recall ---
curl -s -X POST http://localhost:9100/api/chat \
  -F "message=What Rust module did I mention?" \
  -F "conversation_id=isolation-check-$(date +%s)" | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['response'][:300])"
# EXPECTED: Should say nothing was discussed about Rust
```

### Step 3: Verify embedding cleanup

```bash
# Create a test conversation
curl -s -X POST http://localhost:9100/api/chat \
  -F "message=This is a test message for cleanup verification" \
  -F "conversation_id=cleanup-test" > /dev/null
sleep 3

# Verify embeddings exist
podman exec gizmo-orchestrator python3 -c "
import sqlite3
conn = sqlite3.connect('/app/memory/conversations.db')
count = conn.execute(\"SELECT COUNT(*) FROM session_embeddings WHERE conversation_id = 'cleanup-test'\").fetchone()[0]
print(f'Embeddings before delete: {count}')
"

# Delete the conversation
curl -s -X DELETE http://localhost:9100/api/conversations/cleanup-test | python3 -m json.tool

# Verify embeddings were also deleted
podman exec gizmo-orchestrator python3 -c "
import sqlite3
conn = sqlite3.connect('/app/memory/conversations.db')
count = conn.execute(\"SELECT COUNT(*) FROM session_embeddings WHERE conversation_id = 'cleanup-test'\").fetchone()[0]
print(f'Embeddings after delete: {count}')
"
# EXPECTED: 0
```

### Step 4: If any test fails

1. Check logs: `podman logs gizmo-orchestrator --tail 50`
2. Fix the issue, rebuild: `podman compose build gizmo-orchestrator && podman compose up -d gizmo-orchestrator`
3. Rerun the failing test
4. Repeat until all tests pass

## Part 5: Full Audit

After all fixes are applied and all tests pass, perform a full audit of the new code. Check these files for edge cases, error handling, and correctness. Write findings into AUDIT.md following the existing format (severity levels: Critical, Warning, Suggestion).

### Files to audit

1. **`services/orchestrator/recite.py`** (107 lines)
   - Check: Can any regex pattern cause catastrophic backtracking (ReDoS)?
   - Check: Does `fetch_recitation_content()` handle all failure modes gracefully?
   - Check: Is the subject extraction safe (no injection into search queries)?

2. **`services/orchestrator/web_fetch.py`** (37 lines)
   - Check: Can `fetch_page()` follow redirect loops? (httpx has a default redirect limit)
   - Check: Is the 12K char limit applied correctly?
   - Check: Does it handle non-UTF-8 pages?

3. **`services/orchestrator/session_memory.py`** (168 lines)
   - Check: Thread safety of `_get_embedder()` with concurrent `asyncio.to_thread` calls
   - Check: Can `store_turn()` fail silently in a way that corrupts data?
   - Check: Does `retrieve_relevant()` handle empty or corrupted embeddings?
   - Check: Is `get_stored_embeddings()` efficient enough for conversations with hundreds of turns?

4. **`services/orchestrator/charmap.py`** (44 lines)
   - Check: Can `is_charmap_request()` false-positive on common phrases?
   - Check: Does `build_charmap()` handle non-ASCII characters?
   - Check: Does the "how many" pattern work with phrases like "how many e's are in the word 'coffee'"?

5. **`services/orchestrator/router.py`** (115 lines)
   - Check: Can recitation and charmap detection conflict? (e.g., "recite how many r's in strawberry")
   - Check: Is the routing priority correct? (recitation → charmap → keyword → pattern → default)

6. **`services/orchestrator/main.py`** — integration points only
   - Check: `prune_conversations()` — does the session_embeddings DELETE use the same transaction?
   - Check: `delete_conversation()` — same
   - Check: `delete_messages_from()` — same, and does `message_index >= index` correctly match?
   - Check: `window_messages()` — can the smart windowing branch return an empty list when budget is tight?
   - Check: `index_conversation_turns()` — can `_count_messages()` return an incorrect count if called while a background `store_turn` is still running?
   - Check: The deduplication logic — does the 200-char prefix match handle multimodal content arrays (which aren't strings)?
   - Check: `build_system_prompt()` — is the layer order documented and correct?

7. **`config/constitution.txt`** — the `<epistemic-honesty>` section
   - Check: Are the rules clear and non-contradictory with existing sections?
   - Check: Does the session-recall guidance work when no session recall is actually injected?

### Audit output format

Add a new version section to AUDIT.md with findings in the existing table format. Include both issues found AND positive observations (things that were done well). Fix any Critical or Warning issues before committing.

## What NOT to Do

- Do NOT modify the UI. This is backend-only.
- Do NOT add new features. This is cleanup and testing only.
- Do NOT change the embedding model or similarity thresholds. Those can be tuned later.
- Do NOT refactor working code for style. Only fix actual bugs or issues found in the audit.

## Documentation Updates (MANDATORY per CLAUDE.md)

After all fixes, tests, and audit pass, update these files:

1. **CLAUDE.md** — Update Session Log with version entry for this final phase. Update any sections that are now inaccurate after the fixes (e.g., note that session_embeddings are cleaned up on conversation deletion). If the prompt assembly order changed due to the deduplication reordering, update the documented layer order.
2. **README.md** — No changes expected unless the audit found user-facing issues.
3. **AUDIT.md** — Add the full audit report as a new version section.
4. **wiki/architecture.md** — Update if any architectural details changed (e.g., prompt assembly order, deletion cascade).
