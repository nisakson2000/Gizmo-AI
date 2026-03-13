# Gizmo-AI — Claude Code Session Knowledge

This file is maintained automatically across all Claude Code sessions.
Read it at the start of every session. Update it at the end of every session.

## Project Overview
Gizmo-AI is a local AI assistant built on Huihui-Qwen3.5-27B-abliterated,
served via llama.cpp, orchestrated by a Python FastAPI backend, and accessed
via a custom SvelteKit web UI. Everything is containerized via Podman.

## System Facts
- Host: Bazzite OS (immutable Fedora) — use rpm-ostree not dnf for system packages
- GPU: RTX 4090, 24GB VRAM
- Podman rootless v5.8.0 — socket at /run/user/1000/podman/podman.sock
- podman-compose v1.5.0 — no depends_on conditions, use simple lists
- SELinux active — GPU containers need security_opt: ["label=disable"]
- Python packages: pip install --break-system-packages or use venv
- Model quant: Q5_K_M (19.4GB), fits in 24GB with headroom
- mmproj available: Huihui-Qwen3.5-27B-abliterated.mmproj-Q8_0.gguf (629MB)
- llama.cpp minimum version: b8148 (required for Qwen3.5)
- Thinking mode: native llama.cpp enable_thinking API (reasoning_content field)
- All container images must use fully qualified names (docker.io/..., ghcr.io/...)

## Architecture
- gizmo-net: Podman network, subnet 10.90.0.0/24
- Ports: llama=8080, orchestrator=9100, ui=3100, whisper=8200, searxng=8300, kokoro=8400
- All inter-container communication by service name
- GPU passthrough via CDI: nvidia.com/gpu=all + security_opt label=disable

## Container Startup Order
1. gizmo-searxng, gizmo-whisper, gizmo-kokoro (infrastructure, no GPU)
2. gizmo-llama (GPU — model server)
3. gizmo-orchestrator (depends on llama)
4. gizmo-ui (depends on orchestrator)

## Thinking Mode Implementation
- Uses llama.cpp native `enable_thinking` API parameter (not token injection)
- Streaming deltas use `reasoning_content` field for thinking, `content` for response
- Model always thinks regardless of enable_thinking value — parameter controls separation
- Thinking blocks rendered as collapsible in UI, visually distinct from response

## Non-Obvious Facts
- Qwen3.5 chat template key: tokenizer.ggml.pre = qwen35
- Vision requires separate mmproj file alongside the main GGUF
- Model always thinks — enable_thinking controls whether reasoning is in separate field
- SearXNG config must disable rate limiting for local use
- Kokoro TTS: fast, good quality, runs on CPU so no VRAM competition
- Whisper runs CPU-only (fedirz/faster-whisper-server:latest-cpu, v0.5.0)
- Constitution.txt lines starting with # are stripped as comments
- GitHub user: nisakson2000
- Whisper image frozen at v0.5.0 — uses WHISPER__MODEL env var
- Kokoro pinned to v0.2.2 — port 8880, /health endpoint

## Known Issues
- SELinux requires :Z suffix on ALL volume mounts in docker-compose.yml
- GPU container requires security_opt: ["label=disable"] for NVML access
- --flash-attn requires value (on/off/auto), not bare flag
- llama.cpp GGML_BACKEND_DL=ON needed for CUDA backend as runtime-loaded .so
- podman-compose has no `rm` subcommand — use stop+rm via podman directly
- Model always produces reasoning_content even with enable_thinking: false
- Whisper container shows "unhealthy" in podman ps but /health endpoint works (port mapping)

## Spec Deviations
- Thinking mode: uses native enable_thinking API, not token injection as originally planned
- Kokoro: kept at v0.2.2 (compose file), v0.2.4 wasn't tested
- Download script: uses Python heredoc, not huggingface-cli (not available on PATH)

## Session Log
- [2026-03-12] Project created from scratch, greenfield build
- [2026-03-12] All 6 services deployed and healthy, streaming chat verified
