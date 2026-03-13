#!/bin/bash
set -e

MODELS_DIR="$HOME/gizmo-ai/models"
mkdir -p "$MODELS_DIR" "$MODELS_DIR/mmproj"

echo "╔════════════════════════════════════════════════╗"
echo "║  Gizmo-AI — Model Download (9B)               ║"
echo "╚════════════════════════════════════════════════╝"
echo ""
echo "Model: Huihui-Qwen3.5-9B-abliterated"
echo "Source: mradermacher/Huihui-Qwen3.5-9B-abliterated-GGUF"
echo "Quant: Q8_0 (~9.5GB)"
echo ""

# Remove old 27B model if present (free disk space)
OLD_MODEL="$MODELS_DIR/Huihui-Qwen3.5-27B-abliterated.i1-Q5_K_M.gguf"
if [ -f "$OLD_MODEL" ]; then
    echo "Removing old 27B model to free disk space..."
    rm -f "$OLD_MODEL"
    echo "Removed: $OLD_MODEL"
fi

python3 << 'PYEOF'
from huggingface_hub import hf_hub_download
import os

models_dir = os.path.expanduser("~/gizmo-ai/models")
mmproj_dir = os.path.join(models_dir, "mmproj")

# Download 9B Q8_0 (static quant — imatrix not available at Q8_0)
print("Downloading 9B Q8_0...")
hf_hub_download(
    repo_id="mradermacher/Huihui-Qwen3.5-9B-abliterated-GGUF",
    filename="Huihui-Qwen3.5-9B-abliterated.Q8_0.gguf",
    local_dir=models_dir,
)
print("Main model downloaded.")

# Download mmproj (vision projector)
print("\nDownloading vision projector (mmproj Q8_0)...")
hf_hub_download(
    repo_id="mradermacher/Huihui-Qwen3.5-9B-abliterated-GGUF",
    filename="Huihui-Qwen3.5-9B-abliterated.mmproj-Q8_0.gguf",
    local_dir=mmproj_dir,
)
print("Vision projector downloaded.")

# Download chat template
print("\nDownloading chat template...")
hf_hub_download(
    repo_id="huihui-ai/Huihui-Qwen3.5-9B-abliterated",
    filename="chat_template.jinja",
    local_dir=models_dir,
)
print("Chat template downloaded.")
PYEOF

echo ""
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║  Download complete.                                           ║"
echo "║  Main model: $MODELS_DIR/Huihui-Qwen3.5-9B-abliterated.Q8_0.gguf"
echo "║  Vision:     $MODELS_DIR/mmproj/Huihui-Qwen3.5-9B-abliterated.mmproj-Q8_0.gguf"
echo "║  Template:   $MODELS_DIR/chat_template.jinja"
echo "╚════════════════════════════════════════════════════════════════╝"
