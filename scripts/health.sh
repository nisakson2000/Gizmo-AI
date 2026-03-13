#!/bin/bash
echo "Gizmo-AI Service Health"
echo "─────────────────────────────────"

services=(
    "gizmo-llama:8080:/health"
    "gizmo-orchestrator:9100:/health"
    "gizmo-whisper:8200:/health"
    "gizmo-tts:8400:/health"
    "gizmo-searxng:8300:/"
    "gizmo-ui:3100:/health"
)

for service in "${services[@]}"; do
    name=$(echo "$service" | cut -d: -f1)
    port=$(echo "$service" | cut -d: -f2)
    path=$(echo "$service" | cut -d: -f3)

    if curl -sf "http://localhost:$port$path" &>/dev/null; then
        printf "  ✓ %-25s (port %s)\n" "$name" "$port"
    else
        printf "  ✗ %-25s (port %s) — NOT RESPONDING\n" "$name" "$port"
    fi
done
