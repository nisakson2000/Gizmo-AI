#!/bin/bash
set -e

PROJECT_DIR="$HOME/gizmo-ai"
cd "$PROJECT_DIR"

echo "╔════════════════════════════════╗"
echo "║        Gizmo-AI Startup        ║"
echo "╚════════════════════════════════╝"
echo ""

# Check model exists
MODEL_FILE="$PROJECT_DIR/models/Huihui-Qwen3.5-27B-abliterated.i1-Q5_K_M.gguf"
if [ ! -f "$MODEL_FILE" ]; then
    echo "ERROR: Model file not found at $MODEL_FILE"
    echo "Run: bash scripts/download-model.sh"
    exit 1
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

# Start infrastructure services first
echo "Starting infrastructure services..."
podman compose up -d gizmo-searxng gizmo-whisper gizmo-kokoro

# Wait for them
sleep 5

# Start model server
echo "Starting model server (this may take 30-60s to load)..."
podman compose up -d gizmo-llama

# Wait for model to load
echo "Waiting for model to load..."
for i in $(seq 1 60); do
    if curl -sf http://localhost:8080/health &>/dev/null; then
        echo " ready."
        break
    fi
    echo -n "."
    sleep 5
done

if ! curl -sf http://localhost:8080/health &>/dev/null; then
    echo ""
    echo "WARNING: Model server not responding after 5 minutes."
    echo "Check logs: podman logs gizmo-llama"
fi

# Start orchestrator and UI
echo "Starting orchestrator and UI..."
podman compose up -d gizmo-orchestrator gizmo-ui

sleep 3

echo ""
echo "╔════════════════════════════════════════════╗"
echo "║  Gizmo-AI is running                       ║"
echo "║  UI:           http://localhost:3100        ║"
echo "║  Orchestrator: http://localhost:9100        ║"
echo "║  Model API:    http://localhost:8080        ║"
echo "╚════════════════════════════════════════════╝"
echo ""
echo "Tailscale: access via your Tailscale IP on port 3100"
