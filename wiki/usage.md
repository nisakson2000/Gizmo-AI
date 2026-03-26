# Usage Guide

Day-to-day guide for using Gizmo-AI. Assumes setup is complete and services are running.

---

## Chat Basics

- **Send a message:** Type in the input box and press **Enter**
- **New line:** Press **Shift+Enter**
- **Stop generation:** Click the stop button that appears during generation
- **New conversation:** Click "+ New Chat" in the sidebar

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

1. **Upload voice references** — provide a sample of any voice
2. **Name and save voices** — build a library of cloned voices
3. **Select a voice** — choose which saved voice to use for synthesis
4. **Set clip duration** — choose how much of the reference audio to use (30s, 60s, 90s, or 120s)
5. **Type and speak** — enter text and hear it spoken in the selected voice

Voice references are processed server-side: truncated to the selected duration, downsampled to 16kHz mono WAV to prevent VRAM issues. Saved voices persist across sessions.

## Text-to-Speech

Toggle TTS in Settings under "Read Responses Aloud."

When enabled, Gizmo speaks responses aloud. An audio player appears below each assistant message. The TTS engine is Qwen3-TTS — a GPU-accelerated neural voice cloning model. It loads into VRAM on demand and auto-unloads after 60 seconds of idle time to free memory for the LLM.

By default, the bundled voice is used. To use a cloned voice for chat TTS, go to **Settings** and select a voice from the **TTS Voice** dropdown. Any voices you've created in Voice Studio will appear here.

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

## Memory Manager

Open the Memory Manager from **Settings → Memory Manager** to view and manage all stored memories.

- **Browse memories** by category: All, Facts, Notes, Conversations
- **Click a memory** to read its full content
- **Delete individual memories** with the X button
- **Add new memories** via the "+ Add Memory" form (filename, category, content)
- **Clear all memories** with a confirmation step

## Code Playground

Open the Code Playground via the **Code** suggestion card on the home screen or from the chat area.

The Code Playground offers two modes:

- **Run** — Execute Python code directly in a sandboxed container. You see stdout, stderr, and exit code immediately. Click the **clipboard icon** in the output header to copy results. The sandbox has no network access, 256MB RAM limit, and a read-only filesystem. Libraries available: numpy, pandas, matplotlib, sympy, scipy.
- **Ask Gizmo** — Send your code to the chat as a markdown code block for Gizmo to run and explain.

The playground resets to a clean state each time you open it.

**Shortcuts:**
- **Ctrl+Enter** — Run the code
- **Tab** — Insert 4 spaces (instead of moving focus)
- **Escape** — Close the playground

**Timeout options:** 5s, 10s, 20s, 30s (default 10s)

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
- **Boot sequences** — animated startup screen plays once per session when switching themes, with per-console sound. Includes CRT static (NES), converging stars (SNES), white flash (GBA), 3D spinning cube (N64), dropping cube with impact flash (GameCube), expanding ring ripples (Wii), Joy-Con click flash (Switch), screen flash pulse (DS), parallax depth text (3DS). Click to dismiss or wait 2.8s.
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
- Auto-save: edits in the detail panel save automatically after 800ms
- Segmented controls for status and priority, tag pills with Enter/comma to add
- Subtask progress shown as "2/5" on parent tasks
- Overdue tasks highlighted with a red ring and red due-date pill
- Set recurrence (daily, weekly, biweekly, monthly, yearly) for repeating tasks

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

## Settings

Access via the **Settings** icon at the bottom of the left navigation rail.

| Setting | Description |
|---------|-------------|
| **Theme** | Select a visual theme (Default + 9 Nintendo console themes) |
| **Sounds** | Toggle per-console sound effects for UI interactions |
| **Read Responses Aloud** | Toggle spoken responses ON/OFF (Qwen3-TTS, GPU-accelerated) |
| **TTS Voice** | Select which cloned voice to use for chat TTS (default or any saved voice) |
| **Voice Studio** | Shortcut to open Voice Studio |
| **Memory Manager** | Shortcut to open Memory Manager (view, add, delete memories) |
| **Context Length** | Slider: 2,048–131,072 tokens. Controls conversation history windowing — orchestrator drops oldest messages to fit within the selected token budget. |
| **Keyboard Shortcuts** | Quick reference for all keyboard shortcuts |
| **Service Health** | Live status of all backend services |

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| **Ctrl+Shift+N** | New conversation |
| **Ctrl+Shift+T** | Toggle thinking mode |
| **Ctrl+Shift+S** | Toggle sidebar |
| **Ctrl+/** | Focus chat input |
| **Escape** | Close any open modal |

Shortcuts use Ctrl on Linux/Windows and Cmd on macOS. Most shortcuts don't fire while typing in an input field (exceptions: Escape and Ctrl+/ work from anywhere).

## Remote Access via Tailscale

Gizmo is accessible from any device on your Tailscale network.

- **HTTP**: `http://{tailscale-ip}:3100` — works for chat, but mic access requires HTTPS
- **HTTPS**: `https://<your-tailscale-hostname>/` — valid Let's Encrypt certificate via `tailscale serve`, enables microphone access from laptops/phones

Set up HTTPS:
```bash
tailscale serve --https=443 http://127.0.0.1:3100
```
