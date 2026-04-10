<div align="center">

# Gizmo-AI

**A fully local AI assistant — no cloud, no limits, no data leaving your machine.**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform: Linux](https://img.shields.io/badge/Platform-Linux-green.svg)]()
[![Model: Qwen3.5-9B](https://img.shields.io/badge/LLM-Qwen3.5--9B_Q8__0-8A2BE2.svg)]()
[![TTS: Qwen3-TTS](https://img.shields.io/badge/TTS-Qwen3--TTS_1.7B-orange.svg)]()
[![STT: Whisper](https://img.shields.io/badge/STT-Whisper-red.svg)]()
[![Services: 6](https://img.shields.io/badge/Services-6_Containers-teal.svg)]()

---

A complete self-hosted AI assistant running entirely on local hardware.
9B LLM with vision, neural voice cloning, code execution, web search, task tracking —
all in six containers. Zero cloud dependencies.

</div>

---

## Highlights

<table>
<tr>
<td width="33%" valign="top">

### Intelligence
- 9B parameter LLM with thinking mode
- Vision (images) and video analysis
- 30 structured analysis patterns
- Smart context windowing with semantic recall
- Character-accurate letter counting

</td>
<td width="33%" valign="top">

### Voice
- Streaming audio — hear responses as they generate (~3s to first audio)
- Neural TTS with voice cloning
- Speech-to-text via Whisper
- 10 language support
- Voice Studio for managing cloned voices
- Auto-unloads from VRAM when idle

</td>
<td width="33%" valign="top">

### Tools
- Code execution in 7 languages
- Syntax-highlighted Code Playground
- Document generation (PDF, DOCX, XLSX, PPTX)
- Web search via self-hosted SearXNG
- BM25-ranked persistent memory

</td>
</tr>
</table>

---

## Quick Start

```bash
git clone https://github.com/nisakson2000/Gizmo-AI.git
cd Gizmo-AI
bash scripts/download-model.sh   # Downloads ~14GB (LLM + TTS + vision projector)
bash scripts/build-llamacpp.sh   # Builds model server (~5-10min)
bash scripts/start.sh            # Starts all 6 services
# Open http://localhost:3100
```

---

## Architecture

```
                    ┌─────────────── gizmo-net (10.90.0.0/24) ──────────────┐
                    │                                                        │
  ┌──────────┐     │  ┌────────────────┐          ┌───────────────┐         │
  │ gizmo-ui │────▶│  │    gizmo-      │────────▶ │ gizmo-llama   │         │
  │  :3100   │     │  │  orchestrator  │          │   :8080       │         │
  │ SvelteKit│     │  │  :9100 FastAPI │          │  Qwen3.5-9B   │         │
  │ + nginx  │     │  └───────┬────────┘          │  [GPU]        │         │
  └──────────┘     │     ┌────┼─────┐             └───────────────┘         │
                   │┌────▼──┐ │ ┌───▼─────┐  ┌─────────────┐               │
                   ││searxng│ │ │gizmo-tts│  │gizmo-whisper│               │
                   ││ :8300 │ │ │  :8400  │  │   :8200     │               │
                   ││ [CPU] │ │ │  [GPU]  │  │   [CPU]     │               │
                   │└───────┘ │ └─────────┘  └─────────────┘               │
                   └──────────┴────────────────────────────────────────────┘
```

| Service | Port | Role | GPU |
|---------|------|------|-----|
| **gizmo-llama** | 8080 | LLM inference (Qwen3.5-9B Q8_0 + vision) | Yes |
| **gizmo-orchestrator** | 9100 | FastAPI backend — routing, streaming, tools | No |
| **gizmo-ui** | 3100 | SvelteKit web UI via nginx | No |
| **gizmo-tts** | 8400 | Qwen3-TTS neural voice cloning | Yes |
| **gizmo-whisper** | 8200 | faster-whisper speech-to-text | No |
| **gizmo-searxng** | 8300 | Self-hosted web search | No |

---

## Features

<details>
<summary><strong>Chat & Conversation</strong></summary>

- Streaming chat with persistent server-side history and LLM-generated titles
- Regenerate & edit — re-roll any response or edit a sent message, with `< 1/N >` variant navigation
- Full-text search — sidebar filters by title; press Enter for deep message content search
- Conversation export as formatted Markdown
- Double-click to rename conversations
- Scroll-to-bottom floating button when scrolled up
- Mobile swipe gestures for sidebar (swipe right to open, left to close)

</details>

<details>
<summary><strong>AI Capabilities</strong></summary>

- **Mode switcher** — 6 behavioral modes (Chat, Brainstorm, Coder, Research, Planner, Roleplay) + custom mode creation with prompt editor
- **Usage analytics** — token counts, response times, and cloud cost comparison dashboard at `/analytics`
- **Thinking mode** — step-by-step reasoning in collapsible blocks (toggle on/off)
- **Vision** — analyze images via multimodal vision projector (mmproj)
- **Video analysis** — upload video, extract frames, analyze visual content with playback
- **Audio transcription** — upload M4A/MP3/WAV for Whisper transcription + LLM analysis
- **Multi-round tool calling** — model autonomously chains up to 5 rounds of tool calls
- **Web search** via self-hosted SearXNG — no API keys
- **Document upload** — PDFs, text, code up to 50MB
- **Memory** — BM25-ranked facts with recency weighting + semantic session recall (CPU embeddings)
- **Cross-conversation recall** — two-tier semantic search across all past conversations with topic room categorization
- **Conversation compaction** — rolling LLM summaries preserve context awareness in long conversations
- **Knowledge extraction** — automatic temporal fact tracking with entity normalization and invalidation
- **Smart context windowing** — keeps most relevant older messages by semantic similarity
- **Recitation** — fetches authoritative text from the web for poems, lyrics, speeches
- **Character analysis** — accurate letter counting via pre-computed character maps

</details>

<details>
<summary><strong>Voice & TTS</strong></summary>

- **Streaming TTS** — sentence-level audio streaming via WebSocket (~3s to first audio vs 7-45s batch mode), with gapless browser playback
- **Voice Studio** — upload reference audio, name and save voices, adjustable clip duration
- **Qwen3-TTS** — GPU-accelerated neural voice cloning (x-vector mode) via faster-qwen3-tts
- **Speed control** — 0.5x to 2.0x
- **10 languages** — English, Chinese, Japanese, Korean, German, French, Russian, Portuguese, Spanish, Italian
- **Speech-to-text** — dictate via microphone with Whisper
- **Auto-unload** — TTS model frees VRAM after 60s idle

</details>

<details>
<summary><strong>Code & Tools</strong></summary>

- **Sandbox** — 7 languages (Python, JavaScript, Bash, C, C++, Go, Lua) in isolated containers (no network, 256MB RAM, read-only fs)
- **Code Playground** — `/code` route with syntax highlighting (highlight.js), resizable split pane, auto-save, copy/download, word wrap, output file display
- **AI code assistant** — isolated chat overlay with multi-round tool calling
- **Document generation** — PDF, DOCX, XLSX, PPTX, CSV, TXT via natural language
- **Markup preview** — live rendering for HTML, CSS, SVG, Markdown
- **Memory Manager** — browse, add, and delete memories from the UI

</details>

<details>
<summary><strong>Patterns & Routing</strong></summary>

- **30 patterns** — Fabric-inspired cognitive templates (extract_wisdom, summarize, analyze_threat, etc.)
- **Intelligent routing** — model sees only 3-8 relevant tools per request via keyword pre-routing
- **Auto or explicit** — patterns activate by keyword matching or `[pattern:name]` prefix
- **Pattern-scoped tools** — each pattern declares which tools are available

</details>

<details>
<summary><strong>Task Tracker</strong></summary>

- Built-in task and note management at `/tracker`
- Tags, priorities, due dates, recurrence (daily/weekly/biweekly/monthly/yearly), subtasks
- Free-text search across titles, descriptions, and tags
- Keyboard navigation — `j`/`k` navigate, `x` toggle status, `n` new task, `/` search
- Inline title editing (double-click), collapsible subtasks, undo delete with toast
- LLM chat overlay for natural language task creation

</details>

<details>
<summary><strong>UI & Accessibility</strong></summary>

- **9 Nintendo themes** — NES, SNES, GBA, N64, GameCube, Wii, DS, 3DS, Switch with console frames, sound effects, screen overlays, and boot animations
- **Keyboard shortcuts** — Ctrl+Shift+N (new chat), Ctrl+Shift+T (think), Ctrl+/ (focus), Escape (close)
- **Mobile support** — swipe gestures, always-visible message actions on touch devices
- **Accessibility** — focus trapping in modals, aria-expanded, sidebar keyboard nav, prefers-reduced-motion
- **Service health** — live status dashboard for all backend services
- **Dual API** — WebSocket for streaming UI, REST (`/api/chat`) for programmatic access
- **Tailscale HTTPS** — secure access from any device on your tailnet
- **100% local** — your data never leaves your machine

</details>

<details>
<summary><strong>Android App</strong></summary>

- **Native Compose chat** — streaming responses, markdown rendering, syntax highlighting
- **Multi-server profiles** — connect to LAN, Tailscale, or any Gizmo instance
- **Thinking mode** — collapsible reasoning blocks, tool call status cards
- **Vision + documents** — attach images and files for analysis
- **Conversation management** — search, rename, delete with undo
- **Mode selector** — Chat, Brainstorm, Coder, Research, and custom modes
- **Auto-reconnect** — exponential backoff on network interruption
- **Build from source** — containerized Podman build, no Android Studio needed
- **CI releases** — GitHub Actions builds APK on version tags

</details>

---

## Hardware Requirements

| | Minimum | Tested |
|---|---|---|
| **GPU** | NVIDIA, 16GB+ VRAM | RTX 4090, 24GB |
| **RAM** | 32GB | 64GB DDR5 |
| **Disk** | 50GB free | NVMe SSD |
| **OS** | Linux (Ubuntu, Fedora, Arch) | Bazzite OS (Fedora) |
| **Runtime** | Podman or Docker + NVIDIA container support | Podman 5.8 |

<details>
<summary>VRAM breakdown</summary>

| Component | VRAM | Notes |
|-----------|------|-------|
| Qwen3.5-9B weights (Q8_0) | ~9.5 GB | Always loaded |
| KV cache (Q8_0, 32K context) | ~6.2 GB | Grows with conversation |
| Qwen3-TTS | ~4.0 GB | Auto-unloads after 60s idle |
| **Peak total** | **~20.7 GB** | LLM + TTS active |
| Whisper | 0 GB | Runs on CPU |

</details>

---

## Documentation

Full documentation is available on the **[Wiki](https://github.com/nisakson2000/Gizmo-AI/wiki)**.

| Page | Description |
|------|-------------|
| [How Local AI Works](https://github.com/nisakson2000/Gizmo-AI/wiki/How-the-AI-Works) | First-principles explanation of LLMs and local AI |
| [Setup Guide](https://github.com/nisakson2000/Gizmo-AI/wiki/Setup) | Step-by-step installation |
| [Usage Guide](https://github.com/nisakson2000/Gizmo-AI/wiki/Usage) | Day-to-day usage |
| [Architecture](https://github.com/nisakson2000/Gizmo-AI/wiki/Architecture) | Full technical reference |
| [Model Reference](https://github.com/nisakson2000/Gizmo-AI/wiki/Model-Reference) | Qwen3.5-9B, TTS, Whisper specs and VRAM budget |
| [Development](https://github.com/nisakson2000/Gizmo-AI/wiki/Development) | Extending the stack |

---

## License

[MIT](LICENSE)
