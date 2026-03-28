"""Code execution sandbox via Podman container API."""

import asyncio
import logging
import os
import shutil
import uuid
from pathlib import Path

import httpx

logger = logging.getLogger(__name__)

PODMAN_SOCKET = "/run/podman/podman.sock"
SANDBOX_IMAGE = "gizmo-sandbox:latest"
API_BASE = "http://d/v5.0.0"
DEFAULT_TIMEOUT = 10
MAX_TIMEOUT = 30
MAX_OUTPUT = 8000
MEDIA_DIR = Path("/app/media")
MEDIA_HOST_DIR = os.getenv("MEDIA_HOST_DIR", "/app/media")

# Supported languages and their execution commands

INTERPRETED_LANGS = {
    "python":     {"ext": "code.py",  "run": "python3 /tmp/code.py"},
    "javascript": {"ext": "code.js",  "run": "node /tmp/code.js"},
    "bash":       {"ext": "code.sh",  "run": "bash /tmp/code.sh"},
    "lua":        {"ext": "code.lua", "run": "lua5.4 /tmp/code.lua"},
}

# Direct execution (no file) for interpreted langs when no stdin needed
INTERPRETED_DIRECT = {
    "python":     lambda code: ["python3", "-c", code],
    "javascript": lambda code: ["node", "-e", code],
    "bash":       lambda code: ["bash", "-c", code],
    "lua":        lambda code: ["lua5.4", "-e", code],
}

COMPILED_LANGS = {
    "c": {
        "ext": "prog.c",
        "compile": "gcc -o /tmp/prog /tmp/prog.c -lm",
        "run": "/tmp/prog",
    },
    "cpp": {
        "ext": "prog.cpp",
        "compile": "g++ -o /tmp/prog /tmp/prog.cpp -lm",
        "run": "/tmp/prog",
    },
    "go": {
        "ext": "main.go",
        "compile": None,  # go run compiles and runs in one step
        "run": "cd /tmp && go run main.go",
    },
}

SUPPORTED_LANGUAGES = list(INTERPRETED_LANGS.keys()) + list(COMPILED_LANGS.keys())


async def _api(method: str, path: str, **kwargs) -> httpx.Response:
    """Make a request to the Podman API over Unix socket."""
    transport = httpx.AsyncHTTPTransport(uds=PODMAN_SOCKET)
    async with httpx.AsyncClient(transport=transport, timeout=30.0) as client:
        return await client.request(method, f"{API_BASE}{path}", **kwargs)


def _collect_output_files(sandbox_dir: Path) -> list[dict]:
    """Collect generated files from a sandbox output directory.

    Moves files to /app/media/ and returns download URL metadata.
    """
    if not sandbox_dir.exists():
        return []

    MEDIA_DIR.mkdir(parents=True, exist_ok=True)
    extracted = []

    for fpath in sandbox_dir.iterdir():
        if not fpath.is_file() or fpath.name.startswith("."):
            continue
        ext = fpath.suffix.lower() or ".bin"
        doc_id = uuid.uuid4().hex[:8]
        saved_name = f"doc-{doc_id}{ext}"
        saved_path = MEDIA_DIR / saved_name

        try:
            shutil.move(str(fpath), str(saved_path))
            extracted.append({
                "filename": fpath.name,
                "url": f"/api/media/{saved_name}",
            })
            logger.info("Extracted output file: %s → %s", fpath.name, saved_name)
        except Exception as e:
            logger.warning("Failed to move output file %s: %s", fpath.name, e)

    return extracted


async def run_code(code: str, language: str = "python", timeout: int = DEFAULT_TIMEOUT, stdin_data: str = "") -> dict:
    """Execute code in an isolated container.

    Returns dict with stdout, stderr, exit_code, timed_out, language, and output_files.
    If stdin_data is provided, it is piped to the process as standard input.
    Files written to /tmp/output/ are extracted via bind mount and returned as output_files.
    """
    timeout = max(1, min(timeout, MAX_TIMEOUT))
    language = language.lower().strip()

    if language not in SUPPORTED_LANGUAGES:
        return {
            "stdout": "",
            "stderr": f"Unsupported language: {language}. Supported: {', '.join(SUPPORTED_LANGUAGES)}",
            "exit_code": -1,
            "timed_out": False,
            "language": language,
            "output_files": [],
        }

    use_stdin = bool(stdin_data)
    stdin_redirect = " < /tmp/stdin.txt" if use_stdin else ""

    # Build the Cmd for the container
    env_vars = []

    if language in INTERPRETED_LANGS:
        if use_stdin:
            # File-based execution with stdin redirect
            spec = INTERPRETED_LANGS[language]
            shell_cmd = f'printenv SOURCE_CODE > /tmp/{spec["ext"]} && printenv STDIN_DATA > /tmp/stdin.txt && {spec["run"]}{stdin_redirect}'
            cmd = ["sh", "-c", shell_cmd]
            env_vars = [f"SOURCE_CODE={code}", f"STDIN_DATA={stdin_data}"]
        else:
            # Direct execution (current behavior, unchanged)
            cmd = INTERPRETED_DIRECT[language](code)
    else:
        # Compiled language: write source via env var, compile, run
        spec = COMPILED_LANGS[language]
        if use_stdin:
            stdin_setup = "printenv STDIN_DATA > /tmp/stdin.txt && "
            env_vars = [f"SOURCE_CODE={code}", f"STDIN_DATA={stdin_data}"]
        else:
            stdin_setup = ""
            env_vars = [f"SOURCE_CODE={code}"]

        if spec["compile"]:
            shell_cmd = f'{stdin_setup}printenv SOURCE_CODE > /tmp/{spec["ext"]} && {spec["compile"]} && {spec["run"]}{stdin_redirect}'
        else:
            shell_cmd = f'{stdin_setup}printenv SOURCE_CODE > /tmp/{spec["ext"]} && {spec["run"]}{stdin_redirect}'
        cmd = ["sh", "-c", shell_cmd]

    # Create a temporary directory for output file extraction (bind mount)
    sandbox_id = uuid.uuid4().hex[:12]
    output_dir_container = MEDIA_DIR / f".sandbox-{sandbox_id}"
    output_dir_host = Path(MEDIA_HOST_DIR) / f".sandbox-{sandbox_id}"
    output_dir_container.mkdir(parents=True, exist_ok=True)
    # Make writable by sandbox nobody user
    os.chmod(output_dir_container, 0o777)

    # Create container with strict resource limits
    create_body = {
        "Image": SANDBOX_IMAGE,
        "Cmd": cmd,
        "NetworkDisabled": True,
        "HostConfig": {
            "Memory": 256 * 1024 * 1024,  # 256MB
            "NanoCpus": 1_000_000_000,  # 1.0 CPU
            "PidsLimit": 256,
            "ReadonlyRootfs": True,
            "Tmpfs": {"/tmp": "size=150m"},
            "SecurityOpt": ["no-new-privileges"],
            "Binds": [f"{output_dir_host}:/tmp/output:Z"],
        },
    }

    if env_vars:
        create_body["Env"] = env_vars

    container_id = None
    try:
        # Create
        resp = await _api("POST", "/containers/create", json=create_body)
        if resp.status_code != 201:
            return {"stdout": "", "stderr": f"Container create failed: {resp.text}", "exit_code": -1, "timed_out": False, "language": language, "output_files": []}
        container_id = resp.json()["Id"]

        # Start
        resp = await _api("POST", f"/containers/{container_id}/start")
        if resp.status_code not in (200, 204):
            return {"stdout": "", "stderr": f"Container start failed: {resp.text}", "exit_code": -1, "timed_out": False, "language": language, "output_files": []}

        # Wait with timeout
        timed_out = False
        try:
            resp = await _api("POST", f"/containers/{container_id}/wait", timeout=timeout + 5)
            exit_code = resp.json().get("StatusCode", -1)
        except (httpx.ReadTimeout, asyncio.TimeoutError):
            timed_out = True
            exit_code = -1
            # Kill the container
            try:
                await _api("POST", f"/containers/{container_id}/kill")
            except Exception:
                pass

        # Read logs
        stdout = ""
        stderr = ""
        try:
            resp = await _api("GET", f"/containers/{container_id}/logs", params={"stdout": "true", "stderr": "true"})
            # Podman multiplexed log stream: 8-byte header per frame
            raw = resp.content
            pos = 0
            while pos + 8 <= len(raw):
                stream_type = raw[pos]  # 1=stdout, 2=stderr
                size = int.from_bytes(raw[pos + 4:pos + 8], "big")
                pos += 8
                chunk = raw[pos:pos + size].decode("utf-8", errors="replace")
                pos += size
                if stream_type == 1:
                    stdout += chunk
                elif stream_type == 2:
                    stderr += chunk
        except Exception as e:
            logger.warning("Failed to read container logs: %s", e)

        # Collect output files from bind mount
        output_files = _collect_output_files(output_dir_container)

        return {
            "stdout": stdout[:MAX_OUTPUT],
            "stderr": stderr[:MAX_OUTPUT],
            "exit_code": exit_code,
            "timed_out": timed_out,
            "language": language,
            "output_files": output_files,
        }
    except Exception as e:
        logger.error("Sandbox error: %s", e)
        return {"stdout": "", "stderr": f"Sandbox error: {str(e)}", "exit_code": -1, "timed_out": False, "language": language, "output_files": []}
    finally:
        # Cleanup container
        if container_id:
            try:
                await _api("DELETE", f"/containers/{container_id}", params={"force": "true"})
            except Exception:
                pass
        # Cleanup temp output directory
        try:
            if output_dir_container.exists():
                shutil.rmtree(output_dir_container, ignore_errors=True)
        except Exception:
            pass
