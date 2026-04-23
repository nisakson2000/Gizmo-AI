#!/usr/bin/env python3
"""Gizmo stack-control — host-side service for remote start/stop of the podman stack.

Runs OUTSIDE podman as a systemd user service. The orchestrator can't control the
stack it lives inside, so this tiny always-on sidecar on port 9101 lets the Android
app (or any Tailscale client) start/stop the stack.

Endpoints:
    GET  /health             -> {"status": "healthy"}
    GET  /api/system/status  -> {"running": bool, "containers": [...]}
    POST /api/system/start   -> {"success": bool, "message": str}
    POST /api/system/stop    -> {"success": bool, "message": str}

No auth. Trust model is network-level (Tailscale). Do not expose port 9101
publicly — see services/stack-control/install.sh and the setup wiki.

Env:
    GIZMO_COMPOSE_DIR     directory containing docker-compose.yml (default %h/gizmo)
    GIZMO_COMPOSE_PROJECT compose project name for label filter (default 'gizmo')
    PORT                  listen port (default 9101)
    BIND_ADDR             bind address (default 0.0.0.0)
"""
from __future__ import annotations

import json
import logging
import os
import subprocess
import threading
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger("stack-control")

COMPOSE_DIR = os.environ.get("GIZMO_COMPOSE_DIR", os.path.expanduser("~/gizmo"))
COMPOSE_PROJECT = os.environ.get("GIZMO_COMPOSE_PROJECT", "gizmo")
PORT = int(os.environ.get("PORT", "9101"))
BIND_ADDR = os.environ.get("BIND_ADDR", "0.0.0.0")

START_TIMEOUT = 180
STOP_TIMEOUT = 60
STATUS_TIMEOUT = 15

_PODMAN_COMPOSE_LABEL = f"io.podman.compose.project={COMPOSE_PROJECT}"
_DOCKER_COMPOSE_LABEL = f"com.docker.compose.project={COMPOSE_PROJECT}"

_mutation_lock = threading.Lock()


def _run_podman(args: list[str], timeout: int) -> tuple[int, str, str]:
    """Run podman <args> in COMPOSE_DIR. Returns (rc, stdout, stderr).

    Translates subprocess failures into sentinel rcs so callers never see exceptions.
    """
    try:
        result = subprocess.run(
            ["podman", *args],
            cwd=COMPOSE_DIR,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        return result.returncode, result.stdout or "", result.stderr or ""
    except subprocess.TimeoutExpired as e:
        stderr = (e.stderr or "") + f"\n[timed out after {timeout}s]"
        return 124, e.stdout or "", stderr
    except FileNotFoundError:
        return 127, "", "podman executable not found in PATH"


def _parse_started_at(value: Any) -> datetime | None:
    """Parse podman 'StartedAt' (unix int or ISO 8601 string) to aware datetime."""
    # Sentinel values podman emits for never-started containers
    if value in (None, 0, -62135596800):
        return None
    try:
        if isinstance(value, (int, float)):
            return datetime.fromtimestamp(float(value), tz=timezone.utc)
        if isinstance(value, str):
            return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (ValueError, OSError, TypeError):
        return None
    return None


def _list_project_containers() -> list[dict[str, Any]]:
    """Return raw `podman ps -a --format json` records for the compose project.

    Tries both label keys podman-compose emits; falls back to empty list on any
    failure so status endpoint always returns a valid shape.
    """
    for label in (_PODMAN_COMPOSE_LABEL, _DOCKER_COMPOSE_LABEL):
        rc, stdout, stderr = _run_podman(
            ["ps", "-a", "--filter", f"label={label}", "--format", "json"],
            timeout=STATUS_TIMEOUT,
        )
        if rc != 0:
            logger.debug("podman ps rc=%d stderr=%s", rc, stderr[-200:])
            continue
        try:
            records = json.loads(stdout or "[]")
        except json.JSONDecodeError as e:
            logger.warning("podman ps JSON parse failed: %s", e)
            continue
        if records:
            return records
    return []


def get_container_status() -> list[dict[str, Any]]:
    """Return [{name, status, uptime_seconds}] for all stack containers."""
    now = datetime.now(timezone.utc)
    out: list[dict[str, Any]] = []
    for rec in _list_project_containers():
        names = rec.get("Names")
        if isinstance(names, list) and names:
            name = names[0]
        elif isinstance(names, str) and names:
            name = names
        else:
            name = rec.get("Name") or "unknown"
        state = str(rec.get("State") or "unknown").lower()
        uptime: int | None = None
        if state == "running":
            started = _parse_started_at(rec.get("StartedAt"))
            if started is not None:
                uptime = max(0, int((now - started).total_seconds()))
        out.append({"name": name, "status": state, "uptime_seconds": uptime})
    out.sort(key=lambda c: c["name"])
    return out


def _is_running(containers: list[dict[str, Any]] | None = None) -> bool:
    if containers is None:
        containers = get_container_status()
    return any(c["status"] == "running" for c in containers)


def _format_failure(rc: int, stdout: str, stderr: str, op: str) -> str:
    tail = (stderr.strip() or stdout.strip())[-400:]
    if tail:
        return f"{op} failed (rc={rc}): {tail}"
    return f"{op} failed (rc={rc})"


def handle_status() -> dict[str, Any]:
    containers = get_container_status()
    return {"running": _is_running(containers), "containers": containers}


def handle_start() -> dict[str, Any]:
    with _mutation_lock:
        if _is_running():
            return {"success": True, "message": "Stack already running"}
        logger.info("Starting stack: podman compose up -d (cwd=%s)", COMPOSE_DIR)
        rc, stdout, stderr = _run_podman(
            ["compose", "up", "-d"],
            timeout=START_TIMEOUT,
        )
        if rc == 0:
            logger.info("Stack started")
            return {"success": True, "message": "Stack started"}
        logger.warning("Start failed rc=%d stderr=%s", rc, stderr[-400:])
        return {"success": False, "message": _format_failure(rc, stdout, stderr, "start")}


def handle_stop() -> dict[str, Any]:
    with _mutation_lock:
        if not _is_running():
            return {"success": True, "message": "Stack already stopped"}
        logger.info("Stopping stack: podman compose stop (cwd=%s)", COMPOSE_DIR)
        rc, stdout, stderr = _run_podman(
            ["compose", "stop"],
            timeout=STOP_TIMEOUT,
        )
        if rc == 0:
            logger.info("Stack stopped")
            return {"success": True, "message": "Stack stopped"}
        logger.warning("Stop failed rc=%d stderr=%s", rc, stderr[-400:])
        return {"success": False, "message": _format_failure(rc, stdout, stderr, "stop")}


class StackControlHandler(BaseHTTPRequestHandler):
    server_version = "gizmo-stack-control/1.0"
    protocol_version = "HTTP/1.1"

    def _send_json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, format: str, *args: Any) -> None:
        logger.info("%s %s", self.address_string(), format % args)

    def do_GET(self) -> None:
        if self.path == "/health":
            self._send_json(HTTPStatus.OK, {"status": "healthy"})
            return
        if self.path == "/api/system/status":
            try:
                self._send_json(HTTPStatus.OK, handle_status())
            except Exception as e:
                logger.exception("status handler failed")
                self._send_json(
                    HTTPStatus.INTERNAL_SERVER_ERROR,
                    {"running": False, "containers": [], "error": str(e)},
                )
            return
        self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})

    def do_POST(self) -> None:
        if self.path == "/api/system/start":
            try:
                self._send_json(HTTPStatus.OK, handle_start())
            except Exception as e:
                logger.exception("start handler failed")
                self._send_json(
                    HTTPStatus.INTERNAL_SERVER_ERROR,
                    {"success": False, "message": f"start failed: {e}"},
                )
            return
        if self.path == "/api/system/stop":
            try:
                self._send_json(HTTPStatus.OK, handle_stop())
            except Exception as e:
                logger.exception("stop handler failed")
                self._send_json(
                    HTTPStatus.INTERNAL_SERVER_ERROR,
                    {"success": False, "message": f"stop failed: {e}"},
                )
            return
        self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})


def main() -> None:
    logger.info(
        "gizmo-stack-control listening on %s:%d (compose_dir=%s project=%s)",
        BIND_ADDR, PORT, COMPOSE_DIR, COMPOSE_PROJECT,
    )
    server = ThreadingHTTPServer((BIND_ADDR, PORT), StackControlHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logger.info("Shutting down (SIGINT)")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
