# Model Reference

Technical reference for the models used by Gizmo-AI: Qwen3.5-9B (language + vision), Qwen3-TTS (speech synthesis), and Whisper (speech-to-text).

---

## Qwen3.5-9B — Language Model

| Property | Value |
|----------|-------|
| **Developer** | Alibaba Cloud / Qwen Team |
| **Release** | February 2025 |
| **Architecture** | Dense transformer (NOT mixture-of-experts) |
| **Parameters** | 9 billion |
| **Context Window** | 262,144 tokens (native), 32,768 configured |
| **Training Data** | Multilingual text, code, reasoning, instruction-following |
| **Modality** | Text + vision (natively multimodal, mmproj enabled) |
| **Thinking** | Hybrid — supports `<think>` reasoning blocks |

### Key Capabilities
- Strong multilingual support (English, Chinese, Japanese, Korean, many more)
- Competitive code generation (Python, JS, Rust, Go, etc.)
- Mathematical and logical reasoning
- Instruction following and conversation
- Image understanding via mmproj vision projector (enabled in V3)
- Video frame analysis (frames extracted via ffmpeg, analyzed as images)
- Tool calling (web search, memory, etc.)

## Abliteration

The model used by Gizmo-AI is `huihui-ai/Huihui-Qwen3.5-9B-abliterated`.

**What abliteration does:** Standard models are fine-tuned with RLHF to refuse certain requests. The model learns a "refusal direction" in its residual stream — a specific vector in the model's internal representation space that, when activated, causes it to output refusals. Abliteration identifies this direction and removes it by editing the model weights directly.

**What it does NOT change:** The model's knowledge, reasoning ability, coding skills, and general intelligence are unaffected. Only the refusal behavior is removed.

**Tradeoffs:**
- Slightly less coherent on topics near the abliterated directions
- The technique is imperfect — occasional refusals may still occur
- Some responses may lack nuance that the refusal training provided
- You are responsible for your use of an unconstrained model

**Source:** [huihui-ai/Huihui-Qwen3.5-9B-abliterated](https://huggingface.co/huihui-ai/Huihui-Qwen3.5-9B-abliterated) on HuggingFace

## GGUF Quantization Reference

All quantizations from [mradermacher/Huihui-Qwen3.5-9B-abliterated-GGUF](https://huggingface.co/mradermacher/Huihui-Qwen3.5-9B-abliterated-GGUF) (static quants).

| Quant | File Size | VRAM Needed | Quality | Notes |
|-------|-----------|-------------|---------|-------|
| Q2_K | ~3.5GB | ~5GB | Poor | Avoid — significant quality loss |
| Q3_K_M | ~4.5GB | ~6GB | Usable | Minimum for acceptable quality |
| Q4_K_M | ~5.5GB | ~7GB | Good | Good balance for constrained VRAM |
| Q5_K_M | ~6.5GB | ~8GB | Very good | Strong quality/size tradeoff |
| Q6_K | ~7.5GB | ~9GB | Near-lossless | Excellent quality |
| **Q8_0** | **~9.5GB** | **~12GB** | **Lossless** | **Used by Gizmo — best quality** |
| FP16 | ~18GB | ~20GB | Full precision | Requires 20GB+ VRAM |

**How to choose:** Pick the largest quant that fits in your VRAM with headroom for the KV cache, TTS model, and OS overhead. With Qwen3-TTS also loaded (~4GB when active), the 9B Q8_0 uses ~16.8GB peak on a 24GB card.

## llama.cpp Configuration

Gizmo-AI's llama.cpp instance is tuned for the RTX 4090:

| Flag | Value | Purpose |
|------|-------|---------|
| `--n-gpu-layers 99` | All layers | Full GPU offload — nothing runs on CPU |
| `--ctx-size 32768` | 32K tokens | Context window (native 262K, limited for VRAM) |
| `--flash-attn on` | Enabled | Memory-efficient attention algorithm |
| `--cache-type-k q8_0` | Quantized | **KV cache quantization** — halves cache VRAM (~6GB saved) |
| `--cache-type-v q8_0` | Quantized | Near-lossless quality, frees VRAM for TTS coexistence |
| `--batch-size 4096` | 4K tokens | Prompt processing batch (faster than default 2048) |
| `--ubatch-size 1024` | 1K tokens | Physical micro-batch size |
| `--parallel 2` | 2 slots | Concurrent sequences (chat + title generation) |
| `--cont-batching` | Enabled | Continuous batching for concurrent requests |

### VRAM Budget with KV Cache Quantization

| Component | VRAM |
|-----------|------|
| Model weights (Q8_0) | ~9.5 GB |
| KV cache (32K × 2 slots, Q8_0) | ~6.2 GB |
| CUDA overhead | ~1.0 GB |
| **LLM total** | **~16.7 GB** |
| TTS when loaded | ~4.0 GB |
| **Peak (LLM + TTS)** | **~20.7 GB** |
| **Headroom on 24GB** | **~3.3 GB** |

Without KV cache quantization (FP16), the KV cache would consume ~12.3 GB, leaving virtually no headroom for TTS. The Q8_0 KV cache is the single most impactful VRAM optimization.

## Thinking Mode Details

### Token Format
Qwen3.5 uses ChatML format with thinking blocks:

```
<|im_start|>system
You are a helpful assistant.<|im_end|>
<|im_start|>user
What is 2+2?<|im_end|>
<|im_start|>assistant
<think>
The user is asking a simple arithmetic question. 2+2=4.
</think>

The answer is 4.<|im_end|>
```

### How Gizmo Controls It
- llama.cpp native `enable_thinking` API parameter
- Streaming deltas use `reasoning_content` field for thinking, `content` for response
- Model always thinks — parameter controls whether reasoning appears in a separate field
- Think toggle is a pill button in the input area (like Claude/ChatGPT)

### When Thinking Helps
- **Complex reasoning:** Math proofs, logic puzzles, strategic analysis
- **Code debugging:** Model checks its own logic before committing to an answer
- **Multi-step problems:** Anything requiring more than one logical step
- **Factual accuracy:** Model cross-checks its knowledge internally

### When Thinking Doesn't Help
- **Simple factual questions:** "What's the capital of France?" — no reasoning needed
- **Creative writing:** Thinking can over-constrain creative output
- **Casual conversation:** Adds latency without improving quality
- **Translation:** Direct language mapping doesn't benefit from chain-of-thought

---

## Qwen3-TTS — Text-to-Speech Model

| Property | Value |
|----------|-------|
| **Developer** | Alibaba Cloud / Qwen Team |
| **Model** | Qwen3-TTS-12Hz-1.7B-Base |
| **Parameters** | 1.7 billion |
| **Architecture** | Neural codec language model |
| **Sample Rate** | 24,000 Hz output |
| **Codec Rate** | 12 Hz (tokens per second of audio) |
| **Languages** | Multilingual (Chinese, English, Japanese, Korean, etc.) |
| **Voice Cloning** | Yes — from short reference audio (30-120s recommended) |
| **VRAM** | ~4GB (bfloat16) |

### How It Works
Qwen3-TTS is a voice cloning model. It takes a text prompt and a reference audio sample, then generates speech that matches the voice characteristics of the reference. The Base variant does not have a built-in default voice — it always requires reference audio.

### Gizmo Integration
- Runs as a separate GPU-accelerated container (`gizmo-tts`)
- Loads on startup, auto-unloads from VRAM after 60 seconds idle
- Reloads automatically on next TTS request
- Bundles a default reference voice (espeak-ng generated) for out-of-box use
- Voice Studio: upload, name, save, and select from multiple cloned voices
- Voice references truncated via ffmpeg (30-120s configurable) and downsampled to 16kHz mono to prevent VRAM OOM
- OpenAI-compatible endpoint at `/v1/audio/speech`

### Python Package
```
pip install qwen-tts>=0.1.1
```
Requires `transformers==4.57.3` (exact pin).

**Source:** [Qwen/Qwen3-TTS-12Hz-1.7B-Base](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-1.7B-Base) on HuggingFace

---

## Whisper — Speech-to-Text Model

| Property | Value |
|----------|-------|
| **Implementation** | faster-whisper (CTranslate2-based) |
| **Model** | Systran/faster-whisper-base |
| **Parameters** | 74 million |
| **Architecture** | Encoder-decoder transformer |
| **Languages** | 99 languages |
| **Compute** | CPU only (no VRAM consumed) |
| **Container** | docker.io/fedirz/faster-whisper-server:0.5.0-cpu |

### How It Works
Whisper is an automatic speech recognition model that converts audio to text. The `faster-whisper` implementation uses CTranslate2 for efficient CPU inference.

### Gizmo Integration
- Runs as a CPU-only container (`gizmo-whisper`) — no VRAM contention with LLM/TTS
- Microphone dictation: records audio in browser, sends to Whisper, inserts transcribed text
- Audio file analysis: uploaded M4A/MP3/WAV files are transcribed, then the transcript is sent to the LLM for analysis
- Model auto-downloads on first container start (~150MB, cached in `models/whisper-cache/`)
- Requires `security_opt: label=disable` on SELinux systems for HuggingFace cache access

**Source:** [Systran/faster-whisper-base](https://huggingface.co/Systran/faster-whisper-base) on HuggingFace
