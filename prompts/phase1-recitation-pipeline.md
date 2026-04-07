# Phase 1: Recitation Pipeline — Coder Prompt

You are implementing a recitation pipeline for Gizmo-AI. Read CLAUDE.md first for full project context.

## Problem

When users ask Gizmo to recite known texts (poems, speeches, lyrics), the 9B model guesses from training memory and gets it wrong. Follow-up questions like "recite the second to last line from the 4th stanza" fail because the original recitation was inaccurate.

## Solution

Intercept recitation requests **before** the LLM sees the message. Search the web for the actual text, fetch the full page, extract clean content, and inject it into the system prompt. The LLM then presents retrieved text faithfully at low temperature (0.2) instead of hallucinating from parametric memory.

Follow-up questions work automatically because the full text is in the assistant's previous response, which stays in conversation history.

## What to Build

### 1. Create `services/orchestrator/web_fetch.py` (~60 lines)

A page fetcher that retrieves URLs and extracts clean text.

- `async def fetch_page(url: str, timeout: float = 15.0) -> str`
  - Uses `httpx` (already a dependency) with `follow_redirects=True`
  - Set a reasonable User-Agent header (e.g., `"Mozilla/5.0 (compatible; Gizmo/1.0)"`)
  - Parse response HTML with `BeautifulSoup(html, "html.parser")`
  - Remove `<script>`, `<style>`, `<nav>`, `<footer>`, `<header>` tags via `.decompose()`
  - Extract text with `soup.get_text(separator="\n", strip=True)`
  - Return up to 12,000 characters
  - Return `""` on any exception (network error, timeout, parse failure)

### 2. Create `services/orchestrator/recite.py` (~100 lines)

The recitation detection and retrieval pipeline.

**`def is_recitation_request(message: str) -> tuple[bool, str]`**

Regex-based detection. Returns `(True, extracted_subject)` or `(False, "")`.

Trigger patterns (case-insensitive, word boundaries):
- `recite {subject}`
- `quote {subject}` / `"quote from {subject}"`
- `full text of {subject}`
- `lyrics to {subject}` / `lyrics of {subject}`
- `how does {subject} go`
- `words to {subject}`
- `the {ordinal} amendment` (constitutional amendments)
- `word for word`
- `verbatim`

The subject is everything after the trigger phrase to the end of the message (stripped). Be careful not to match overly broad phrases — "quote" alone in casual usage ("I got a quote for the repair") should NOT trigger. Use word boundaries and require the trigger to be near the start or be part of a clear request pattern.

**`async def fetch_recitation_content(subject: str) -> tuple[str, str]`**

1. Import and call `web_search` from `search.py`: `await web_search(f'"{subject}" full text', num_results=5)`
2. Filter out results with errors
3. For the top 3 results, call `fetch_page(result["url"])` from `web_fetch.py`
4. Pick the result with the most content (longest text that's > 200 chars)
5. Return `(content, source_url)` or `("", "")` if all fetches fail

**`def build_recitation_context(content: str, source_url: str, subject: str) -> str`**

Return an XML-tagged block:
```
<recitation-content>
The user has asked you to recite or present the following known text.
The complete, authoritative text is provided below. Present it EXACTLY as shown.
Do not paraphrase, abbreviate, rewrite, or fill in gaps from memory.
Preserve line breaks for poems and paragraph structure for prose.
You may add a brief one-sentence introduction acknowledging their request.
If the text appears incomplete, say so rather than completing it from memory.

Source: {source_url}
Subject: {subject}

--- BEGIN AUTHORITATIVE TEXT ---
{content}
--- END AUTHORITATIVE TEXT ---
</recitation-content>
```

### 3. Modify `services/orchestrator/router.py`

**RouteResult class (line 37-42):** Add a new field:
```python
self.recitation_subject: str = ""
```

**route() function (line 50-97):** Add Step 0 before the keyword pre-routing block (before line 62). Import `is_recitation_request` from `recite` at the top of the file.

```python
# ── Step 0: Recitation detection (pre-LLM content injection) ──
is_recite, subject = is_recitation_request(user_message)
if is_recite:
    result.recitation_subject = subject
    result.source = "recitation"
    logger.info("Recitation detected: '%s' → subject '%s'", user_message[:60], subject)
```

The rest of routing still runs normally after this (tools and patterns still get selected).

### 4. Modify `services/orchestrator/llm.py`

**stream_chat() (line 19-24):** Add `temperature` and `top_p` parameters with current values as defaults:
```python
async def stream_chat(
    messages: list[dict],
    tools: list | None = None,
    thinking_enabled: bool = False,
    max_tokens: int = 8192,
    temperature: float = 0.7,
    top_p: float = 0.9,
):
```

**Payload construction (line 37-44):** Replace the hardcoded values:
```python
"temperature": temperature,
"top_p": top_p,
```

### 5. Modify `services/orchestrator/main.py`

**stream_chat wrapper (line 342-351):** Add `temperature` passthrough:
```python
async def stream_chat(messages, thinking_enabled=False, tools=True, tool_schemas=None, temperature=0.7):
    """Stream chat from llama.cpp. Optionally accepts pre-selected tool schemas."""
    if tool_schemas is not None:
        tool_defs = tool_schemas
    elif tools:
        tool_defs = TOOL_DEFINITIONS
    else:
        tool_defs = None
    async for event in _llm_stream_chat(messages, tools=tool_defs, thinking_enabled=thinking_enabled, temperature=temperature):
        yield event
```

**build_system_prompt() (line 254-274):** Add `recitation_context` parameter. Inject it between pattern and vision layers:
```python
def build_system_prompt(user_message: str = "", has_vision: bool = False,
                        pattern: dict | None = None,
                        recitation_context: str = "") -> str:
    """Build the full system prompt with constitution, pattern, and relevant memories."""
    constitution = load_constitution()
    parts = [constitution]

    if pattern:
        parts.append(f"\n\n--- Active Analysis Pattern: {pattern['name']} ---")
        parts.append(pattern["system_prompt"])

    if recitation_context:
        parts.append(f"\n\n{recitation_context}")

    if has_vision:
        parts.append(f"\n\n{VISION_PROMPT}")

    memories = get_relevant_memories(user_message)
    if memories:
        parts.append("\n\n--- Relevant memories ---")
        for mem in memories:
            parts.append(f"- {mem}")

    return "\n".join(parts)
```

**ws_chat() (~line 552-573):** After routing and before building the system prompt, add the recitation fetch. Also pass temperature to `stream_chat()`:

After `route_result = route(user_text)` and before `system_prompt = build_system_prompt(...)`:
```python
# Recitation pipeline — fetch content before LLM sees the message
recitation_context = ""
llm_temperature = 0.7
if route_result.recitation_subject:
    from recite import fetch_recitation_content, build_recitation_context
    content, source_url = await fetch_recitation_content(route_result.recitation_subject)
    if content:
        recitation_context = build_recitation_context(content, source_url, route_result.recitation_subject)
        llm_temperature = 0.2
        conv_log.info("[%s] Recitation content fetched: %d chars from %s", trace_id, len(content), source_url)
```

Pass to build_system_prompt:
```python
system_prompt = build_system_prompt(
    clean_text,
    has_vision=has_vision,
    pattern=route_result.pattern,
    recitation_context=recitation_context,
)
```

Pass temperature to stream_chat (both initial call and tool-loop calls):
```python
async for event in stream_chat(messages, thinking_enabled=thinking,
                               tool_schemas=route_result.tool_schemas,
                               temperature=llm_temperature):
```

**rest_chat() (~line 732-754):** Same pattern — add recitation fetch after `route_result = route(message)`, pass `recitation_context` to `build_system_prompt()`, pass `temperature` to `stream_chat()`.

### 6. Modify `services/orchestrator/requirements.txt`

Add one line:
```
beautifulsoup4>=4.12.0
```

## What NOT to Do

- Do NOT add a new LLM tool. The recitation pipeline is pre-LLM (intercepted before the model sees the message).
- Do NOT modify constitution.txt (that comes in Phase 3).
- Do NOT modify the UI. This is backend-only.
- Do NOT add any GPU/VRAM-consuming dependencies.
- Do NOT change the existing web_search tool behavior.
- Do NOT over-engineer the regex detection. False negatives (missing a recitation request) are acceptable and can be refined later. False positives (triggering recitation on a normal question) are bad — be conservative.

## Build, Test, and Iterate

**You are responsible for testing.** After writing the code, you MUST rebuild, deploy, and run every test below yourself. Do not just list the commands — execute them. Check each response for correctness. If a test fails, debug using `podman logs gizmo-orchestrator --tail 50`, fix the code, rebuild, and retest. Do not move on to documentation until all tests pass.

### Step 1: Rebuild and deploy

```bash
podman compose build gizmo-orchestrator && podman compose up -d gizmo-orchestrator
```

Wait for the health check to pass:
```bash
sleep 10 && curl -s http://localhost:9100/health
```

### Step 2: Run all tests (execute these yourself, check each response)

**Test 1 — Poem recitation:** Response MUST contain the actual Jabberwocky text with correct lines, not a hallucinated approximation.
```bash
curl -s -X POST http://localhost:9100/api/chat \
  -F "message=Recite the Jabberwocky by Lewis Carroll" | python3 -m json.tool
```
Save the `conversation_id` from this response — you need it for Tests 2 and 3.

**Test 2 — Follow-up recall:** Use the conversation_id from Test 1. Response should correctly identify the last line.
```bash
curl -s -X POST http://localhost:9100/api/chat \
  -F "message=What is the last line of the poem?" \
  -F "conversation_id=<ID_FROM_TEST_1>" | python3 -m json.tool
```

**Test 3 — Specific stanza navigation:** Use same conversation_id. Response should correctly extract the requested line.
```bash
curl -s -X POST http://localhost:9100/api/chat \
  -F "message=Recite only the second to last line from the 4th stanza" \
  -F "conversation_id=<ID_FROM_TEST_1>" | python3 -m json.tool
```

**Test 4 — Non-recitation pass-through:** Should NOT trigger the recitation pipeline. Check orchestrator logs to confirm no "Recitation detected" log line appears.
```bash
curl -s -X POST http://localhost:9100/api/chat \
  -F "message=Tell me about the history of the Jabberwocky poem" | python3 -m json.tool
```

**Test 5 — Different content:** Verify it works for prose, not just poetry.
```bash
curl -s -X POST http://localhost:9100/api/chat \
  -F "message=Recite the Gettysburg Address" | python3 -m json.tool
```

**Test 6 — False positive check:** The word "quote" in a non-recitation context must NOT trigger the pipeline.
```bash
curl -s -X POST http://localhost:9100/api/chat \
  -F "message=Can you give me a quote for repairing a laptop screen?" | python3 -m json.tool
```

**Test 7 — Graceful fallback:** Stop SearXNG, verify no crash, model responds normally from training memory.
```bash
podman stop gizmo-searxng
curl -s -X POST http://localhost:9100/api/chat \
  -F "message=Recite the Star Spangled Banner" | python3 -m json.tool
podman start gizmo-searxng
```

### Step 3: If any test fails

1. Check logs: `podman logs gizmo-orchestrator --tail 50`
2. Fix the issue in the code
3. Rebuild: `podman compose build gizmo-orchestrator && podman compose up -d gizmo-orchestrator`
4. Rerun the failing test
5. Repeat until all 7 tests pass

**Do not proceed to documentation until all tests pass.**

## Documentation Updates (MANDATORY per CLAUDE.md)

After all tests pass, update these files:

1. **CLAUDE.md** — Add "Recitation Pipeline" subsection describing the feature. Update Session Log with version entry.
2. **README.md** — Add "Recitation & accurate recall" to the AI Capabilities feature list.
3. **wiki/architecture.md** — Add `recite.py` and `web_fetch.py` to the file tree. Update the request lifecycle to show the recitation interception step.
4. **wiki/usage.md** — Add a section explaining recitation (trigger words, how it works, limitations).
5. **AUDIT.md** — Add version entry header for this change.
