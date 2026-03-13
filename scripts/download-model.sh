#!/bin/bash
set -e

MODELS_DIR="/home/nisakson/gizmo-ai/models"
mkdir -p "$MODELS_DIR/mmproj"

echo "Downloading Huihui-Qwen3.5-27B-abliterated Q5_K_M..."
echo "Source: mradermacher/Huihui-Qwen3.5-27B-abliterated-i1-GGUF"
echo "Size: ~19.4GB — this will take a while on first run."
echo ""

python3 << 'PYEOF'
from huggingface_hub import hf_hub_download, snapshot_download
import os

models_dir = os.path.expanduser("~/gizmo-ai/models")

# Download main model (imatrix quantized)
print("Downloading main model Q5_K_M...")
hf_hub_download(
    repo_id="mradermacher/Huihui-Qwen3.5-27B-abliterated-i1-GGUF",
    filename="Huihui-Qwen3.5-27B-abliterated.i1-Q5_K_M.gguf",
    local_dir=models_dir,
)
print("Main model downloaded.")

# Download mmproj (vision projector) from static repo
print("\nDownloading vision projector (mmproj Q8_0)...")
mmproj_dir = os.path.join(models_dir, "mmproj")
os.makedirs(mmproj_dir, exist_ok=True)
hf_hub_download(
    repo_id="mradermacher/Huihui-Qwen3.5-27B-abliterated-GGUF",
    filename="Huihui-Qwen3.5-27B-abliterated.mmproj-Q8_0.gguf",
    local_dir=mmproj_dir,
)
print("Vision projector downloaded.")
PYEOF

echo ""
echo "Model download complete."
echo "Main model: $MODELS_DIR/Huihui-Qwen3.5-27B-abliterated.i1-Q5_K_M.gguf"
echo "Vision projector: $MODELS_DIR/mmproj/Huihui-Qwen3.5-27B-abliterated.mmproj-Q8_0.gguf"
