#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

# Copy build-time defaults if present
ASSETS_DIR="android/app/src/main/assets"
mkdir -p "$ASSETS_DIR"
if [ -f "android/gizmo-defaults.json" ]; then
    echo "Found gizmo-defaults.json — bundling server defaults into APK"
    cp "android/gizmo-defaults.json" "$ASSETS_DIR/gizmo-defaults.json"
else
    echo "No gizmo-defaults.json found — APK will use onboarding flow"
    rm -f "$ASSETS_DIR/gizmo-defaults.json"
fi

echo "Building Android APK via Podman..."
podman build -t gizmo-android-builder .
podman run --rm -v ./android:/app:Z gizmo-android-builder \
    sh -c "chmod +x gradlew && ./gradlew assembleDebug --no-daemon"

APK="android/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
    SIZE=$(du -h "$APK" | cut -f1)
    echo ""
    echo "Build successful: $APK ($SIZE)"
else
    echo "Build failed — no APK produced"
    exit 1
fi
