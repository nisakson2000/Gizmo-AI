# Gizmo-AI

**A fully local AI assistant — no cloud, no limits, no data leaving your machine.**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform: Linux](https://img.shields.io/badge/Platform-Linux-green.svg)]()
[![Model: Qwen3.5-27B](https://img.shields.io/badge/Model-Qwen3.5--27B-purple.svg)]()

---

## What This Is

Gizmo-AI is a complete, self-hosted AI assistant that runs entirely on your own hardware. The model — a 27-billion parameter language model called Qwen3.5-27B — runs directly on your GPU. Your conversations, files, and data never touch the internet. There are no subscription fees, no usage limits, and no third-party content policies.

This is not a wrapper around ChatGPT or any cloud API. This is a real language model running locally, capable of reasoning, writing code, searching the web, analyzing images, remembering things across conversations, and speaking responses aloud. It is served via llama.cpp, orchestrated by a Python FastAPI backend, and accessed through a custom-built SvelteKit web UI. Everything runs in containers via Podman.

The model used is an abliterated variant of Qwen3.5-27B — meaning the safety refusal mechanisms have been removed at the weight level. The model will engage with any topic directly and without disclaimers. You own the hardware, you own the model, you control the behavior.

## Features

- **Streaming chat** with persistent conversation history
- **Toggleable thinking mode** — model reasons step-by-step before responding
- **Web search** via self-hosted SearXNG — no API keys needed
- **Image and document analysis** — upload files directly in chat
- **Voice input** via OpenAI Whisper — local, no cloud transcription
- **Text-to-speech** via Kokoro — runs on CPU, natural voice output
- **Memory system** — Gizmo remembers facts across conversations
- **100% local** — your data never leaves your machine
- **No content filtering** — abliterated model, no nannying

## Hardware Requirements

| | Minimum | Tested |
|---|---|---|
| **GPU** | NVIDIA, 20GB+ VRAM | RTX 4090, 24GB |
| **RAM** | 32GB | 64GB DDR5 |
| **Disk** | 100GB free | NVMe SSD |
| **OS** | Linux (Ubuntu, Fedora, Arch) | Bazzite OS (Fedora) |
| **Runtime** | Podman or Docker + NVIDIA container support | Podman 5.8 |

The GPU requirement exists because the model weights (19GB at Q5_K_M quantization) must fit in VRAM. The CPU handles transcription (Whisper) and text-to-speech (Kokoro) but is not the bottleneck for inference.

## Quick Start

```bash
git clone https://github.com/nisakson2000/Gizmo-AI.git
cd Gizmo-AI
bash scripts/download-model.sh   # Downloads ~20GB model
bash scripts/build-llamacpp.sh   # Builds model server (~5-10min)
bash scripts/start.sh            # Starts all 6 services
# Open http://localhost:3100
```

## Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                        HOST MACHINE                            │
│                                                                │
│  ┌────────────────────── gizmo-net ──────────────────────┐    │
│  │                                                        │    │
│  │  ┌──────────┐   ┌────────────────┐   ┌─────────────┐ │    │
│  │  │ gizmo-ui │──▶│   gizmo-       │──▶│ gizmo-llama │ │    │
│  │  │  :3100   │   │  orchestrator  │   │   :8080     │ │    │
│  │  │ SvelteKit│   │  :9100 FastAPI │   │  llama.cpp  │ │    │
│  │  └──────────┘   └───────┬────────┘   │  [GPU]      │ │    │
│  │                     ┌────┼─────┐     └─────────────┘ │    │
│  │               ┌─────▼┐ ┌▼────┐ ┌▼───────┐            │    │
│  │               │whisper│ │searx│ │ kokoro │            │    │
│  │               │ :8200 │ │:8300│ │ :8400  │            │    │
│  │               │ [CPU] │ │[CPU]│ │ [CPU]  │            │    │
│  │               └───────┘ └─────┘ └────────┘            │    │
│  └────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────┘
```

## Documentation

| Page | Description |
|------|-------------|
| [How AI Works](wiki/how-ai-works.md) | First-principles explanation of LLMs and local AI |
| [Architecture](wiki/architecture.md) | Full technical reference |
| [Setup Guide](wiki/setup.md) | Step-by-step installation |
| [Usage Guide](wiki/usage.md) | Day-to-day usage |
| [Development](wiki/development.md) | Extending the stack |
| [Model Reference](wiki/model-reference.md) | Qwen3.5-27B specs and quant table |

## License

[MIT](LICENSE)
