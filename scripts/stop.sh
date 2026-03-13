#!/bin/bash
cd "$HOME/gizmo-ai"
echo "Stopping Gizmo-AI..."
podman compose down
echo "All services stopped."
