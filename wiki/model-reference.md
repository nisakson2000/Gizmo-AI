# Model Reference

Technical reference for Qwen3.5-27B and the abliterated variant used by Gizmo-AI.

---

## Qwen3.5-27B — Base Model

| Property | Value |
|----------|-------|
| **Developer** | Alibaba Cloud / Qwen Team |
| **Release** | February 2025 |
| **Architecture** | Dense transformer (NOT mixture-of-experts) |
| **Parameters** | 27 billion |
| **Context Window** | 262,144 tokens (native) |
| **Training Data** | Multilingual text, code, reasoning, instruction-following |
| **Modality** | Text + vision (natively multimodal) |
| **Thinking** | Hybrid — supports `<think>` reasoning blocks |

### Key Capabilities
- Strong multilingual support (English, Chinese, Japanese, Korean, many more)
- Competitive code generation (Python, JS, Rust, Go, etc.)
- Mathematical and logical reasoning
- Instruction following and conversation
- Image understanding (with mmproj vision projector)

## Abliteration

The model used by Gizmo-AI is `huihui-ai/Huihui-Qwen3.5-27B-abliterated`.

**What abliteration does:** Standard models are fine-tuned with RLHF to refuse certain requests. The model learns a "refusal direction" in its residual stream — a specific vector in the model's internal representation space that, when activated, causes it to output refusals. Abliteration identifies this direction and removes it by editing the model weights directly.

**What it does NOT change:** The model's knowledge, reasoning ability, coding skills, and general intelligence are unaffected. Only the refusal behavior is removed.

**Tradeoffs:**
- Slightly less coherent on topics near the abliterated directions
- The technique is imperfect — occasional refusals may still occur
- Some responses may lack nuance that the refusal training provided
- You are responsible for your use of an unconstrained model

**Source:** [huihui-ai/Huihui-Qwen3.5-27B-abliterated](https://huggingface.co/huihui-ai/Huihui-Qwen3.5-27B-abliterated) on HuggingFace

## GGUF Quantization Reference

All quantizations from [mradermacher/Huihui-Qwen3.5-27B-abliterated-i1-GGUF](https://huggingface.co/mradermacher/Huihui-Qwen3.5-27B-abliterated-i1-GGUF).

The `i1-` prefix means **imatrix quantization** — a technique that analyzes real text data during the quantization process to allocate more precision to the weights that matter most. Always prefer imatrix variants when available.

| Quant | File Size | VRAM Needed | Quality | Notes |
|-------|-----------|-------------|---------|-------|
| Q2_K | ~10GB | ~12GB | Poor | Avoid — significant quality loss |
| IQ3_M | ~12GB | ~14GB | Below average | Only for very constrained VRAM |
| Q3_K_M | ~13GB | ~15GB | Usable | Minimum for acceptable quality |
| Q4_K_M | ~16GB | ~18GB | Good | Good balance for 20GB GPUs |
| **Q5_K_M** | **~19GB** | **~21GB** | **Very good** | **Recommended — used by Gizmo** |
| Q6_K | ~22GB | ~24GB | Near-lossless | Tight fit on 24GB GPU |
| Q8_0 | ~29GB | ~31GB | Lossless | Exceeds 24GB VRAM |
| FP16 | ~54GB | ~56GB | Full precision | Requires 2x 24GB GPUs |

**How to choose:** Pick the largest quant that fits in your VRAM with ~2-3GB headroom for the KV cache and OS overhead.

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
- **Thinking ON:** Inject `<think>\n` as the start of the assistant turn
- **Thinking OFF:** Inject `\n</think>\n\n` to immediately close the think block

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
