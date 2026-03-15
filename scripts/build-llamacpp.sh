#!/bin/bash
set -e

PROJECT_DIR="$HOME/gizmo-ai"

echo "Building llama.cpp container image..."
echo "This compiles llama.cpp from source with CUDA 89 (RTX 4090)."
echo "Expected time: 5-10 minutes."
echo ""
podman build -t gizmo-llama:latest "$PROJECT_DIR/services/llama/"
echo ""
echo "Build complete. Image: gizmo-llama:latest"

echo ""
echo "Building code execution sandbox image..."
podman build -t gizmo-sandbox:latest "$PROJECT_DIR/services/sandbox/"
echo ""
echo "Build complete. Image: gizmo-sandbox:latest"
