#!/bin/bash
set -e

PROJECT_DIR="$HOME/gizmo-ai"
cd "$PROJECT_DIR"

echo "╔════════════════════════════════╗"
echo "║        Gizmo-AI Startup        ║"
echo "╚════════════════════════════════╝"
echo ""

# Check model exists
MODEL_FILE="$PROJECT_DIR/models/Huihui-Qwen3.5-9B-abliterated.Q8_0.gguf"
if [ ! -f "$MODEL_FILE" ]; then
    MODEL_FILE="$PROJECT_DIR/models/Huihui-Qwen3.5-9B-abliterated.i1-Q5_K_M.gguf"
    if [ ! -f "$MODEL_FILE" ]; then
        echo "ERROR: No 9B model file found."
        echo "Expected: models/Huihui-Qwen3.5-9B-abliterated.Q8_0.gguf"
        echo "Run: bash scripts/download-model.sh"
        exit 1
    fi
fi

# Check Podman is running
if ! podman info &>/dev/null; then
    echo "ERROR: Podman is not running or not accessible"
    exit 1
fi

# Check GPU is available
if ! nvidia-smi &>/dev/null; then
    echo "WARNING: nvidia-smi not found — GPU may not be available"
fi

# Check llama image exists
if ! podman image exists gizmo-llama:latest; then
    echo "ERROR: gizmo-llama image not found. Run: bash scripts/build-llamacpp.sh"
    exit 1
fi

# Start CPU-only infrastructure first
echo "Starting infrastructure services..."
podman compose up -d gizmo-searxng gizmo-whisper
sleep 5

# Start model server (GPU)
echo "Starting LLM server (Qwen3.5-9B, ~10GB VRAM)..."
podman compose up -d gizmo-llama

echo "Waiting for LLM to load..."
until curl -sf http://localhost:8080/health &>/dev/null; do
    echo -n "."
    sleep 3
done
echo " ready."

# Start TTS server (also GPU, loads after LLM to avoid contention)
echo "Starting Qwen3-TTS server (~4GB VRAM)..."
podman compose up -d gizmo-tts
sleep 5

# Start orchestrator and UI
echo "Starting orchestrator and UI..."
podman compose up -d gizmo-orchestrator gizmo-ui
sleep 3

echo ""
echo "╔═══════════════════════════════════════════════════════╗"
echo "║  Gizmo-AI is running                                  ║"
echo "║  UI:           http://localhost:3100                  ║"
echo "║  Orchestrator: http://localhost:9100                  ║"
echo "║  LLM API:      http://localhost:8080                  ║"
echo "║  TTS API:      http://localhost:8400                  ║"
echo "╚═══════════════════════════════════════════════════════╝"
echo ""
echo "Tailscale: access via your Tailscale IP on port 3100"
