# Usage Guide

> **Audience:** Daily users of Gizmo-AI. Assumes [[setup|Setup]] is complete and services are running.

---

### Contents

| Section | What's covered |
|---------|---------------|
| [Chat Basics](#chat-basics) | Sending messages, thinking mode, vision, video, audio |
| [Voice & TTS](#voice-studio) | Voice Studio, cloning, speech-to-text, speed/language |
| [Web Search & Memory](#web-search) | Search, recitation, memory, session recall |
| [Code Playground](#code-playground) | Editor, syntax highlighting, AI assistant, sandbox |
| [Task Tracker](#task-tracker) | Tasks, notes, keyboard navigation, LLM chat |
| [Themes](#themes) | 9 Nintendo console themes with sound and boot animations |
| [Settings & Shortcuts](#settings) | All settings and keyboard shortcuts |

---

## Chat Basics

- **Send a message:** Type in the input box and press **Enter**
- **New line:** Press **Shift+Enter**
- **Stop generation:** Click the stop button that appears during generation
- **New conversation:** Click "+ New Chat" in the sidebar

## Behavioral Modes

Click the **mode pill button** in the toolbar (next to Think) to switch between behavioral modes. Modes change _how_ Gizmo responds without affecting its core identity, memory, or tool access.

### Built-in Modes

| Mode | Behavior |
|------|----------|
| **Chat** (default) | General conversation — no additional behavioral prompt |
| **Brainstorm** | Creative ideation, "yes and" thinking, quantity over judgment |
| **Coder** | Code-first responses, working implementations, use run_code tool |
| **Research** | Proactive web search, source citations, explain from fundamentals |
| **Planner** | Goal decomposition, actionable steps, risk identification |
| **Roleplay** | Stay in character, match vocabulary/mannerisms, no meta-commentary |

### Mode Editor

Open **Settings > Modes > Open** to customize mode prompts or create entirely new modes.

- Edit any mode's system prompt (the behavioral instructions injected into the system prompt)
- Adjust the label and description shown in the selector
- Create new custom modes with your own behavioral templates
- Delete custom modes (built-in modes are protected)

Modes coexist with analysis patterns — a mode sets the behavioral posture ("think like a brainstormer") while a pattern adds structured output formatting.

## Thinking Mode

Toggle thinking mode with the **Think** pill button below the input box (similar to Claude and ChatGPT).

**When to use it:**
- Complex reasoning (math, logic, strategy)
- Debugging code
- Multi-step analysis
- When accuracy matters more than speed

**When NOT to use it:**
- Simple factual questions
- Casual conversation
- Quick code generation

When thinking is ON, the model reasons internally in a collapsible block before responding. The thinking block appears above the response and can be expanded to see the model's chain of thought. Thinking mode adds latency — the model generates the thinking tokens in addition to the response.

## Vision / Image Analysis

Gizmo can see and analyze images. Click the **paperclip button** to upload an image, then ask about it:

- "What's in this image?"
- "Describe the architecture in this diagram"
- "What code errors do you see in this screenshot?"

Supported formats: PNG, JPG, GIF, WebP (up to 50MB). The image is encoded and sent to the vision-capable model via the multimodal projector (mmproj).

## Video Analysis

Upload a video file and Gizmo will extract frames and analyze the visual content.

- Click the **paperclip button** and select a video file (up to 500MB)
- Frames are extracted automatically via ffmpeg
- The video appears as a playable player in your chat message
- Ask questions about what's happening in the video

Supported formats: MP4, MOV, AVI, WebM, MKV.

## Audio Transcription

Upload an audio file for automatic transcription and analysis.

- Click the **paperclip button** and select an audio file
- Gizmo transcribes it via Whisper, then analyzes the transcript with the LLM
- Great for analyzing podcasts, meetings, voice memos, lectures

Supported formats: M4A, MP3, WAV, OGG, FLAC.

## Speech-to-Text (Microphone)

Click the **microphone button** in the input area to dictate your message.

- Works with the built-in mic or any connected audio device
- Audio is transcribed via Whisper and inserted as your message text
- **Requires HTTPS** for browser mic access — use `https://<your-tailscale-hostname>/` from other devices on your tailnet, or `localhost` which is always considered secure

## Voice Studio

Open the **Voice Studio** via the pill button below the input box or from Settings.

Voice Studio is a dedicated TTS playground where you can:

1. **Upload voice references** — provide a sample of any voice. The audio is automatically transcribed via Whisper on upload (transcript stored for future use).
2. **Name and save voices** — build a library of cloned voices
3. **Migrate existing voices** — click the migrate button to backfill Whisper transcripts for voices uploaded before auto-transcription was added
5. **Select a voice** — choose which saved voice to use for synthesis
6. **Set clip duration** — choose how much of the reference audio to use (30s, 60s, 90s, or 120s)
7. **Type and speak** — enter text and hear it spoken in the selected voice

Voice cloning uses x-vector-only mode for clean synthesis without warmup artifacts. Voice references are processed server-side: truncated to the selected duration, downsampled to 24kHz mono WAV (matches the model's speaker encoder). Saved voices persist across sessions.

## Text-to-Speech

Toggle TTS in Settings under "Read Responses Aloud."

When enabled, Gizmo speaks responses aloud. An audio player appears below each assistant message. The TTS engine is Qwen3-TTS — a GPU-accelerated neural voice cloning model. It loads into VRAM on demand and auto-unloads after 60 seconds of idle time to free memory for the LLM.

Full responses are now synthesized without truncation — text is split at ~200 character sentence boundaries and chunked, so even long responses get complete TTS audio (no more silent 4,000 character cutoff).

By default, the bundled voice is used. To use a cloned voice for chat TTS, go to **Settings** and select a voice from the **TTS Voice** dropdown. Any voices you've created in Voice Studio will appear here.

**Speed control:** Adjust speech speed from 0.5x (half speed) to 2.0x (double speed) using the **TTS Speed** slider in Settings. Speed is applied via post-synthesis resampling.

**Language selection:** Choose a TTS language from the **TTS Language** dropdown in Settings. Options: Auto (default), English, Chinese, Japanese, Korean, German, French, Russian, Portuguese, Spanish, Italian. "Auto" lets the model detect the language from the text.

## Web Search

Gizmo searches the web via a self-hosted SearXNG instance. Trigger it naturally:

- "Search for the latest news about AI"
- "What's the current weather in Seattle?"
- "Find information about Rust async runtimes"

The model decides when to search based on context. When it does:
1. You'll see a "web_search: running" status
2. SearXNG returns the top 5 results
3. Results are injected into the conversation context
4. The model responds with knowledge of the search results

No API keys or accounts needed — SearXNG aggregates results from multiple search engines.

## Recitation & Accurate Recall

When you ask Gizmo to recite a known text — a poem, speech, song lyrics, or constitutional amendment — it automatically fetches the authoritative source from the web instead of guessing from training memory. This means you get the actual text, not a hallucinated approximation.

**Trigger phrases:**
- "Recite the Jabberwocky by Lewis Carroll"
- "Full text of the Gettysburg Address"
- "Lyrics to Bohemian Rhapsody"
- "Words to the Star Spangled Banner"
- "How does Stopping by Woods on a Snowy Evening go"
- "Quote from Martin Luther King I Have a Dream"
- "The 14th amendment"
- "Give me the text word for word"

**How it works:**
1. The router detects recitation intent via regex pattern matching (conservative — prefers missing a request over false positives)
2. SearXNG searches for the full text, the orchestrator fetches and cleans the top results
3. The authoritative text is injected into the system prompt with strict instructions to present it exactly
4. LLM temperature is lowered to 0.2 for faithful reproduction
5. Follow-up questions work naturally because the full text is in conversation history

**Limitations:**
- Detection is regex-based; some phrasing may not trigger the pipeline. If it doesn't activate, try a more explicit phrase like "Recite..." or "Full text of..."
- Web scraping quality varies by source — some pages include navigation or metadata
- If SearXNG is unavailable, the model falls back to its training memory (less accurate)

## File Uploads

Click the **paperclip button** to upload files.

**Supported types and limits:**

| Type | Formats | Max Size | Processing |
|------|---------|----------|------------|
| **Images** | PNG, JPG, GIF, WebP | 50MB | Base64-encoded, analyzed by vision model |
| **Video** | MP4, MOV, AVI, WebM, MKV | 500MB | Frame extraction, server storage, video player in chat |
| **Audio** | M4A, MP3, WAV, OGG, FLAC | 50MB | Whisper transcription → LLM analysis |
| **Documents** | PDF, TXT, MD, code files | 50MB | Text extracted and sent to model |

## Memory

Gizmo can remember things across conversations.

**To save a memory:**
- "Remember that my name is Nick"
- "Remember that I prefer Python over JavaScript"
- "Save a note: project deadline is March 20"

**To recall:**
- "What's my name?"
- "What do you remember about me?"
- "List what you remember"

Memories persist across conversations and browser sessions. They are text files injected into the system prompt using BM25 relevance ranking with recency weighting.

## Session Recall

In long conversations (15+ messages), Gizmo automatically retrieves relevant earlier messages that have scrolled out of the context window. This happens transparently — no user action required.

**How it works:** Every message is embedded using a lightweight CPU-based model (BAAI/bge-small-en-v1.5, ~33MB) and stored in the conversation database. When you ask about something discussed earlier, the most semantically relevant earlier turns are retrieved by cosine similarity and injected into the prompt.

**When it activates:** Only in conversations with 15+ messages. Shorter conversations fit entirely in the context window, so recall isn't needed.

**Limitations:** Session recall is per-conversation. For cross-conversation context, see below.

## Cross-Conversation Recall

When you reference something from a previous conversation — "we discussed malware analysis in another chat" — Gizmo searches all past conversations for semantically relevant exchanges. Results include the conversation title and date so you can see where they came from.

**How it works:** Each user+assistant exchange is automatically embedded and classified into a topic room (technical, architecture, planning, decisions, problems). When your message is similar enough to a past exchange (above 0.45 cosine similarity), the relevant context is injected into the prompt.

**Topic rooms:** If your question strongly matches a topic — e.g., asking about code or debugging — the search filters to the "technical" room first for more focused results, falling back to a broader search if needed.

**No action needed.** Cross-conversation recall happens transparently. The first time the feature runs, it backfills context from all existing conversations.

## Smart Context Windowing

When a conversation exceeds the context window budget, Gizmo uses semantic scoring to decide which older messages to keep. Instead of simply dropping the oldest messages first, it scores older messages by relevance to your current question and keeps the most useful ones.

This activates automatically for conversations with 6+ messages that have stored embeddings. The last 6 messages (3 exchanges) are always kept for recency. If embeddings are unavailable, standard oldest-first dropping is used as a fallback.

## Conversation Compaction

In long conversations (20+ messages), Gizmo automatically generates concise summaries of older message segments that have scrolled out of the context window. These summaries are injected into the prompt, giving the model persistent awareness of earlier content even after the raw messages are gone.

**How it works:** After each response, a background task checks whether any older segments haven't been summarized yet. If so, it sends the segment to the local LLM for a 2-4 sentence summary. Summaries are also indexed for cross-conversation search.

**No action needed.** Compaction is fully automatic and never blocks your conversation.

## Response Handling

**Repetition detection:** If the model enters a repetitive loop (3+ identical blocks of 50+ characters), generation stops automatically with a notice. You can ask it to continue from where it left off.

**Truncation notice:** If a response hits the maximum token limit, a notice appears at the end instead of silently cutting off. You can ask the model to continue.

## Character Analysis

When you ask about individual characters in a word — letter counting, spelling, character positions — Gizmo pre-computes a character breakdown and provides it to the model. This overcomes a fundamental limitation of LLM tokenizers, which see subword tokens rather than individual letters.

**Examples that trigger character analysis:**
- "How many r's are in strawberry?"
- "Count the letters in onomatopoeia"
- "Spell out bureaucracy"
- "What letters are in rhythm?"

No user action needed — detection and injection happen automatically.

## Memory Manager

Open the Memory Manager from **Settings → Memory Manager** to view and manage all stored memories.

- **Browse memories** by category: All, Facts, Notes, Conversations
- **Click a memory** to read its full content
- **Delete individual memories** with the X button
- **Add new memories** via the "+ Add Memory" form (filename, category, content)
- **Clear all memories** with a confirmation step

## Code Playground

Access via the **Code** icon in the left navigation rail, or navigate to `/code`.

The Code Playground is a dedicated split-pane coding environment with a built-in AI assistant.

**Layout:**
- Left pane: code editor with line numbers and per-language placeholder text
- Right pane: execution output (stdout/stderr/exit code) or markup preview (iframe)
- AI chat: slide-in overlay from the right via "Ask Gizmo" button — isolated from main chat, no memory access

**Executable languages** (Python, JavaScript, Bash, C, C++, Go, Lua):
- Code runs in an isolated Podman container with no network, 256MB RAM, read-only filesystem
- Python includes numpy, pandas, matplotlib, sympy, scipy
- Compiled languages (C, C++, Go) compile and run in one step — use higher timeouts (15-20s)
- See stdout, stderr, exit code; click "Copy" in the output header to copy results

**Markup languages** (HTML, CSS, SVG, Markdown):
- Live auto-preview as you type (debounced 300ms) — no Run button needed
- CSS provides sample HTML elements for styling preview
- SVG renders on a dark background
- Markdown renders with dark-themed styling

**Auto language detection:**
- Paste code into an empty editor and the language switches automatically based on code signatures
- Works with Ctrl+A → paste to replace existing code
- Detects C, C++, Go, JavaScript, Bash, Lua, Python, HTML, SVG, CSS, Markdown

**AI Code Assistant:**
- Click "Ask Gizmo" to open the chat overlay
- The AI sees your current code and language as context with every message
- Can execute code via the `run_code` tool — responses include syntax-highlighted code blocks with Copy buttons
- Fully isolated from main chat — no shared history, no memory tools

**Editor features:**
- **Syntax highlighting** — real-time code coloring via highlight.js overlay (same theme as chat code blocks)
- **Resizable split pane** — drag the divider between editor and output panels; double-click to reset to default 55/45 split. Position persists across sessions.
- **Auto-save** — code saves to localStorage after 2 seconds of inactivity with a "Saved" indicator in the footer
- **Copy button** — copy code to clipboard from the editor header
- **Download button** — save code as a file with the correct extension (.py, .js, .sh, etc.)
- **Word wrap toggle** — switch between wrapped and horizontal-scroll modes (persisted)
- **Output files** — when code generates files (e.g., matplotlib plots), they appear as downloadable links with inline image preview

**Shortcuts:**
- **Ctrl+Enter** — Run code
- **Tab** — Insert 4 spaces (instead of moving focus)

**Timeout options** (executable only): 5s, 10s, 20s, 30s (default 10s)

Code persists per-language in localStorage and restores when you return.

## Document Generation

Ask Gizmo to create documents in natural language and it will generate downloadable files.

**Supported formats:**

| Format | Library | Example |
|--------|---------|---------|
| **PDF** | reportlab | "Create a PDF report summarizing these findings" |
| **DOCX** | python-docx | "Write a Word document with a project proposal" |
| **XLSX** | openpyxl | "Make a spreadsheet with monthly budget data" |
| **PPTX** | python-pptx | "Generate a presentation about machine learning basics" |
| **CSV** | built-in | "Create a CSV file with this data table" |
| **TXT** | built-in | "Save these notes as a text file" |

**How it works:**

The LLM uses the `generate_document` tool (not `run_code`) which runs pre-tested Python templates inside the sandbox container. The generated file is extracted via a temporary bind mount, moved to the media directory, and served as a download link in the chat response.

Files are served via `/api/media/` with proper `Content-Disposition: attachment` headers, so your browser will prompt a file download when you click the link.

## Conversation Management

- **Sidebar** shows all past conversations sorted by recency
- **Auto-titles** — after the first exchange, the LLM generates a concise 3-5 word title (replaces truncated first message)
- **Click a conversation** to load its history (auto-scrolls to the latest message)
- **Search** conversations by title (instant filter as you type) or by message content (press **Enter** for full-text search across all messages)
- **Rename** a conversation by double-clicking its title in the sidebar (Enter to save, Escape to cancel)
- **Export** a conversation as Markdown using the download button (hover to reveal) next to each conversation
- **Delete** a conversation with the X button (hover to reveal)
- **New Chat** starts a fresh conversation with no history

Conversations are stored in a server-side SQLite database, accessible from any device on your network (not limited to a single browser origin like localStorage).

## Regenerate & Edit

- **Regenerate**: Hover the last assistant message to reveal the **Regenerate** button (cycle icon). Click it to re-send the same user message for a fresh answer. The previous response is preserved — use the `< 1/2 >` arrows to navigate between all response versions.
- **Edit**: Hover any user message to reveal the **Edit** button (pencil icon). Click it to open an inline editor. Modify the text and click **Save** — the edited message is resubmitted. Use the `< 1/2 >` arrows on both the user message and the response to navigate between prompt/response pairs. Press **Escape** or **Cancel** to discard changes.
- **Response history**: All previous responses are preserved when regenerating or editing. Navigation arrows appear on hover. Responses are linked to the prompt that generated them — navigating a response automatically shows the corresponding prompt, and navigating a prompt jumps to its latest response. Image and video attachments are preserved across regenerations and edits.

## Themes

Gizmo-AI includes a Nintendo console-inspired theme system. Select a theme in **Settings → Theme**.

**Available themes:**

| Theme | Era | Visual Style |
|-------|-----|-------------|
| Default | — | Dark mode with warm amber accent |
| NES | 8-bit | Gray console body, red stripe, cartridge slot, pixel font, scanlines |
| SNES | 16-bit | Gray-purple body, YXBA colored buttons, pixel font, scanlines |
| GBA | Handheld | Indigo body, D-pad, A/B buttons, shoulder buttons, speaker grille, "GAME BOY ADVANCE" label |
| N64 | 3D Era | Charcoal body, 4-color rainbow strip, controller ports |
| GameCube | 3D Era | Deep indigo body, handle, disc cover, rounded plastic feel |
| Wii | Modern | White glossy body, disc slot, blue LED, clean minimal |
| DS | Dual-Screen | Silver metallic body, dual-screen layout with visible hinge gap, D-pad, ABXY |
| 3DS | Dual-Screen | Dark body with teal accents, dual-screen, circle pad, 3D depth slider |
| Switch | Modern | Dark tablet with neon red/blue Joy-Con rails, analog sticks, button clusters |

Handheld themes (GBA, DS, 3DS, Switch) feature physical console elements — buttons, analog sticks, branded labels, and screen bezels that make the UI resemble the actual device. DS and 3DS themes show two separate screen bezels with a visible hinge gap between the chat area and input area.

**Per-console enhancements:**
- **Sound effects** — each console has its own unique sonic identity (NES harsh square waves, SNES warm echo, GBA tinny chimes, N64 bassy pitch bends, GameCube crystalline plinks, Wii bubbly detuned tones, Switch ultra-short tactile clicks, DS cute sine, 3DS refined delay). Enable in Settings → Sounds.
- **Screen effects** — subtle display technology overlays: CRT phosphor vignette (NES), warm phosphor glow (SNES), LCD dot matrix grid (GBA), distance fog (N64), ambient indigo glow (GameCube), horizontal stripes (Wii), neon Joy-Con bleed (Switch), touch crosshair (DS), stereoscopic parallax (3DS)
- **Message styling** — assistant and user messages styled per console: RPG dialog boxes with double borders and blinking prompt (8-bit), Gouraud-gradient panels (N64), glassy translucent cards (GameCube), white glossy plastic with sheen (Wii), flat neon-accent cards (Switch), blue/cyan bordered cards (DS/3DS)
- **Boot sequences** — animated startup screen plays every time you switch to a console theme, with per-console sound. Disable via the "Boot Animations" toggle in Settings. Includes CRT static (NES), converging stars (SNES), white flash (GBA), 3D spinning cube (N64), dropping cube with impact flash (GameCube), expanding ring ripples (Wii), Joy-Con click flash (Switch), screen flash pulse (DS), parallax depth text (3DS). Click to dismiss or wait 2.8s.
- **Interactive buttons** — console frame buttons are clickable: power replays boot sequence, reset starts new chat, eject opens file picker

All console chrome and screen effects are stripped on mobile screens (< 640px) for usability.

## Task Tracker

Access via the **Tasks** icon in the left navigation rail, or navigate to `/tracker`.

The Tracker is a built-in task and note management system with LLM integration. The interface uses a full-width list layout with slide-in overlay panels for editing.

**Tasks:**
- Create tasks via the quick-add card at the top (type and press Enter)
- Card-based task list with colored left borders indicating priority (red=urgent, accent=high, yellow=medium, dim=low)
- SVG status circles: click to cycle through todo → active → done
- Filter by status (with counts), tag (dropdown), and sort by priority or due date
- **Free-text search** — search across task titles, descriptions, and tags from the filter bar
- Auto-save: edits in the detail panel save automatically after 800ms
- Segmented controls for status and priority, tag pills with Enter/comma to add
- **Collapsible subtasks** — click the subtask count badge (e.g., "2/5") to expand/collapse inline subtasks
- **Inline title editing** — double-click a task title to edit in place (Enter saves, Escape cancels; desktop only)
- **Undo delete** — deleting a task shows a toast with "Undo" button; the actual deletion is deferred 5 seconds
- Overdue tasks highlighted with a red ring and red due-date pill
- Set recurrence (daily, weekly, biweekly, monthly, yearly) for repeating tasks

**Keyboard navigation** (active when no input is focused):
- **j** / **ArrowDown** — move to next task
- **k** / **ArrowUp** — move to previous task
- **Enter** — open detail overlay for focused task
- **x** — cycle status of focused task
- **n** — focus the quick-add input
- **/** — focus the search input

**Notes:**
- Card-based note list with pinned notes visually distinguished (accent tint)
- 2-line content preview, search by title/content, filter pinned only
- Auto-save in the editor panel, tag pills, pin toggle button

**LLM Chat:**
- Toggle the "Ask Gizmo" button in the header to open the chat overlay
- Create, update, complete, and delete tasks via natural language
- Example: "Add a high priority task to review the security audit by Friday"
- Example: "Mark the grocery shopping task as done"
- The LLM verifies task identity before completing or deleting to prevent mix-ups
- Tool call progress shown as styled cards (pulse dot when running, checkmark when done)

## Analysis Patterns

Gizmo includes 30 structured analysis patterns that produce consistent, high-quality output for complex tasks. Patterns activate automatically based on keywords in your message, or you can invoke them explicitly.

### Automatic Activation

Just ask naturally — the system detects your intent:
- "What are the key takeaways?" → `extract_wisdom` pattern
- "Summarize this article" → `summarize` pattern
- "Analyze this threat report" → `analyze_threat` pattern
- "Review this code" → `review_code` pattern
- "Is this claim true?" → `analyze_claims` pattern

### Explicit Invocation

Prefix your message with `[pattern:name]`:
```
[pattern:debug_code] Here's my broken function: ...
[pattern:threat_model] Our application exposes a REST API that...
[pattern:create_visualization] Show the data flow between our microservices
```

### Available Patterns

| Category | Patterns |
|----------|----------|
| Information Extraction | extract_wisdom, summarize, extract_insights |
| Analysis | analyze_claims, analyze_threat, security_review |
| Code | review_code, explain_code, debug_code, create_coding_project |
| Writing | improve_writing, improve_prompt, write_essay |
| Research | create_summary, explain_technical, analyze_paper, compare_options |
| Risk & Planning | analyze_risk, create_plan, analyze_incident |
| Security | create_sigma_rules, threat_model, analyze_logs, write_detection |
| Productivity | rate_output, create_checklist, extract_action_items |
| Data | create_visualization, analyze_data, create_report |

### API

List all patterns: `GET /api/patterns`

## Settings

Access via the **Settings** icon at the bottom of the left navigation rail.

| Setting | Description |
|---------|-------------|
| **Theme** | Select a visual theme (Default + 9 Nintendo console themes) |
| **Sounds** | Toggle per-console sound effects for UI interactions |
| **Read Responses Aloud** | Toggle spoken responses ON/OFF (Qwen3-TTS, GPU-accelerated) |
| **TTS Voice** | Select which cloned voice to use for chat TTS (default or any saved voice) |
| **TTS Speed** | Speech speed slider: 0.5x–2.0x (default 1.0x) |
| **TTS Language** | Language for TTS synthesis: Auto, English, Chinese, Japanese, Korean, German, French, Russian, Portuguese, Spanish, Italian |
| **Voice Studio** | Shortcut to open Voice Studio |
| **Memory Manager** | Shortcut to open Memory Manager (view, add, delete memories) |
| **Context Length** | Slider: 2,048–131,072 tokens. Controls conversation history windowing — orchestrator drops oldest messages to fit within the selected token budget. |
| **Keyboard Shortcuts** | Quick reference for all keyboard shortcuts |
| **Service Health** | Live status of all backend services |

## Keyboard Shortcuts

| Shortcut | Context | Action |
|----------|---------|--------|
| **Ctrl+Shift+N** | Global | New conversation |
| **Ctrl+Shift+T** | Global | Toggle thinking mode |
| **Ctrl+Shift+S** | Global | Toggle sidebar |
| **Ctrl+/** | Global | Focus chat input |
| **Escape** | Global | Close any open modal or overlay |
| **Ctrl+Enter** | Code Playground | Run code |
| **Tab** | Code Playground | Insert 4 spaces |
| **j** / **↓** | Tracker (no input focused) | Move to next task |
| **k** / **↑** | Tracker (no input focused) | Move to previous task |
| **Enter** | Tracker (task focused) | Open detail overlay |
| **x** | Tracker (task focused) | Cycle task status |
| **n** | Tracker (no input focused) | Focus quick-add input |
| **/** | Tracker (no input focused) | Focus search input |

Global shortcuts use Ctrl on Linux/Windows and Cmd on macOS. Tracker and Code Playground shortcuts only fire when no input/textarea is focused (exceptions: Escape and Ctrl+/ work from anywhere).

## Remote Access via Tailscale

Gizmo is accessible from any device on your Tailscale network.

- **HTTP**: `http://{tailscale-ip}:3100` — works for chat, but mic access requires HTTPS
- **HTTPS**: `https://<your-tailscale-hostname>/` — valid Let's Encrypt certificate via `tailscale serve`, enables microphone access from laptops/phones

Set up HTTPS:
```bash
tailscale serve --https=443 http://127.0.0.1:3100
```
