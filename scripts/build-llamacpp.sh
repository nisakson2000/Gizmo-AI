#!/bin/bash
set -e
echo "Building llama.cpp container image..."
echo "This compiles llama.cpp from source with CUDA 89 (RTX 4090)."
echo "Expected time: 5-10 minutes."
echo ""
podman build -t gizmo-llama:latest /home/nisakson/gizmo-ai/services/llama/
echo ""
echo "Build complete. Image: gizmo-llama:latest"
