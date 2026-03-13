# Gizmo-AI — Claude Code Session Knowledge

This file is maintained automatically across all Claude Code sessions.
Read it at the start of every session. Update it at the end of every session.

## Project Overview
Gizmo-AI is a local AI assistant built on Huihui-Qwen3.5-9B-abliterated (Q8_0),
served via llama.cpp, orchestrated by a Python FastAPI backend, and accessed
via a custom SvelteKit web UI. Text-to-speech uses Qwen3-TTS-12Hz-1.7B-Base
(GPU-accelerated, voice cloning). Everything is containerized via Podman.

## System Facts
- Host: Bazzite OS (immutable Fedora) — use rpm-ostree not dnf for system packages
- GPU: RTX 4090, 24GB VRAM
- Podman rootless v5.8.0 — socket at /run/user/1000/podman/podman.sock
- podman-compose v1.5.0 — no depends_on conditions, use simple lists
- SELinux active — GPU containers need security_opt: ["label=disable"]
- Python packages: pip install --break-system-packages or use venv
- LLM: Huihui-Qwen3.5-9B-abliterated.Q8_0.gguf (~9.5GB VRAM)
- TTS: Qwen3-TTS-12Hz-1.7B-Base (~4GB VRAM, bfloat16)
- Total peak VRAM: ~16.8GB (LLM + TTS loaded)
- mmproj available: mmproj Q8_0 for vision (~600MB)
- Chat template: chat_template.jinja (handles thinking, vision, tool calling)
- llama.cpp minimum version: b8148 (required for Qwen3.5)
- Thinking mode: native llama.cpp enable_thinking API (reasoning_content field)
- All container images must use fully qualified names (docker.io/..., ghcr.io/...)
- TTS package: qwen-tts>=0.1.1, requires transformers==4.57.3

## Architecture
- gizmo-net: Podman network, subnet 10.90.0.0/24
- Ports: llama=8080, orchestrator=9100, ui=3100, whisper=8200, searxng=8300, tts=8400
- All inter-container communication by service name
- GPU passthrough via CDI: nvidia.com/gpu=all + security_opt label=disable
- Both gizmo-llama and gizmo-tts use GPU (shared RTX 4090)

## Container Startup Order
1. gizmo-searxng, gizmo-whisper (infrastructure, no GPU)
2. gizmo-llama (GPU — LLM server)
3. gizmo-tts (GPU — TTS, loads model on startup, auto-unloads after 60s idle)
4. gizmo-orchestrator (depends on llama)
5. gizmo-ui (depends on orchestrator)

## Thinking Mode Implementation
- Uses llama.cpp native `enable_thinking` API parameter (not token injection)
- Streaming deltas use `reasoning_content` field for thinking, `content` for response
- Model always thinks regardless of enable_thinking value — parameter controls separation
- Thinking blocks rendered as collapsible in UI, visually distinct from response

## TTS Implementation
- Qwen3-TTS Base model only does voice cloning (no default voice built in)
- Default voice: bundled espeak-ng generated WAV at /app/assets/default_voice.wav
- API: POST /v1/audio/speech (OpenAI-compatible JSON body)
- Voice cloning: accepts voice_reference (base64 WAV) + voice_reference_text
- Auto-unloads from VRAM after TTS_IDLE_UNLOAD_SECONDS (default 60)
- Reloads automatically on next request
- Container base: docker.io/pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime

## Non-Obvious Facts
- Qwen3.5 chat template key: tokenizer.ggml.pre = qwen35
- Vision requires separate mmproj file alongside the main GGUF
- Model always thinks — enable_thinking controls whether reasoning is in separate field
- SearXNG config must disable rate limiting for local use
- Whisper runs CPU-only (fedirz/faster-whisper-server:latest-cpu, v0.5.0)
- Constitution.txt lines starting with # are stripped as comments
- GitHub user: nisakson2000
- Whisper image frozen at v0.5.0 — uses WHISPER__MODEL env var
- huggingface-cli not on PATH — use Python hf_hub_download/snapshot_download APIs
- Podman requires fully qualified image names (docker.io/...)
- Q8_0 only available in static repo (not imatrix): mradermacher/Huihui-Qwen3.5-9B-abliterated-GGUF
- Context window: 32768 tokens configured (native 262144)

## Known Issues
- SELinux requires :Z suffix on ALL volume mounts in docker-compose.yml
- GPU container requires security_opt: ["label=disable"] for NVML access
- --flash-attn requires value (on/off/auto), not bare flag
- llama.cpp GGML_BACKEND_DL=ON needed for CUDA backend as runtime-loaded .so
- podman-compose has no `rm` subcommand — use stop+rm via podman directly
- Model always produces reasoning_content even with enable_thinking: false
- Whisper container shows "unhealthy" in podman ps but /health endpoint works (port mapping)
- After podman restart, nginx in gizmo-ui may cache stale DNS — fix with nginx -s reload

## Spec Deviations
- Thinking mode: uses native enable_thinking API, not token injection as originally planned
- Download script: uses Python heredoc, not huggingface-cli (not available on PATH)
- TTS Base model has no built-in voice — requires bundled reference audio

## V2 Status
Build complete. All 6 services deployed and operational.
- LLM: Qwen3.5-9B Q8_0 (was 27B Q5_K_M)
- TTS: Qwen3-TTS (was Kokoro CPU)
- VRAM: ~16.8GB peak / 24GB available

## Future Roadmap
- Multi-turn tool use (agent loop with iterative tool calls)
- Conversation search and export
- Image generation (Stable Diffusion integration)
- RAG pipeline (document ingestion + vector search)
- Multi-model support (hot-swap between different GGUFs)
- Mobile-optimized UI (PWA)
- Conversation branching / forking
- Streaming TTS (chunk audio as it generates)
- Custom voice profiles for TTS

## Session Log
- [2026-03-12] Project created from scratch, greenfield build
- [2026-03-12] All 6 services deployed and healthy, streaming chat verified
- [2026-03-12] V1 complete — GitHub repo + wiki published
- [2026-03-13] V2 upgrade — 27B→9B LLM, Kokoro→Qwen3-TTS, all docs updated
