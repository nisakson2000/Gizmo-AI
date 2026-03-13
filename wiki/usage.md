# Usage Guide

Day-to-day guide for using Gizmo-AI. Assumes setup is complete and services are running.

---

## Chat Basics

- **Send a message:** Type in the input box and press **Enter**
- **New line:** Press **Shift+Enter**
- **Stop generation:** The send button becomes a spinner during generation — generation stops when the model finishes (stop button coming in v2)
- **New conversation:** Click "+ New Chat" in the sidebar

## Thinking Mode

Toggle thinking mode with the **Think ON/OFF** button in the header.

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

No API keys or accounts needed — SearXNG aggregates results from Google, DuckDuckGo, and Bing.

## File Uploads

Click the **paperclip button** to upload files.

**Supported types:**
- **Images:** PNG, JPG, GIF, WebP — model can describe, analyze, read text in images
- **PDFs:** Text extracted and sent to model (first 10,000 characters)
- **Text/Code:** .txt, .md, .py, .js, .ts, .json, .yaml, .csv — content sent directly

The model receives the file content in its context and can discuss, analyze, or answer questions about it.

## Voice Input

Click the **microphone button** to record audio.

1. Browser requests microphone permission (works on localhost without HTTPS)
2. Speak your message
3. Click the mic button again to stop recording
4. Audio is sent to Whisper for transcription
5. Transcribed text appears in the input box
6. Edit if needed, then send

The Whisper model used is `Systran/faster-whisper-large-v3` — high accuracy across languages.

## Text-to-Speech

Toggle TTS with the **TTS button** in the header or in Settings.

When enabled, Gizmo will speak responses aloud. An audio player appears in the chat message. The TTS engine is Kokoro — it runs on CPU so it doesn't compete with the model for GPU VRAM.

Voice options (configurable in Settings):
- **Heart** (default) — warm, natural
- **Bella**, **Nicole** — alternative female voices
- **Adam**, **Michael** — male voices

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

**To forget:**
- Memory deletion is manual — memory files are stored in `~/gizmo-ai/memory/facts/`

Memories persist across conversations and browser sessions. They are simple text files injected into the system prompt based on keyword relevance.

## Conversation Management

- **Sidebar** shows all past conversations sorted by recency
- **Click a conversation** to load its history
- **Search** conversations using the search box in the sidebar
- **Delete** a conversation with the X button (hover to reveal)
- **New Chat** starts a fresh conversation with no history

Conversations are stored in SQLite at `~/gizmo-ai/memory/conversations.db`.

## Settings

Access via the **Settings** button at the bottom of the sidebar.

| Setting | Description |
|---------|-------------|
| **Thinking Mode** | Toggle extended reasoning ON/OFF |
| **Text-to-Speech** | Toggle spoken responses ON/OFF |
| **TTS Voice** | Select Kokoro voice (when TTS is on) |
| **Context Length** | Slider: 2,048–16,384 tokens. Higher = more history visible to model, but slower |
| **Service Health** | Live status of all 6 backend services |
