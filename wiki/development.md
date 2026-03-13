# Development Guide

How to extend, modify, and contribute to Gizmo-AI. Assumes strong Python/JS skills.

---

## How to Add a New Tool

Tools let the model call external functions. Here's how to add one.

### Example: `get_weather(city)`

**1. Define the tool schema** in `services/orchestrator/tools.py`:

```python
{
    "type": "function",
    "function": {
        "name": "get_weather",
        "description": "Get current weather for a city.",
        "parameters": {
            "type": "object",
            "properties": {
                "city": {
                    "type": "string",
                    "description": "City name, e.g. 'Seattle'",
                }
            },
            "required": ["city"],
        },
    },
}
```

Add this to the `TOOL_DEFINITIONS` list.

**2. Implement the function** (in a new file or in `tools.py`):

```python
async def get_weather(city: str) -> str:
    async with httpx.AsyncClient() as client:
        resp = await client.get(f"https://wttr.in/{city}?format=3")
        return resp.text
```

**3. Register in the dispatch table** in `execute_tool()`:

```python
elif name == "get_weather":
    return await get_weather(arguments["city"])
```

**4. Test:** Ask the model "What's the weather in Seattle?" — it should call `get_weather`.

## How to Change the Model

1. Download the new GGUF to `~/gizmo-ai/models/`
2. Update `docker-compose.yml` — change the `--model` path in gizmo-llama's command
3. Update `config/models.yaml` with the new model's specs
4. Update `CLAUDE.md` with model-specific facts
5. Restart: `bash scripts/stop.sh && bash scripts/start.sh`

**Watch for:**
- Different chat templates (not all models use ChatML)
- Different context window sizes
- Different VRAM requirements (check the quant table)
- Thinking mode may not work if the model wasn't trained for `<think>` tags

Abliterated models from the same base family (e.g., other Qwen3.5 variants) are usually drop-in replacements.

## How to Add a New Service

Pattern:

1. Add container to `docker-compose.yml` on `gizmo-net`
2. Add environment variables to orchestrator for host/port
3. Add health check to `scripts/health.sh`
4. Add proxy rule in `services/ui/nginx.conf` if the UI needs direct access
5. Document in `config/services.yaml`
6. Rebuild and restart

## Planned v2 Features

- **ChromaDB semantic memory** — replace keyword matching with vector similarity search
- **Vision support** — mmproj is already downloaded, needs llama.cpp `--mmproj` flag and image message handling
- **Agent mode** — multi-step task execution with tool chaining
- **Model hot-swap** — switch models via API without restarting
- **Prompt template editor** — edit constitution.txt from the UI
- **Mobile-optimized layout** — responsive design for phone access via Tailscale
- **Usage analytics** — token counts, response times, cost estimation dashboard
- **Stop generation** — client-side abort signal to cancel streaming

## Contributing

1. Fork the repo
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Make changes, test locally
4. Push and open a PR

**Code style:**
- Python: standard library style, type hints, async where appropriate
- TypeScript/Svelte: Svelte 5 runes, TailwindCSS, no component libraries
- Keep it simple — minimal abstractions, no over-engineering

**Testing:**
- Run `bash scripts/health.sh` after changes
- Test streaming chat, tool calls, and edge cases manually
- Automated tests coming in v2
