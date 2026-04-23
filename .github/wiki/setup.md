# Setup Guide

> **Audience:** Technical users comfortable with a terminal. No prior local AI experience needed.
>
> **Time:** ~1-2 hours for first setup. After that, starting Gizmo takes ~30 seconds.

---

### Contents
- [Before You Start](#before-you-start) — Hardware requirements
- [Steps 1-3](#step-1--install-podman) — Install Podman, NVIDIA toolkit, huggingface-hub
- [Steps 4-6](#step-4--clone-the-repo) — Clone, download models (~14GB), build containers
- [Steps 7-9](#step-7--start-gizmo) — Start services, verify health, open UI
- [Step 10](#step-10--remote-access-via-tailscale) — Remote access via Tailscale
- [Troubleshooting](#troubleshooting) — Common issues and fixes

---

## Before You Start

You are about to set up a stack of 6 containerized services that work together to run a 9-billion parameter language model, a neural TTS model, and a speech-to-text service on your hardware. This involves:

- Building software from source (llama.cpp with CUDA support)
- Downloading ~14GB of models from HuggingFace (LLM, TTS, vision projector, chat template)
- Running containers via Podman (or Docker)

**Estimated time:** 1-2 hours on first setup. After that, starting Gizmo takes ~30 seconds.

## Hardware Requirements

### GPU (Most Important)
You need an **NVIDIA GPU with 20GB+ VRAM**. The LLM weights (Q8_0 quantization) are ~9.5GB plus ~6.2GB for the Q8_0 KV cache at 32K context. Qwen3-TTS adds ~4GB when active — total peak ~20.7GB. The RTX 4090 (24GB) is the tested configuration with ~3.3GB headroom. Whisper runs on CPU and does not consume VRAM.

If you have less VRAM:
- 16GB VRAM → use Q8_0 (fits with TTS, tight but works)
- 12GB VRAM → use Q4_K_M (~5.5GB LLM + 4GB TTS = ~10GB)

AMD GPUs are not supported (llama.cpp CUDA build required).

### RAM
**32GB minimum.** The orchestrator, SearXNG, and Whisper run on CPU and use system RAM. 64GB recommended for comfortable headroom.

### Disk
**50GB free space.** Breakdown: LLM model file (~9.5GB), TTS model (~4GB), Whisper model (~150MB, auto-downloaded), container images (~10GB), mmproj vision file (600MB), logs and memory (variable).

### CPU
Less critical than GPU. The AMD Ryzen 9 7950X3D is the tested configuration.

---

## Step 1 — Install Podman

### Ubuntu/Debian
```bash
sudo apt update
sudo apt install -y podman
```

### Fedora/RHEL/Bazzite
```bash
# Podman is pre-installed on Fedora-based systems
podman --version
```

### Arch
```bash
sudo pacman -S podman
```

> **Verify:** `podman --version` should show 4.0+.

You also need `podman-compose`:
```bash
pip install podman-compose
```

## Step 2 — NVIDIA Container Support

Install the NVIDIA Container Toolkit:

```bash
# Add NVIDIA repo (Ubuntu/Debian)
curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey | sudo gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
curl -s -L https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list | \
  sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' | \
  sudo tee /etc/apt/sources.list.d/nvidia-container-toolkit.list
sudo apt update
sudo apt install -y nvidia-container-toolkit

# For Fedora/Bazzite (may already be installed)
sudo dnf install nvidia-container-toolkit
```

Generate CDI configuration:
```bash
sudo nvidia-ctk cdi generate --output=/etc/cdi/nvidia.yaml
```

> **Verify GPU passthrough:**
```bash
podman run --rm --security-opt=label=disable --device nvidia.com/gpu=all docker.io/nvidia/cuda:12.4.1-base-ubuntu22.04 nvidia-smi
```

> You should see your GPU listed. The `--security-opt=label=disable` is required on SELinux systems (Fedora, Bazzite).

## Step 3 — Install huggingface-hub

```bash
pip install --break-system-packages huggingface-hub
```

Or via pipx:
```bash
pipx install huggingface-hub
```

HuggingFace is the platform where the model files are hosted. The `huggingface-hub` Python package provides the download functionality.

## Step 4 — Clone the Repo

```bash
git clone https://github.com/nisakson2000/Gizmo.git
cd Gizmo
```

## Step 5 — Download the Model

```bash
bash scripts/download-model.sh
```

This downloads:
- **Main model:** `Huihui-Qwen3.5-9B-abliterated.Q8_0.gguf` (~9.5GB)
- **Vision projector:** mmproj Q8_0 (~600MB) — enables image understanding
- **Chat template:** `chat_template.jinja` (handles thinking, vision, and tool calling)
- **TTS model:** `Qwen3-TTS-12Hz-1.7B-Base` (~3.6GB)
- **TTS tokenizer:** `Qwen3-TTS-Tokenizer-12Hz` (~651MB)

The Whisper model (~150MB) is downloaded automatically on first container start.

The download is resumable — if it fails, run the script again and it will continue where it left off.

**Verify:**
```bash
ls -lh models/*.gguf
# Should show ~9.5GB file
ls -lh models/mmproj/*.gguf
# Should show ~600MB file
ls models/qwen3-tts/1.7B-Base/
# Should show model.safetensors, config.json, etc.
```

## Step 6 — Build llama.cpp and Sandbox

```bash
bash scripts/build-llamacpp.sh
```

This builds llama.cpp from source inside a container with CUDA 12.4 support, targeting the Ada Lovelace architecture (RTX 4090). We build from source because pre-built images don't guarantee the version needed for Qwen3.5 support.

**Build the code execution sandbox image:**
```bash
podman build -t gizmo-sandbox:latest services/sandbox/
```

This creates a multi-runtime sandbox image with Python (numpy, pandas, matplotlib, sympy, scipy), Node.js, GCC/G++, Go, and Lua for sandboxed code execution in 7 languages. The sandbox runs with no network access, 256MB memory limit, and a read-only filesystem.

**Estimated time:** 5-10 minutes for llama.cpp, ~2 minutes for sandbox.

**Verify:**
```bash
podman images | grep gizmo-llama
# Should show gizmo-llama:latest
podman images | grep gizmo-sandbox
# Should show gizmo-sandbox:latest
```

### Common Build Failures
- **CUDA not found:** Ensure NVIDIA Container Toolkit is installed and CDI is configured
- **Out of disk space:** The build needs ~10GB temporarily
- **Network timeout:** Re-run the script — it will use cached layers

## Step 7 — Start Gizmo

```bash
bash scripts/start.sh
```

The script:
1. Checks that the model file exists
2. Starts infrastructure services (SearXNG, Whisper)
3. Starts the LLM server (takes 15-30 seconds to load 9.5GB into VRAM)
4. Starts the TTS server (GPU, loads on first request)
5. Starts the orchestrator and UI

**Expected output:**
```
╔════════════════════════════════╗
║        Gizmo Startup        ║
╚════════════════════════════════╝

Starting infrastructure services...
Starting LLM server (Qwen3.5-9B, ~10GB VRAM)...
Waiting for LLM to load...
........... ready.
Starting Qwen3-TTS server (~3GB VRAM)...
Starting Whisper server (CPU)...
Starting orchestrator and UI...

╔═══════════════════════════════════════════════════════╗
║  Gizmo is running                                  ║
║  UI:           http://localhost:3100                  ║
║  Orchestrator: http://localhost:9100                  ║
║  LLM API:      http://localhost:8080                  ║
║  TTS API:      http://localhost:8400                  ║
║  Whisper API:  http://localhost:8200                  ║
╚═══════════════════════════════════════════════════════╝

Tailscale: access via your Tailscale IP on port 3100
HTTPS:     https://<your-tailscale-hostname>/
```

## Step 8 — Verify Everything Works

```bash
bash scripts/health.sh
```

**Healthy output:**
```
Gizmo Service Health
─────────────────────────────────
  ✓ gizmo-llama              (port 8080)
  ✓ gizmo-orchestrator       (port 9100)
  ✓ gizmo-tts                (port 8400)
  ✓ gizmo-whisper            (port 8200)
  ✓ gizmo-searxng            (port 8300)
  ✓ gizmo-ui                 (port 3100)
```

## Step 9 — Open the UI

Navigate to **http://localhost:3100** in your browser.

You should see a dark-themed chat interface with "Gizmo" centered and capability hints. Send a test message like "Hello, who are you?" and verify you get a streaming response.

## Step 10 — Remote Access via Tailscale

[Tailscale](https://tailscale.com/) is a zero-config VPN that lets your devices communicate securely without port forwarding. If Tailscale is running on both your server and your laptop/phone, you can access Gizmo from anywhere.

1. Install Tailscale on your server (if not already): `curl -fsSL https://tailscale.com/install.sh | sh`
2. Run `tailscale ip -4` to get your server's Tailscale IP
3. Access the UI at `http://{tailscale-ip}:3100` from any device on your Tailnet

**For HTTPS (required for microphone access from non-localhost):**
```bash
tailscale serve --https=443 http://127.0.0.1:3100
```

This provides a valid Let's Encrypt certificate at `https://<your-tailscale-hostname>/`. The browser requires a secure context (HTTPS or localhost) for microphone access.

**Firewall note:** If port 3100 is blocked on your server's firewall:
```bash
sudo firewall-cmd --add-port=3100/tcp --permanent
sudo firewall-cmd --reload
```

---

## Troubleshooting

### Model won't load
- Check VRAM: `nvidia-smi` — is there enough free VRAM?
- Check file path: `ls models/*.gguf`
- Check GPU passthrough: `podman logs gizmo-llama`

### WebSocket disconnects
- Check nginx config in `services/ui/nginx.conf` — proxy timeouts
- Check orchestrator: `podman logs gizmo-orchestrator`

### SearXNG returns no results
- Check container: `podman logs gizmo-searxng`
- Check config: `services/searxng/config/settings.yml`
- Test directly: `curl http://localhost:8300/search?q=test&format=json`

### Qwen3-TTS silent
- Check container health: `curl http://localhost:8400/health`
- Check TTS toggle in UI settings
- TTS auto-unloads after 60s idle — first request after idle takes longer

### Whisper transcription fails
- Check container: `podman logs gizmo-whisper`
- Test directly: `curl http://localhost:8200/health`
- SELinux note: Whisper container requires `security_opt: label=disable` for HuggingFace cache mount

### Microphone not working
- **From localhost:** Should work — browsers treat localhost as secure context
- **From other devices:** Must use HTTPS. Set up `tailscale serve --https=443 http://127.0.0.1:3100` and access via `https://<your-tailscale-hostname>/`
- Check browser permissions: click the lock icon in the address bar

### Voice Studio upload fails
- Check orchestrator logs: `podman logs gizmo-orchestrator`
- File size limit: 50MB per voice reference
- Long audio files are automatically truncated to the selected duration (30-120s)

### Tailscale not reaching UI
- Verify both devices are on same Tailnet: `tailscale status`
- Check firewall: `sudo firewall-cmd --list-ports`
- Test connection: `curl http://{tailscale-ip}:3100`

---

## Stack Control Service (Optional)

The **stack-control** service is a tiny host-side HTTP daemon that lets remote clients (primarily the Android app) start and stop the Gizmo stack over Tailscale. It runs **outside** Podman as a systemd user service — the orchestrator can't control the stack it lives inside, so this sidecar on port 9101 fills that gap.

### What it does

Four endpoints on port 9101:

| Endpoint | Method | Purpose |
|---|---|---|
| `/health` | GET | Liveness probe — `{"status": "healthy"}` |
| `/api/system/status` | GET | Per-container state and uptime |
| `/api/system/start` | POST | `podman compose up -d` (180s timeout, idempotent) |
| `/api/system/stop` | POST | `podman compose stop` (60s timeout, idempotent; does NOT run `compose down` — volumes and networks are preserved) |

All endpoints return JSON. There is no authentication.

### Install

```bash
bash services/stack-control/install.sh
```

The installer:
1. Enables **linger** for your user (`loginctl enable-linger`) — required so the systemd user service runs without an active login session and survives reboot
2. Copies the unit file to `~/.config/systemd/user/gizmo-stack-control.service`
3. Runs `systemctl --user daemon-reload && systemctl --user enable --now gizmo-stack-control`
4. Prints verification commands

### Verify

```bash
systemctl --user status gizmo-stack-control        # → active (running)
curl http://localhost:9101/health                  # → {"status":"healthy"}
curl http://localhost:9101/api/system/status       # → JSON with containers list
curl -X POST http://localhost:9101/api/system/stop
curl -X POST http://localhost:9101/api/system/start
```

### Firewall and security — read this

Port 9101 has **no authentication**. The trust model is identical to the rest of the Gizmo stack: trust the network. Only expose port 9101 over Tailscale; never forward it on your public router.

- Tailscale: port 9101 is reachable on your Tailnet IP automatically. Nothing to configure.
- Local firewall: on Fedora / Bazzite, the `firewalld` default zone blocks inbound 9101 from non-Tailscale interfaces, which is what you want. Do not add a `--permanent` allow rule for 9101 on the `public` zone.
- Do not set up `tailscale serve` or nginx reverse-proxying for this port. It's intentionally a separate, locally-bound plane.

### Config

Environment variables (edit `~/.config/systemd/user/gizmo-stack-control.service` after `daemon-reload` to change):

| Variable | Default | Purpose |
|---|---|---|
| `GIZMO_COMPOSE_DIR` | `%h/gizmo` | Directory containing `docker-compose.yml` |
| `GIZMO_COMPOSE_PROJECT` | `gizmo` | podman-compose project label filter |
| `PORT` | `9101` | Listen port |
| `BIND_ADDR` | `0.0.0.0` | Listen address |

### Uninstall

```bash
systemctl --user disable --now gizmo-stack-control
rm ~/.config/systemd/user/gizmo-stack-control.service
systemctl --user daemon-reload
# optional: disable linger
sudo loginctl disable-linger "$USER"
```

### Troubleshooting

- **`podman: command not found` in journal** — your `~/.local/bin` is not in `PATH`. The unit sets `PATH=%h/.local/bin:/usr/local/bin:/usr/bin:/bin` by default; if `podman-compose` is installed somewhere else, edit `Environment=PATH=...` accordingly.
- **Start hangs for >180s** — usually an image is being pulled or built. Check `journalctl --user -u gizmo-stack-control` and run `podman compose up -d` manually in `GIZMO_COMPOSE_DIR` to see the actual blocker.
- **Service keeps restarting** — `systemctl --user status gizmo-stack-control` and `journalctl --user -u gizmo-stack-control -n 50`. Port 9101 may already be in use (`ss -ltnp | grep 9101`).

---

## Android App (Optional)

Sideload the Gizmo Android app for one-tap access from your phone.

### Install from GitHub Releases

1. Download the latest `app-debug.apk` from [Releases](https://github.com/nisakson2000/Gizmo/releases)
2. On your Android device, enable "Install unknown apps" for your browser (Settings → Apps → Special access)
3. Open the APK and install

### Install via ADB

```bash
bash mobile/build-apk.sh
adb install mobile/android/app/build/outputs/apk/debug/app-debug.apk
```

### VPN for Remote Access

To use Gizmo from outside your home network, set up a VPN tunnel:

- **Tailscale** — easiest option, free for personal use. Provides automatic HTTPS certificates (needed for mic access)
- **WireGuard** — open source, manual configuration
- **ZeroTier** — similar to Tailscale

For any VPN, configure it as an always-on VPN in Android settings (Settings → Network → VPN → your VPN → Always-on VPN) for seamless access.

### ALLOWED_ORIGINS

When the Android app connects via a non-default URL (e.g., a Tailscale hostname that isn't already in `ALLOWED_ORIGINS`), WebSocket connections will be rejected. Add your server URL to `ALLOWED_ORIGINS` in `docker-compose.yml`:

```yaml
environment:
  - ALLOWED_ORIGINS=http://localhost:3100,https://your-hostname.ts.net,http://192.168.1.100:3100
```
