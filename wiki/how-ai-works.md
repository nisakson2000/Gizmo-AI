# How Local AI Works

This page explains how large language models actually work — from first principles — using Gizmo-AI as the concrete example throughout. No prior AI knowledge assumed.

---

## What Is a Language Model?

A language model is a mathematical function that predicts what comes next in a sequence of text. It was trained on massive amounts of human-written text — books, websites, code, conversations — and learned the statistical patterns of how words relate to each other.

Given a sequence like "The sky is", the model assigns a probability to every possible next word in its vocabulary. "Blue" might get 35%, "clear" might get 12%, "falling" might get 3%. The model then selects from these high-probability candidates. By repeating this process — one word at a time — it generates coherent paragraphs, code, analysis, and conversation.

The key insight: the model does not "understand" language the way you do. It has no internal experience. What it has is an extraordinarily good statistical map of how language works, built from billions of examples. The apparent intelligence emerges from doing next-word prediction extremely well across an enormous range of topics and contexts.

The basic unit is not a word but a **token** — roughly a word or word-piece. Common words like "the" are single tokens. Longer or rarer words get split: "unbelievable" might become "un" + "believ" + "able". Qwen3.5-27B has a vocabulary of about 150,000 tokens. Every input and output is converted to and from these tokens.

## What Makes a Model "Large"?

The "intelligence" of a language model lives in its **parameters** — also called weights. These are the numbers inside the model that encode all the patterns it learned during training. Think of them as billions of tiny knobs, each tuned during training to capture some aspect of language.

Qwen3.5-27B has **27 billion parameters**. Each parameter is a number (a floating-point value) stored in your GPU's memory. This is why VRAM is the primary hardware constraint — the weights must fit in memory for the model to run.

At full precision (16-bit floating point), 27 billion parameters would require about 54GB of memory. That exceeds every consumer GPU. This is where quantization comes in.

## What Is Quantization?

**Quantization** reduces the precision of each parameter to shrink the model into available memory. Instead of storing each weight as a 16-bit float, you store it as a 5-bit, 4-bit, or even 3-bit number.

Gizmo-AI uses **Q5_K_M** quantization:
- **Q5**: 5-bit precision per weight
- **K**: K-quant method (a smart quantization technique that allocates more bits to important weights)
- **M**: Medium quality within the K-quant family

The result: the 27B model fits in **19.4GB** instead of 54GB. Quality loss is minimal — roughly 1-3% degradation on benchmarks compared to full precision. For most conversations, the difference is imperceptible.

| Quant | Size | VRAM Needed | Quality |
|-------|------|-------------|---------|
| Q4_K_M | ~16GB | ~18GB | Good |
| **Q5_K_M** | **~19GB** | **~21GB** | **Very good (used by Gizmo)** |
| Q6_K | ~22GB | ~24GB | Near-lossless |
| Q8_0 | ~29GB | ~31GB | Lossless — exceeds 24GB VRAM |

## What Is GGUF?

**GGUF** is the file format used by llama.cpp to store quantized models. It is a single, self-contained file that includes:

- The model weights (quantized)
- The model architecture configuration
- The tokenizer vocabulary

In Gizmo-AI, the GGUF file lives at `~/gizmo-ai/models/Huihui-Qwen3.5-27B-abliterated.i1-Q5_K_M.gguf` — a single 19GB file. This contrasts with the HuggingFace format, which spreads weights across multiple files alongside separate config and tokenizer files.

The `i1-` prefix means this file was quantized using **imatrix** — a technique that analyzes real text data during quantization to minimize quality loss on the patterns that matter most.

## What Is llama.cpp?

**llama.cpp** is a C++ inference engine that loads GGUF models and serves them via an HTTP API compatible with OpenAI's format. It handles:

- **GPU offloading**: Moving model layers onto the GPU for fast inference (`--n-gpu-layers 99` means "put all layers on GPU")
- **KV cache management**: Efficiently storing the conversation context in memory
- **Continuous batching**: Processing multiple requests simultaneously
- **CUDA acceleration**: Using NVIDIA GPU compute for matrix operations
- **Flash attention**: A memory-efficient attention algorithm (`--flash-attn`)

In Gizmo-AI, llama.cpp runs inside the `gizmo-llama` container and exposes an API at port 8080. The orchestrator sends it messages in OpenAI format and receives streamed responses.

## What Is a Context Window?

The **context window** is the model's working memory — everything it can see when generating a response. This includes:

- The system prompt (who the model is, how to behave)
- The entire conversation history
- Any tool results (search results, memory lookups)
- Any uploaded file content

Qwen3.5-27B supports up to 262,144 tokens natively, but Gizmo-AI caps it at **16,384 tokens** in v1. Why? Larger contexts require more VRAM for the KV cache, increase latency, and provide diminishing returns for typical conversations. 16,384 tokens is roughly 12,000 words — enough for long conversations with code, search results, and document analysis.

When the context fills up, the oldest messages are trimmed to make room for new ones. The system prompt and most recent messages always stay.

## What Is Thinking Mode?

Qwen3.5-27B is a **thinking model** — it was trained to reason step-by-step inside `<think>...</think>` tags before producing its final response. This is similar to OpenAI's o1/o3 models or Anthropic's extended thinking.

When thinking is **ON**: The model works through the problem internally — considering approaches, checking its reasoning, catching errors — then gives a cleaner, more accurate answer. The thinking content appears as a collapsible block in the UI.

When thinking is **OFF**: The model responds immediately without the internal reasoning step. Faster, but may be less accurate on complex problems.

Gizmo-AI implements this via **token injection**:
- **Thinking ON**: The assistant's response is prefixed with `<think>\n`, which triggers the model to continue reasoning
- **Thinking OFF**: The assistant's response is prefixed with `\n</think>\n\n`, which tricks the model into immediately closing the think block and jumping to the answer

Simple factual questions ("What's the capital of France?") don't benefit from thinking. Complex multi-step problems (debugging code, mathematical proofs, strategic analysis) do.

## What Is Abliteration?

Standard language models are fine-tuned with RLHF (Reinforcement Learning from Human Feedback) to refuse certain requests — violence, illegal activity, controversial topics. The model learns a "refusal direction" in its internal representation space.

**Abliteration** is a technique that directly edits the model weights to remove this refusal direction from the residual stream. The result is a model that will engage with any topic without safety filters.

The model used in Gizmo-AI — `huihui-ai/Huihui-Qwen3.5-27B-abliterated` — has been abliterated. Its knowledge, reasoning ability, and coding skills are unchanged. Only the refusal behavior has been removed.

Tradeoffs:
- The model may occasionally be slightly less coherent on topics near the abliterated directions
- The technique is imperfect — some refusals may still occur, and some non-refusals may be less nuanced
- **You are responsible** for how you use an unconstrained model

## What Is RAG and Memory?

**Retrieval-Augmented Generation (RAG)** means injecting relevant information into the context window at inference time, rather than relying solely on what the model learned during training.

In Gizmo-AI v1, this takes the form of a simple file-based memory system:
- You tell Gizmo to "remember that my name is Nick"
- Gizmo writes a file to `memory/facts/user_name.txt`
- On future conversations, the orchestrator scans memory files for keywords matching your message
- Relevant memories are injected into the system prompt

This is basic keyword matching — v2 will use ChromaDB for semantic similarity search, finding relevant memories based on meaning rather than exact keyword overlap.

## What Is Tool Calling?

Modern LLMs can be trained to output structured JSON that triggers external functions. In Gizmo-AI:

1. The model receives tool definitions (web_search, read_memory, write_memory, list_memories) in its context
2. When appropriate, instead of regular text, the model outputs a JSON tool call: `{"name": "web_search", "arguments": {"query": "AI news"}}`
3. The orchestrator parses this JSON, executes the tool (queries SearXNG for web results)
4. The results are injected back into the conversation context
5. The model then responds with full knowledge of what the tool returned

This is how Gizmo searches the web without the model itself having internet access. The model decides when to use tools based on the conversation — if you ask about current events, it will call `web_search`. If you ask it to remember something, it will call `write_memory`.

## What Is a System Prompt?

The **system prompt** (or constitution) is a block of text prepended to every conversation that tells the model who it is, how to behave, and what capabilities it has. In Gizmo-AI, this lives in `config/constitution.txt`.

It defines:
- The model's identity ("You are Gizmo, a local AI assistant")
- Communication style ("technical, direct, no filler")
- Available tools and capabilities
- Behavioral guidelines

The system prompt is the primary way to customize the model's personality without retraining. Edit `constitution.txt` to change how Gizmo behaves.
