# Development Guide

> **Audience:** Developers extending Gizmo-AI. Assumes strong Python and JavaScript skills.

---

### Contents
- [How to Add a New Tool](#how-to-add-a-new-tool) — Step-by-step with `get_weather` example
- [How to Add a New Pattern](#how-to-add-a-new-pattern) — Creating cognitive templates
- [Coding Conventions](#coding-conventions) — Style, async, error handling
- [Build & Deploy](#build--deploy) — Container rebuild commands

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

**5. Register in the tool registry** in `tools.py` (above `TOOL_DEFINITIONS`):

```python
TOOL_REGISTRY["get_weather"] = {
    "category": "information",
    "keywords": ["weather", "temperature", "forecast"],
    "always_available": False,  # True if always included; False if loaded by routing
}
```

**6. Add a keyword route** (optional) in `router.py`:

```python
(re.compile(r"\b(weather|forecast|temperature)\b.{0,15}\b(in|for|at)\b", re.I),
 ["get_weather"]),
```

## How to Add a New Pattern

Patterns are cognitive templates that structure the model's output for specific task types.

### Steps

1. Create a directory in `config/patterns/<pattern_name>/`
2. Write `config.yaml`:

```yaml
name: my_pattern
description: One-line description of what this pattern does
keywords:
  - trigger phrase one
  - trigger phrase two
tools:
  - web_search      # optional: tools to scope (empty = default tools)
```

3. Write `system.md` following the canonical structure:

```markdown
# IDENTITY and PURPOSE
<role and expertise>

# STEPS
1. First step
2. Second step

# OUTPUT INSTRUCTIONS
- SECTION NAME: format instructions

# INPUT
```

4. Restart the orchestrator — patterns load on startup

**Tips:**
- Use longer, more specific keywords to avoid false matches (longest match wins)
- Test with `[pattern:my_pattern] test input` to verify activation
- Check `GET /api/patterns` to confirm it loaded

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

## UI Development

The UI is a SvelteKit app using Svelte 5 runes mode:

- **State:** Use `$state()`, `$derived()`, `$effect()` — NOT `export let`
- **Props:** Use `$props()` with `$bindable()` for two-way binding
- **Styling:** TailwindCSS with custom design tokens defined in `app.css`
- **Components:** `services/ui/src/lib/components/`
- **Stores:** `services/ui/src/lib/stores/` (chat.ts, settings.ts, connection.ts, theme.ts, toast.ts, sounds.ts, tracker.ts)

**Important:** UI changes require rebuilding the container image:
```bash
podman compose build gizmo-ui && podman compose up -d --force-recreate gizmo-ui
```
The Dockerfile copies the build output at image build time. Just running `up -d` will not pick up source changes.

## Building the Android APK

The Android app lives in `mobile/android/`. It's a Kotlin WebView wrapper — no server-side changes.

### Containerized Build (Recommended)

```bash
bash mobile/build-apk.sh
```

This builds a Docker image with JDK 17 + Android SDK, then runs Gradle inside it. No Android Studio or local SDK needed. The APK appears at `mobile/android/app/build/outputs/apk/debug/app-debug.apk`.

### GitHub Actions CI

The workflow at `.github/workflows/build-android.yml` automatically builds the APK on version tags (`v*`) and attaches it to the GitHub Release. You can also trigger it manually via workflow dispatch.

### Customizing Build-Time Defaults

To ship an APK with pre-configured servers (skipping the onboarding flow):

```bash
cp mobile/android/gizmo-defaults.json.example mobile/android/gizmo-defaults.json
# Edit with your server URLs
bash mobile/build-apk.sh
```

The `gizmo-defaults.json` file is gitignored — it's for personal builds only. The GitHub Releases APK ships without defaults (universal).

### Project Structure

| Directory | Purpose |
|-----------|---------|
| `mobile/android/app/src/main/kotlin/ai/gizmo/app/` | Kotlin source (11 files) |
| `mobile/android/app/src/main/res/` | Layouts, colors, themes, drawables, animations |
| `mobile/android/app/src/main/AndroidManifest.xml` | Permissions, activities, config |
| `mobile/Dockerfile` | Build environment (JDK 17 + Android SDK) |
| `mobile/build-apk.sh` | One-command Podman build script |

## Future Features

- **Model hot-swap** — switch models via API without restarting
- **Image generation** — Stable Diffusion integration

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
