# Gizmo-AI

**A fully local AI assistant — no cloud, no limits, no data leaving your machine.**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform: Linux](https://img.shields.io/badge/Platform-Linux-green.svg)]()
[![Model: Qwen3.5-9B](https://img.shields.io/badge/Model-Qwen3.5--9B-purple.svg)]()
[![TTS: Qwen3-TTS](https://img.shields.io/badge/TTS-Qwen3--TTS-orange.svg)]()
[![STT: Whisper](https://img.shields.io/badge/STT-Whisper-red.svg)]()

---

## What This Is

Gizmo-AI is a complete, self-hosted AI assistant that runs entirely on your own hardware. It uses a 9-billion parameter language model (Qwen3.5-9B) for reasoning, conversation, and tool use, a dedicated neural TTS model (Qwen3-TTS) for natural voice output and voice cloning, and Whisper for speech-to-text — all orchestrated by a Python FastAPI backend and accessed through a custom-built SvelteKit web UI. Everything runs in six containers via Podman.

This is not a wrapper around ChatGPT or any cloud API. This is a real language model running locally, capable of reasoning, writing code, searching the web, analyzing documents and images, understanding video and audio, remembering things across conversations, cloning voices, and speaking responses aloud. Your conversations, files, and data never touch the internet. There are no subscription fees, no usage limits, and no third-party content policies.

The model used is an abliterated variant of Qwen3.5-9B — meaning the safety refusal mechanisms have been removed at the weight level. The model will engage with any topic directly and without disclaimers. You own the hardware, you own the model, you control the behavior.

## Features

### Chat & Conversation
- **Streaming chat** with persistent server-side conversation history and LLM-generated titles
- **Regenerate & edit** — re-roll any response or edit a sent message and resubmit, with full response history navigation (`< 1/N >` arrows)
- **Full-text search** — sidebar filters by title instantly; press Enter for deep search across all message content
- **Conversation export** — download as formatted Markdown from the sidebar
- **Conversation rename** — double-click any title in the sidebar to rename
- **Keyboard shortcuts** — Ctrl+Shift+N (new chat), Ctrl+Shift+T (toggle think), Ctrl+/ (focus input), Escape (close modals); Tracker: j/k (navigate), x (toggle status), n (new task), / (search)

### AI Capabilities
- **Thinking mode** — model reasons step-by-step in collapsible blocks before responding (toggle on/off)
- **Vision** — analyze images directly in chat via the multimodal vision projector (mmproj)
- **Video analysis** — upload video files, extract frames, analyze visual content; video playback in chat
- **Audio transcription** — upload M4A/MP3/WAV for automatic Whisper transcription and LLM analysis
- **Multi-round tool calling** — model autonomously chains up to 5 rounds of tool calls (web search, memory, code execution) in a single exchange
- **Web search** via self-hosted SearXNG — no API keys needed
- **Document upload** — analyze PDFs, text files, and code directly in chat (up to 50MB)
- **Memory system** — remembers facts across conversations via BM25-ranked file storage with recency weighting; within long conversations, semantic recall retrieves relevant earlier messages that scrolled out of the context window (CPU-only embeddings, zero VRAM impact)
- **Smart context windowing** — when the conversation exceeds the context budget, keeps the most relevant older messages (by semantic similarity to the current question) instead of just the most recent ones
- **Recitation & accurate recall** — when asked to recite poems, speeches, lyrics, or other known texts, Gizmo fetches the authoritative source from the web and presents it verbatim at low temperature instead of guessing from training memory
- **Character analysis** — accurate letter counting and spelling (e.g., "How many r's in strawberry?" → 3) via pre-computed character maps injected into the system prompt

### Voice
- **Voice Studio** — dedicated TTS playground with voice cloning: upload reference audio (auto-transcribed via Whisper), name and save voices, adjustable clip duration (30/60/90/120s)
- **Text-to-speech** via Qwen3-TTS — GPU-accelerated neural voice cloning (x-vector mode), long text chunking (no silent truncation), auto-unloads from VRAM when idle
- **Speech speed control** — adjustable 0.5x–2.0x speed slider in Settings
- **Language selection** — TTS supports 10 languages: English, Chinese, Japanese, Korean, German, French, Russian, Portuguese, Spanish, Italian (plus Auto-detect)
- **Speech-to-text** — dictate messages via microphone using Whisper transcription
- **TTS voice selection** — choose any cloned voice from Voice Studio for chat responses

### Tools
- **Code execution sandbox** — run code in 7 languages (Python, JavaScript, Bash, C, C++, Go, Lua) in isolated Podman containers (no network, 256MB RAM, read-only filesystem; Python includes numpy, pandas, matplotlib, sympy, scipy)
- **Document generation** — generate PDF, DOCX, XLSX, PPTX, CSV, and TXT files via natural language using the `generate_document` tool (pre-tested Python templates run in sandbox, files served as downloads)
- **Code Playground** — dedicated `/code` route with syntax-highlighted split-pane editor (resizable via drag handle), built-in AI code assistant with isolated chat, auto language detection on paste, auto-save, copy/download buttons, word wrap toggle, output file display
- **Markup preview** — live client-side rendering for HTML, CSS, SVG, and Markdown with auto-preview as you type
- **Memory Manager** — browse, add, and delete memories from the UI

### Patterns & Routing
- **Pattern library** — 30 Fabric-inspired cognitive templates (extract_wisdom, summarize, analyze_threat, security_review, debug_code, etc.) that give the model structured rubrics for complex analysis tasks
- **Intelligent routing** — keyword pre-routing and pattern matching ensure the model only sees 3-8 relevant tools per request, regardless of total tool count (scalable to 20+ tools without accuracy degradation)
- **Pattern invocation** — patterns activate automatically by keyword matching, or explicitly via `[pattern:name]` prefix
- **Pattern-scoped tools** — each pattern specifies which tools are available, preventing tool confusion on a 9B model

### Productivity
- **Task Tracker** — built-in task and note management with tags, priorities, due dates, recurrence, and subtasks; LLM-powered natural language task creation via dedicated `/tracker` route; free-text task search, keyboard navigation (j/k/x/n), inline title editing, collapsible subtasks, undo delete with toast

### UI & System
- **Nintendo console themes** — 9 themes (NES, SNES, GBA, N64, GameCube, Wii, DS, 3DS, Switch) with physical console frames, per-console sound effects, screen technology overlays (CRT vignette, LCD dot matrix, fog, neon bleed), era-specific message styling, and animated boot sequences (plays on every theme switch, with opt-out toggle in Settings)
- **Conversation context** — active conversation title displayed in the header bar
- **Icon rail navigation** — labeled sidebar with Chat, Tasks, Code, and Settings
- **Toast notifications** — non-intrusive feedback for copy, export, error events, and undo actions
- **Scroll-to-bottom** — floating button appears when scrolled up in chat, click to jump to latest
- **Mobile gestures** — swipe right from left edge to open sidebar, swipe left to close; message actions always visible on touch devices
- **Accessibility** — modal focus trapping, aria-expanded on collapsible blocks, sidebar keyboard navigation, prefers-reduced-motion support
- **Service health dashboard** — live status monitoring for all backend services
- **Customizable persona** — XML-tagged constitution file (`config/constitution.txt`) with tool decision framework and abliteration-aware precision rules
- **KV cache quantization** — Q8_0 quantized KV cache frees ~6GB VRAM for LLM + TTS coexistence
- **Dual API** — WebSocket for streaming UI, REST endpoint (`/api/chat`) for programmatic access
- **Tailscale HTTPS** — access from any device on your tailnet with valid cert for secure mic access
- **100% local** — your data never leaves your machine

## Hardware Requirements

| | Minimum | Tested |
|---|---|---|
| **GPU** | NVIDIA, 16GB+ VRAM | RTX 4090, 24GB |
| **RAM** | 32GB | 64GB DDR5 |
| **Disk** | 50GB free | NVMe SSD |
| **OS** | Linux (Ubuntu, Fedora, Arch) | Bazzite OS (Fedora) |
| **Runtime** | Podman or Docker + NVIDIA container support | Podman 5.8 |

VRAM breakdown: the 9B LLM uses ~9.5GB for weights plus ~6.2GB for the quantized KV cache (Q8_0). Qwen3-TTS adds ~4GB when active (auto-unloads after 60s idle). Peak with both loaded is ~20.7GB on a 24GB card, leaving ~3.3GB headroom. Whisper runs on CPU and does not consume VRAM.

## Quick Start

```bash
git clone https://github.com/nisakson2000/Gizmo-AI.git
cd Gizmo-AI
bash scripts/download-model.sh   # Downloads ~14GB (LLM + TTS + vision projector)
bash scripts/build-llamacpp.sh   # Builds model server (~5-10min)
bash scripts/start.sh            # Starts all 6 services
# Open http://localhost:3100
```

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                         HOST MACHINE                              │
│                                                                    │
│  ┌─────────────────────── gizmo-net ───────────────────────┐      │
│  │                                                          │      │
│  │  ┌──────────┐   ┌────────────────┐   ┌───────────────┐  │      │
│  │  │ gizmo-ui │──▶│   gizmo-       │──▶│ gizmo-llama   │  │      │
│  │  │  :3100   │   │  orchestrator  │   │   :8080       │  │      │
│  │  │ SvelteKit│   │  :9100 FastAPI │   │  llama.cpp    │  │      │
│  │  │ + nginx  │   └───────┬────────┘   │  [GPU]        │  │      │
│  │  └──────────┘      ┌────┼─────┐      └───────────────┘  │      │
│  │               ┌────▼──┐ │  ┌──▼──────┐ ┌─────────────┐  │      │
│  │               │ searx │ │  │qwen3-tts│ │gizmo-whisper│  │      │
│  │               │ :8300 │ │  │  :8400  │ │  :8200      │  │      │
│  │               │ [CPU] │ │  │  [GPU]  │ │  [CPU]      │  │      │
│  │               └───────┘ │  └─────────┘ └─────────────┘  │      │
│  │                         │                                │      │
│  └─────────────────────────┴────────────────────────────────┘      │
└──────────────────────────────────────────────────────────────────┘
```

## Documentation

| Page | Description |
|------|-------------|
| [How AI Works](wiki/how-ai-works.md) | First-principles explanation of LLMs and local AI |
| [Architecture](wiki/architecture.md) | Full technical reference |
| [Setup Guide](wiki/setup.md) | Step-by-step installation |
| [Usage Guide](wiki/usage.md) | Day-to-day usage |
| [Development](wiki/development.md) | Extending the stack |
| [Model Reference](wiki/model-reference.md) | Qwen3.5-9B specs, quant table, llama.cpp config, VRAM budget, TTS and Whisper details |

## License

[MIT](LICENSE)
